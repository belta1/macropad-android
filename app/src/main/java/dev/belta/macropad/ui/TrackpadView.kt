package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.belta.macropad.hid.HidKeys
import dev.belta.macropad.ui.Ui.Companion.CAP_SKIRT
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.withAlpha
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touchpad surface: one finger moves the pointer (with acceleration), tap = left click,
 * two-finger tap = right click, two-finger drag = scroll, long-press = drag (left button held),
 * and the strip on the right edge scrolls with one finger.
 */
@SuppressLint("ViewConstructor")
class TrackpadView(private val ui: Ui, private val listener: Listener) : View(ui.context) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onScroll(vertical: Int, horizontal: Int)
        fun onButton(button: Int, down: Boolean)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Handler(Looper.getMainLooper())
    private val stripWidth = ui.dp(64).toFloat()
    private val scrollStep = ui.dp(18).toFloat() // finger travel per wheel notch

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var moved = false
    private var maxPointers = 0
    private var dragging = false
    private var inStrip = false
    private var fracX = 0f
    private var fracY = 0f
    private var scrollAcc = 0f
    private var panAcc = 0f

    // A well cut into the case: darker than the plate around it, with a shaded top lip. The scroll
    // strip is ridged like a mouse wheel.
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE }
    private val lip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(1).toFloat()
        color = HAIRLINE
    }
    private val ridge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = ui.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = CAP_SKIRT
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textAlign = Paint.Align.CENTER
        textSize = ui.dp(14).toFloat()
        typeface = ui.fontHint
    }
    private val dragHint = Paint(hint).apply {
        color = TEXT
        textSize = ui.dp(17).toFloat()
        typeface = ui.fontLabel
    }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(TEXT, 0.05f) }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = ui.dp(14).toFloat()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        if (dragging) canvas.drawRoundRect(rect, r, r, active)
        lip.shader = LinearGradient(0f, 0f, 0f, ui.dp(10).toFloat(), withAlpha(Color.BLACK, 0.45f),
            withAlpha(Color.BLACK, 0f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, lip)
        canvas.drawRoundRect(rect, r, r, border)

        // Scroll strip: a column of ridges, closer together than the scroll step so they read as grip.
        val sx0 = width - stripWidth + ui.dp(18)
        val sx1 = width - ui.dp(18).toFloat()
        canvas.drawLine(width - stripWidth, ui.dp(14).toFloat(), width - stripWidth, height - ui.dp(14).toFloat(), border)
        var y = ui.dp(22).toFloat()
        while (y < height - ui.dp(20)) {
            canvas.drawLine(sx0, y, sx1, y, ridge)
            y += ui.dp(9)
        }

        val cx = (width - stripWidth) / 2
        if (dragging) {
            canvas.drawText("Dragging. Lift to drop.", cx, height / 2f, dragHint)
        } else {
            val base = height - ui.dp(36).toFloat()
            canvas.drawText("Tap to click, two-finger tap to right-click", cx, base, hint)
            canvas.drawText("Two fingers or the strip to scroll, long-press to drag", cx, base + ui.dp(20), hint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y; lastT = e.eventTime
                moved = false
                maxPointers = 1
                inStrip = e.x > width - stripWidth
                fracX = 0f; fracY = 0f; scrollAcc = 0f; panAcc = 0f
                if (!inStrip) longPress.postDelayed(startDrag, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                longPress.removeCallbacks(startDrag)
                lastX = avgX(e); lastY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = avgX(e)
                val y = avgY(e)
                if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                    moved = true
                    longPress.removeCallbacks(startDrag)
                }
                val dx = x - lastX
                val dy = y - lastY
                when {
                    inStrip -> scrollBy(-dy, 0f)
                    e.pointerCount >= 2 -> scrollBy(dy, -dx)
                    moved || dragging -> movePointer(dx, dy, e.eventTime - lastT)
                }
                lastX = x; lastY = y; lastT = e.eventTime
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor on the remaining fingers so the pointer doesn't jump.
                val remaining = (0 until e.pointerCount).filter { it != e.actionIndex }
                lastX = remaining.map { e.getX(it) }.average().toFloat()
                lastY = remaining.map { e.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPress.removeCallbacks(startDrag)
                val tap = !moved && e.eventTime - downTime < TAP_MS && e.actionMasked == MotionEvent.ACTION_UP
                when {
                    dragging -> {
                        dragging = false
                        listener.onButton(HidKeys.mouseButtons.getValue("left"), false)
                        invalidate()
                    }
                    tap && !inStrip -> click(if (maxPointers >= 2) "right" else "left")
                }
            }
        }
        return true
    }

    private val startDrag = Runnable {
        dragging = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener.onButton(HidKeys.mouseButtons.getValue("left"), true)
        invalidate()
    }

    private fun click(name: String) {
        val button = HidKeys.mouseButtons.getValue(name)
        listener.onButton(button, true)
        listener.onButton(button, false)
    }

    /** Pointer acceleration: slow movements stay precise, fast flicks cross the screen. */
    private fun movePointer(dx: Float, dy: Float, dtMs: Long) {
        val speed = hypot(dx, dy) / maxOf(dtMs, 1L)            // px per ms
        val gain = BASE_GAIN * (1f + minOf(speed, 3f) * ACCELERATION)
        fracX += dx * gain
        fracY += dy * gain
        val ix = fracX.toInt()
        val iy = fracY.toInt()
        if (ix != 0 || iy != 0) {
            fracX -= ix; fracY -= iy
            listener.onMove(ix, iy)
        }
    }

    /** [up] > 0 scrolls up; [right] > 0 scrolls right. */
    private fun scrollBy(up: Float, right: Float) {
        scrollAcc += up / scrollStep
        panAcc += right / scrollStep
        val v = scrollAcc.toInt()
        val h = panAcc.toInt()
        if (v != 0 || h != 0) {
            scrollAcc -= v; panAcc -= h
            listener.onScroll(v, if (abs(h) > 0) h else 0)
        }
    }

    private fun avgX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun avgY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    private companion object {
        const val TAP_MS = 220L
        const val LONG_PRESS_MS = 450L
        const val BASE_GAIN = 1.2f
        const val ACCELERATION = 0.9f
    }
}
