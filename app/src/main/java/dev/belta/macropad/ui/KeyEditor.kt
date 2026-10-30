package dev.belta.macropad.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Gravity
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.belta.macropad.R
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.MacroButton
import dev.belta.macropad.model.MacroException
import dev.belta.macropad.model.Palette
import dev.belta.macropad.model.Page
import dev.belta.macropad.ui.Ui.Companion.DIALOG
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.PLATE
import dev.belta.macropad.ui.Ui.Companion.RAISED
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.legendTone

/**
 * Dialog for creating or editing one key: label, action, toggle behaviour, color and icon, with a
 * live preview of the key at the top (it can be pressed, but sends nothing).
 */
object KeyEditor {

    fun show(
        ui: Ui,
        title: String,
        initial: MacroButton?,
        canDelete: Boolean,
        onSave: (MacroButton) -> Unit,
        onDelete: () -> Unit,
    ) {
        val context = ui.context
        var color = initial?.color ?: Palette.colors[0]
        var iconName: String? = initial?.icon

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, ui.dp(24), ui.dp(16))
        }
        val label = EditText(context).apply {
            hint = "Shown on the key"
            setText(initial?.label ?: "")
            setSingleLine()
        }
        val action = EditText(context).apply {
            hint = "e.g. ctrl+shift+t"
            setText(initial?.action ?: "")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        val toggle = CheckBox(context).apply {
            text = "Toggle key: lights up while on (mute, camera)"
            typeface = ui.fontBody
            textSize = 16f
            isChecked = initial?.toggle == true
        }
        val longAction = EditText(context).apply {
            hint = "Optional, e.g. ctrl+space"
            setText(initial?.longAction ?: "")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
        }
        // The preview stays in view beside the form while it scrolls (colors and icons are far down).
        val previewSlot = FrameLayout(context)
        content.addView(ui.sectionTitle("Label").apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) })
        content.addView(label)
        content.addView(ui.sectionTitle("Action"))
        content.addView(action)
        // Quick slider presets (filled in below, once paint() exists).
        val sliderPresets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(sliderPresets)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(8)
        })
        content.addView(ui.sectionTitle("Long press"))
        content.addView(longAction)
        content.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) })

        content.addView(ui.sectionTitle("Color"))
        val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(swatches)

        content.addView(ui.sectionTitle("Icon"))
        val icons = GridLayout(context).apply { columnCount = 10 }
        content.addView(icons)

        val previewKeys = KeyViews(ui, object : KeyViews.Listener {
            override fun onKeyPressed(view: View, page: Page, index: Int, button: MacroButton) = Unit
            override fun onKeyReleased(view: View, page: Page, index: Int, button: MacroButton, cancelled: Boolean) = Unit
            override fun onEditKey(page: Page, index: Int) = Unit
            override fun onSlide(page: Page, index: Int, button: MacroButton, up: Boolean) = Unit
            override fun onSlideTap(page: Page, index: Int, button: MacroButton) = Unit
            override fun onSlideLevel(page: Page, index: Int, button: MacroButton, level: Int, done: Boolean) = Unit
        })
        val previewPage = Page("Preview", mutableListOf())
        fun preview() {
            val actionText = action.text.toString().trim()
            // The same fallback label as save().
            val button = MacroButton(label.text.toString().trim().ifEmpty { actionText.lines().first() },
                actionText, color, iconName, toggle.isChecked, longAction.text.toString().trim().ifEmpty { null })
            previewSlot.removeAllViews()
            previewSlot.addView(previewKeys.build(previewPage, 0, button, compact = false, editing = false).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            })
        }

        fun paint() {
            for (i in 0 until swatches.childCount) {
                val c = Palette.colors[i]
                // The chosen color gets a ring, set off from the swatch by a gap of dialog color.
                // Shown as the theme draws it; the key keeps the palette's color.
                swatches.getChildAt(i).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Ui.accent(c))
                    if (c == color) setStroke(ui.dp(3), DIALOG)
                }
                swatches.getChildAt(i).foreground = if (c == color) GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(ui.dp(2), TEXT)
                } else null
            }
            for (i in 0 until icons.childCount) {
                val v = icons.getChildAt(i) as ImageView
                val selected = v.tag == iconName
                v.background = ui.pressable(if (selected) RAISED else PLATE, ui.dp(10).toFloat())
                v.foreground = if (selected) ui.roundRect(0, ui.dp(10).toFloat()).apply { setStroke(ui.dp(2), TEXT) } else null
                v.imageTintList = ColorStateList.valueOf(if (selected) legendTone(Ui.accent(color)) else MUTED)
            }
            preview()
        }
        for (c in Palette.colors) {
            swatches.addView(View(context).apply {
                contentDescription = "Color ${Palette.colors.indexOf(c) + 1}"
                setOnClickListener { color = c; paint() }
            }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) })
        }
        for (name in listOf<String?>(null) + Icons.all.keys) {
            icons.addView(ImageView(context).apply {
                tag = name
                setImageResource(Icons.get(name) ?: R.drawable.ic_none)
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                contentDescription = name ?: "No icon"
                setOnClickListener { iconName = name; paint() }
            }, GridLayout.LayoutParams().apply {
                width = ui.dp(40); height = ui.dp(40)
                setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            })
        }
        val repaint = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = preview()
        }
        for (field in listOf(label, action, longAction)) field.addTextChangedListener(repaint)
        toggle.setOnCheckedChangeListener { _, _ -> preview() }
        paint()
        for ((name, presetIcon) in listOf("volume" to "volume_up", "scroll" to "mouse", "zoom" to "search")) {
            val title = name.replaceFirstChar { it.uppercase() }
            sliderPresets.addView(ui.chip("$title slider", presetIcon) {
                action.setText("slide:$name")
                if (label.text.isBlank()) label.setText(title)
                if (iconName == null) iconName = presetIcon
                paint()
            }.apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = ui.dp(6) } })
        }

        content.addView(ui.sectionTitle("Action syntax"))
        content.addView(ui.bodyText(HELP, 12f, MUTED).apply { typeface = Typeface.MONOSPACE })

        // Title and Save / Cancel / Delete sit in a header above the scrolling form, so the
        // on-screen keyboard can't cover them (the dialog's own buttons are at the bottom).
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(24), ui.dp(16), ui.dp(16), ui.dp(8))
            addView(TextView(context).apply {
                text = title
                setTextColor(TEXT)
                typeface = ui.fontTitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(24), ui.dp(4), ui.dp(24), 0)
                    addView(ui.sectionTitle("Preview").apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) })
                    addView(previewSlot, LinearLayout.LayoutParams(ui.dp(PREVIEW_WIDTH_DP), ui.dp(136)))
                    addView(ui.bodyText("Try it here; it sends nothing.", 14f, MUTED).apply {
                        setPadding(0, ui.dp(10), 0, 0)
                    }, LinearLayout.LayoutParams(ui.dp(PREVIEW_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
                })
                addView(ScrollView(context).apply { addView(content) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        val dialog = ui.dialog().setView(root).create()
        fun save() {
            val actionText = action.text.toString().trim()
            try {
                Macro.parse(actionText)
            } catch (e: MacroException) {
                action.error = e.message
                action.requestFocus()
                return
            }
            val longText = longAction.text.toString().trim().ifEmpty { null }
            if (longText != null) {
                try {
                    Macro.parse(longText)
                } catch (e: MacroException) {
                    longAction.error = e.message
                    longAction.requestFocus()
                    return
                }
            }
            val labelText = label.text.toString().trim().ifEmpty { actionText.lines().first() }
            onSave(MacroButton(labelText, actionText, color, iconName, toggle.isChecked, longText))
            dialog.dismiss()
        }
        if (canDelete) header.addView(ui.chip("Delete", "delete") { dialog.dismiss(); onDelete() })
        header.addView(ui.chip("Cancel", "close") { dialog.dismiss() })
        header.addView(ui.chip("Save", "check", TEXT) { save() })
        for (field in listOf(label, action, longAction)) ui.showKeyboardOnFocus(field)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        // Wide enough for the preview column and the form side by side (the default dialog is narrow).
        val width = minOf(ui.dp(860), (context.resources.displayMetrics.widthPixels * 0.92f).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private const val PREVIEW_WIDTH_DP = 180


    private const val HELP = """One step per line:
  ctrl+shift+t     key combo (US key names)
  media:volup      media key
  text:Hi there\n  type text (\n = Enter)
  delay:200        wait (ms)
  click:left       mouse button (right, middle)
  scroll:3         wheel (+ up, − down)
  page:next        tablet page (prev, or a name)
  slide:volume     drag strip: volume, scroll,
                   zoom, brightness, tabs, undo,
                   or up | down (e.g. right | left)
  pc:teams mute    Teams via the PC link: mute,
                   camera, hand, blur, leave,
                   react like (love, applause…)
  pc:run lock      a command in the computer's
                   commands.json (PC link)

In text: {date} {time} {clipboard}
  {date:dd/MM/yyyy} custom format

Modifiers: ctrl shift alt win/cmd altgr
Keys: a-z 0-9 f1-f24 enter esc tab space
  backspace delete insert home end
  pageup pagedown up down left right
  printscreen menu capslock plus minus
  equal comma period slash backslash
  semicolon quote grave kp0-kp9 iso …
Media: playpause next prev stop mute
  volup voldown brightup brightdown
  calc browser mail files search

A single combo/media key/click is held
while touched; multi-step macros run on
tap. With a long-press action, a tap
runs the action on release and holding
(0.45 s) runs the long-press one.
A slide: key is the key's only step;
long press and Toggle don't apply.
text: uses the computer's keyboard
layout (Settings → Computers)."""
}
