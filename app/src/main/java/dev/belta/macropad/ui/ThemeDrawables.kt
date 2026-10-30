package dev.belta.macropad.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.belta.macropad.ui.Ui.Companion.blend
import dev.belta.macropad.ui.Ui.Companion.withAlpha
import kotlin.random.Random

/**
 * [icon] with a soft halo of [color] behind it, as if the legend were lit from within (themes that
 * glow). The halo is the icon's own shape, blurred by [radius] px, rendered once per size.
 */
class GlowIcon(private val icon: Drawable, color: Int, private val radius: Float) : Drawable() {
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.color = withAlpha(color, 0.9f) }
    private var halo: Bitmap? = null
    private val offset = IntArray(2)

    override fun onBoundsChange(bounds: Rect) {
        icon.bounds = bounds
        halo?.recycle()
        halo = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val glow = halo ?: render(b).also { halo = it }
        canvas.drawBitmap(glow, (b.left + offset[0]).toFloat(), (b.top + offset[1]).toFloat(), haloPaint)
        icon.draw(canvas)
    }

    // A software canvas, where a blur mask works on any drawing.
    private fun render(b: Rect): Bitmap {
        val shape = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
        Canvas(shape).apply {
            translate(-b.left.toFloat(), -b.top.toFloat())
            icon.draw(this)
        }
        val blur = Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
        return shape.extractAlpha(blur, offset).also { shape.recycle() }
    }

    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        haloPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The case behind the keys, the tabs and the sidebar: plain, or the theme's scenery ([Backdrop]),
 * kept dim so it only shows between the keys and never fights their legends. Fixed patterns (a
 * seeded random), so the scenery doesn't change from one start to the next. [theme] is the one in
 * use, or another one for its preview in the theme picker.
 */
