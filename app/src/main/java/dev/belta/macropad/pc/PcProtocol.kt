package dev.belta.macropad.pc

import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.Step
import dev.belta.macropad.net.NetActions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Base64
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
    /** When the Teams call under way started (as the tablet saw it), for its timer; null outside a call. */
    val callStartedAt: Long? = null,
    val meeting: NetActions.CalendarEvent? = null,
    /** The computer's clipboard texts, newest first, at most [PcProtocol.MAX_CLIPS]. */
    val clips: List<String> = emptyList(),
    /** Open windows, as Alt+Tab lists them; null while the script doesn't report them (before 1.8). */
    val windows: List<PcWindow>? = null,
    /** The window in front ([PcWindow.id]). */
    val frontWindow: Long? = null,
    /** The speakers' volume and the apps playing through them; null while unknown. */
    val volume: PcVolume? = null,
    /** The script reports what's playing; [media] null then means nothing is. */
    val hasMedia: Boolean = false,
    val media: PcMedia? = null,
    /** The terminal last used (needs the shell hook); null while unknown or that terminal is closed. */
    val shell: PcShell? = null,
    /** The computer's monitors, numbered from the left; null while the script doesn't mirror screens. */
    val monitors: List<PcMonitor>? = null,
    /** The computer is locked (Win+L, or locked after a while); null while unknown. */
    val locked: Boolean? = null,
    /** Windows' keyboard layout id (KLID, e.g. "0000080A") of the window in front; null while unknown. */
    val keyboard: String? = null,
    /** Counts to show on the tabs of these apps (process names, e.g. "outlook" -> unread mails). */
    val badges: Map<String, Int> = emptyMap(),
    /** The latest new mail the computer reported (since the link opened). */
    val mail: PcMail? = null,
    /** The script may record the computer's keyboard (started with -AllowRecording). */
    val canCapture: Boolean = false,
    /** The script's `$ScriptVersion` and SHA-256 (null from scripts before 1.8), and whether it takes updates. */
    val scriptVersion: String? = null,
    val scriptSha256: String? = null,
    val canUpdate: Boolean = false,
    /** PowerPoint's slide show, or the slide being edited; null while PowerPoint isn't open. */
    val slides: PcSlides? = null,
)

/**
 * PowerPoint on the computer: [show] while a slide show runs ([state] "running", "black",
 * "white", "paused", or "done" on the closing black slide), else the presentation being edited.
 * [slide] is 1-based (0 when none); [version] changes when the slides' pictures must be sent again.
 *
 * Or, [html], a single-file HTML slide deck open in a browser (docs/pc-link.md): [show] while it
 * fills its screen; it has no black or white screen. [app] is the program showing the slides
 * ("powerpnt", "chrome"…).
 */
data class PcSlides(
    val show: Boolean, val file: String, val slide: Int, val count: Int, val title: String?,
    val notes: String, val state: String, val version: Int,
    val html: Boolean = false, val app: String = "powerpnt",
)

/** New mail in classic Outlook: the newest one's sender and subject, [count] new ones in all; [at] when it came. */
data class PcMail(val from: String, val subject: String, val count: Int, val at: Long)

/** A monitor; [number] counts from the left (1, 2, …), as the Screen page offers them. */
data class PcMonitor(val number: Int, val width: Int, val height: Int, val primary: Boolean)

/** Part of a mirrored monitor: a JPEG to draw at ([x], [y]) of the picture. */
class ScreenTile(val x: Int, val y: Int, val jpeg: ByteArray)

/** [exe] is the process name as in [PcStatus.app]; [app] the program's name ("Google Chrome"). */
data class PcWindow(val id: Long, val exe: String, val app: String, val title: String)

/** Levels 0–100. */
data class PcVolume(val level: Int, val muted: Boolean, val apps: List<PcAudioApp> = emptyList())

data class PcAudioApp(val exe: String, val app: String, val level: Int, val muted: Boolean)

data class PcMedia(val title: String, val artist: String?, val playing: Boolean)

