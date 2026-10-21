package dev.belta.macropad.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * [toggle] keys remember an on/off state (e.g. Mute) and show it; they flip on every press.
 * [longAction], when set, runs instead of [action] if the key is held down (and is itself held while
 * it is a single combo); [action] then runs on release of a short tap.
 */
data class MacroButton(
    var label: String,
    var action: String,
    var color: Int,
    var icon: String? = null,
    var toggle: Boolean = false,
    var longAction: String? = null,
)

enum class PageKind { KEYS, TRACKPAD }

class Page(var name: String, val buttons: MutableList<MacroButton?>, val kind: PageKind = PageKind.KEYS)

/**
 * [dock] is a row of keys (media controls and desktop switching by default) shown below every page.
 * [hardware] holds the actions of the tablet's volume up / volume down buttons; an empty slot
 * leaves that button controlling the tablet volume as usual.
 */
class Layout(var rows: Int, var cols: Int, val pages: MutableList<Page>, val dock: Page, val hardware: Page) {
    val cells get() = rows * cols

    /** Cells keep their slot index, so shrinking the grid hides buttons instead of deleting them. */
    fun button(page: Page, index: Int): MacroButton? = page.buttons.getOrNull(index)

    fun setButton(page: Page, index: Int, button: MacroButton?) {
        while (page.buttons.size <= index) page.buttons += null
        page.buttons[index] = button
    }

    companion object {
        const val HW_VOLUME_UP = 0
        const val HW_VOLUME_DOWN = 1
        const val DOCK_SIZE = 8
    }
}

object Palette {
    val colors = intArrayOf(
        0xFF4F8CFF.toInt(), 0xFF2FCB8E.toInt(), 0xFFFF9F43.toInt(), 0xFFFF5C7A.toInt(),
        0xFFA77BFF.toInt(), 0xFF2EC5E8.toInt(), 0xFFFFD43B.toInt(), 0xFF9AA4B8.toInt(),
    )
}

/** JSON form of a [Layout]; used for storage and for export/import files. */
object LayoutJson {
    const val FORMAT = "macropad-layout"
    const val VERSION = 2 // 2: export files also carry "computers" (ComputerProfiles)

    fun toJson(layout: Layout) = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("rows", layout.rows)
        put("cols", layout.cols)
        put("pages", JSONArray().apply { for (page in layout.pages) put(pageToJson(page)) })
        put("dock", pageToJson(layout.dock))
        put("hardware", pageToJson(layout.hardware))
    }

    /** Throws [org.json.JSONException] / [IllegalArgumentException] on malformed input. */
    fun fromJson(json: JSONObject): Layout {
        val format = json.optString("format", FORMAT)
        require(format == FORMAT) { "Not a macropad layout file" }
        val pagesJson = json.getJSONArray("pages")
        val pages = MutableList(pagesJson.length()) { pageFromJson(pagesJson.getJSONObject(it)) }
        require(pages.isNotEmpty()) { "Layout has no pages" }
        return Layout(
            json.getInt("rows").coerceIn(1, 8),
            json.getInt("cols").coerceIn(1, 8),
            pages,
            json.optJSONObject("dock")?.let { pageFromJson(it) } ?: DefaultLayout.dock(),
            json.optJSONObject("hardware")?.let { pageFromJson(it) } ?: DefaultLayout.hardware(),
        )
    }

    private fun pageToJson(page: Page) = JSONObject().apply {
        put("name", page.name)
        if (page.kind != PageKind.KEYS) put("kind", page.kind.name)
        put("buttons", JSONArray().apply {
            for (b in page.buttons) put(b?.let {
                JSONObject().put("label", it.label).put("action", it.action).put("color", it.color)
                    .put("icon", it.icon ?: JSONObject.NULL)
                    .apply { if (it.toggle) put("toggle", true) }
                    .apply { it.longAction?.let { long -> put("longAction", long) } }
            } ?: JSONObject.NULL)
        })
    }

    private fun pageFromJson(p: JSONObject): Page {
        val arr = p.getJSONArray("buttons")
        val buttons = MutableList(arr.length()) { j ->
            arr.optJSONObject(j)?.let {
                MacroButton(
                    it.getString("label"), it.getString("action"), it.getInt("color"),
                    if (it.isNull("icon")) null else it.optString("icon"),
                    it.optBoolean("toggle", false),
                    it.optString("longAction").ifBlank { null },
                )
            }
        }
        val kind = PageKind.values().firstOrNull { it.name == p.optString("kind") } ?: PageKind.KEYS
        return Page(p.getString("name"), buttons, kind)
    }
}
