package dev.belta.macropad.model

/**
 * Commands in a slide's speaker notes, for live demos: a line starting with `>` is a command the
 * Presenter page offers to type on the computer (then Enter); `>>` types it without Enter. The
 * rest of the notes is text to read.
 */
object NotesCommands {
    sealed class Part {
        data class Text(val text: String) : Part()
        data class Command(val text: String, val enter: Boolean) : Part()
    }

    /** [notes] as text and commands, in order; consecutive text lines stay one part. */
    fun parse(notes: String): List<Part> {
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        fun flushText() {
            val t = text.toString().trim('\n')
            if (t.isNotBlank()) parts += Part.Text(t)
            text.clear()
        }
        for (line in notes.lines()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                val enter = !trimmed.startsWith(">>")
                val command = typable(trimmed.removePrefix(">>").removePrefix(">").removePrefix(" "))
                if (command.isNotBlank()) {
                    flushText()
                    parts += Part.Command(command, enter)
                }
                continue // an empty ">" line is dropped
            }
            text.append(line).append('\n')
        }
        flushText()
        return parts
    }

    /**
     * Undoes what PowerPoint's AutoCorrect does to code as it's typed in the notes: curly quotes
     * back to straight ones, a dash before an option back to "--", "…" to "...", no-break spaces.
     */
    fun typable(command: String): String = command
        .replace('“', '"').replace('”', '"').replace('„', '"')
        .replace('‘', '\'').replace('’', '\'')
        .replace("…", "...")
        .replace(' ', ' ')
        .replace(Regex("""(^|\s)[–—](?=\w)"""), "$1--")
        .trimEnd()
}
