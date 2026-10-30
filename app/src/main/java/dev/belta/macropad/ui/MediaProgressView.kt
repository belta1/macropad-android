package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.belta.macropad.pc.PcMedia
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE_STRONG

/**
 * The media card's progress: a thin bar of how far the song or video has played, and "1:23 / 3:45"
 * after it. It moves on by itself while playing (the computer only says when it jumps).
 */
@SuppressLint("ViewConstructor")
class MediaProgressView(private val ui: Ui, private val media: PcMedia, private val fill: Int, private val textColor: Int) : View(ui.context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ui.fontHint
        textSize = ui.dpf(11f) * ui.scale
    }

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, TICK_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (media.playing) post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As low as its text: the dock's card has room for one more short line under the artist.
        val height = paint.fontSpacing.toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val timeline = media.timeline ?: return
        val position = timeline.positionAt(System.currentTimeMillis(), media.playing)
        val text = "${clock(position)} / ${clock(timeline.length)}"
        contentDescription = "Played $text"
        val textWidth = paint.measureText(text)
        val bar = ui.dpf(3f)
        val y = height / 2f
        val end = width - textWidth - ui.dpf(8f)
        if (end > 0) {
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = bar
            paint.color = HAIRLINE_STRONG
            canvas.drawLine(bar / 2, y, end - bar / 2, y, paint)
            paint.color = fill
            val done = (bar / 2 + (end - bar) * (position / timeline.length)).toFloat()
            canvas.drawLine(bar / 2, y, done.coerceAtLeast(bar / 2), y, paint)
        }
        paint.color = textColor
        canvas.drawText(text, width - textWidth, y - (paint.descent() + paint.ascent()) / 2, paint)
    }

    private companion object {
        const val TICK_MS = 500L

        /** "3:07", or "1:02:07" from an hour. */
        fun clock(seconds: Double): String {
            val s = seconds.toLong()
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
