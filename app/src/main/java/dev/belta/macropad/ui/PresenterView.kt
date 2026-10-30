package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.belta.macropad.model.AppSettings
import dev.belta.macropad.model.NotesCommands
import dev.belta.macropad.pc.PcSlides
import dev.belta.macropad.pc.PcStatus
import dev.belta.macropad.ui.Ui.Companion.AMBER
import dev.belta.macropad.ui.Ui.Companion.BLUE
import dev.belta.macropad.ui.Ui.Companion.FAINT
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE_STRONG
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.RED
import dev.belta.macropad.ui.Ui.Companion.TEXT
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PowerPoint's presenter view on the tablet (PC link): the current slide's speaker notes, big, on
 * the left; the slide and the next one on the right, with the time since the show started and
 * the clock; ◀ ▶, black / white screen, start / end and a grid of every slide to jump to.
 * Dragging on the slide moves PowerPoint's laser pointer there. `>` lines in the notes are
 * commands to type for a demo ([NotesCommands]), into [typeTarget]. Without PowerPoint on the
 * computer the buttons still send the slide show's keys ([onCommand] decides).
 *
 * [images] are the slides' pictures by number; [startedAt] when the show started (0: none),
 * [onResetTimer] sets it to now.
 */
@SuppressLint("ViewConstructor")
class PresenterView(
    private val ui: Ui, private val settings: AppSettings,
    private val images: () -> Map<Int, Bitmap>?,
    private val startedAt: () -> Long,
    private val onResetTimer: () -> Unit,
    private val onCommand: (action: String, slide: Int) -> Unit,
    private val onLaser: (x: Float, y: Float) -> Unit,
    /** The app commands in the notes are typed into (its name), and choosing another. */
    private val typeTarget: () -> String?,
    private val onPickTarget: () -> Unit,
    /** Types a command from the notes (then Enter if [enter]) into [typeTarget]'s app. */
    private val onType: (command: String, enter: Boolean) -> Unit,
) : LinearLayout(ui.context), PcStatusView {

    private var slides: PcSlides? = null
    private val handler = Handler(Looper.getMainLooper())

    private val position = label(28f, TEXT, ui.fontTitle)
    private val title = label(18f, MUTED, ui.fontLabel).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    /** The notes: paragraphs to read and, for `>` lines, commands to type ([NotesCommands]). */
    private val notes = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(ui.dp(4), ui.dp(8), ui.dp(12), ui.dp(48))
    }
    /** What [notes] shows, and whether it is a message rather than the slide's notes. */
    private var notesShown: Pair<String, Boolean>? = null
    private val notesScroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        addView(notes, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val targetChip = ui.chip("Types in: the window in front", "terminal", null) { onPickTarget() }
    private val timer = label(30f, TEXT, ui.fontTitle)
    private val clock = label(18f, MUTED, ui.fontLabel)
    private val current = SlidePicture(big = true)
    private val next = SlidePicture(big = false)
    private val nextLabel = label(14f, MUTED, ui.fontHint)
    private val showButton = ui.chip("Start show", "play", null) { onCommand(if (slides?.show == true) "end" else "start", 0) }
    private val blackButton = ui.chip("Black", "stop", null) { onCommand("black", 0) }
    private val whiteButton = ui.chip("White", "desktop", null) { onCommand("white", 0) }
    /** Teams' mute, while a call is under way: presenting in a meeting. */
    private val muteButton = ui.chip("Mute", "mic_off", null) { onCommand("mute", 0) }
    /** The open grid of every slide, to refresh as their pictures arrive. */
    private var gridCells: List<SlidePicture>? = null

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        orientation = HORIZONTAL
        isMotionEventSplittingEnabled = true
        setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))

        // Left: where we are and the notes.
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ui.pressable(android.graphics.Color.TRANSPARENT, ui.dp(12).toFloat())
                setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
                addView(position)
                addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(14) })
                setOnClickListener { showGrid() }
                contentDescription = "Every slide"
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)))
            addView(View(context).apply { setBackgroundColor(HAIRLINE_STRONG) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)))
            addView(notesScroller, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Text-only chips: the size is the label.
                addView(ui.chip("A−", "heading", null) { textSize(-2f) }.also { it.removeViewAt(0); it.contentDescription = "Smaller notes" })
                addView(ui.chip("A+", "heading", null) { textSize(2f) }.also { it.removeViewAt(0); it.contentDescription = "Bigger notes" })
                addView(View(context), LayoutParams(0, 1, 1f))
                addView(targetChip)
                addView(ui.chip("Slides", "table", null) { showGrid() })
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)))
        }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))

        addView(View(context), LayoutParams(ui.dp(12), 1))

        // Right: time, the slides, the controls.
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(timer.apply {
                    background = ui.pressable(android.graphics.Color.TRANSPARENT, ui.dp(10).toFloat())
                    setPadding(ui.dp(8), 0, ui.dp(8), 0)
                    contentDescription = "Time since the show started; tap to restart it"
                    setOnClickListener { onResetTimer(); updateTime() }
                })
                addView(View(context), LayoutParams(0, 1, 1f))
                addView(clock)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)))
            addView(current, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 5f))
            addView(nextLabel.apply { setPadding(0, ui.dp(6), 0, ui.dp(2)) })
            addView(next, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.4f))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                isMotionEventSplittingEnabled = true
                addView(arrowKey("chevron_left", "Previous slide") { onCommand("prev", 0) },
                    LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(0, ui.dp(8), ui.dp(5), 0) })
                addView(arrowKey("chevron_right", "Next slide") { onCommand("next", 0) },
                    LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f).apply { setMargins(ui.dp(5), ui.dp(8), 0, 0) })
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.sdp(96)))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                addView(muteButton)
                addView(blackButton)
                addView(whiteButton)
                addView(showButton)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)))
        }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
    }

    private fun label(sizeSp: Float, color: Int, font: android.graphics.Typeface) = TextView(context).apply {
        typeface = font
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER_VERTICAL
    }

    /** A big key that changes slides: a keycap, since it acts like the slide show's arrow keys. */
    @SuppressLint("ClickableViewAccessibility")
    private fun arrowKey(icon: String, description: String, onTap: () -> Unit) = FrameLayout(context).apply {
        val cap = KeyCap(ui, BLUE, lit = false)
        background = cap
        contentDescription = description
        val glyph = ui.icon(icon, Ui.legendTone(BLUE))
        addView(glyph, FrameLayout.LayoutParams(ui.sdp(44), ui.sdp(44), Gravity.CENTER))
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    glyph.translationY = cap.travel
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTap() // at once: a presenter waits for nothing
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; glyph.translationY = 0f }
            }
            true
        }
        setOnClickListener { onTap() } // TalkBack
    }

    private fun textSize(delta: Float) {
        settings.notesSize = (settings.notesSize + delta).coerceIn(14f, 48f)
        notesShown?.let { (text, message) -> notesShown = null; showNotes(text, message) }
    }

    /** [text] as paragraphs and command keys; a [message] (no notes, no PowerPoint…) is plain and dim. */
    private fun showNotes(text: String, message: Boolean) {
        if (notesShown == text to message) return
        notesShown = text to message
        notes.removeAllViews()
        val parts = if (message) listOf(NotesCommands.Part.Text(text)) else NotesCommands.parse(text)
        for (part in parts) when (part) {
            is NotesCommands.Part.Text -> notes.addView(TextView(context).apply {
                this.text = part.text
                typeface = ui.fontBody
                setTextColor(if (message) MUTED else TEXT)
                setLineSpacing(0f, 1.3f)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.notesSize)
            })
            is NotesCommands.Part.Command -> notes.addView(commandKey(part), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, ui.dp(6), 0, ui.dp(6)) })
        }
    }

    /** A command from the notes: monospace on a key; tapping types it (⏎: then Enter). */
    private fun commandKey(command: NotesCommands.Part.Command) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ui.pressable(Ui.RAISED, ui.dp(10).toFloat())
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        contentDescription = "Type ${command.text}" + if (command.enter) " and Enter" else ""
        addView(ui.icon("terminal", Ui.legendTone(Ui.GREEN)), LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(12) })
        addView(TextView(context).apply {
            text = command.text
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.notesSize * 0.8f)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (command.enter) addView(ui.icon("newline", MUTED), LayoutParams(ui.dp(20), ui.dp(20)).apply { marginStart = ui.dp(10) })
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onType(command.text, command.enter)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun updateTime() {
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val since = startedAt()
        val running = slides?.show == true && since > 0
        val seconds = if (running) (System.currentTimeMillis() - since) / 1000 else 0
        timer.text = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            else "%02d:%02d".format(seconds / 60, seconds % 60)
        timer.setTextColor(if (running) TEXT else FAINT)
    }

    override fun update(status: PcStatus?) {
        val old = slides
        slides = status?.slides
        val s = slides
        position.text = when {
            s == null || s.slide == 0 -> "—"
            else -> "${s.slide} / ${s.count}"
        }
        title.text = when {
            status == null -> "PC link not connected"
            s == null -> "PowerPoint isn't open on the computer"
            s.show && s.state == "done" -> "End of the show"
            else -> (if (s.show) "" else "Editing · ") + (s.title?.takeIf { it.isNotBlank() } ?: s.file)
        }
        val text = when {
            status == null -> "The buttons send the slide show's keys (→ ← B W F5 Esc).\n\nWith the PC link and " +
                "PowerPoint on the computer, this page shows each slide's speaker notes, the slide and the next one."
            s == null -> "Open a presentation in PowerPoint on the computer (not inside Citrix) to see its notes " +
                "here. The buttons still send the slide show's keys."
            s.slide == 0 -> ""
            s.notes.isBlank() -> "No notes on this slide."
            else -> s.notes
        }
        showNotes(text, message = s?.notes.isNullOrBlank())
        if (old?.slide != s?.slide) notesScroller.scrollTo(0, 0)
        (targetChip.getChildAt(1) as TextView).text = "Types in: ${typeTarget() ?: "the window in front"}"
        targetChip.visibility = if (s != null && NotesCommands.parse(s.notes).any { it is NotesCommands.Part.Command }) VISIBLE else GONE
        nextLabel.text = when {
            s == null || s.slide == 0 -> ""
            s.slide >= s.count -> "Last slide"
            else -> "Next: ${s.slide + 1}"
        }
        (showButton.getChildAt(1) as TextView).text = if (s?.show == true) "End show" else "Start show"
        tint(blackButton, s?.show == true && s.state == "black")
        tint(whiteButton, s?.show == true && s.state == "white")
        val teams = status?.teams
        muteButton.visibility = if (teams?.meeting == true) VISIBLE else GONE
        (muteButton.getChildAt(1) as TextView).text = if (teams?.muted == true) "Muted" else "Mute"
        tint(muteButton, teams?.muted == true, RED)
        current.screen = when (s?.state) { "black" -> "Black screen"; "white" -> "White screen"; else -> null }.takeIf { s?.show == true }
        updateTime()
        refreshImages()
    }

    private fun tint(chip: LinearLayout, on: Boolean, color: Int = AMBER) {
        chip.background = ui.pressable(if (on) color else Ui.RAISED, ui.dp(12).toFloat())
        val ink = if (on) Ui.inkOn(color) else TEXT
        (chip.getChildAt(1) as TextView).setTextColor(ink)
        (chip.getChildAt(0) as android.widget.ImageView).imageTintList = android.content.res.ColorStateList.valueOf(ink)
    }

    /** New slide pictures arrived. */
    fun refreshImages() {
        val s = slides
        val pictures = images().orEmpty()
        current.show(s?.slide?.takeIf { it > 0 }?.let { pictures[it] }, s?.slide ?: 0)
        val following = s?.slide?.takeIf { it in 1 until s.count }?.plus(1)
        next.show(following?.let { pictures[it] }, following ?: 0)
        gridCells?.forEachIndexed { i, cell -> cell.show(pictures[i + 1], i + 1) }
    }

    /** Every slide as a picture; tapping one goes there. */
    private fun showGrid() {
        val s = slides ?: return ui.toast("Open the presentation in PowerPoint on the computer first")
        if (s.count == 0) return
        onCommand("thumbs", 0)
        val grid = GridLayout(context).apply { columnCount = 4 }
        val cells = (1..s.count).map { SlidePicture(big = false) }
        val dialog = ui.dialog()
            .setTitle(s.file)
            .setView(ScrollView(context).apply {
                addView(grid.apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)) })
            })
            .setNegativeButton("Close", null)
            .create()
        cells.forEachIndexed { i, cell ->
            cell.isClickable = true
            cell.setOnClickListener {
                onCommand("goto", i + 1)
                dialog.dismiss()
            }
            cell.highlight = i + 1 == s.slide
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = ui.dp(GRID_CELL_HEIGHT_DP)
                setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            })
        }
        gridCells = cells
        dialog.setOnDismissListener { gridCells = null }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.85).toInt(), (resources.displayMetrics.heightPixels * 0.85).toInt())
        refreshImages()
    }

    /**
     * A slide's picture, fit in its box, or its number while the picture is on the way. The big one
     * drives PowerPoint's laser pointer: a red dot follows the finger on the slide.
     */
    private inner class SlidePicture(private val big: Boolean) : View(ui.context) {
        private var bitmap: Bitmap? = null
        private var number = 0
        /** "Black screen" / "White screen" over the picture. */
        var screen: String? = null
            set(value) { field = value; invalidate() }
        var highlight = false
        private var laser: Pair<Float, Float>? = null
        private var lastLaserAt = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()

        fun show(picture: Bitmap?, slide: Int) {
            if (picture === bitmap && slide == number) return
            bitmap = picture
            number = slide
            contentDescription = if (slide > 0) "Slide $slide" else null
            invalidate()
        }

        /** Where the picture is drawn: fit in the view, 16:9 while there is none. */
        private fun pictureBox(): RectF {
            val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
            val w = minOf(width.toFloat(), height * ratio)
            val h = w / ratio
            box.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            return box
        }

        override fun onDraw(canvas: Canvas) {
            val r = pictureBox()
            val corner = ui.dp(8).toFloat()
            val picture = bitmap
            if (picture != null && screen == null) {
                canvas.save()
                canvas.clipRect(r)
                canvas.drawBitmap(picture, null, r, paint)
                canvas.restore()
            } else {
                paint.color = when (screen) { "White screen" -> android.graphics.Color.WHITE; "Black screen" -> android.graphics.Color.BLACK; else -> PLATE }
                canvas.drawRoundRect(r, corner, corner, paint)
                val label = screen ?: if (number > 0) "$number" else ""
                paint.color = if (screen == "White screen") Ui.INK else FAINT
                paint.typeface = ui.fontTitle
                paint.textSize = ui.dpf(if (big && screen == null) 40f else 18f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, r.centerX(), r.centerY() + paint.textSize / 3, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ui.dpf(if (highlight) 3f else 1f)
            paint.color = if (highlight) BLUE else HAIRLINE_STRONG
            canvas.drawRoundRect(r, corner, corner, paint)
            paint.style = Paint.Style.FILL
            laser?.let { (x, y) ->
                paint.color = RED
                canvas.drawCircle(r.left + x * r.width(), r.top + y * r.height(), ui.dpf(7f), paint)
            }
            if (!big && number > 0 && picture != null) {
                // The number in a corner, for the grid and the next slide.
                paint.color = Ui.withAlpha(android.graphics.Color.BLACK, 0.6f)
                paint.textSize = ui.dpf(13f)
                paint.typeface = ui.fontLabel
                paint.textAlign = Paint.Align.LEFT
                val text = "$number"
                canvas.drawRoundRect(r.left + ui.dpf(4f), r.top + ui.dpf(4f), r.left + ui.dpf(12f) + paint.measureText(text),
                    r.top + ui.dpf(22f), ui.dpf(4f), ui.dpf(4f), paint)
                paint.color = TEXT
                canvas.drawText(text, r.left + ui.dpf(8f), r.top + ui.dpf(18f), paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!big) return super.onTouchEvent(event)
            if (slides?.show != true) return false
            val r = pictureBox()
            val x = ((event.x - r.left) / r.width()).coerceIn(0f, 1f)
            val y = ((event.y - r.top) / r.height()).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    laser = x to y
                    val now = SystemClock.uptimeMillis()
                    if (event.actionMasked == MotionEvent.ACTION_DOWN || now - lastLaserAt >= LASER_MS) {
                        lastLaserAt = now
                        onLaser(x, y)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    laser = null
                    onLaser(-1f, -1f)
                }
            }
            invalidate()
            return true
        }
    }

    private companion object {
        const val GRID_CELL_HEIGHT_DP = 110
        /** At most one laser position per this many ms: Bluetooth has the notes and pictures to carry too. */
        const val LASER_MS = 50L
    }
}
