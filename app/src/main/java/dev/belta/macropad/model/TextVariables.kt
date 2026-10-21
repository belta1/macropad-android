package dev.belta.macropad.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fills in `{...}` variables of `text:` steps when a key runs:
 *
 *   {date}            2026-10-21
 *   {time}            14:05
 *   {date:dd/MM/yyyy} any java.text.SimpleDateFormat pattern
 *   {clipboard}       the tablet's clipboard text
 *   {{                a literal "{"
 *
 * Unknown names are left as they are.
 */
object TextVariables {
    private val VARIABLE = Regex("""\{\{|\{(date|time|clipboard)(?::([^}]*))?\}""")

    fun expand(text: String, now: Date = Date(), clipboard: () -> String? = { null }): String {
        if ('{' !in text) return text
        return VARIABLE.replace(text) { m ->
            if (m.value == "{{") return@replace "{"
            val pattern = m.groupValues[2].ifEmpty { null }
            when (m.groupValues[1]) {
                "date" -> format(pattern ?: "yyyy-MM-dd", now) ?: m.value
                "time" -> format(pattern ?: "HH:mm", now) ?: m.value
                else -> clipboard().orEmpty()
            }
        }
    }

    private fun format(pattern: String, now: Date) =
        try { SimpleDateFormat(pattern, Locale.getDefault()).format(now) } catch (e: IllegalArgumentException) { null }
}
