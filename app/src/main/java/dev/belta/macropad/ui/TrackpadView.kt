package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.belta.macropad.hid.HidKeys
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.STROKE_STRONG
import dev.belta.macropad.ui.Ui.Companion.SURFACE
import dev.belta.macropad.ui.Ui.Companion.SURFACE_HI
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

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE_HI }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(1).toFloat()
        color = STROKE_STRONG
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textAlign = Paint.Align.CENTER
        textSize = ui.dp(15).toFloat()
        typeface = ui.fontMedium
    }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Ui.BLUE, 0.12f) }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = ui.dp(22).toFloat()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, if (dragging) active else bg)
        canvas.drawRoundRect(rect, r, r, border)
        rect.set(width - stripWidth, ui.dp(8).toFloat(), width - ui.dp(8).toFloat(), height - ui.dp(8).toFloat())
        canvas.drawRoundRect(rect, r, r, stripPaint)

        val cx = (width - stripWidth) / 2
        val cy = height / 2f
        canvas.drawText(if (dragging) "Dragging — lift to drop" else "Trackpad", cx, cy - ui.dp(14), hint)
        hint.textSize = ui.dp(12).toFloat()
        canvas.drawText("tap = click · two-finger tap = right click", cx, cy + ui.dp(10), hint)
        canvas.drawText("two fingers = scroll · long-press = drag", cx, cy + ui.dp(28), hint)
        val sx = width - stripWidth / 2 - ui.dp(4)
        canvas.drawText("▲", sx, ui.dp(32).toFloat(), hint)
        canvas.drawText("scroll", sx, cy + ui.dp(4), hint)
        canvas.drawText("▼", sx, height - ui.dp(22).toFloat(), hint)
        hint.textSize = ui.dp(15).toFloat()
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
