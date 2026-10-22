package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.model.MacroButton
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.Page
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.STROKE_STRONG
import dev.belta.macropad.ui.Ui.Companion.SURFACE
import dev.belta.macropad.ui.Ui.Companion.SURFACE_HI
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.blend
import dev.belta.macropad.ui.Ui.Companion.darken
import dev.belta.macropad.ui.Ui.Companion.lighten
import dev.belta.macropad.ui.Ui.Companion.withAlpha

/**
 * Builds key views: tinted icon badge, label and a muted shortcut hint on a dark surface washed
 * with the key's accent color. Compact keys (the dock) lay the icon and label side by side, or
 * show just a larger icon when [build]'s `iconOnly` is set (media keys).
 */
class KeyViews(private val ui: Ui, private val listener: Listener) {

    interface Listener {
        fun onKeyPressed(view: View, page: Page, index: Int, button: MacroButton)
        fun onKeyReleased(view: View, page: Page, index: Int, button: MacroButton, cancelled: Boolean)
        fun onEditKey(page: Page, index: Int)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun build(
        page: Page, index: Int, button: MacroButton?,
        compact: Boolean, editing: Boolean, toggledOn: Boolean = false, emphasize: Boolean = false,
        iconOnly: Boolean = false, showHint: Boolean = true, small: Boolean = false,
    ): View {
        val key = LinearLayout(ui.context).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            isHapticFeedbackEnabled = true
        }
        val radius = ui.dp(if (compact) 18 else 20).toFloat()

