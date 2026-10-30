package dev.belta.macropad.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.model.LiveLabels
import dev.belta.macropad.model.MacroButton
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.Page
import dev.belta.macropad.pc.PcMedia
import dev.belta.macropad.ui.Ui.Companion.HAIRLINE_STRONG
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.inkOn
import dev.belta.macropad.ui.Ui.Companion.legendTone
import dev.belta.macropad.ui.Ui.Companion.withAlpha

/**
 * Builds key views: a [KeyCap] with its legends printed on the face — the icon in the key's color,
 * the label in warm white and the shortcut as a smaller secondary legend. Keys with an on/off state
 * carry an indicator light and fill with their color while ON. Compact keys (the dock) lay the icon
 * and label side by side, or show just the icon when [build]'s `iconOnly` is set (media keys).
 */
class KeyViews(private val ui: Ui, private val listener: Listener) {

    interface Listener {
        fun onKeyPressed(view: View, page: Page, index: Int, button: MacroButton)
        fun onKeyReleased(view: View, page: Page, index: Int, button: MacroButton, cancelled: Boolean)
        fun onEditKey(page: Page, index: Int)
        fun onSlide(page: Page, index: Int, button: MacroButton, up: Boolean)
        /** The slider's icon was tapped (its tap step, e.g. Mute). */
        fun onSlideTap(page: Page, index: Int, button: MacroButton)
        /** An absolute slider (one given a `sliderLevel`) was moved to [level]; [done] once the finger lifts. */
        fun onSlideLevel(page: Page, index: Int, button: MacroButton, level: Int, done: Boolean)
        /** One of the media card's ⏮ ⏯ ⏭ was tapped: [action] is its media key. */
        fun onCardButton(action: String) {}
    }

    companion object {
        /** A key showing what's playing (a live label) whose action is Play / Pause: drawn with ⏮ ⏯ ⏭ as a card. */
        fun isMediaCard(button: MacroButton?) =
            button != null && button.action.trim().lowercase() == "media:playpause" && LiveLabels.isLive(button.label)
    }

