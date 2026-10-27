package dev.belta.macropad.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.MacroException
import dev.belta.macropad.ui.Ui.Companion.RAISED
import dev.belta.macropad.ui.Ui.Companion.TEXT

/**
 * Records a macro on the tablet: every action is sent to the computer live (by [send]) and
 * captured, with the pause since the previous action kept as a `delay:` step.
 */
class Recorder(private val ui: Ui, private val send: (String) -> Unit, private val onChanged: () -> Unit) {

    private var steps: MutableList<String>? = null
    private var lastAt = 0L

    val isRecording get() = steps != null

    fun start() {
        steps = mutableListOf()
        lastAt = 0L
        onChanged()
    }

    fun discard() {
        steps = null
        onChanged()
    }

    /** Ends the recording; returns the macro text, or null if nothing was recorded. */
    fun finish(): String? {
        val recorded = steps ?: return null
        steps = null
        onChanged()
        return if (recorded.any { !isDelay(it) }) recorded.joinToString("\n") else null
    }

    fun record(action: String) {
        val s = steps ?: return
        val now = System.currentTimeMillis()
        if (s.isNotEmpty()) {
            val gap = ((now - lastAt).coerceAtMost(MAX_DELAY) / 50) * 50
            if (gap >= MIN_DELAY) s += "delay:$gap"
        }
        s += action.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lastAt = now
        onChanged()
    }

    fun undo() {
        val s = steps ?: return
        if (s.isNotEmpty()) s.removeAt(s.lastIndex)
        while (s.lastOrNull()?.let(::isDelay) == true) s.removeAt(s.lastIndex)
        onChanged()
    }

    fun status(): String {
        val s = steps ?: return ""
        val actions = s.count { !isDelay(it) }
        return "●  Recording · $actions action${if (actions == 1) "" else "s"}" +
            (s.lastOrNull { !isDelay(it) }?.let { "  ·  last: ${Macro.describe(it)}" } ?: "")
    }

    fun typeTextDialog() {
        val input = EditText(ui.context).apply {
            hint = "Text to type on the computer"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val pressEnter = CheckBox(ui.context).apply { text = "Press Enter afterwards" }
        ui.dialog()
            .setTitle("Type text")
            .setView(LinearLayout(ui.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
                addView(input)
                addView(pressEnter)
            })
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString() + if (pressEnter.isChecked) "\n" else ""
                if (text.isNotEmpty()) sendAndRecord("text:" + text.replace("\n", "\\n").replace("\t", "\\t"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun keyComboDialog() {
        val input = EditText(ui.context).apply {
            hint = "e.g. win+r, ctrl+shift+esc, alt+f4"
            setSingleLine()
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val quick = GridLayout(ui.context).apply { columnCount = 4 }
        val dialog = ui.dialog()
            .setTitle("Key combo")
            .setView(LinearLayout(ui.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
                addView(input)
                addView(quick)
            })
            .setPositiveButton("Send", null)
            .setNegativeButton("Close", null)
            .create()
        fun trySend(spec: String): Boolean {
            try { Macro.parse(spec) } catch (e: MacroException) { input.error = e.message; return false }
            sendAndRecord(spec)
            return true
        }
        // Tapping a quick key sends it immediately and keeps the dialog open for the next one.
        for (key in QUICK_KEYS) {
            quick.addView(TextView(ui.context).apply {
                text = Macro.describe(key)
                typeface = ui.fontLabel
                setTextColor(TEXT)
                gravity = Gravity.CENTER
                setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
                background = ui.pressable(RAISED, ui.dp(10).toFloat())
                setOnClickListener { trySend(key) }
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val spec = input.text.toString().trim().lowercase()
                if (spec.isNotEmpty() && trySend(spec)) input.setText("")
            }
        }
        dialog.show()
    }

    private fun sendAndRecord(action: String) {
        record(action)
        send(action)
    }

    private fun isDelay(line: String) = line.startsWith("delay:")

    private companion object {
        const val MIN_DELAY = 300L
        const val MAX_DELAY = 2000L
        val QUICK_KEYS = listOf(
            "enter", "esc", "tab", "backspace", "up", "down", "left", "right",
            "win", "win+r", "win+e", "win+d", "alt+tab", "alt+f4", "ctrl+c", "ctrl+v",
        )
    }
}
