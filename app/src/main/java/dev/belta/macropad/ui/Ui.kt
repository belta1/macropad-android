package dev.belta.macropad.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.belta.macropad.R
import dev.belta.macropad.model.Palette

/**
 * Theme, fonts and small view/drawable helpers shared by the UI classes.
 *
 * The look is a keyboard: keys are keycaps ([KeyCap]) on a dark case, and only things that send
 * keystrokes look like keys. Tabs, app controls and banners are flat. The colors, the caps and the
 * fonts come from the [theme] chosen in ⚙ → Theme (Graphite by default).
 */
class Ui(val context: Context) {
    private fun font(barlow: Int, monoStyle: Int): Typeface =
        if (theme.mono) Typeface.create(Typeface.MONOSPACE, monoStyle) else context.resources.getFont(barlow)

    /** Dialog text and messages. */
    val fontBody: Typeface = font(R.font.barlow_medium, Typeface.NORMAL)
    /** Key legends, tab names, buttons. */
    val fontLabel: Typeface = font(R.font.barlow_sc_semibold, Typeface.BOLD)
    /** Shortcut hints and other secondary legends. */
    val fontHint: Typeface = font(R.font.barlow_sc_medium, Typeface.NORMAL)
    /** Titles and the selected tab. */
    val fontTitle: Typeface = font(R.font.barlow_sc_bold, Typeface.BOLD)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /**
     * How much bigger key legends, icons, the dock and the sidebar are on larger tablets: 1 on an
     * 8" tablet (600 dp, Fire HD 8), up to 1.3 on a 10–11" one (Galaxy Tab S5e is about 800 dp).
     */
    val scale = (context.resources.configuration.smallestScreenWidthDp / 600f).coerceIn(1f, 1.3f)

    /** [dp] times [scale]: sizes that grow with the screen. */
    fun sdp(value: Int) = dp(Math.round(value * scale))
    fun dpf(value: Float) = value * context.resources.displayMetrics.density

    fun dialog(): AlertDialog.Builder = MenuDialogBuilder(this)

