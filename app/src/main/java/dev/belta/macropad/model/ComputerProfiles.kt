package dev.belta.macropad.model

import android.content.Context
import dev.belta.macropad.hid.KeyTiming
import dev.belta.macropad.hid.KeyboardLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings for one computer, applied while it is connected. Null fields fall back to the app-wide
 * defaults in [AppSettings].
 */
data class ComputerProfile(
    var name: String? = null,
    var keyboardLayout: KeyboardLayout? = null,
    var keyTiming: KeyTiming? = null,
    /** Pages not shown while this computer is connected, by page name. */
    val hiddenPages: MutableSet<String> = linkedSetOf(),
    /** Page shown when this computer connects. */
    var startPage: String? = null,
) {
    val isEmpty get() = this == ComputerProfile()
}

/** Per-computer settings by Bluetooth address. */
class ComputerProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("computer_profiles", Context.MODE_PRIVATE)

    operator fun get(address: String): ComputerProfile =
        prefs.getString(address, null)?.let {
            try { fromJson(JSONObject(it)) } catch (e: Exception) { null }
        } ?: ComputerProfile()

    fun update(address: String, change: (ComputerProfile) -> Unit) {
        val profile = get(address).also(change)
        val editor = prefs.edit()
        if (profile.isEmpty) editor.remove(address) else editor.putString(address, toJson(profile).toString())
        editor.apply()
    }

    fun remove(address: String) = prefs.edit().remove(address).apply()

    /** Keeps hidden/start pages pointing at a page after it is renamed. */
    fun renamePage(old: String, new: String) {
        for (address in prefs.all.keys) update(address) { p ->
            if (p.hiddenPages.remove(old)) p.hiddenPages += new
            if (p.startPage == old) p.startPage = new
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        for (address in prefs.all.keys.sorted()) put(address, toJson(this@ComputerProfiles[address]))
    }

    /** Replaces all profiles with those in [json] (from [toJson]). */
    fun replaceAll(json: JSONObject) {
        val editor = prefs.edit().clear()
        for (address in json.keys()) {
            val profile = fromJson(json.getJSONObject(address))
            if (!profile.isEmpty) editor.putString(address, toJson(profile).toString())
        }
        editor.apply()
    }

    companion object {
        fun toJson(p: ComputerProfile) = JSONObject().apply {
            p.name?.let { put("name", it) }
            p.keyboardLayout?.let { put("keyboardLayout", it.name) }
            p.keyTiming?.let { put("keyTiming", it.name) }
            if (p.hiddenPages.isNotEmpty()) put("hiddenPages", JSONArray(p.hiddenPages.toList()))
            p.startPage?.let { put("startPage", it) }
        }

        fun fromJson(json: JSONObject): ComputerProfile {
            val hidden = json.optJSONArray("hiddenPages")
            return ComputerProfile(
                name = json.optString("name").ifBlank { null },
                keyboardLayout = KeyboardLayout.values().firstOrNull { it.name == json.optString("keyboardLayout") },
                keyTiming = KeyTiming.values().firstOrNull { it.name == json.optString("keyTiming") },
                hiddenPages = (0 until (hidden?.length() ?: 0)).mapTo(linkedSetOf()) { hidden!!.getString(it) },
                startPage = json.optString("startPage").ifBlank { null },
            )
        }
    }
}
