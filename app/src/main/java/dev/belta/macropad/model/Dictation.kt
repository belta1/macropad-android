package dev.belta.macropad.model

/**
 * Dictated text for the computer: spoken punctuation ("coma", "punto y aparte", "comma", "new
 * line"…) becomes the mark, sentences start with a capital, and each phrase joins the text so far
 * with the right space.
 */
object Dictation {
    /** Spoken marks, longest first so "punto y aparte" wins over "punto". */
    private val MARKS = listOf(
        "punto y aparte" to ".\n", "nuevo párrafo" to "\n\n", "nueva línea" to "\n", "punto y coma" to ";",
        "dos puntos" to ":", "abre signo de interrogación" to "¿", "cierra signo de interrogación" to "?",
        "signo de interrogación" to "?", "abre signo de exclamación" to "¡", "cierra signo de exclamación" to "!",
        "signo de exclamación" to "!", "abre paréntesis" to "(", "cierra paréntesis" to ")", "puntos suspensivos" to "...",
        "punto" to ".", "coma" to ",", "guion" to "-", "arroba" to "@",
        "new paragraph" to "\n\n", "new line" to "\n", "full stop" to ".", "period" to ".", "comma" to ",",
        "question mark" to "?", "exclamation mark" to "!", "colon" to ":", "semicolon" to ";",
    ).sortedByDescending { it.first.length }

    /** Marks that stick to the word before them (no space before). */
    private const val CLOSING = ".,;:?!)"

    /** One recognised phrase with its spoken marks turned into marks. */
    fun marks(phrase: String): String {
        var text = " " + phrase.trim() + " "
        for ((spoken, mark) in MARKS) {
            text = text.replace(Regex("""\s${Regex.escape(spoken)}(?=[\s.,;:?!]|$)""", RegexOption.IGNORE_CASE), " \u0000$mark")
        }
        // A mark goes against the word before it ("hola ,") and opening ones against the next ("¿ qué").
        return text.replace(Regex("""\s*\u0000([.,;:?!)…]|\.\.\.)"""), "$1")
            .replace(Regex("""\u0000([¿¡(])\s*"""), "$1")
            .replace(Regex("""\s*\u0000(\n+)\s*"""), "$1")
            .replace("\u0000", "")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex(""" {2,}"""), " ")
            .trim(' ')
    }

    /** [text] with [phrase] after it: a space between words, a capital after a sentence's end. */
    fun join(text: String, phrase: String): String {
        var addition = marks(phrase)
        if (addition.isEmpty()) return text
        val before = text.trimEnd(' ')
        val sentenceStart = before.isEmpty() || before.last() in ".?!\n" || before.endsWith("¿") || before.endsWith("¡")
        if (sentenceStart) addition = addition.replaceFirstChar { it.uppercaseChar() }
        val glue = when {
            before.isEmpty() || before.last() == '\n' || before.last() in "¿¡(" -> ""
            addition.first() in CLOSING || addition.first() == '\n' -> ""
            else -> " "
        }
        return before + glue + addition
    }
}
