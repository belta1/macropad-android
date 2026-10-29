package dev.belta.macropad.pc

import dev.belta.macropad.net.NetActions
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the computer's PC link script last reported. */
data class PcStatus(
    val host: String? = null,
    /** The script watches Teams / reads Outlook (from its hello). */
    val hasTeams: Boolean = false,
    val hasOutlook: Boolean = false,
    /** Process name of the window in front, without ".exe", lowercase. */
    val app: String? = null,
    /** Null while the script isn't connected to Teams. */
    val teams: TeamsState? = null,
    val meeting: NetActions.CalendarEvent? = null,
)

data class TeamsState(
    val meeting: Boolean = false,
    val muted: Boolean = false,
    val camera: Boolean = false,
    val hand: Boolean = false,
    val blur: Boolean = false,
)

sealed class PcMessage {
    data class Hello(val host: String?, val teams: Boolean, val outlook: Boolean) : PcMessage()
    data class App(val exe: String?) : PcMessage()
    data class Teams(val state: TeamsState?) : PcMessage()
    data class Meeting(val event: NetActions.CalendarEvent?) : PcMessage()
    data class Result(val id: Int, val ok: Boolean, val error: String?) : PcMessage()
}

/**
 * The PC link protocol (docs/pc-link.md): one JSON object per line, UTF-8, `"t"` names the
 * message. Unknown messages and fields are ignored, so the app and the script can be updated
 * separately.
 */
object PcProtocol {
    const val VERSION = 1

    /** A line from the computer, or null for anything this version doesn't know. */
    fun parse(line: String): PcMessage? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (json.optString("t")) {
            "hello" -> PcMessage.Hello(json.text("host"), json.optBoolean("teams"), json.optBoolean("outlook"))
            "app" -> PcMessage.App(json.text("exe")?.lowercase()?.removeSuffix(".exe"))
            "teams" -> PcMessage.Teams(if (!json.has("meeting")) null else TeamsState(
                meeting = json.optBoolean("meeting"),
                muted = json.optBoolean("muted"),
                camera = json.optBoolean("camera"),
                hand = json.optBoolean("hand"),
                blur = json.optBoolean("blur"),
            ))
            "meeting" -> PcMessage.Meeting(meeting(json))
            "result" -> PcMessage.Result(json.optInt("id"), json.optBoolean("ok"), json.text("error"))
            else -> null
        }
    }

    /** [status] with [message] applied. */
    fun apply(status: PcStatus, message: PcMessage): PcStatus = when (message) {
        is PcMessage.Hello -> status.copy(host = message.host, hasTeams = message.teams, hasOutlook = message.outlook)
        is PcMessage.App -> status.copy(app = message.exe)
        is PcMessage.Teams -> status.copy(teams = message.state)
        is PcMessage.Meeting -> status.copy(meeting = message.event)
        is PcMessage.Result -> status
    }

    fun hello(appVersion: String) = line(JSONObject().put("t", "hello").put("v", VERSION).put("app", appVersion))

    fun ping() = line(JSONObject().put("t", "ping"))

    /** A Teams command: mute, camera, hand, blur, leave, or react with [param] (like, love, …). */
    fun teams(action: String, param: String?, id: Int) =
        line(JSONObject().put("t", "teams").put("do", action).put("id", id).apply { param?.let { put("param", it) } })

    /** Runs the command named [name] in the computer's commands.json. */
    fun run(name: String, id: Int) = line(JSONObject().put("t", "run").put("name", name).put("id", id))

    private fun line(json: JSONObject) = json.toString() + "\n"

    private fun meeting(json: JSONObject): NetActions.CalendarEvent? {
        val title = json.text("title") ?: return null
        val start = json.text("start")?.let(::parseTime) ?: return null
        val end = json.text("end")?.let(::parseTime)
        val now = Date()
        return NetActions.CalendarEvent(title, start, end, start <= now && (end == null || end > now))
    }

    /** ISO-8601 with an offset, as PowerShell's `ToString("o")` / `"yyyy-MM-ddTHH:mm:sszzz"` writes it. */
    fun parseTime(text: String): Date? {
        // Drop fractions of a second; SimpleDateFormat's XXX takes "Z" and "-03:00".
        val trimmed = text.replace(Regex("""\.\d+"""), "")
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(trimmed) }.getOrNull()
    }

    private fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }
}