    /**
     * Opens the on-screen keyboard when [field] is focused. Fire OS sometimes leaves it closed for
     * fields inside dialogs.
     */
    fun showKeyboardOnFocus(field: EditText) {
        field.setOnFocusChangeListener { v, focused ->
            if (focused) v.post {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun pressable(color: Int, radius: Float) = StateListDrawable().apply {
        val pressed = if (Color.alpha(color) == 0) RAISED else lighten(color, 0.10f)
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(pressed, radius))
        addState(intArrayOf(), roundRect(color, radius))
    }

    fun icon(name: String, tint: Int) = ImageView(context).apply {
        Icons.get(name)?.let { setImageResource(it) }
        imageTintList = ColorStateList.valueOf(tint)
    }

    /** Flat app control (record, edit, settings…): no keycap, since it doesn't send anything. */
    fun iconButton(icon: String, description: String, onClick: () -> Unit) = ImageView(context).apply {
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setIconButton(this, icon, null)
        setOnClickListener { onClick() }
    }

    /** [fill] marks an active control (editing, recording); its icon takes the matching ink. */
    fun setIconButton(view: ImageView, icon: String, fill: Int?, tint: Int = TEXT) {
        view.setImageResource(Icons.get(icon)!!)
        view.imageTintList = ColorStateList.valueOf(if (fill != null) inkOn(fill) else tint)
        view.background = pressable(fill ?: Color.TRANSPARENT, dp(12).toFloat())
    }

    /** Text button with an icon; [fill] makes it the main one (Save). */
    fun chip(text: String, icon: String, fill: Int? = null, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(16), 0)
        val ink = if (fill != null) inkOn(fill) else TEXT
        background = pressable(fill ?: RAISED, dp(12).toFloat())
        addView(icon(icon, ink), LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(ink)
            typeface = fontLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), 0, 0, 0)
        })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(8)
        }
    }

    /** Label over a form field. */
    fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text
        typeface = fontLabel
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(18), 0, dp(6))
    }

    fun bodyText(text: CharSequence, sizeSp: Float = 15f, color: Int = TEXT) = TextView(context).apply {
        this.text = text
        typeface = fontBody
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.25f)
    }

    /**
     * A key's icon legend. On themes whose legends glow ([Theme.glow]) it is lit from within: a
     * halo of [halo] (null: none, e.g. on a lit key's bright face).
     */
    fun legendIcon(name: String, tint: Int, halo: Int? = tint) = ImageView(context).apply {
        Icons.get(name)?.let { setImageDrawable(glowing(context.getDrawable(it)!!.mutate().apply { setTint(tint) }, halo)) }
    }

    /** [icon] with a halo of [halo] on themes that glow; [icon] itself otherwise. */
    fun glowing(icon: Drawable, halo: Int?): Drawable =
        if (halo != null && theme.glow > 0f) GlowIcon(icon, halo, dpf(theme.glow)) else icon

    /** A text legend lit like a neon sign, on themes that glow. */
    fun glow(view: TextView, halo: Int?) {
        if (halo != null && theme.glow > 0f) view.setShadowLayer(dpf(theme.glow), 0f, 0f, withAlpha(halo, 0.85f))
    }

    companion object {
        /** The look in use; set once, before any view is built (a new theme rebuilds the activity). */
        var theme: Theme = Themes.GRAPHITE

        // Case and plate are what the keys sit on; caps are a step lighter.
        val CASE get() = theme.case
        val PLATE get() = theme.plate
        val CAP_SKIRT get() = theme.capSkirt
        val CAP_TOP get() = theme.capTop
        /** Flat controls: pressed buttons, the selected tab, dialogs. */
        val RAISED get() = theme.raised
        val DIALOG get() = theme.dialog
        val HAIRLINE get() = theme.hairline
        val HAIRLINE_STRONG get() = theme.hairlineStrong
        /** Legends (Graphite: warm white like PBT plastic), and the dimmer secondary legend. */
        val TEXT get() = theme.text
        val MUTED get() = theme.muted
        /** Decorative or non-essential text only (below 4.5:1). */
        val FAINT get() = theme.faint
        /** Dark legend on bright surfaces (lit keys, the main button). */
        val INK get() = theme.ink
        val BLUE get() = theme.accents[0]
        val GREEN get() = theme.accents[1]
        val AMBER get() = theme.accents[2]
        val RED get() = theme.accents[3]
        /** The theme's colors for the palette's slots, e.g. to color apps by name. */
        val ACCENTS: IntArray get() = theme.accents

        /** How a key's stored [color] looks in this theme: palette colors become the theme's. */
        fun accent(color: Int): Int {
            val slot = Palette.colors.indexOf(color)
            return if (slot >= 0) theme.accents[slot] else color
        }

        fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

        // Channel arithmetic rather than android.graphics.Color, so themes can be checked in unit tests.
        private fun red(c: Int) = (c shr 16) and 0xFF
        private fun green(c: Int) = (c shr 8) and 0xFF
        private fun blue(c: Int) = c and 0xFF

        fun blend(top: Int, bottom: Int, amount: Float): Int {
            fun ch(a: Int, b: Int) = (b + (a - b) * amount).toInt()
            return (0xFF shl 24) or (ch(red(top), red(bottom)) shl 16) or (ch(green(top), green(bottom)) shl 8) or
                ch(blue(top), blue(bottom))
        }

        fun lighten(color: Int, amount: Float) = blend(Color.WHITE, color, amount)
        fun darken(color: Int, amount: Float) = blend(Color.BLACK, color, amount)

        /** WCAG relative luminance. */
        fun luminance(color: Int): Double {
            fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(red(color)) + 0.7152 * ch(green(color)) + 0.0722 * ch(blue(color))
        }

        fun contrast(a: Int, b: Int): Double {
            val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
            return (hi + 0.05) / (lo + 0.05)
        }

        /** Legend color for text on [fill]: dark ink on bright fills, warm white on dark ones. */
        fun inkOn(fill: Int) = if (contrast(INK, fill) >= contrast(TEXT, fill)) INK else TEXT

        /** [accent] lightened until it reads on a keycap (3:1, the bar for icons). */
        fun legendTone(accent: Int): Int {
            var tone = accent
            var step = 0
            while (contrast(tone, CAP_TOP) < 3.0 && step++ < 10) tone = lighten(tone, 0.12f)
            return tone
        }
    }
}
