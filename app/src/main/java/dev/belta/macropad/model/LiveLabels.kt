package dev.belta.macropad.model

import dev.belta.macropad.pc.PcStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Key labels can show what the PC link reports, kept up to date while the key is on screen:
 *
 *   {pcclipboard}, {pcclipboard:2}   the computer's clipboard (1 = the latest copy), on one line
 *   {venv}                           the terminal's Python environment and version, or "No venv"
 *   {folder}, {cwd}                  the terminal's folder (its name / the full path)
 *   {media}                          what's playing ("Title — Artist"), or "Nothing playing"
 *   {title}, {artist}                the same, apart
 *   {volume}                         the computer's volume ("42%", "Muted")
 *   {app}                            the app in front
 *   {meeting}                        the next Outlook meeting ("Standup · 10:30", "Standup · now")
 *   {call}                           how long the Teams call under way has lasted ("12:04")
 *
 * `{name|fallback}` shows the fallback while the value is unknown (no PC link, nothing reported
 * yet, an empty clipboard slot). Lines left empty are dropped. The terminal's values need the
 * shell hook (docs/pc-link.md).
 */
object LiveLabels {
    private val VARIABLE =
        Regex("""\{(pcclipboard|venv|folder|cwd|media|title|artist|volume|app|meeting|call)(?::(\d+))?(?:\|([^}]*))?\}""")

    /** A meeting starting within this long lights the join key. */
    private const val SOON_MS = 5 * 60_000L

    fun isLive(label: String) = '{' in label && VARIABLE.containsMatchIn(label)

    fun expand(label: String, status: PcStatus?, now: Date = Date()): String {
        if (!isLive(label)) return label
        return VARIABLE.replace(label) { m -> value(m.groupValues[1], m.groupValues[2].toIntOrNull(), status, now) ?: m.groupValues[3] }
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** The next Outlook meeting is under way or starts within 5 minutes (the join key lights). */
    fun meetingSoon(status: PcStatus?, now: Date = Date()): Boolean {
        val meeting = status?.meeting?.takeIf { status.hasOutlook } ?: return false
        return meeting.now || meeting.start.time - now.time in 0..SOON_MS
    }

    private fun value(name: String, index: Int?, status: PcStatus?, now: Date): String? {
        status ?: return null
        val shell = status.shell
        val media = status.media
        return when (name) {
            "pcclipboard" -> status.clips.getOrNull((index ?: 1) - 1)?.let(::oneLine)
            "venv" -> shell?.let { s -> s.venv?.let { lastPart(it) + (s.python?.let { v -> " · $v" } ?: "") } ?: "No venv" }
            "folder" -> shell?.cwd?.let(::lastPart)
            "cwd" -> shell?.cwd
            "media" -> if (media != null) listOfNotNull(media.title, media.artist).joinToString(" — ") else nothingPlaying(status)
            "title" -> media?.title ?: nothingPlaying(status)
            "artist" -> media?.artist
            "volume" -> status.volume?.let { if (it.muted) "Muted" else "${it.level}%" }
            "meeting" -> status.meeting?.takeIf { status.hasOutlook && (it.now || it.start.after(now)) }?.let {
                it.title + " · " + if (it.now) "now" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(it.start)
            }
            "call" -> status.callStartedAt?.let { duration((now.time - it) / 1000) }
            else -> status.app
        }
    }

    /** "4:05", "1:02:09". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Whether [label] shows the call timer, which changes every second. */
    fun isTicking(label: String) = label.contains("{call")

    private fun nothingPlaying(status: PcStatus) = if (status.hasMedia) "Nothing playing" else null

    /** "C:\code\shop\" → "shop", "C:\" → "C:". */
    private fun lastPart(path: String) = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifEmpty { path }

    /** A clipboard text as one line: runs of spaces, tabs and line breaks become one space. */
    private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "(spaces)" }
}