/** [venv] is the active environment's folder (VIRTUAL_ENV or conda's), [python] its version. */
data class PcShell(val cwd: String, val venv: String?, val python: String?)

data class TeamsState(
    val meeting: Boolean = false,
    val muted: Boolean = false,
    val camera: Boolean = false,
    val hand: Boolean = false,
    val blur: Boolean = false,
)

sealed class PcMessage {
    data class Hello(
        val host: String?, val teams: Boolean, val outlook: Boolean, val capture: Boolean = false,
        val script: String? = null, val sha256: String? = null, val updates: Boolean = false,
    ) : PcMessage()
    data class App(val exe: String?) : PcMessage()
    data class Teams(val state: TeamsState?) : PcMessage()
    data class Meeting(val event: NetActions.CalendarEvent?) : PcMessage()
    data class Result(val id: Int, val ok: Boolean, val error: String?) : PcMessage()
    data class Clip(val text: String) : PcMessage()
    data class Windows(val windows: List<PcWindow>, val front: Long?) : PcMessage()
    /** An app's icon (PNG), sent once per app before the first message that lists it. */
    class Icon(val exe: String, val png: ByteArray) : PcMessage()
    data class Volume(val volume: PcVolume) : PcMessage()
    data class Media(val media: PcMedia?) : PcMessage()
    data class Shell(val shell: PcShell?) : PcMessage()
    data class Monitors(val monitors: List<PcMonitor>) : PcMessage()
    /**
     * Changed parts of the mirrored monitor, [width] x [height] in all. A picture can take several
     * lines; the last has [end] and the mouse pointer ([cursorX], [cursorY]: 0–1, or -1 when it is
     * on another monitor). The tablet acknowledges [seq] after the last line, which lets the
     * computer send the next picture.
     */
    class Frame(
        val seq: Int, val width: Int, val height: Int, val tiles: List<ScreenTile>, val end: Boolean,
        val cursorX: Float, val cursorY: Float, val view: Int = 0,
    ) : PcMessage()
    data class Lock(val locked: Boolean) : PcMessage()
    data class Keyboard(val klid: String?) : PcMessage()
    data class Badges(val counts: Map<String, Int>) : PcMessage()
    data class Mail(val from: String, val subject: String, val count: Int) : PcMessage()
    /** A step recorded from the computer's own keyboard (`ctrl+s`, `text:hola`) while capturing. */
    data class Recorded(val step: String) : PcMessage()
    data class Slides(val slides: PcSlides?) : PcMessage()
    /** The text of the file selected in File Explorer, answering [PcProtocol.typeFile] request [id]. */
    data class FileText(val id: Int, val name: String, val text: String) : PcMessage()
    /** A picture (JPEG) of slide [slide] of [file], for [PcSlides.version] [version]. */
    class SlideImage(val file: String, val slide: Int, val version: Int, val jpeg: ByteArray) : PcMessage()
}

/**
 * The part of a monitor the Screen page shows: from ([x], [y]), [size] of its width and of its
 * height (0–1; 1 = all of it).
 */
data class ScreenViewport(val x: Float = 0f, val y: Float = 0f, val size: Float = 1f) {
    val zoomed get() = size < 0.999f
}

/**
 * The PC link protocol (docs/pc-link.md): one JSON object per line, UTF-8, `"t"` names the
 * message. Unknown messages and fields are ignored, so the app and the script can be updated
 * separately.
 */
object PcProtocol {
    const val VERSION = 1
    const val MAX_CLIPS = 20

