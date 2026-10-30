package dev.belta.macropad.ui

import android.app.AlertDialog
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.belta.macropad.model.Macro
import dev.belta.macropad.ui.Ui.Companion.MUTED
import dev.belta.macropad.ui.Ui.Companion.TEXT

/**
 * A recording before it is saved: its steps as a list to tidy up (move, delete, change a pause),
 * whether it starts by bringing its app to the front (PC link), and a test run ([onTry]).
 * [onSave] gets the macro text; the key editor then takes over.
 */
class RecordingEditor(private val ui: Ui) {

    fun show(recording: Recorder.Recording, appName: String?, onTry: (String) -> Unit, onSave: (String) -> Unit) {
        val steps = recording.steps.toMutableList()
        val list = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        // Replaying from anywhere: bring the app it was recorded in to the front first.
        val startInApp = recording.app?.let { app ->
            CheckBox(ui.context).apply {
                text = "Start by bringing ${appName ?: app} to the front"
                setTextColor(TEXT)
                isChecked = steps.firstOrNull { !Recorder.isDelayStep(it) } != "pc:focus $app"
            }
        }
        fun macro() = (listOfNotNull(startInApp?.takeIf { it.isChecked }?.let { "pc:focus ${recording.app}" }) +
            steps.dropWhile(Recorder::isDelayStep)).joinToString("\n")

        fun render() {
            list.removeAllViews()
            steps.forEachIndexed { i, line -> list.addView(row(line, i, steps.size,
                onMove = { by -> steps.add(i + by, steps.removeAt(i)); render() },
                onDelete = { steps.removeAt(i); render() },
                onPause = { ms -> steps[i] = "delay:$ms"; render() })) }
            if (steps.isEmpty()) list.addView(ui.bodyText("No steps left", 15f, MUTED))
        }
        render()

        val content = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(12), 0)
            startInApp?.let { addView(it) }
            addView(ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(420)))
        }
        val dialog = ui.dialog()
            .setTitle("Recorded macro")
            .setView(content)
            .setPositiveButton("Save…", null)
            .setNeutralButton("Try it", null)
            .setNegativeButton("Discard", null)
            .create()
        dialog.setOnShowListener {
            // Trying it keeps the list open; saving needs a step.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { if (steps.isNotEmpty()) onTry(macro()) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (steps.none { !Recorder.isDelayStep(it) }) return@setOnClickListener ui.toast("Nothing left to save")
                dialog.dismiss()
                onSave(macro())
            }
        }
        dialog.show()
    }

    /** A step: what it does over its text, with ↑ ↓ ✕; a pause opens a field to change it. */
    private fun row(
        line: String, index: Int, count: Int, onMove: (Int) -> Unit, onDelete: () -> Unit, onPause: (Long) -> Unit,
    ) = LinearLayout(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(2), 0, ui.dp(2))
        val pause = line.takeIf { Recorder.isDelayStep(it) }?.substringAfter(':')?.trim()?.toLongOrNull()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = if (pause != null) "Pause ${"%.1f".format(pause / 1000.0)} s" else Macro.describe(line)
                setTextColor(if (pause != null) MUTED else TEXT)
                typeface = ui.fontLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (pause == null) addView(TextView(context).apply {
                text = line
                setTextColor(MUTED)
                typeface = ui.fontHint
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (pause != null) {
                background = ui.pressable(android.graphics.Color.TRANSPARENT, ui.dpf(8f))
                setOnClickListener { askPause(pause, onPause) }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun small(icon: String, description: String, enabled: Boolean, action: () -> Unit) =
            addView(ui.iconButton(icon, description, action).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.3f },
                LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        small("arrow_up", "Move up", index > 0) { onMove(-1) }
        small("arrow_down", "Move down", index < count - 1) { onMove(1) }
        small("delete", "Delete", true, onDelete)
    }

    private fun askPause(current: Long, onPause: (Long) -> Unit) {
        val field = EditText(ui.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.1f".format(current / 1000.0).replace(',', '.'))
            setSelectAllOnFocus(true)
        }
        ui.dialog()
            .setTitle("Pause, in seconds")
            .setView(LinearLayout(ui.context).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(field) })
            .setPositiveButton("OK") { _, _ ->
                val seconds = field.text.toString().replace(',', '.').toDoubleOrNull() ?: return@setPositiveButton
                onPause((seconds * 1000).toLong().coerceIn(0, 10_000))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
