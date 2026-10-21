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

    private companion object {
        const val KEY = "layout_v3"
        const val BACKUP_NAME = "macropad-layout.json"
    }
}
