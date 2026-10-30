package dev.belta.macropad.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.MacroButton
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.legendTone
import dev.belta.macropad.ui.Ui.Companion.withAlpha
import kotlin.math.hypot

/**
 * A slider key: dragging along its longer side sends one tick per [TICK_DP] of travel, up / right
 * = [onTick] (true), down / left = [onTick] (false). It is relative (the computer's volume etc. is
 * unknown), so the thumb follows the finger and springs back to the center when it lifts. Holding
 * the finger at either end keeps ticking. Compact sliders (the dock) show the icon but no label.
 * When the slider has a tap step (Mute for the volume slider), its icon sits on a small disc, and a
 * touch that starts there and lifts without sliding calls [onTap]. The − and + at the ends are
 * buttons too: a tap sends one step down / up, holding keeps stepping.
 *
 * Drawn as a groove cut into the keycap's face, with a small knob as the thumb.
 *
 * With a [level] (0–100, e.g. the computer's volume over the PC link) and [onLevel] it is absolute
 * instead: the knob sits at the level, a touch on the groove moves it there, dragging follows the
 * finger and [onLevel] reports each new level (`done` once the finger lifts). − / + and the icon
 * work as before. [setLevel] moves it when the computer reports a change.
 */
@SuppressLint("ViewConstructor")
class SliderView(
    private val ui: Ui, private val button: MacroButton, keyBackground: Drawable, private val compact: Boolean,
    private val onTap: (() -> Unit)? = null,
    level: Int? = null,
    private var muted: Boolean = false,
    private val onLevel: ((level: Int, done: Boolean) -> Unit)? = null,
    private val onTick: (up: Boolean) -> Unit,
) : View(ui.context) {

    val absolute = level != null && onLevel != null
    private var level = level ?: 0
    /** The level changed during this touch, so lifting reports it as done. */
    private var levelMoved = false

    private val tickPx = ui.dp(TICK_DP).toFloat()
    private val edge = ui.dp(28).toFloat()
    private val pad = ui.dp(15).toFloat()
    /** The cap's front skirt: the face (and the groove's center) sits above it. */
    private val skirt = ui.dp(6).toFloat()
    private val sign = ui.dp(22).toFloat()
    private val thumbR = ui.dp(10).toFloat()
    private val groove = ui.dp(4).toFloat()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val travel = ui.dpf(3f)
    private val tapStep = Macro.slider(button.action)?.tap

    /** The key's color as the theme shows it. */
    private val accent = Ui.accent(button.color)
    private val icon = Icons.get(button.icon)?.let {
        ui.glowing(context.getDrawable(it)!!.mutate().apply { setTint(legendTone(accent)) }, accent)
    }
    /** A speaker icon shows crossed out while [muted]. */
    private val mutedIcon = Icons.get("volume_off")?.takeIf { button.icon?.startsWith("volume") == true }?.let {
        ui.glowing(context.getDrawable(it)!!.mutate().apply { setTint(legendTone(accent)) }, accent)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        typeface = ui.fontLabel
        textSize = ui.dp(17).toFloat()
    }
    private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textAlign = Paint.Align.CENTER
        typeface = ui.fontLabel
        textSize = ui.dp(20).toFloat()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = legendTone(accent) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(TEXT, 0.25f) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // Geometry, set in onSizeChanged. Positions "along" the slider grow towards up / right
    // (vertical sliders use -y); [cross] is the track's center on the other axis.
    private var horizontal = true
    private var labelled = false
    private var cross = 0f
    private var start = 0f
    private var end = 0f
    private var center = 0f
    private var maxThumb = 0f

    private var anchor = 0f // finger position at touch down
    private var origin = 0f // anchor, shifted by the ticks sent while holding at an end
    private var sent = 0
    private var thumb = 0f
    private var repeatDir = 0
    private var springBack: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    /** The touch started on the icon of a slider with a tap step. */
    private var onIcon = false
    /** It moved past the touch slop, so it is a drag, not a tap. */
    private var dragged = false
    /** The touch started on − (-1) or + (1); 0 = elsewhere. */
    private var onSign = 0
    /** Steps the held − / + has sent. */
    private var signSteps = 0

    init {
        background = keyBackground
        isHapticFeedbackEnabled = true
        describe()
    }

    private fun describe() {
        contentDescription = "${button.label} (${Macro.describe(button.action)})" +
            if (absolute) ", " + (if (muted) "muted" else "$level%") else ""
    }

    /** The level (and mute) the computer reports; ignored while the slider is being touched. */
    fun setLevel(level: Int, muted: Boolean) {
        if (isPressed || (level == this.level && muted == this.muted)) return
        this.level = level.coerceIn(0, 100)
        this.muted = muted
        describe()
        invalidate()
    }

    /** Where the knob sits for [level]: 0 at the − end, 100 at the + end. */
    private fun levelAt(level: Int) = center - maxThumb + 2 * maxThumb * level / 100f

    private val tapLabel = when {
        tapStep == null -> null
        Macro.slider(button.action)?.preset == "volume" -> "Mute"
        else -> Macro.describe(button.action.substringAfterLast('|').trim())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        horizontal = w >= h
        labelled = horizontal && !compact && h >= ui.dp(84) && button.label.isNotBlank()
        if (horizontal) {
            cross = if (labelled) (pad + ui.dp(22) + h - skirt) / 2f else (h - skirt) / 2f
            start = pad + if (!labelled && icon != null) ui.dp(ICON_ROOM_DP) else 0
            end = w - pad
        } else {
            cross = w / 2f
            // Vertical: the face's bottom edge is the skirt.
            start = -(h - pad - skirt)
            end = -(pad + if (icon != null) ui.dp(ICON_ROOM_DP) else 0)
        }
        center = (start + end) / 2
        maxThumb = maxOf(0f, (end - start) / 2 - sign - thumbR)
    }

    override fun onDraw(canvas: Canvas) {
        val iconSize = ui.dp(if (labelled) 20 else 24)
        (if (muted && mutedIcon != null) mutedIcon else icon)?.let {
            val left = if (horizontal) pad.toInt() else (width - iconSize) / 2
            val drop = if (isPressed && onIcon && !dragged) travel.toInt() else 0
            val top = drop + if (horizontal && !labelled) ((height - skirt - iconSize) / 2).toInt() else pad.toInt()
            if (tapStep != null) {
                discPaint.color = withAlpha(TEXT, if (drop > 0) 0.18f else 0.08f)
                canvas.drawCircle(left + iconSize / 2f, top + iconSize / 2f, iconSize * 0.75f, discPaint)
            }
            it.setBounds(left, top, left + iconSize, top + iconSize)
            it.draw(canvas)
        }
        if (labelled) {
            val x = pad + if (icon != null) ui.dp(30) else 0
            val text = TextUtils.ellipsize(button.label, labelPaint, width - x - pad, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, x, pad + ui.dp(16), labelPaint)
        }

        drawSign(canvas, "−", start + sign / 2, pressed = onSign == -1 && !dragged)
        drawSign(canvas, "+", end - sign / 2, pressed = onSign == 1 && !dragged)
        bar(center - maxThumb - thumbR, center + maxThumb + thumbR, groove)
        canvas.drawRoundRect(rect, groove, groove, trackPaint)
        val knob = if (absolute) levelAt(level) else center + thumb
        if (absolute) {
            // Filled up to the level, faintly while muted.
            fillPaint.alpha = if (muted) 90 else 255
            bar(center - maxThumb - thumbR, knob, groove / 2)
            canvas.drawRoundRect(rect, groove / 2, groove / 2, fillPaint)
        } else {
            if (thumb != 0f) {
                bar(center, center + thumb, groove / 2)
                canvas.drawRoundRect(rect, groove / 2, groove / 2, fillPaint)
            }
            // A dot per tick, so the spacing of the steps can be seen.
            var t = tickPx
            while (t <= maxThumb) {
                for (u in floatArrayOf(center + t, center - t)) canvas.drawCircle(px(u), py(u), ui.dp(2).toFloat(), dotPaint)
                t += tickPx
            }
        }
        // The knob: a warm-white disc with a shadow, darker while held.
        val kx = px(knob)
        val ky = py(knob)
        thumbPaint.color = withAlpha(PLATE, 0.8f)
        canvas.drawCircle(kx, ky + ui.dp(2), thumbR, thumbPaint)
        thumbPaint.color = if (isPressed) MUTED else TEXT
        canvas.drawCircle(kx, ky, thumbR, thumbPaint)
        thumbPaint.color = legendTone(accent)
        canvas.drawCircle(kx, ky, thumbR * 0.32f, thumbPaint)
    }

    /** − or + on a faint disc, so it reads as a button; held, the disc lightens and the sign drops. */
    private fun drawSign(canvas: Canvas, text: String, at: Float, pressed: Boolean) {
        val drop = if (pressed) travel else 0f
        discPaint.color = withAlpha(TEXT, if (pressed) 0.18f else 0.07f)
        canvas.drawCircle(px(at), py(at) + drop, sign * 0.72f, discPaint)
        signPaint.color = if (pressed) TEXT else MUTED
        canvas.drawText(text, px(at), py(at) + drop - (signPaint.ascent() + signPaint.descent()) / 2, signPaint)
    }

    /** Sets [rect] to a bar from [a] to [b] along the slider, [half] thick on each side of the track. */
    private fun bar(a: Float, b: Float, half: Float) {
        if (horizontal) rect.set(minOf(a, b), cross - half, maxOf(a, b), cross + half)
        else rect.set(cross - half, minOf(-a, -b), cross + half, maxOf(-a, -b))
    }

    private fun px(along: Float) = if (horizontal) along else cross
    private fun py(along: Float) = if (horizontal) cross else -along
    private fun along(e: MotionEvent) = if (horizontal) e.x else -e.y

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                springBack?.cancel()
                downX = e.x
                downY = e.y
                onIcon = tapStep != null && inIconZone(e)
                onSign = if (onIcon) 0 else signAt(e)
                signSteps = 0
                if (onSign != 0) postDelayed(signHold, SIGN_HOLD_MS)
                dragged = false
                anchor = along(e)
                origin = anchor
                sent = 0
                thumb = 0f
                levelMoved = false
                isPressed = true
                // Absolute: a touch on the groove puts the knob there.
                if (absolute && !onIcon && onSign == 0) moveLevelTo(e)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged && hypot(e.x - downX, e.y - downY) > slop) {
                    dragged = true
                    removeCallbacks(signHold) // it's a slide after all
                }
                // A finger resting on − / + is a button press, not the start of a slide.
                if (onSign != 0 && !dragged) return true
                if (absolute) {
                    if (!onIcon || dragged) moveLevelTo(e)
                    return true
                }
                val pos = along(e)
                thumb = (pos - anchor).coerceIn(-maxThumb, maxThumb)
                val target = ((pos - origin) / tickPx).toInt()
                while (sent < target) tick(1)
                while (sent > target) tick(-1)
                // At either end (or past it), after a tick's worth of travel that way, keep ticking.
                val dir = when {
                    pos - anchor >= tickPx && atEnd(e, up = true) -> 1
                    anchor - pos >= tickPx && atEnd(e, up = false) -> -1
                    else -> 0
                }
                if (dir != repeatDir) {
                    removeCallbacks(repeat)
                    repeatDir = dir
                    if (dir != 0) postDelayed(repeat, REPEAT_DELAY_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeat)
                removeCallbacks(signHold)
                repeatDir = 0
                isPressed = false
                val lifted = e.actionMasked == MotionEvent.ACTION_UP && !dragged
                if (lifted && onIcon && sent == 0 && !levelMoved) tap()
                if (lifted && onSign != 0 && signSteps == 0) signStep(onSign)
                onIcon = false
                onSign = 0
                if (absolute) {
                    if (levelMoved) onLevel?.invoke(level, true)
                    thumb = 0f
                    invalidate()
                    return true
                }
                springBack = ValueAnimator.ofFloat(thumb, 0f).apply {
                    duration = 220
                    interpolator = OvershootInterpolator(2f)
                    addUpdateListener { thumb = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }

    /** Absolute: the knob follows the finger; each new level goes to [onLevel], with a tick every 5. */
    private fun moveLevelTo(e: MotionEvent) {
        if (maxThumb <= 0f) return
        val new = ((along(e) - (center - maxThumb)) / (2 * maxThumb) * 100).toInt().coerceIn(0, 100)
        if (new == level) return
        if (new / 5 != level / 5) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        level = new
        levelMoved = true
        onLevel?.invoke(new, false)
        describe()
        invalidate()
    }

    /** The icon and the padding around it, at least a 48 dp target. */
    private fun inIconZone(e: MotionEvent): Boolean {
        val reach = maxOf(ui.dp(48).toFloat(), pad + ui.dp(ICON_ROOM_DP))
        return when {
            labelled -> e.y < cross - thumbR - ui.dp(4) // the label row above the groove
            horizontal -> e.x < reach
            else -> e.y < reach
        }
    }

    /** −1 on the − end, 1 on the + end (the sign and a little around it, at least 40 dp), else 0. */
    private fun signAt(e: MotionEvent): Int {
        val reach = maxOf(sign + ui.dp(12), ui.dp(40).toFloat())
        if (labelled && e.y < cross - thumbR - ui.dp(4)) return 0 // the label row
        val pos = along(e)
        return when {
            pos <= start + reach -> -1
            pos >= end - reach -> 1
            else -> 0
        }
    }

    /** One step from − / +: like a tick of drag, with the knob nudged that way. */
    private fun signStep(dir: Int) {
        signSteps++
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onTick(dir > 0)
        thumb = (dir * tickPx).coerceIn(-maxThumb, maxThumb)
        invalidate()
    }

    /** Holding − / +: after a moment, keep stepping like a held key. */
    private val signHold = object : Runnable {
        override fun run() {
            if (onSign == 0 || dragged) return
            signStep(onSign)
            postDelayed(this, REPEAT_MS)
        }
    }

    private fun tap() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onTap?.invoke()
    }

    // TalkBack: double tap runs the tap step, swipes up / down tick the slider.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        tapLabel?.let { info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, it)) }
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> if (tapStep != null) { tap(); true } else false
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { onTick(true); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { onTick(false); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun atEnd(e: MotionEvent, up: Boolean) = when {
        horizontal && up -> e.x > width - edge
        horizontal -> e.x < edge
        up -> e.y < edge
        else -> e.y > height - edge
    }

    private val repeat = object : Runnable {
        override fun run() {
            origin -= repeatDir * tickPx
            tick(repeatDir)
            postDelayed(this, REPEAT_MS)
        }
    }

    private fun tick(dir: Int) {
        sent += dir
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onTick(dir > 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(repeat)
        removeCallbacks(signHold)
        springBack?.cancel()
    }

    private companion object {
        const val TICK_DP = 18
        const val REPEAT_DELAY_MS = 300L
        /** Room for the icon (and its disc) before the − button. */
        const val ICON_ROOM_DP = 38
        /** How long − / + must be held before it repeats. */
        const val SIGN_HOLD_MS = 400L
        const val REPEAT_MS = 110L
    }
}