class ThemeBackdrop(private val ui: Ui, private val theme: Theme = Ui.theme) : Drawable() {
    private val case = theme.case
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = case
        canvas.drawRect(b, paint)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        when (theme.backdrop) {
            Backdrop.PLAIN -> Unit
            Backdrop.SUNSET_GRID -> sunsetGrid(canvas, w, h)
            Backdrop.SCANLINES -> scanlines(canvas, w, h)
            Backdrop.AURORA -> aurora(canvas, w, h)
            Backdrop.EMBERS -> embers(canvas, w, h)
            Backdrop.BLOSSOM -> blossom(canvas, w, h)
        }
        canvas.restore()
        paint.shader = null
    }

    /** A striped sun sinking behind a neon grid running to the horizon. */
    private fun sunsetGrid(canvas: Canvas, w: Float, h: Float) {
        val horizon = h * 0.62f
        val pink = theme.accents[3]
        val violet = theme.accents[4]
        // Sky: dusk rising from the horizon.
        paint.shader = LinearGradient(0f, 0f, 0f, horizon, case, blend(violet, case, 0.22f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, horizon, paint)

        // The sun: yellow to pink, sliced by bands that widen towards the horizon.
        val r = minOf(w, h) * 0.24f
        val cx = w / 2
        paint.shader = LinearGradient(0f, horizon - r, 0f, horizon, withAlpha(theme.accents[6], 0.55f),
            withAlpha(pink, 0.5f), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(0f, 0f, w, horizon)
        for (i in 0 until 6) {
            val bandTop = horizon - r * 0.55f + i * r * 0.1f
            canvas.clipOutRect(0f, bandTop, w, bandTop + ui.dpf(1.5f + i * 1.2f))
        }
        canvas.drawCircle(cx, horizon, r, paint)
        canvas.restore()

        // The floor, then its grid: lines crowd together towards the horizon.
        paint.shader = LinearGradient(0f, horizon, 0f, h, blend(violet, case, 0.16f), case, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, horizon, w, h, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        fun neonLine(x1: Float, y1: Float, x2: Float, y2: Float, fade: Float) {
            paint.strokeWidth = ui.dpf(4f)
            paint.color = withAlpha(pink, 0.10f * fade)
            canvas.drawLine(x1, y1, x2, y2, paint)
            paint.strokeWidth = ui.dpf(1.2f)
            paint.color = withAlpha(pink, 0.5f * fade)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        neonLine(0f, horizon, w, horizon, 1f)
        val rows = 9
        for (i in 1..rows) {
            val t = i / rows.toFloat()
            val y = horizon + (h - horizon) * t * t
            neonLine(0f, y, w, y, 0.4f + 0.6f * t)
        }
        val columns = 14
        for (i in 0..columns) {
            val x = -w + 3 * w * i / columns
            neonLine(cx + (x - cx) * 0.02f, horizon, x, h, 0.8f)
        }
        paint.style = Paint.Style.FILL
    }

    /** A CRT: fine scanlines, a phosphor glow in the middle and darker corners. */
    private fun scanlines(canvas: Canvas, w: Float, h: Float) {
        val green = theme.accents[1]
        paint.shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.7f,
            intArrayOf(withAlpha(green, 0.07f), withAlpha(green, 0.02f), withAlpha(0, 0.55f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = scanline
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    /** One dark line every 3 dp, repeated down the screen. */
    private val scanline by lazy {
        val line = Bitmap.createBitmap(1, maxOf(3, ui.dp(3)), Bitmap.Config.ARGB_8888).apply {
            eraseColor(0)
            setPixel(0, 0, withAlpha(0, 0.35f))
        }
        BitmapShader(line, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Curtains of green, cyan and violet light over a few stars. */
    private fun aurora(canvas: Canvas, w: Float, h: Float) {
        val random = Random(7)
        paint.shader = null
        repeat(60) {
            paint.color = withAlpha(theme.text, 0.12f + random.nextFloat() * 0.25f)
            canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h * 0.7f, ui.dpf(0.6f + random.nextFloat()), paint)
        }
        val colors = intArrayOf(theme.accents[1], theme.accents[5], theme.accents[4])
        for ((i, color) in colors.withIndex()) {
            val top = h * (0.04f + i * 0.07f)
            val depth = h * (0.22f - i * 0.04f)
            path.reset()
            path.moveTo(0f, top + depth * 0.4f)
            path.cubicTo(w * 0.3f, top - depth * 0.2f, w * 0.55f, top + depth * 0.9f, w, top + depth * 0.1f)
            path.lineTo(w, top + depth * 0.1f + depth)
            path.cubicTo(w * 0.6f, top + depth * 1.6f, w * 0.35f, top + depth * 0.7f, 0f, top + depth * 1.3f)
            path.close()
            paint.shader = LinearGradient(0f, top, 0f, top + depth * 1.5f, withAlpha(color, 0.16f), withAlpha(color, 0f),
                Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
        }
    }

    /** Coals glowing under the keys, sparks drifting up. */
    private fun embers(canvas: Canvas, w: Float, h: Float) {
        val orange = theme.accents[2]
        val red = theme.accents[3]
        paint.shader = RadialGradient(w / 2, h * 1.05f, maxOf(w, h) * 0.75f,
            intArrayOf(withAlpha(orange, 0.20f), withAlpha(red, 0.07f), withAlpha(red, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val random = Random(11)
        repeat(70) {
            // More sparks low down, where the fire is.
            val y = h * (1f - random.nextFloat() * random.nextFloat())
            val heat = y / h
            paint.color = withAlpha(if (random.nextBoolean()) orange else theme.accents[6], 0.15f + 0.45f * heat * random.nextFloat())
            canvas.drawCircle(random.nextFloat() * w, y, ui.dpf(0.8f + random.nextFloat() * 1.8f), paint)
        }
    }

    /** Pink light in a corner and petals drifting across. */
    private fun blossom(canvas: Canvas, w: Float, h: Float) {
        val pink = theme.accents[3]
        paint.shader = RadialGradient(w, 0f, maxOf(w, h) * 0.8f, withAlpha(pink, 0.14f), withAlpha(pink, 0f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val random = Random(5)
        repeat(26) {
            val size = ui.dpf(5f + random.nextFloat() * 5f)
            canvas.save()
            canvas.translate(random.nextFloat() * w, random.nextFloat() * h)
            canvas.rotate(random.nextFloat() * 360f)
            paint.color = withAlpha(if (random.nextInt(3) == 0) theme.text else pink, 0.12f + random.nextFloat() * 0.2f)
            rect.set(-size, -size * 0.55f, size, size * 0.55f)
            canvas.drawOval(rect, paint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) = Unit // the case is always opaque

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * A few keys of [theme] on its case, for the theme picker, drawn in that theme's colors and caps
 * whatever theme is in use. The third key is ON (lit).
 */
class ThemeSample(private val ui: Ui, private val theme: Theme) : Drawable() {
    private class Key(val accent: Int, val lit: Boolean, val icon: Drawable)

    private val backdrop = ThemeBackdrop(ui, theme)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val keys = listOf(0 to "play_pause", 1 to "volume_up", 3 to "bolt", 4 to "star").mapIndexed { i, (slot, name) ->
        val accent = theme.accents[slot]
        val lit = i == 2
        val icon = ui.context.getDrawable(Icons.get(name)!!)!!.mutate().apply {
            setTint(if (lit) theme.ink else Ui.lighten(accent, 0.15f))
        }
        Key(accent, lit, if (theme.glow > 0f && !lit) GlowIcon(icon, accent, ui.dpf(theme.glow * 0.6f)) else icon)
    }

    override fun onBoundsChange(bounds: Rect) {
        backdrop.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        backdrop.draw(canvas)
        val pad = ui.dpf(8f)
        val gap = ui.dpf(6f)
        val width = (b.width() - pad * 2 - gap * (keys.size - 1)) / keys.size
        val radius = ui.dpf(if (theme.caps == Caps.TERMINAL) 2f else 6f)
        for ((i, key) in keys.withIndex()) {
            val left = b.left + pad + i * (width + gap)
            rect.set(left, b.top + pad, left + width, b.bottom - pad)
            when (theme.caps) {
                Caps.SCULPTED -> {
                    paint.color = if (key.lit) Ui.darken(key.accent, 0.38f) else blend(key.accent, theme.capSkirt, 0.045f)
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    rect.set(rect.left + ui.dpf(3f), rect.top + ui.dpf(1f), rect.right - ui.dpf(3f), rect.bottom - ui.dpf(6f))
                    paint.color = if (key.lit) key.accent else blend(key.accent, theme.capTop, 0.045f)
                    canvas.drawRoundRect(rect, radius, radius, paint)
                }
                Caps.NEON -> {
                    rect.inset(ui.dpf(3f), ui.dpf(3f))
                    paint.color = if (key.lit) withAlpha(key.accent, 0.9f) else blend(key.accent, theme.capTop, 0.1f)
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    stroke.strokeWidth = ui.dpf(1.4f)
                    stroke.color = if (key.lit) Ui.lighten(key.accent, 0.35f) else key.accent
                    stroke.setShadowLayer(ui.dpf(4f), 0f, 0f, key.accent)
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                }
                Caps.TERMINAL -> {
                    paint.color = if (key.lit) key.accent else theme.capTop
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    stroke.strokeWidth = ui.dpf(1f)
                    stroke.color = withAlpha(key.accent, if (key.lit) 1f else 0.6f)
                    stroke.setShadowLayer(ui.dpf(2f), 0f, 0f, withAlpha(key.accent, 0.45f))
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                }
            }
            val size = (minOf(rect.width(), rect.height()) * 0.5f).toInt()
            val cx = rect.centerX().toInt()
            val cy = rect.centerY().toInt()
            key.icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            key.icon.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
