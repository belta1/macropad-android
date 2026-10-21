package dev.belta.macropad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MacroButton(var label: String, var action: String, var color: Int, var icon: String? = null)

class Page(var name: String, val buttons: MutableList<MacroButton?>)

/**
 * [dock] is a row of keys (media controls by default) shown below every page.
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
}

object Palette {
    val colors = intArrayOf(
        0xFF4F8CFF.toInt(), 0xFF2FCB8E.toInt(), 0xFFFF9F43.toInt(), 0xFFFF5C7A.toInt(),
        0xFFA77BFF.toInt(), 0xFF2EC5E8.toInt(), 0xFFFFD43B.toInt(), 0xFF9AA4B8.toInt(),
    )
}

class MacroStore(context: Context) {
    private val prefs = context.getSharedPreferences("macropad", Context.MODE_PRIVATE)

    fun load(): Layout {
        val layout = prefs.getString(KEY, null)?.let {
            try { fromJson(JSONObject(it)) } catch (e: Exception) { null }
        } ?: defaults()
        addNewDefaultPages(layout)
        return layout
    }

    /** Adds default pages introduced after the user's layout was saved, once each, at their default position. */
    private fun addNewDefaultPages(layout: Layout) {
        var changed = false
        for (name in ADDED_PAGES) {
            val flag = "added_page_$name"
            if (prefs.getBoolean(flag, false)) continue
            if (layout.pages.none { it.name == name }) {
                val defaultPages = defaults().pages
                defaultPages.firstOrNull { it.name == name }?.let { page ->
                    // Insert after the page that precedes it in the defaults (or first if none).
                    val previous = defaultPages.getOrNull(defaultPages.indexOf(page) - 1)?.name
                    val at = if (previous == null) 0 else layout.pages.indexOfFirst { it.name == previous } + 1
                    layout.pages.add(if (at > 0 || previous == null) at else layout.pages.size, page)
                    changed = true
                }
            }
            prefs.edit().putBoolean(flag, true).apply()
        }
        if (changed) save(layout)
    }

    fun save(layout: Layout) = prefs.edit().putString(KEY, toJson(layout).toString()).apply()

    private fun toJson(layout: Layout) = JSONObject().apply {
        put("rows", layout.rows)
        put("cols", layout.cols)
        put("pages", JSONArray().apply { for (page in layout.pages) put(pageToJson(page)) })
        put("dock", pageToJson(layout.dock))
        put("hardware", pageToJson(layout.hardware))
    }

    private fun pageToJson(page: Page) = JSONObject().apply {
        put("name", page.name)
        put("buttons", JSONArray().apply {
            for (b in page.buttons) put(b?.let {
                JSONObject().put("label", it.label).put("action", it.action).put("color", it.color)
                    .put("icon", it.icon ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
        })
    }

    private fun pageFromJson(p: JSONObject): Page {
        val arr = p.getJSONArray("buttons")
        val buttons = MutableList(arr.length()) { j ->
            arr.optJSONObject(j)?.let {
                MacroButton(it.getString("label"), it.getString("action"), it.getInt("color"),
                    if (it.isNull("icon")) null else it.optString("icon"))
            }
        }
        return Page(p.getString("name"), buttons)
    }

    private fun fromJson(json: JSONObject): Layout {
        val pages = mutableListOf<Page>()
        val pagesJson = json.getJSONArray("pages")
        for (i in 0 until pagesJson.length()) pages += pageFromJson(pagesJson.getJSONObject(i))
        if (pages.isEmpty()) return defaults()
        val dock = json.optJSONObject("dock")?.let { pageFromJson(it) } ?: defaultDock()
        val hardware = json.optJSONObject("hardware")?.let { pageFromJson(it) } ?: defaultHardware()
        return Layout(json.getInt("rows"), json.getInt("cols"), pages, dock, hardware)
    }

    companion object {
        private const val KEY = "layout_v3"
        const val CUSTOM_PAGE = "Custom"
        private val ADDED_PAGES = listOf("Desktop", "Teams")

        /** Opens an app through the Windows Run box (Win+R), so it works for anything on the PATH. */
        private fun run(command: String) = "win+r\ndelay:400\ntext:$command\\n"
        const val HW_VOLUME_UP = 0
        const val HW_VOLUME_DOWN = 1

        private val c = Palette.colors
        private val BLUE = c[0]; private val GREEN = c[1]; private val ORANGE = c[2]; private val RED = c[3]
        private val PURPLE = c[4]; private val TEAL = c[5]; private val YELLOW = c[6]; private val GRAY = c[7]

        private fun b(label: String, action: String, color: Int, icon: String) = MacroButton(label, action, color, icon)

        fun defaultDock() = Page("Media", mutableListOf(
            b("Previous", "media:prev", GRAY, "prev"),
            b("Play / Pause", "media:playpause", BLUE, "play_pause"),
            b("Next", "media:next", GRAY, "next"),
            b("Volume −", "media:voldown", GRAY, "volume_down"),
            b("Volume +", "media:volup", GRAY, "volume_up"),
        ))

        fun defaultHardware() = Page("Tablet buttons", mutableListOf(
            b("Volume +", "media:volup", GRAY, "volume_up"),
            b("Volume −", "media:voldown", GRAY, "volume_down"),
        ))

        // Shortcuts are the Windows/Linux defaults of each app.
        fun defaults() = Layout(3, 5, mutableListOf(
            // Windows: launch common apps and manage virtual desktops.
            Page("Desktop", mutableListOf(
                b("Chrome", run("chrome"), BLUE, "globe"),
                b("VS Code", run("code"), TEAL, "code"),
                b("Excel", run("excel"), GREEN, "table"),
                b("Word", run("winword"), BLUE, "document"),
                b("Teams", run("ms-teams"), PURPLE, "group"),
                b("File Explorer", "win+e", YELLOW, "folder"),
                b("Terminal", run("wt"), GRAY, "terminal"),
                b("Outlook", run("outlook"), BLUE, "mail"),
                b("Calculator", "media:calc", ORANGE, "calculator"),
                b("Settings", "win+i", GRAY, "settings"),
                b("Prev desktop", "win+ctrl+left", TEAL, "chevron_left"),
                b("Task view", "win+tab", PURPLE, "task_view"),
                b("New desktop", "win+ctrl+d", GREEN, "desktop_add"),
                b("Close desktop", "win+ctrl+f4", RED, "desktop_close"),
                b("Next desktop", "win+ctrl+right", TEAL, "chevron_right"),
            )),
            Page("VS Code", mutableListOf(
                b("Command palette", "ctrl+shift+p", BLUE, "command"),
                b("Quick open", "ctrl+p", BLUE, "search"),
                b("Terminal", "ctrl+grave", GRAY, "terminal"),
                b("Sidebar", "ctrl+b", GRAY, "sidebar"),
                b("Search files", "ctrl+shift+f", TEAL, "find_replace"),
                b("Go to definition", "f12", BLUE, "open_in_new"),
                b("Rename symbol", "f2", ORANGE, "edit"),
                b("Format document", "shift+alt+f", ORANGE, "align_left"),
                b("Toggle comment", "ctrl+slash", TEAL, "comment"),
                b("Save all", "ctrl+k\ns", GREEN, "save"),
                b("Run / Debug", "f5", GREEN, "play"),
                b("Stop", "shift+f5", RED, "stop"),
                b("Step over", "f10", PURPLE, "step_over"),
                b("Step into", "f11", PURPLE, "step_into"),
                b("Cursor below", "ctrl+alt+down", YELLOW, "cursor"),
            )),
            Page("Chrome", mutableListOf(
                b("New tab", "ctrl+t", BLUE, "add"),
                b("Close tab", "ctrl+w", RED, "close"),
                b("Reopen tab", "ctrl+shift+t", ORANGE, "undo"),
                b("Prev tab", "ctrl+shift+tab", GRAY, "chevron_left"),
                b("Next tab", "ctrl+tab", GRAY, "chevron_right"),
                b("Back", "alt+left", TEAL, "back"),
                b("Forward", "alt+right", TEAL, "forward"),
                b("Reload", "ctrl+r", GREEN, "refresh"),
                b("Hard reload", "ctrl+shift+r", GREEN, "refresh"),
                b("Address bar", "ctrl+l", BLUE, "link"),
                b("Find", "ctrl+f", PURPLE, "search"),
                b("Bookmark", "ctrl+d", YELLOW, "bookmark"),
                b("History", "ctrl+h", PURPLE, "history"),
                b("Downloads", "ctrl+j", PURPLE, "download"),
                b("DevTools", "f12", GRAY, "code"),
            )),
            Page("Excel", mutableListOf(
                b("AutoSum", "alt+equal", GREEN, "sum"),
                b("Insert row", "shift+space\nctrl+shift+plus", BLUE, "row_add"),
                b("Delete row", "shift+space\nctrl+minus", RED, "row_remove"),
                b("Fill down", "ctrl+d", TEAL, "arrow_down"),
                b("Fill right", "ctrl+r", TEAL, "forward"),
                b("Format cells", "ctrl+1", ORANGE, "tune"),
                b("Edit cell", "f2", BLUE, "edit"),
                b("Toggle \$ ref", "f4", YELLOW, "dollar"),
                b("Today's date", "ctrl+semicolon", PURPLE, "calendar"),
                b("Filter", "ctrl+shift+l", ORANGE, "filter"),
                b("Paste values", "ctrl+alt+v\ndelay:300\nv\nenter", GREEN, "paste"),
                b("Make table", "ctrl+t", TEAL, "table"),
                b("Prev sheet", "ctrl+pageup", GRAY, "chevron_left"),
                b("Next sheet", "ctrl+pagedown", GRAY, "chevron_right"),
                b("New line in cell", "alt+enter", PURPLE, "newline"),
            )),
            Page("Word", mutableListOf(
                b("Bold", "ctrl+b", BLUE, "bold"),
                b("Italic", "ctrl+i", BLUE, "italic"),
                b("Underline", "ctrl+u", BLUE, "underline"),
                b("Heading 1", "ctrl+alt+1", PURPLE, "heading"),
                b("Heading 2", "ctrl+alt+2", PURPLE, "heading"),
                b("Normal style", "ctrl+shift+n", PURPLE, "notes"),
                b("Bullets", "ctrl+shift+l", TEAL, "bullets"),
                b("Align left", "ctrl+l", GRAY, "align_left"),
                b("Center", "ctrl+e", GRAY, "align_center"),
                b("Align right", "ctrl+r", GRAY, "align_right"),
                b("Page break", "ctrl+enter", ORANGE, "page_break"),
                b("Find & replace", "ctrl+h", YELLOW, "find_replace"),
                b("Track changes", "ctrl+shift+e", RED, "track_changes"),
                b("Word count", "ctrl+shift+g", GREEN, "hash"),
                b("Clear formatting", "ctrl+space", RED, "format_clear"),
            )),
            // Shortcuts for the new Microsoft Teams on Windows.
            Page("Teams", mutableListOf(
                b("Mute / Unmute", "ctrl+shift+m", RED, "mic_off"),
                b("Camera on / off", "ctrl+shift+o", BLUE, "videocam"),
                b("Raise hand", "ctrl+shift+k", YELLOW, "hand"),
                b("Share screen", "ctrl+shift+e", TEAL, "screen_share"),
                b("Leave call", "ctrl+shift+h", RED, "call_end"),
                b("Push to talk", "ctrl+space", GREEN, "mic"),
                b("Blur background", "ctrl+shift+p", PURPLE, "blur"),
                b("Admit from lobby", "ctrl+shift+y", ORANGE, "group"),
                b("Accept call", "ctrl+shift+a", GREEN, "call"),
                b("Decline call", "ctrl+shift+d", RED, "call_end"),
                b("Activity", "ctrl+1", GRAY, "bell"),
                b("Chat", "ctrl+2", BLUE, "chat"),
                b("Calendar", "ctrl+4", PURPLE, "calendar"),
                b("Search", "ctrl+e", TEAL, "search"),
                b("New chat", "ctrl+n", BLUE, "edit"),
            )),
            // Empty page for the user's own buttons and recorded macros.
            Page(CUSTOM_PAGE, mutableListOf()),
        ), defaultDock(), defaultHardware())
    }
}
