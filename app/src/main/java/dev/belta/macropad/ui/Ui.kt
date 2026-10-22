package dev.belta.macropad.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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

/** Theme, fonts and small view/drawable helpers shared by the UI classes. */
class Ui(val context: Context) {
    val fontMedium: Typeface = context.resources.getFont(R.font.inter_medium)
    val fontSemiBold: Typeface = context.resources.getFont(R.font.inter_semibold)
    val fontBold: Typeface = context.resources.getFont(R.font.inter_bold)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

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
        val pressed = if (color == Color.TRANSPARENT) withAlpha(Color.WHITE, 0.08f) else lighten(color, 0.12f)
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(pressed, radius))
        addState(intArrayOf(), roundRect(color, radius))
    }

    fun icon(name: String, tint: Int) = ImageView(context).apply {
        Icons.get(name)?.let { setImageResource(it) }
        imageTintList = ColorStateList.valueOf(tint)
    }

    fun iconButton(icon: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(Icons.get(icon)!!)
        imageTintList = ColorStateList.valueOf(TEXT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = pressable(SURFACE_HI, dp(14).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
    }

    fun setIconButton(view: ImageView, icon: String, fill: Int?) {
        view.setImageResource(Icons.get(icon)!!)
        view.imageTintList = ColorStateList.valueOf(TEXT)
        view.background = pressable(fill ?: SURFACE_HI, dp(14).toFloat())
    }

    fun chip(text: String, icon: String, accent: Int? = null, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(14), 0)
        background = pressable(accent ?: SURFACE_HI, dp(18).toFloat())
        addView(icon(icon, TEXT), LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(TEXT)
            typeface = fontSemiBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), 0, 0, 0)
        })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            marginStart = dp(6)
        }
    }

    fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text.uppercase()
        typeface = fontSemiBold
        letterSpacing = 0.08f
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(16), 0, dp(8))
    }

    fun bodyText(text: CharSequence, sizeSp: Float = 14f, color: Int = TEXT) = TextView(context).apply {
        this.text = text
        typeface = fontMedium
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    companion object {
        const val BG_TOP = 0xFF0E1118.toInt()
        const val BG_BOTTOM = 0xFF06070A.toInt()
        const val SURFACE = 0xFF13161D.toInt()
        const val SURFACE_HI = 0xFF1C2029.toInt()
        const val SURFACE_ACTIVE = 0xFF262B37.toInt()
        const val STROKE = 0x12FFFFFF
        const val STROKE_STRONG = 0x2EFFFFFF
        const val TEXT = 0xFFF2F4F8.toInt()
        const val MUTED = 0xFF8C94A6.toInt()
        val BLUE = Palette.colors[0]
        val GREEN = Palette.colors[1]
        val ORANGE = Palette.colors[2]
        val RED = Palette.colors[3]

        fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

        fun blend(top: Int, bottom: Int, amount: Float): Int {
            fun ch(a: Int, b: Int) = (b + (a - b) * amount).toInt()
            return Color.rgb(ch(Color.red(top), Color.red(bottom)), ch(Color.green(top), Color.green(bottom)),
                ch(Color.blue(top), Color.blue(bottom)))
        }

        fun lighten(color: Int, amount: Float) = blend(Color.WHITE, color, amount)
        fun darken(color: Int, amount: Float) = blend(Color.BLACK, color, amount)
    }
}
