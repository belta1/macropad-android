package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.pc.PcLink
import dev.belta.macropad.pc.PcStatus
import dev.belta.macropad.pc.ScreenViewport
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.withAlpha
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A monitor of the computer, mirrored over the PC link and made touchable: a tap clicks there,
 * holding right-clicks, dragging drags with the left button down, two fingers scroll, and spreading
 * or pinching two fingers zooms (keep them down to move around). Chips at the top choose the
 * monitor ([onSelect]), go back to the whole screen and show the tablet's keyboard, which types on
 * the computer ([onType], [onKey]). [refresh] redraws when a new picture arrived in [mirror].
 *
 * [viewport] is the part of the monitor shown; a zoom ends in [onViewport], and the computer then
 * sends that part sharp. Until then the picture so far is shown scaled.
 */
@SuppressLint("ViewConstructor")
class ScreenView(
    private val ui: Ui, private val mirror: () -> PcLink.ScreenMirror?, private val selected: () -> Int?,
    private var viewport: ScreenViewport,
    private val onSelect: (Int) -> Unit,
    private val onViewport: (ScreenViewport) -> Unit,
    private val onPointer: (x: Float, y: Float, action: String, amount: Int) -> Unit,
    private val onType: (String) -> Unit,
    private val onKey: (String) -> Unit,
) : LinearLayout(ui.context), PcStatusView {

    private val chips = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val rate = TextView(context).apply {
        setTextColor(MUTED)
        typeface = ui.fontHint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END
    }
    private val surface = Surface()
    private val message = pcPageMessage(ui, "")
    private val typing = TypingField()
    private var shownChips: List<Any?>? = null
    private var keyboardShown = false

    init {
        orientation = VERTICAL
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chips)
            addView(rate, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Takes the tablet keyboard's input; invisible.
            addView(typing, LayoutParams(1, 1))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(44)).apply { setMargins(ui.dp(5), 0, ui.dp(5), ui.dp(4)) })
        addView(FrameLayout(context).apply {
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private var monitors: List<Pair<Int, Boolean>> = emptyList()

    override fun update(status: PcStatus?) {
        val list = status?.monitors
        message.text = when {
            status == null -> "Mirrors a monitor of the computer through the PC link (docs/pc-link.md). Tap to click, hold for a right click, drag, scroll with two fingers, spread them to zoom."
            list == null -> "Update the PC link script (tools/macropad-link.ps1) to mirror the computer's screen."
            list.isEmpty() -> "The computer reports no monitor"
            else -> ""
        }
        val showing = !list.isNullOrEmpty()
        message.visibility = if (showing) View.GONE else View.VISIBLE
        surface.visibility = if (showing) View.VISIBLE else View.INVISIBLE
        monitors = list.orEmpty().map { it.number to it.primary }
        renderChips()
        refresh()
    }

    /** Monitor chips (with more than one), "Fit" while zoomed, and the keyboard. */
    private fun renderChips() {
        val state = listOf(monitors, selected(), viewport.zoomed, keyboardShown)
        if (state == shownChips) return
        shownChips = state
        chips.removeAllViews()
        if (monitors.size > 1) for ((number, primary) in monitors) {
            chips.addView(ui.chip("Screen $number" + if (primary) " (main)" else "", "desktop",
                if (number == selected()) PLATE else null) { onSelect(number) })
        }
        if (viewport.zoomed) chips.addView(ui.chip("Fit", "open_in_new") { setViewport(ScreenViewport()) })
        if (monitors.isNotEmpty()) chips.addView(ui.chip("Keyboard", "keyboard", if (keyboardShown) PLATE else null) { toggleKeyboard() })
    }

    /** A new picture arrived (or the rate changed). */
    fun refresh() {
        val m = mirror()
        rate.text = if (m?.bitmap == null) "" else "${m.fps} fps · ${m.kbps} KB/s"
        surface.invalidate()
    }

    private fun setViewport(new: ScreenViewport) {
        viewport = new
        renderChips()
        surface.invalidate()
        onViewport(new)
    }

    private fun toggleKeyboard() {
        val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboardShown = !keyboardShown
        if (keyboardShown) {
            typing.requestFocus()
            input.showSoftInput(typing, InputMethodManager.SHOW_IMPLICIT)
        } else {
            input.hideSoftInputFromWindow(typing.windowToken, 0)
            typing.clearFocus()
        }
        renderChips()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (keyboardShown) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(typing.windowToken, 0)
        }
    }

    /**
     * Passes what the tablet's keyboard types to [onType] / [onKey] at once. It always holds one
     * space, so deleting it is a Backspace; suggestions are off, so letters arrive one by one.
     */
    private inner class TypingField : EditText(context) {
        private var resetting = false

        init {
            alpha = 0f
            isCursorVisible = false
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            reset()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    if (resetting) return
                    when {
                        s.isEmpty() -> onKey("backspace")
                        s.length > 1 -> onType(s.substring(1))
                    }
                    reset()
                }
            })
            setOnEditorActionListener { _, _, _ -> onKey("enter"); true }
            setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DEL -> if (text.length <= 1) { onKey("backspace"); true } else false
                    KeyEvent.KEYCODE_ENTER -> { onKey("enter"); true }
                    else -> false
                }
            }
        }

        private fun reset() {
            resetting = true
            setText(" ")
            setSelection(1)
            resetting = false
        }
    }

    /** The picture, letterboxed, and the gestures on it. */
    private inner class Surface : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ui.dpf(2f)
        }
        private val waiting = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textAlign = Paint.Align.CENTER
            typeface = ui.fontBody
            textSize = ui.dpf(18f)
        }
        /** Where the shown part of the monitor is on screen (letterboxed). */
        private val frame = RectF()
        private val picture = RectF()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val zoomSlop = ui.dpf(28f)
        private val scrollStep = ui.dpf(36f)

        // Gesture state
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var held = false
        private var twoFingers = TWO_NONE
        private var startDistance = 0f
        private var startX = 0f
        private var startY = 0f
        private var startViewport = ScreenViewport()
        private var scrollFrom = 0f
        private var lastMove = 0L

        init {
            isHapticFeedbackEnabled = true
            contentDescription = "Computer screen; tap to click"
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val m = mirror() ?: return
            synchronized(m) {
                val bitmap = m.bitmap
                if (bitmap == null) {
                    canvas.drawText("Waiting for the computer's screen…", width / 2f, height / 2f, waiting)
                    frame.setEmpty()
                    return
                }
                // The shown part has the monitor's shape, like the picture.
                val scale = minOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                frame.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                // The picture covers its own part of the monitor (m.viewport); place it in this one.
                val bv = m.viewport
                picture.set(screenX(bv.x), screenY(bv.y), screenX(bv.x + bv.size), screenY(bv.y + bv.size))
                canvas.save()
                canvas.clipRect(frame)
                canvas.drawBitmap(bitmap, null, picture, paint)
                canvas.restore()
                // The mouse pointer isn't in the picture: a ring where it is.
                if (m.cursorX >= 0 && m.cursorY >= 0) {
                    val x = screenX(bv.x + m.cursorX * bv.size)
                    val y = screenY(bv.y + m.cursorY * bv.size)
                    if (frame.contains(x, y)) {
                        ring.color = withAlpha(Color.BLACK, 0.6f)
                        canvas.drawCircle(x, y, ui.dpf(9f), ring)
                        ring.color = TEXT
                        canvas.drawCircle(x, y, ui.dpf(7f), ring)
                    }
                }
            }
        }

        /** On-screen x of [monitorX] (0–1 across the monitor), and back. */
        private fun screenX(monitorX: Float) = frame.left + (monitorX - viewport.x) / viewport.size * frame.width()
        private fun screenY(monitorY: Float) = frame.top + (monitorY - viewport.y) / viewport.size * frame.height()
        private fun monitorX(x: Float, v: ScreenViewport = viewport) = v.x + (x - frame.left) / frame.width() * v.size
        private fun monitorY(y: Float, v: ScreenViewport = viewport) = v.y + (y - frame.top) / frame.height() * v.size

        private fun send(action: String, x: Float, y: Float, amount: Int = 0) {
            if (frame.isEmpty) return
            onPointer(monitorX(x), monitorY(y), action, amount)
        }

        private val longPress = Runnable {
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            send("right", downX, downY)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.x
                    downY = e.y
                    dragging = false
                    held = false
                    twoFingers = TWO_NONE
                    if (!frame.isEmpty) postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // A second finger: scroll or zoom, decided by how it moves (letting go of a drag first).
                    removeCallbacks(longPress)
                    if (dragging) send("up", e.getX(0), e.getY(0))
                    dragging = false
                    if (e.pointerCount == 2) {
                        twoFingers = TWO_UNDECIDED
                        startDistance = distance(e)
                        startX = centerX(e)
                        startY = centerY(e)
                        startViewport = viewport
                        scrollFrom = startY
                    }
                }
                MotionEvent.ACTION_MOVE -> when {
                    twoFingers != TWO_NONE && e.pointerCount >= 2 -> twoFingerMove(e)
                    twoFingers != TWO_NONE || held -> Unit
                    !dragging && hypot(e.x - downX, e.y - downY) > slop -> {
                        removeCallbacks(longPress)
                        dragging = true
                        send("down", downX, downY)
                        send("move", e.x, e.y)
                        lastMove = SystemClock.uptimeMillis()
                    }
                    // Moves at most every [MOVE_MS]: the Bluetooth link is slow.
                    dragging && SystemClock.uptimeMillis() - lastMove >= MOVE_MS -> {
                        send("move", e.x, e.y)
                        lastMove = SystemClock.uptimeMillis()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> if (twoFingers == TWO_ZOOM && e.pointerCount == 2) zoomDone()
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    when {
                        twoFingers == TWO_ZOOM -> zoomDone()
                        twoFingers != TWO_NONE -> Unit
                        dragging -> send("up", e.x, e.y)
                        !held && abs(e.eventTime - e.downTime) < LONG_PRESS_MS -> {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            send("tap", downX, downY)
                        }
                    }
                    dragging = false
                    twoFingers = TWO_NONE
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (dragging) send("up", e.x, e.y)
                    if (twoFingers == TWO_ZOOM) zoomDone()
                    dragging = false
                    twoFingers = TWO_NONE
                }
            }
            return true
        }

        private fun twoFingerMove(e: MotionEvent) {
            if (twoFingers == TWO_UNDECIDED) {
                twoFingers = when {
                    abs(distance(e) - startDistance) > zoomSlop -> TWO_ZOOM
                    hypot(centerX(e) - startX, centerY(e) - startY) > slop -> TWO_SCROLL
                    else -> TWO_UNDECIDED
                }
            }
            when (twoFingers) {
                TWO_SCROLL -> {
                    val y = centerY(e)
                    val notches = ((y - scrollFrom) / scrollStep).toInt()
                    // Like a touch screen: fingers moving down scroll the page up.
                    if (notches != 0) {
                        send("scroll", centerX(e), y, notches)
                        scrollFrom += notches * scrollStep
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                TWO_ZOOM -> {
                    // The point of the monitor under the fingers stays under them, scaled by their spread.
                    val size = (startViewport.size * startDistance / distance(e)).coerceIn(MIN_SIZE, 1f)
                    val x = monitorX(startX, startViewport) - (centerX(e) - frame.left) / frame.width() * size
                    val y = monitorY(startY, startViewport) - (centerY(e) - frame.top) / frame.height() * size
                    viewport = ScreenViewport(x.coerceIn(0f, 1 - size), y.coerceIn(0f, 1 - size), size)
                    invalidate()
                }
            }
        }

        private fun zoomDone() {
            twoFingers = TWO_DONE // a finger left down doesn't start a drag
            // Nearly all of it: the whole screen.
            setViewport(if (viewport.size > 0.95f) ScreenViewport() else viewport)
        }

        private fun distance(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        private fun centerX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
        private fun centerY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(longPress)
        }
    }

    private companion object {
        const val LONG_PRESS_MS = 550L
        const val MOVE_MS = 50L
        /** Zoomed in at most this far: a fifth of the monitor across. */
        const val MIN_SIZE = 0.2f
        const val TWO_NONE = 0
        const val TWO_UNDECIDED = 1
        const val TWO_SCROLL = 2
        const val TWO_ZOOM = 3
        const val TWO_DONE = 4
    }
}
