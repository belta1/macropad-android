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
 *   {pcclipboard}     the computer's latest copied text (PC link); {pcclipboard:2} the one before, …
 *   {secret:name}     a value saved on the tablet ([SecretStore]), e.g. a login
 *   {{                a literal "{"
 *
 * Unknown names are left as they are.
 */
object TextVariables {
    private val VARIABLE = Regex("""\{\{|\{(date|time|clipboard|pcclipboard|secret)(?::([^}]*))?\}""")
    private val SECRET = Regex("""\{secret:([^}]*)\}""")
    private val PC_CLIPBOARD = Regex("""\{pcclipboard(?::(\d+))?\}""")

    /** Names of the saved values that [text] uses. */
    fun secretNames(text: String): List<String> = SECRET.findAll(text).map { it.groupValues[1].trim() }.toList()

    /** The computer's clipboard entries (1 = latest) that [text] types. */
    fun pcClipboardSlots(text: String): List<Int> =
        PC_CLIPBOARD.findAll(text).map { it.groupValues[1].toIntOrNull() ?: 1 }.toList()

    fun expand(
        text: String, now: Date = Date(), secret: (String) -> String? = { null },
        pcClipboard: (Int) -> String? = { null }, clipboard: () -> String? = { null },
    ): String {
        if ('{' !in text) return text
        return VARIABLE.replace(text) { m ->
            if (m.value == "{{") return@replace "{"
            val pattern = m.groupValues[2].ifEmpty { null }
            when (m.groupValues[1]) {
                "date" -> format(pattern ?: "yyyy-MM-dd", now) ?: m.value
                "time" -> format(pattern ?: "HH:mm", now) ?: m.value
                "secret" -> secret(pattern.orEmpty().trim()).orEmpty()
                "pcclipboard" -> pattern?.trim()?.toIntOrNull().let { pcClipboard(it ?: 1) }.orEmpty()
                else -> clipboard().orEmpty()
            }
        }
    }

    private fun format(pattern: String, now: Date) =
        try { SimpleDateFormat(pattern, Locale.getDefault()).format(now) } catch (e: IllegalArgumentException) { null }
}
