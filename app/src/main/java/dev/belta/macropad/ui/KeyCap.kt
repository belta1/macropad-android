package dev.belta.macropad.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.belta.macropad.ui.Ui.Companion.CAP_SKIRT
import dev.belta.macropad.ui.Ui.Companion.CAP_TOP
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.blend
import dev.belta.macropad.ui.Ui.Companion.darken
import dev.belta.macropad.ui.Ui.Companion.lighten
import dev.belta.macropad.ui.Ui.Companion.withAlpha

/**
 * A keycap seen from slightly in front: a top face sitting in a darker skirt, with a shadow on the
 * plate below. While pressed the face drops [travel] into the skirt, so the key visibly goes down
 * (the key's legends move with it, see [KeyViews]).
 *
 * The cap is graphite dyed with [dye] of [accent]; [lit] fills the face with the accent (toggle
 * and lock keys that are ON). [led] = null for keys without an on/off state, otherwise whether the
 * indicator is on. [editing] outlines the face (tap to edit).
 */
class KeyCap(
    ui: Ui, accent: Int, private val lit: Boolean, private val led: Boolean? = null,
    private val editing: Boolean = false, dye: Float = 0.045f,
) : Drawable() {

    val travel = ui.dpf(3f)
    /** Face insets from the drawable's bounds (unpressed): legends should sit inside them. */
    val insetSide = ui.dpf(4f)
    val insetTop = ui.dpf(2f)
    val insetBottom = ui.dpf(10f)

    private val ui1 = ui.dpf(1f)
    private val shadowDrop = ui.dpf(2f)
    private val outerRadius = ui.dpf(11f)
    private val faceRadius = ui.dpf(8f)
    private val ledRadius = ui.dpf(3.5f)
    private val ledInset = ui.dpf(11f)

    private val face = if (lit) accent else blend(accent, CAP_TOP, dye)
    private val skirt = if (lit) darken(accent, 0.38f) else blend(accent, CAP_SKIRT, dye)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dpf(1f)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dpf(1.5f)
        color = withAlpha(TEXT, 0.45f)
        pathEffect = DashPathEffect(floatArrayOf(ui.dpf(6f), ui.dpf(4f)), 0f)
    }
    private val rect = RectF()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bottom = b.bottom.toFloat()

        // Shadow on the plate, then the skirt.
        paint.shader = null
        paint.color = withAlpha(PLATE, if (pressed) 0.5f else 0.9f)
        rect.set(l + ui1, t + shadowDrop, r - ui1, bottom)
        canvas.drawRoundRect(rect, outerRadius, outerRadius, paint)
        // The skirt catches a little light along its top rim and falls into shade at the front.
        val rim = if (pressed) darken(skirt, 0.08f) else skirt
        paint.shader = LinearGradient(0f, t, 0f, bottom, lighten(rim, 0.07f), darken(rim, 0.18f), Shader.TileMode.CLAMP)
        rect.set(l, t, r, bottom - shadowDrop)
        canvas.drawRoundRect(rect, outerRadius, outerRadius, paint)
        paint.shader = null

        // Top face: slightly lighter at the far edge, where the light catches it.
        val drop = if (pressed) travel else 0f
        val faceTop = t + insetTop + drop
        val faceBottom = bottom - insetBottom + drop * 0.6f
        val base = if (pressed) darken(face, if (lit) 0.08f else 0.05f) else face
        rect.set(l + insetSide, faceTop, r - insetSide, faceBottom)
        paint.shader = LinearGradient(0f, faceTop, 0f, faceBottom, lighten(base, 0.05f), base, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, faceRadius, faceRadius, paint)
        paint.shader = null
        edge.color = withAlpha(Color.WHITE, if (lit) 0.22f else 0.06f)
        canvas.drawRoundRect(rect, faceRadius, faceRadius, edge)
        if (editing) {
            rect.inset(ui1 * 2, ui1 * 2)
            canvas.drawRoundRect(rect, faceRadius, faceRadius, dashed)
        }

        led?.let { on ->
            val cx = r - insetSide - ledInset
            val cy = faceTop + ledInset
            if (on) {
                paint.shader = RadialGradient(cx, cy, ledRadius * 3.2f,
                    withAlpha(Color.WHITE, 0.55f), withAlpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, ledRadius * 3.2f, paint)
                paint.shader = null
                paint.color = Color.WHITE
            } else {
                paint.color = darken(face, 0.35f)
            }
            canvas.drawCircle(cx, cy, ledRadius, paint)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_pressed in state
        if (now == pressed) return false
        pressed = now
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) = Unit // keys are always drawn opaque

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