        if (button == null) {
            if (editing) {
                key.background = GradientDrawable().apply {
                    cornerRadius = radius
                    setStroke(ui.dp(2), STROKE_STRONG, ui.dp(8).toFloat(), ui.dp(6).toFloat())
                }
                key.addView(ui.icon("add", MUTED), LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
            }
        } else {
            val accent = button.color
            val filled = emphasize || toggledOn
            key.background = keyBackground(accent, radius, filled)
            val iconRes = Icons.get(button.icon)
            if (compact && iconOnly && iconRes != null) {
                key.contentDescription = button.label
                key.addView(ui.icon(button.icon!!, if (filled) Color.WHITE else lighten(accent, 0.35f)),
                    LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
            } else if (compact) {
                if (iconRes != null) key.addView(ui.icon(button.icon!!, if (filled) Color.WHITE else accent),
                    LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
                key.addView(label(button.label, 14f, 15f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(if (iconRes != null) ui.dp(10) else 0, 0, 0, 0)
                })
            } else if (!small && iconRes != null) {
                // Card: icon badge top-left, label and shortcut pill at the bottom-left.
                key.gravity = Gravity.START
                key.setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12))
                key.addView(badge(button.icon!!, accent, filled, 22, 38))
                key.addView(label(button.label, 12f, 18f).apply {
                    gravity = Gravity.START or Gravity.BOTTOM
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                })
            } else {
                if (iconRes != null) key.addView(badge(button.icon!!, accent, filled, 22, 36).apply {
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(4)
                })
                key.addView(label(button.label, if (small) 12f else 13f, if (small) 15f else 24f).apply {
                    if (!small) layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                })
            }
            // The shortcut as a small keycap-style pill under the label.
            if (!compact && (showHint || button.toggle)) key.addView(TextView(ui.context).apply {
                val hint = Macro.describe(button.action) + if (button.longAction.isNullOrBlank()) "" else " · +hold"
                text = when {
                    // A hint that only repeats the label (numpad digits, "Tab") adds nothing.
                    !button.toggle && hint.equals(button.label, ignoreCase = true) -> ""
                    !button.toggle -> hint
                    toggledOn -> "ON · $hint"
                    else -> "OFF · $hint"
                }
                typeface = if (toggledOn) ui.fontBold else ui.fontMedium
                setTextColor(if (filled) withAlpha(Color.WHITE, 0.9f) else MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
                if (text.isNotEmpty()) background = ui.roundRect(
                    withAlpha(Color.WHITE, if (filled) 0.16f else 0.06f), ui.dp(8).toFloat())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(4)
            })
            if (editing) key.foreground = editBadge(radius)
        }

        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (button != null || editing) {
                        v.isPressed = true
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).setInterpolator(null).start()
                    }
                    if (!editing && button != null) listener.onKeyPressed(v, page, index, button)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(3f)).start()
                    val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                    when {
                        editing && !cancelled -> listener.onEditKey(page, index)
                        !editing && button != null -> listener.onKeyReleased(v, page, index, button, cancelled)
                    }
                }
            }
            true
        }
        return key
    }

    /** Swaps [old] for a freshly built view in the same slot (keeps layout params). */
    fun replace(old: View, new: View) {
        val parent = old.parent as? ViewGroup ?: return
        val at = parent.indexOfChild(old)
        val params = old.layoutParams
        parent.removeViewAt(at)
        parent.addView(new, at, params)
    }

    private fun badge(icon: String, accent: Int, filled: Boolean, iconDp: Int, sizeDp: Int) = FrameLayout(ui.context).apply {
        background = ui.roundRect(withAlpha(if (filled) Color.WHITE else accent, if (filled) 0.2f else 0.16f), ui.dp(12).toFloat())
        addView(ui.icon(icon, if (filled) Color.WHITE else lighten(accent, 0.15f)),
            FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
    }

    private fun label(text: String, minSp: Float, maxSp: Float) = TextView(ui.context).apply {
        this.text = text
        typeface = ui.fontSemiBold
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        maxLines = 2
        setAutoSizeTextTypeUniformWithConfiguration(minSp.toInt(), maxSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(
            if (maxSp > 15f) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ui.dp(if (maxSp > 15f) (maxSp * 2.6f).toInt() else 24),
        )
    }

    private fun keyBackground(accent: Int, radius: Float, filled: Boolean): Drawable {
        fun face(pressed: Boolean) = if (filled) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(if (pressed) lighten(accent, 0.25f) else accent, darken(accent, if (pressed) 0.15f else 0.3f)))
                .apply { cornerRadius = radius }
        } else {
            // Flat dark card with a soft glow of the accent from the top edge.
            LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                    blend(accent, SURFACE_HI, if (pressed) 0.30f else 0.06f), SURFACE,
                )).apply { cornerRadius = radius },
                GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    setGradientCenter(0.5f, 0f)
                    gradientRadius = ui.dp(150).toFloat()
                    colors = intArrayOf(withAlpha(accent, if (pressed) 0.42f else 0.17f), withAlpha(accent, 0f))
                    cornerRadius = radius
                },
                GradientDrawable().apply {
                    cornerRadius = radius
                    setStroke(ui.dp(1), if (pressed) withAlpha(accent, 0.75f) else withAlpha(Color.WHITE, 0.07f))
                },
            ))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(true))
            addState(intArrayOf(), face(false))
        }
    }

    /** Small pencil in the corner of every key while editing. */
    private fun editBadge(radius: Float): Drawable {
        val pencil = ui.context.getDrawable(Icons.get("edit")!!)!!.mutate().apply { setTint(TEXT) }
        val dot = ui.roundRect(withAlpha(Color.BLACK, 0.55f), ui.dp(13).toFloat())
        return LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = radius; setStroke(ui.dp(2), withAlpha(Color.WHITE, 0.35f)) },
            dot, pencil,
        )).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.END)
            setLayerSize(1, ui.dp(26), ui.dp(26))
            setLayerInset(1, 0, ui.dp(8), ui.dp(8), 0)
            setLayerGravity(2, Gravity.TOP or Gravity.END)
            setLayerSize(2, ui.dp(14), ui.dp(14))
            setLayerInset(2, 0, ui.dp(14), ui.dp(14), 0)
        }
    }
}