    /**
     * [stateful] keys (toggles, and lock keys showing the computer's Caps / Num Lock) get the
     * indicator light; [toggledOn] lights it and fills the cap. A slider with a [sliderLevel]
     * (0–100) is absolute and shows that level ([SliderView]). The media card shows how far
     * [media] has played, when its app says.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun build(
        page: Page, index: Int, button: MacroButton?,
        compact: Boolean, editing: Boolean, toggledOn: Boolean = false, emphasize: Boolean = false,
        iconOnly: Boolean = false, showHint: Boolean = true, small: Boolean = false,
        stateful: Boolean = button?.toggle == true, sliderLevel: Int? = null, sliderMuted: Boolean = false,
        card: Boolean = false, media: PcMedia? = null,
    ): View {
        // Sliders are dragged; in edit mode they show as a normal key so a tap opens the editor.
        if (button != null && !editing && Macro.slider(button.action) != null) {
            return SliderView(ui, button, KeyCap(ui, Ui.accent(button.color), lit = false), compact,
                onTap = { listener.onSlideTap(page, index, button) },
                level = sliderLevel, muted = sliderMuted,
                onLevel = { level, done -> listener.onSlideLevel(page, index, button, level, done) }) { up ->
                listener.onSlide(page, index, button, up)
            }
        }
        val key = LinearLayout(ui.context).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isHapticFeedbackEnabled = true
        }
        val cap = KeyCap(ui, button?.color ?: 0, lit = false)

        if (button == null) {
            if (!editing) return key.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            // An empty switch socket: the plate shows through, with a + to add a key.
            key.background = socket()
            key.contentDescription = "Add a key"
            key.addView(ui.icon("add", MUTED), LinearLayout.LayoutParams(ui.sdp(26), ui.sdp(26)))
        } else {
            val lit = stateful && toggledOn
            val color = Ui.accent(button.color)
            key.background = KeyCap(ui, color, lit, led = if (stateful) toggledOn else null, editing = editing,
                dye = if (emphasize) 0.16f else 0.035f)
            // Legends sit on the cap's face, not its skirt.
            val side = cap.insetSide.toInt() + ui.dp(if (compact) 8 else 12)
            key.setPadding(side, cap.insetTop.toInt() + ui.dp(if (compact) 4 else 11), side,
                cap.insetBottom.toInt() + ui.dp(if (compact) 2 else 9))

            val ink = inkOn(color)
            val iconTint = if (lit) ink else legendTone(color)
            val textColor = if (lit) ink else TEXT
            val hintColor = if (lit) withAlpha(ink, 0.75f) else MUTED
            // On themes that glow, legends glow in the key's color (not on a lit key's bright face).
            val halo = if (lit) null else color
            val iconRes = Icons.get(button.icon)
            var alignStart = false
            when {
                compact && iconOnly && iconRes != null ->
                    key.addView(ui.legendIcon(button.icon!!, iconTint, halo), LinearLayout.LayoutParams(ui.sdp(28), ui.sdp(28)))
                compact && (card || '\n' in button.label) -> {
                    // A card (what's playing): the icon, then the first line over the second, muted.
                    // The media card has ⏮ ⏯ ⏭ at its end instead of the icon.
                    val transport = card && button.action.trim().lowercase() == "media:playpause"
                    key.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    if (iconRes != null && !transport) key.addView(ui.legendIcon(button.icon!!, iconTint, halo),
                        LinearLayout.LayoutParams(ui.sdp(24), ui.sdp(24)).apply { marginEnd = ui.dp(12) })
                    val lines = button.label.split('\n', limit = 2)
                    key.addView(LinearLayout(ui.context).apply {
                        orientation = LinearLayout.VERTICAL
                        val styles = listOf(textColor to 15f, hintColor to 13.5f)
                        for ((i, text) in lines.withIndex()) {
                            val (lineColor, size) = styles[i]
                            addView(TextView(ui.context).apply {
                                this.text = text
                                typeface = if (i == 0) ui.fontLabel else ui.fontHint
                                setTextColor(lineColor)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, size * ui.scale)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                if (i == 0) ui.glow(this, halo)
                            })
                        }
                        if (transport && media?.timeline != null) addView(MediaProgressView(ui, media, iconTint, hintColor),
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = ui.dp(1)
                            })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (transport) {
                        val buttons = listOf(
                            Triple("prev", "media:prev", "Previous"),
                            Triple(button.icon ?: "play_pause", "media:playpause", "Play / Pause"),
                            Triple("next", "media:next", "Next"),
                        )
                        for ((icon, action, name) in buttons) {
                            key.addView(ui.legendIcon(icon, if (action == "media:playpause") textColor else iconTint, halo).apply {
                                val pad = ui.dp(10)
                                setPadding(pad, pad, pad, pad)
                                background = ui.pressable(Color.TRANSPARENT, ui.dpf(24f))
                                contentDescription = name
                                isHapticFeedbackEnabled = true
                                setOnClickListener {
                                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    listener.onCardButton(action)
                                }
                            }, LinearLayout.LayoutParams(ui.sdp(52), ui.sdp(52)).apply { marginStart = ui.dp(4) })
                        }
                    }
                }
                compact -> {
                    if (iconRes != null) key.addView(ui.legendIcon(button.icon!!, iconTint, halo),
                        LinearLayout.LayoutParams(ui.sdp(22), ui.sdp(22)))
                    key.addView(TextView(ui.context).apply {
                        text = button.label
                        typeface = ui.fontLabel
                        setTextColor(textColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * ui.scale)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(if (iconRes != null) ui.dp(8) else 0, 0, 0, 0)
                        ui.glow(this, halo)
                    })
                }
                !small && iconRes != null -> {
                    // Icon top-left, the name along the bottom: how a keycap's legends are printed.
                    alignStart = true
                    key.gravity = Gravity.START
                    key.addView(ui.legendIcon(button.icon!!, iconTint, halo), LinearLayout.LayoutParams(ui.sdp(26), ui.sdp(26)))
                    key.addView(label(button.label, textColor, 14f, 20f, Gravity.START or Gravity.BOTTOM).apply { ui.glow(this, halo) })
                }
                else -> {
                    if (iconRes != null) key.addView(ui.legendIcon(button.icon!!, iconTint, halo),
                        LinearLayout.LayoutParams(ui.sdp(26), ui.sdp(26)).apply { bottomMargin = ui.dp(4) })
                    // Without an icon the label is the legend, as big as the key allows (numpad digits).
                    val max = when {
                        small -> 17f
                        iconRes == null -> 32f
                        else -> 20f
                    }
                    key.addView(label(button.label, textColor, 14f, max, Gravity.CENTER).apply {
                        ui.glow(this, halo)
                        // Sidebar keys get shorter in edit mode (the free slot shows): a plain size,
                        // since auto-sizing would need a fixed height.
                        if (small) {
                            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * ui.scale)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                    })
                }
            }

            // The hint line keeps its room even when empty, so labels in a row stay level.
            val hint = hint(button)
            if (!compact && showHint) key.addView(TextView(ui.context).apply {
                text = hint
                if (hint.isEmpty()) visibility = View.INVISIBLE
                typeface = ui.fontHint
                setTextColor(hintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * ui.scale)
                gravity = if (alignStart) Gravity.START else Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            key.contentDescription = listOfNotNull(
                button.label,
                hint.takeIf { it.isNotEmpty() },
                if (stateful) (if (toggledOn) "on" else "off") else null,
                if (editing) "tap to edit" else null,
            ).joinToString(", ")
        }

        // The legends ride down with the cap's face.
        fun press(down: Boolean) {
            key.isPressed = down
            val drop = if (down) cap.travel else 0f
            for (i in 0 until key.childCount) key.getChildAt(i).translationY = drop
        }
        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    press(true)
                    if (!editing && button != null) listener.onKeyPressed(v, page, index, button)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    press(false)
                    val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                    when {
                        editing && !cancelled -> listener.onEditKey(page, index)
                        !editing && button != null -> listener.onKeyReleased(v, page, index, button, cancelled)
                    }
                }
            }
            true
        }
        // Touches are handled above; this is the accessibility action (TalkBack's double tap).
        key.setOnClickListener { v ->
            when {
                editing -> listener.onEditKey(page, index)
                button != null -> {
                    listener.onKeyPressed(v, page, index, button)
                    listener.onKeyReleased(v, page, index, button, cancelled = false)
                }
            }
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

    /** The shortcut, unless it only repeats the label (numpad digits, "Tab"). */
    private fun hint(button: MacroButton): String {
        val shortcut = Macro.describe(button.action)
            .takeIf { button.action.isNotBlank() && !it.equals(button.label, ignoreCase = true) }
        val hold = !button.longAction.isNullOrBlank()
        return when {
            shortcut != null && hold -> "$shortcut, or hold"
            shortcut != null -> shortcut
            hold -> "Hold for more"
            else -> ""
        }
    }

    private fun label(text: String, color: Int, minSp: Float, maxSp: Float, gravity: Int) = TextView(ui.context).apply {
        this.text = text
        typeface = ui.fontLabel
        setTextColor(color)
        this.gravity = gravity
        maxLines = 2
        setLineSpacing(0f, 0.92f)
        setAutoSizeTextTypeUniformWithConfiguration((minSp * ui.scale).toInt(), (maxSp * ui.scale).toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun socket(): LayerDrawable {
        val radius = ui.dpf(11f)
        val outline = ShapeDrawable(RoundRectShape(FloatArray(8) { radius }, null, null)).apply {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ui.dpf(1.5f)
            paint.color = HAIRLINE_STRONG
            paint.pathEffect = DashPathEffect(floatArrayOf(ui.dpf(6f), ui.dpf(5f)), 0f)
            paint.isAntiAlias = true
        }
        return LayerDrawable(arrayOf(GradientDrawable().apply { setColor(PLATE); cornerRadius = radius }, outline)).apply {
            setLayerInset(1, ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
        }
    }
}