    /** A line from the computer, or null for anything this version doesn't know. */
    fun parse(line: String): PcMessage? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return runCatching { parse(json) }.getOrNull()
    }

    private fun parse(json: JSONObject): PcMessage? {
        return when (json.optString("t")) {
            "hello" -> PcMessage.Hello(json.text("host"), json.optBoolean("teams"), json.optBoolean("outlook"), json.optBoolean("capture"),
                json.text("script"), json.text("sha256"), json.optBoolean("updates"))
            "mail" -> PcMessage.Mail(json.text("from").orEmpty(), json.text("subject").orEmpty(), json.optInt("count", 1))
            "recorded" -> json.text("step")?.let { PcMessage.Recorded(it) }
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
            // Windows line endings: one Enter per line when typed.
            "clip" -> json.text("text")?.let { PcMessage.Clip(it.replace("\r\n", "\n").replace('\r', '\n')) }
            "windows" -> PcMessage.Windows(
                json.optJSONArray("list").objects().mapNotNull { w ->
                    val exe = w.text("exe") ?: return@mapNotNull null
                    PcWindow(w.getLong("id"), exe.lowercase(), w.text("app") ?: exe, w.text("title").orEmpty())
                },
                if (json.has("front")) json.optLong("front") else null,
            )
            "icon" -> PcMessage.Icon(json.text("exe")?.lowercase() ?: return null, Base64.getDecoder().decode(json.getString("png")))
            "volume" -> PcMessage.Volume(PcVolume(
                json.getInt("level"), json.optBoolean("muted"),
                json.optJSONArray("apps").objects().mapNotNull { a ->
                    val exe = a.text("exe") ?: return@mapNotNull null
                    PcAudioApp(exe.lowercase(), a.text("app") ?: exe, a.optInt("level"), a.optBoolean("muted"))
                },
            ))
            "media" -> PcMessage.Media(json.text("title")?.let { PcMedia(it, json.text("artist"), json.optBoolean("playing")) })
            "shell" -> PcMessage.Shell(json.text("cwd")?.let { PcShell(it, json.text("venv"), json.text("python")) })
            "monitors" -> PcMessage.Monitors(json.optJSONArray("list").objects().map {
                PcMonitor(it.getInt("n"), it.getInt("w"), it.getInt("h"), it.optBoolean("primary"))
            })
            "frame" -> {
                val tiles = json.optJSONArray("tiles")
                PcMessage.Frame(
                    json.getInt("seq"), json.getInt("w"), json.getInt("h"),
                    (0 until (tiles?.length() ?: 0)).map { i ->
                        val t = tiles!!.getJSONArray(i)
                        ScreenTile(t.getInt(0), t.getInt(1), Base64.getDecoder().decode(t.getString(2)))
                    },
                    json.optBoolean("end"),
                    json.optDouble("cx", -1.0).toFloat(), json.optDouble("cy", -1.0).toFloat(),
                    json.optInt("view"),
                )
            }
            "slides" -> PcMessage.Slides(json.text("file")?.let { file ->
                PcSlides(json.optBoolean("show"), file, json.optInt("slide"), json.optInt("count"), json.text("title"),
                    json.text("notes").orEmpty().replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n').trimEnd(),
                    json.text("state") ?: "running", json.optInt("v"),
                    json.text("kind") == "html", json.text("app")?.lowercase()?.removeSuffix(".exe") ?: "powerpnt")
            })
            // The text as it is: its spaces and blank lines are the file's.
            "file" -> PcMessage.FileText(json.getInt("id"), json.text("name") ?: "file", json.optString("text").replace("\r\n", "\n"))
            "slide-image" ->PcMessage.SlideImage(json.text("file") ?: return null, json.getInt("n"), json.optInt("v"),
                Base64.getDecoder().decode(json.getString("jpg")))
            "lock" -> PcMessage.Lock(json.optBoolean("locked"))
            "keyboard" -> PcMessage.Keyboard(json.text("klid")?.uppercase())
            "badges" -> PcMessage.Badges(json.optJSONObject("counts")?.let { counts ->
                counts.keys().asSequence().associate { it.lowercase() to counts.optInt(it) }
            }.orEmpty())
            else -> null
        }
    }

    /** [status] with [message] applied. */
    fun apply(status: PcStatus, message: PcMessage): PcStatus = when (message) {
        is PcMessage.Hello -> status.copy(host = message.host, hasTeams = message.teams, hasOutlook = message.outlook,
            canCapture = message.capture, scriptVersion = message.script, scriptSha256 = message.sha256, canUpdate = message.updates)
        is PcMessage.Mail -> status.copy(mail = PcMail(message.from, message.subject, message.count, System.currentTimeMillis()))
        is PcMessage.App -> status.copy(app = message.exe)
        is PcMessage.Teams -> status.copy(teams = message.state, callStartedAt = when {
            message.state?.meeting != true -> null
            else -> status.callStartedAt ?: System.currentTimeMillis()
        })
        is PcMessage.Meeting -> status.copy(meeting = message.event)
        // A text copied again moves to the front.
        is PcMessage.Clip -> status.copy(clips = (listOf(message.text) + (status.clips - message.text)).take(MAX_CLIPS))
        is PcMessage.Windows -> status.copy(windows = message.windows, frontWindow = message.front)
        is PcMessage.Volume -> status.copy(volume = message.volume)
        is PcMessage.Media -> status.copy(hasMedia = true, media = message.media)
        is PcMessage.Shell -> status.copy(shell = message.shell)
        is PcMessage.Monitors -> status.copy(monitors = message.monitors)
        is PcMessage.Lock -> status.copy(locked = message.locked)
        is PcMessage.Keyboard -> status.copy(keyboard = message.klid)
        is PcMessage.Badges -> status.copy(badges = message.counts)
        is PcMessage.Slides -> status.copy(slides = message.slides)
        is PcMessage.Result, is PcMessage.Icon, is PcMessage.Frame, is PcMessage.Recorded, is PcMessage.SlideImage,
        is PcMessage.FileText -> status
    }

    fun hello(appVersion: String) = line(JSONObject().put("t", "hello").put("v", VERSION).put("app", appVersion))

    fun ping() = line(JSONObject().put("t", "ping"))

    /** A Teams command: mute, camera, hand, blur, leave, or react with [param] (like, love, …). */
    fun teams(action: String, param: String?, id: Int) =
        line(JSONObject().put("t", "teams").put("do", action).put("id", id).apply { param?.let { put("param", it) } })

    /** A classic Outlook command (Macro.OUTLOOK_ACTIONS: reply, new-mail, mark-read, …). */
    fun outlook(action: String, id: Int) = line(JSONObject().put("t", "outlook").put("do", action).put("id", id))

    /** Runs the command named [name] in the computer's commands.json. */
    fun run(name: String, id: Int) = line(JSONObject().put("t", "run").put("name", name).put("id", id))

    /** Brings the window [window] ([PcWindow.id]) to the front. */
    fun focus(window: Long, id: Int) = line(JSONObject().put("t", "focus").put("window", window).put("id", id))

    /**
     * Sets the speakers' volume, or app [app]'s ([PcAudioApp.exe]): [level] 0–100 and / or [mute];
     * null leaves it as it is.
     */
    fun volume(app: String?, level: Int?, mute: Boolean?, id: Int) = line(JSONObject().put("t", "volume").put("id", id).apply {
        app?.let { put("app", it) }
        level?.let { put("level", it.coerceIn(0, 100)) }
        mute?.let { put("mute", it) }
    })

    /**
     * Starts mirroring [viewport] of monitor [monitor] (or switches to it), scaled to fit [width] x
     * [height]; sends a whole picture first. Its frames carry [view], so ones still on the way for
     * an earlier request can be told apart.
     */
    fun screen(monitor: Int, width: Int, height: Int, viewport: ScreenViewport = ScreenViewport(), view: Int = 0) =
        line(JSONObject().put("t", "screen").put("monitor", monitor).put("w", width).put("h", height).put("view", view).apply {
            if (viewport.zoomed) {
                put("vx", viewport.x.toDouble()).put("vy", viewport.y.toDouble()).put("vw", viewport.size.toDouble())
            }
        })

    /** Brings app [app]'s window to the front, waiting up to 10 s for one; the result says when it's done. */
    fun activate(app: String, id: Int) = line(JSONObject().put("t", "activate").put("app", app).put("id", id))

    /** Answers once the window in front is app [text] or has [text] in its title (up to 10 s). */
    fun waitFor(text: String, id: Int) = line(JSONObject().put("t", "wait").put("for", text).put("id", id))

    /**
     * A recorded `pc:click` / `pc:drag` / `pc:scroll` ([command]) in app [app]'s window (or
     * [Macro.SCREEN]); the result comes once it's done (its window brought to the front first).
     */
    fun click(command: String, app: String, point: Step.PcPoint, id: Int) = line(JSONObject()
        .put("t", "click").put("do", if (command == "click") point.button else command).put("app", app)
        .put("x", point.x).put("y", point.y).put("id", id).apply {
            if (command == "drag") put("x2", point.x2).put("y2", point.y2)
            if (command == "scroll") put("amount", point.amount)
            point.element?.let { put("name", it) }
        })

    /**
     * Starts / stops recording the computer's own keyboard ([on]); [pause] holds it while the
     * tablet's own keys go out (they are recorded on the tablet already).
     */
    fun capture(on: Boolean? = null, pause: Boolean? = null) = line(JSONObject().put("t", "capture").apply {
        on?.let { put("on", it) }
        pause?.let { put("pause", it) }
    })

    /** Opens the online meeting (Teams…) of the next meeting, from the link the computer read itself. */
    fun join(id: Int) = line(JSONObject().put("t", "join").put("id", id))

    /**
     * A PowerPoint command: "next", "prev", "goto" ([slide]), "black" / "white" (on and off),
     * "start", "end", "thumbs" (send every slide's picture), "point" (the laser pointer at [x], [y],
     * 0–1 across the slide) and "point-end". Points have no [id]: no answer.
     */
    fun slides(action: String, id: Int?, slide: Int = 0, x: Float = 0f, y: Float = 0f) = line(JSONObject()
        .put("t", "slides").put("do", action).apply {
            id?.let { put("id", it) }
            if (action == "goto") put("n", slide)
            if (action == "point") put("x", x.coerceIn(0f, 1f).toDouble()).put("y", y.coerceIn(0f, 1f).toDouble())
        })

    /** Asks for the file selected in File Explorer: a `file` message comes back, or a failed result. */
    fun typeFile(id: Int) = line(JSONObject().put("t", "typefile").put("id", id))

    /** Replaces the computer's script with [script]; it checks it, answers, then restarts. */
    fun update(script: LinkScript, id: Int) = line(JSONObject().put("t", "update").put("id", id)
        .put("version", script.version).put("sha256", script.sha256).put("ps1", Base64.getEncoder().encodeToString(script.bytes)))

    fun screenStop() = line(JSONObject().put("t", "screen").put("stop", true))

    /** The picture [seq] arrived; the computer may send the next one. */
    fun screenAck(seq: Int) = line(JSONObject().put("t", "screen-ack").put("seq", seq))

    /**
     * The mouse at ([x], [y]) (0–1) of monitor [monitor]: [action] "tap" (left click), "right",
     * "down" / "move" / "up" (a drag), "scroll" ([amount] notches, positive = up).
     */
    fun pointer(monitor: Int, x: Float, y: Float, action: String, amount: Int = 0) = line(JSONObject()
        .put("t", "pointer").put("monitor", monitor).put("x", x.coerceIn(0f, 1f).toDouble()).put("y", y.coerceIn(0f, 1f).toDouble())
        .put("do", action).apply { if (amount != 0) put("amount", amount) })

    private fun line(json: JSONObject) = json.toString() + "\n"

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun meeting(json: JSONObject): NetActions.CalendarEvent? {
        val title = json.text("title") ?: return null
        val start = json.text("start")?.let(::parseTime) ?: return null
        val end = json.text("end")?.let(::parseTime)
        val now = Date()
        return NetActions.CalendarEvent(title, start, end, start <= now && (end == null || end > now), json.optBoolean("join"))
    }

    /** ISO-8601 with an offset, as PowerShell's `ToString("o")` / `"yyyy-MM-ddTHH:mm:sszzz"` writes it. */
    fun parseTime(text: String): Date? {
        // Drop fractions of a second; SimpleDateFormat's XXX takes "Z" and "-03:00".
        val trimmed = text.replace(Regex("""\.\d+"""), "")
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(trimmed) }.getOrNull()
    }

    private fun JSONObject.text(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }
}
