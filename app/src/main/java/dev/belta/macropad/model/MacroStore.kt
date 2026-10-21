package dev.belta.macropad.model

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Persists the layout in shared preferences and mirrors every save to
 * `<external files dir>/macropad-layout.json`, so it can be pulled with adb
 * (`/sdcard/Android/data/dev.belta.macropad/files/macropad-layout.json`) or kept in the repo.
 */
class MacroStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("macropad", Context.MODE_PRIVATE)
    private val computers = ComputerProfiles(context)

    val backupFile: File? get() = context.getExternalFilesDir(null)?.let { File(it, BACKUP_NAME) }

    fun load(): Layout {
        val layout = prefs.getString(KEY, null)?.let {
            try { LayoutJson.fromJson(JSONObject(it)) } catch (e: Exception) { null }
        } ?: DefaultLayout.layout()
        addNewDefaultPages(layout)
        upgradeTeamsKeys(layout)
        addDesktopDockKeys(layout)
        upgradeDesktopRow(layout)
        addPageIcons(layout)
        addClipboardKey(layout)
        if (backupFile?.exists() == false) writeBackup(export(layout))
        return layout
    }

    fun save(layout: Layout) {
        prefs.edit().putString(KEY, LayoutJson.toJson(layout).toString()).apply()
        writeBackup(export(layout))
    }

    private fun writeBackup(json: String) {
        try {
            backupFile?.writeText(json)
        } catch (e: Exception) {
            Log.w("MacroStore", "could not write backup file: $e")
        }
    }

    /** Layout plus per-computer settings, as written to backup and export files. */
    fun export(layout: Layout): String =
        LayoutJson.toJson(layout).put("computers", computers.toJson()).toString(2)

    fun import(text: String): Layout = LayoutJson.fromJson(JSONObject(text))

    /** Restores the per-computer settings of an export file, if it has them (version 2+). */
    fun importComputers(text: String) {
        JSONObject(text).optJSONObject("computers")?.let { computers.replaceAll(it) }
    }

    /** Adds default pages introduced after the user's layout was saved, once each, at their default position. */
    private fun addNewDefaultPages(layout: Layout) {
        var changed = false
        val defaultPages = DefaultLayout.layout().pages
        for (name in DefaultLayout.ADDED_PAGES) {
            val flag = "added_page_$name"
            if (prefs.getBoolean(flag, false)) continue
            if (layout.pages.none { it.name == name }) {
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

    /** 1.2: Teams keys focus Teams first, so they work from a Citrix window. Only untouched keys change. */
    private fun upgradeTeamsKeys(layout: Layout) {
        if (prefs.getBoolean(TEAMS_UPGRADE_FLAG, false)) return
        var changed = false
        layout.pages.firstOrNull { it.name == DefaultLayout.TEAMS_PAGE }?.buttons?.forEach { b ->
            DefaultLayout.TEAMS_FOCUS_UPGRADE[b?.action]?.let { b!!.action = it; changed = true }
        }
        prefs.edit().putBoolean(TEAMS_UPGRADE_FLAG, true).apply()
        if (changed) save(layout)
    }

    /** 1.3: desktop switching joins the dock, in the free slots after the media keys. */
    private fun addDesktopDockKeys(layout: Layout) {
        if (prefs.getBoolean(DOCK_UPGRADE_FLAG, false)) return
        val start = Layout.DOCK_SIZE - DefaultLayout.DESKTOP_DOCK.size
        var changed = false
        DefaultLayout.DESKTOP_DOCK.forEachIndexed { i, button ->
            if (layout.button(layout.dock, start + i) == null) {
                layout.setButton(layout.dock, start + i, button)
                changed = true
            }
        }
        prefs.edit().putBoolean(DOCK_UPGRADE_FLAG, true).apply()
        if (changed) save(layout)
    }

    /** 1.3: the Desktop page's desktop-switching row (now in the dock) becomes Snip, Clipboard, …. */
    private fun upgradeDesktopRow(layout: Layout) {
        if (prefs.getBoolean(DESKTOP_ROW_FLAG, false)) return
        var changed = false
        val desktop = layout.pages.firstOrNull { it.name == "Desktop" }
        desktop?.buttons?.forEachIndexed { i, b ->
            DefaultLayout.DESKTOP_ROW_UPGRADE[b?.action]?.let { desktop.buttons[i] = it.copy(); changed = true }
        }
        prefs.edit().putBoolean(DESKTOP_ROW_FLAG, true).apply()
        if (changed) save(layout)
    }

    /** 1.3: "Type clipboard" in the sidebar's first free slot. */
    private fun addClipboardKey(layout: Layout) {
        if (prefs.getBoolean(CLIPBOARD_KEY_FLAG, false)) return
        prefs.edit().putBoolean(CLIPBOARD_KEY_FLAG, true).apply()
        val sidebar = layout.sidebar
        if (sidebar.buttons.any { it?.action == DefaultLayout.TYPE_CLIPBOARD.action }) return
        val free = (0 until Layout.SIDEBAR_SIZE).firstOrNull { layout.button(sidebar, it) == null } ?: return
        layout.setButton(sidebar, free, DefaultLayout.TYPE_CLIPBOARD)
        save(layout)
    }

    /** Stores a tab icon for pages without one, so it survives renaming. */
    private fun addPageIcons(layout: Layout) {
        var changed = false
        for (page in layout.pages) if (page.icon == null) {
            DefaultLayout.iconFor(page.name)?.let { page.icon = it; changed = true }
        }
        if (changed) save(layout)
    }

    private companion object {
        const val TEAMS_UPGRADE_FLAG = "upgraded_teams_focus"
        const val DOCK_UPGRADE_FLAG = "upgraded_desktop_dock"
        const val DESKTOP_ROW_FLAG = "upgraded_desktop_row"
        const val CLIPBOARD_KEY_FLAG = "added_clipboard_key"
        const val KEY = "layout_v3"
        const val BACKUP_NAME = "macropad-layout.json"
    }
}
