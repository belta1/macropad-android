package dev.belta.macropad.pc

import dev.belta.macropad.model.Layout
import dev.belta.macropad.model.LayoutJson
import dev.belta.macropad.model.Macro
import dev.belta.macropad.model.MacroException
import dev.belta.macropad.model.PageKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The layout editor on the computer: a web page (assets/editor/) the PC link script serves on that
 * computer only (127.0.0.1), passing each of its requests on to the tablet ([PcMessage.Http]). This
 * answers them: the page, the layout, checking actions and saving the edited layout.
 *
 * The editor is opened from the tablet ([open]), which makes a new key; requests without it are
 * refused, so neither another program on the computer nor an old browser tab can change the keys.
 * Saving needs the layout the page started from ([rev]): changes made on the tablet meanwhile
 * aren't overwritten.
 */
class LayoutEditor(private val host: Host) {

    interface Host {
        val layout: Layout
        /** Puts [layout] in place of the current one, saves it and shows it. */
        fun replaceLayout(layout: Layout)
        /** A file of the editor's page (assets/editor/[name]), or null. */
        fun asset(name: String): ByteArray?
        /** What the page shows for the 8 key colours, in palette order (the tablet's theme). */
        val colors: List<Int>
        /** Tab icon names and what to call them ("excel" to "Excel"). */
        val appIcons: Map<String, String>
        val tabletName: String
        val appVersion: String
    }

    class Reply(val status: Int, val type: String, val body: String, val cache: Boolean = false)

    /** The key of the editor opened last; null until it is opened from the tablet. */
    @Volatile var key: String? = null
        private set

    /** A new key: editor pages opened before stop working. */
    fun open(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.also { key = it }
    }

    fun handle(request: PcMessage.Http): Reply {
        val path = request.path.substringBefore('?')
        val given = request.key ?: query(request.path, "k")
        val key = key
        if (key == null || given == null || !MessageDigest.isEqual(given.toByteArray(), key.toByteArray())) {
            return text(403, "This editor link no longer works. Open the editor again from the tablet (⚙ → Edit on the computer).")
        }
        return try {
            when ("${request.method} $path") {
                "GET /", "GET /index.html" -> file("index.html", HTML)
                "GET /icons.json" -> file("icons.json", JSON)
                "GET /api/state" -> json(200, state())
                "POST /api/check" -> json(200, check(JSONObject(request.body)))
                "PUT /api/layout" -> save(JSONObject(request.body))
                else -> text(404, "Not found")
            }
        } catch (e: org.json.JSONException) {
            json(400, JSONObject().put("error", "The editor sent something the tablet can't read: ${e.message}"))
        }
    }

    /** The layout as the page edits it, and what it needs to draw the keys. */
    private fun state() = JSONObject()
        .put("rev", rev(host.layout))
        .put("tablet", host.tabletName)
        .put("app", host.appVersion)
        .put("layout", LayoutJson.toJson(host.layout))
        .put("colors", JSONArray().apply {
            host.colors.forEachIndexed { i, shown ->
                put(JSONObject().put("value", PALETTE[i]).put("shown", "#%06X".format(shown and 0xFFFFFF)))
            }
        })
        .put("appIcons", JSONObject().apply { host.appIcons.forEach { (k, v) -> put(k, v) } })
        .put("limits", JSONObject().put("dock", Layout.DOCK_SIZE).put("sidebar", Layout.SIDEBAR_SIZE)
            .put("hardware", 2).put("maxRows", MAX_GRID).put("maxCols", MAX_GRID))
        .put("keyPages", JSONArray().apply { put(PageKind.KEYS.name); put(PageKind.TRACKPAD.name) })

    /** `{"actions": [...]}` → for each, whether it is valid and how its key's hint reads. */
    private fun check(body: JSONObject): JSONObject {
        val actions = body.optJSONArray("actions") ?: JSONArray()
        return JSONObject().put("results", JSONArray().apply {
            for (i in 0 until actions.length()) put(checkAction(actions.optString(i)))
        })
    }

    private fun checkAction(action: String): JSONObject = try {
        if (action.isBlank()) throw MacroException("The action is empty")
        Macro.parse(action)
        JSONObject().put("ok", true).put("hint", Macro.describe(action))
    } catch (e: MacroException) {
        JSONObject().put("ok", false).put("error", e.message)
    }

    /** `{"rev": …, "layout": {…}}`: saved unless it no longer starts from the tablet's layout or has a broken key. */
    private fun save(body: JSONObject): Reply {
        val current = rev(host.layout)
        if (body.optString("rev") != current) {
            return json(409, JSONObject().put("rev", current)
                .put("error", "The keys changed on the tablet after you opened the editor. Reload to see them (your changes here are lost), or copy what you need first."))
        }
        val layout = try {
            LayoutJson.fromJson(body.getJSONObject("layout"))
        } catch (e: Exception) {
            return json(400, JSONObject().put("error", "Not a layout the tablet can use: ${e.message}"))
        }
        val problems = JSONArray()
        val places = layout.pages.map { it.name to it } + listOf("Dock" to layout.dock, "Sidebar" to layout.sidebar,
            "Tablet buttons" to layout.hardware)
        for ((name, page) in places) page.buttons.forEachIndexed { index, button ->
            if (button == null) return@forEachIndexed
            for (action in listOfNotNull(button.action, button.longAction)) {
                val result = checkAction(action)
                if (!result.getBoolean("ok")) problems.put(JSONObject().put("page", name).put("index", index)
                    .put("label", button.label).put("error", result.getString("error")))
            }
        }
        if (problems.length() > 0) {
            return json(400, JSONObject().put("error", "Some keys have actions the tablet can't run").put("keys", problems))
        }
        host.replaceLayout(layout)
        return json(200, JSONObject().put("rev", rev(host.layout)))
    }

    private fun file(name: String, type: String): Reply {
        val bytes = host.asset(name) ?: return text(404, "The app has no $name")
        return Reply(200, type, bytes.toString(Charsets.UTF_8), cache = true)
    }

    private fun json(status: Int, body: JSONObject) = Reply(status, JSON, body.toString())
    private fun text(status: Int, body: String) = Reply(status, TEXT, body)

    companion object {
        const val HTML = "text/html; charset=utf-8"
        const val JSON = "application/json; charset=utf-8"
        const val TEXT = "text/plain; charset=utf-8"
        private const val MAX_GRID = 8
        private val PALETTE = dev.belta.macropad.model.Palette.colors

        /** Identifies a layout as saved, so a save can tell whether it changed meanwhile. */
        fun rev(layout: Layout): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(LayoutJson.toJson(layout).toString().toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        private fun query(path: String, name: String): String? = path.substringAfter('?', "").split('&')
            .map { it.split('=', limit = 2) }.firstOrNull { it[0] == name }?.getOrNull(1)
    }
}
