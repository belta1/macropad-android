package dev.belta.macropad.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.belta.macropad.model.Palette
import dev.belta.macropad.ui.Ui.Companion.darken
import dev.belta.macropad.ui.Ui.Companion.lighten

/**
 * App-style icons for the page tabs, drawn in code: brand-colored tiles with a letter (Office apps)
 * or a white glyph, and simplified Windows and Chrome marks. Keys are [DefaultLayout.APP_ICONS].
 */
object AppIcons {

    private sealed class Spec {
        class Tile(val color: Int, val letter: String? = null, val glyph: String? = null, val round: Boolean = false) : Spec()
        object Windows : Spec()
        object Chrome : Spec()
    }

    private val specs = mapOf(
        "windows" to Spec.Windows,
        "monitors" to Spec.Tile(0xFF0E9AA7.toInt(), glyph = "task_view"),
        "switcher" to Spec.Tile(0xFF2F6DB5.toInt(), glyph = "swap"),
        "screen" to Spec.Tile(0xFF1E7F8C.toInt(), glyph = "screen_share"),
        "mixer" to Spec.Tile(0xFF7A52CC.toInt(), glyph = "tune"),
        "clipboard" to Spec.Tile(0xFF23947F.toInt(), glyph = "paste"),
        "home" to Spec.Tile(0xFF1A8FC9.toInt(), glyph = "home"),
        "vscode" to Spec.Tile(0xFF0A7ACC.toInt(), glyph = "code"),
        "python" to Spec.Tile(0xFF3776AB.toInt(), glyph = "terminal"),
        "chrome" to Spec.Chrome,
        "edge" to Spec.Tile(0xFF1B8FC4.toInt(), letter = "e", round = true),
        "excel" to Spec.Tile(0xFF107C41.toInt(), letter = "X"),
        "word" to Spec.Tile(0xFF185ABD.toInt(), letter = "W"),
        "powerpoint" to Spec.Tile(0xFFC43E1C.toInt(), letter = "P"),
        "outlook" to Spec.Tile(0xFF0F6CBD.toInt(), letter = "O"),
        "teams" to Spec.Tile(0xFF5B5FC7.toInt(), letter = "T"),
        "citrix" to Spec.Tile(0xFF4B3A8C.toInt(), glyph = "desktop"),
        "phrases" to Spec.Tile(0xFFE8833A.toInt(), glyph = "chat"),
        "numpad" to Spec.Tile(0xFF5D6B82.toInt(), glyph = "hash"),
        "trackpad" to Spec.Tile(0xFF5D6B82.toInt(), glyph = "mouse"),
        "custom" to Spec.Tile(0xFFD9A514.toInt(), glyph = "star"),
    )

    /** Display names for the page menu's icon chooser. */
    val names = mapOf(
        "windows" to "Windows", "monitors" to "Monitors / windows", "switcher" to "Window switcher", "screen" to "Screen mirror",
        "vscode" to "VS Code", "python" to "Python", "chrome" to "Chrome", "mixer" to "Volume mixer",
        "clipboard" to "PC clipboard", "home" to "Home Assistant",
        "edge" to "Edge", "excel" to "Excel", "word" to "Word", "powerpoint" to "PowerPoint", "outlook" to "Outlook", "teams" to "Teams",
        "citrix" to "Citrix / remote desktop", "phrases" to "Phrases", "numpad" to "Numpad",
        "trackpad" to "Trackpad", "custom" to "Star",
    )

    /** Icon [key], or a tile with the page name's first letter when it is unknown. */
    fun drawable(ui: Ui, key: String?, pageName: String): Drawable = AppIconDrawable(ui,
        specs[key] ?: Spec.Tile(Palette.colors[Math.floorMod(pageName.hashCode(), Palette.colors.size)],
            letter = pageName.trim().take(1).uppercase().ifEmpty { "?" }))

    private class AppIconDrawable(private val ui: Ui, private val spec: Spec) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var alpha = 255

        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat()
            val left = b.exactCenterX() - size / 2
            val top = b.exactCenterY() - size / 2
            val box = RectF(left, top, left + size, top + size)
            paint.alpha = alpha
            when (spec) {
                is Spec.Tile -> tile(canvas, box, spec)
                Spec.Windows -> windows(canvas, box)
                Spec.Chrome -> chrome(canvas, box)
            }
        }

        private fun tile(canvas: Canvas, box: RectF, spec: Spec.Tile) {
            val size = box.width()
            paint.shader = LinearGradient(box.left, box.top, box.right, box.bottom,
                lighten(spec.color, 0.18f), darken(spec.color, 0.12f), Shader.TileMode.CLAMP)
            val radius = if (spec.round) size / 2 else size * 0.27f
            canvas.drawRoundRect(box, radius, radius, paint)
            paint.shader = null
            spec.letter?.let { letter ->
                paint.color = Color.WHITE
                paint.alpha = alpha
                paint.typeface = ui.fontTitle
                paint.textSize = size * 0.58f
                paint.textAlign = Paint.Align.CENTER
                val baseline = box.centerY() - (paint.descent() + paint.ascent()) / 2
                canvas.drawText(letter, box.centerX(), baseline, paint)
            }
            spec.glyph?.let { name ->
                val icon = Icons.get(name)?.let { ui.context.getDrawable(it) }?.mutate() ?: return
                val inset = (size * 0.2f).toInt()
                icon.setBounds(box.left.toInt() + inset, box.top.toInt() + inset,
                    box.right.toInt() - inset, box.bottom.toInt() - inset)
                icon.setTint(Color.WHITE)
                icon.alpha = alpha
                icon.draw(canvas)
            }
        }

        /** Four squares, Windows 11 style. */
        private fun windows(canvas: Canvas, box: RectF) {
            val area = RectF(box).apply { inset(box.width() * 0.08f, box.height() * 0.08f) }
            val gap = area.width() * 0.08f
            val half = (area.width() - gap) / 2
            paint.shader = LinearGradient(area.left, area.top, area.right, area.bottom,
                0xFF4CC2FF.toInt(), 0xFF0067C0.toInt(), Shader.TileMode.CLAMP)
            for (row in 0..1) for (col in 0..1) {
                val l = area.left + col * (half + gap)
                val t = area.top + row * (half + gap)
                canvas.drawRoundRect(l, t, l + half, t + half, half * 0.12f, half * 0.12f, paint)
            }
            paint.shader = null
        }

        /** Red, yellow and green thirds around a white ring and a blue center. */
        private fun chrome(canvas: Canvas, box: RectF) {
            paint.style = Paint.Style.FILL
            val thirds = listOf(210f to 0xFFEA4335.toInt(), 330f to 0xFFFBBC04.toInt(), 90f to 0xFF34A853.toInt())
            for ((start, color) in thirds) {
                paint.color = color
                paint.alpha = alpha
                canvas.drawArc(box, start, 120.5f, true, paint)
            }
            val r = box.width() / 2
            paint.color = Color.WHITE
            paint.alpha = alpha
            canvas.drawCircle(box.centerX(), box.centerY(), r * 0.47f, paint)
            paint.color = 0xFF1A73E8.toInt()
            paint.alpha = alpha
            canvas.drawCircle(box.centerX(), box.centerY(), r * 0.37f, paint)
        }

        override fun setAlpha(alpha: Int) {
            this.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
