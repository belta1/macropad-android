package dev.belta.macropad.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.belta.macropad.R
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.MacroButton
import dev.belta.macropad.model.MacroException
import dev.belta.macropad.model.Palette
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.SURFACE_HI
import dev.belta.macropad.ui.Ui.Companion.TEXT
import dev.belta.macropad.ui.Ui.Companion.withAlpha

/** Dialog for creating or editing one key: label, action, toggle behaviour, color and icon. */
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
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        val label = EditText(context).apply {
            hint = "Label"
            setText(initial?.label ?: "")
            setSingleLine()
        }
        val action = EditText(context).apply {
            hint = "Action, e.g. ctrl+shift+t"
            setText(initial?.action ?: "")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        val toggle = CheckBox(context).apply {
            text = "Toggle key — shows ON/OFF (e.g. mute, camera)"
            isChecked = initial?.toggle == true
        }
        val longAction = EditText(context).apply {
            hint = "Long-press action (optional), e.g. ctrl+space"
            setText(initial?.longAction ?: "")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
        }
        content.addView(label)
        content.addView(action)
        content.addView(longAction)
        content.addView(toggle)

        content.addView(ui.sectionTitle("Color"))
        val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(swatches)

        content.addView(ui.sectionTitle("Icon"))
        val icons = GridLayout(context).apply { columnCount = 10 }
        content.addView(icons)

        fun paint() {
            for (i in 0 until swatches.childCount) {
                val c = Palette.colors[i]
                swatches.getChildAt(i).background = ui.roundRect(c, ui.dp(18).toFloat()).apply {
                    if (c == color) setStroke(ui.dp(3), Color.WHITE)
                }
            }
            for (i in 0 until icons.childCount) {
                val v = icons.getChildAt(i) as ImageView
                val selected = v.tag == iconName
                v.background = ui.roundRect(if (selected) withAlpha(color, 0.3f) else SURFACE_HI, ui.dp(10).toFloat()).apply {
                    if (selected) setStroke(ui.dp(2), color)
                }
                v.imageTintList = ColorStateList.valueOf(if (selected) color else TEXT)
            }
        }
        for (c in Palette.colors) {
            swatches.addView(View(context).apply {
                setOnClickListener { color = c; paint() }
            }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(10) })
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
        paint()

        content.addView(ui.sectionTitle("Action syntax"))
        content.addView(ui.bodyText(HELP, 12f, MUTED).apply { typeface = Typeface.MONOSPACE })

        val dialog = ui.dialog()
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (canDelete) setNeutralButton("Delete") { _, _ -> onDelete() } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val actionText = action.text.toString().trim()
                try {
                    Macro.parse(actionText)
                } catch (e: MacroException) {
                    action.error = e.message
                    return@setOnClickListener
                }
                val longText = longAction.text.toString().trim().ifEmpty { null }
                if (longText != null) {
                    try {
                        Macro.parse(longText)
                    } catch (e: MacroException) {
                        longAction.error = e.message
                        return@setOnClickListener
                    }
                }
                val labelText = label.text.toString().trim().ifEmpty { actionText.lines().first() }
                onSave(MacroButton(labelText, actionText, color, iconName, toggle.isChecked, longText))
                dialog.dismiss()
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private const val HELP = """One step per line:
  ctrl+shift+t     key combo (US key names)
  media:volup      media key
  text:Hi there\n  type text (\n = Enter)
  delay:200        wait (ms)
  click:left       mouse button (right, middle)
  scroll:3         wheel (+ up, − down)
  page:next        tablet page (prev, or a name)

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
text: uses the computer's keyboard
layout (Settings → Computers)."""
}
