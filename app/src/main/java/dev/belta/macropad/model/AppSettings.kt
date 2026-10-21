package dev.belta.macropad.model

import android.content.Context
import dev.belta.macropad.hid.KeyTiming
import dev.belta.macropad.hid.KeyboardLayout

/** App-wide preferences (not part of the exported layout). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var keyboardLayout: KeyboardLayout
        get() = KeyboardLayout.values().firstOrNull { it.name == prefs.getString("keyboard_layout", null) }
            ?: KeyboardLayout.US
        set(value) = prefs.edit().putString("keyboard_layout", value.name).apply()

    var keyTiming: KeyTiming
        get() = KeyTiming.values().firstOrNull { it.name == prefs.getString("key_timing", null) }
            ?: KeyTiming.NORMAL
        set(value) = prefs.edit().putString("key_timing", value.name).apply()

    /** Start the macropad when the tablet boots. */
    var openOnBoot: Boolean
        get() = prefs.getBoolean("open_on_boot", true)
        set(value) = prefs.edit().putBoolean("open_on_boot", value).apply()

    /** Bring the macropad to the front when the charger is plugged in. */
    var openOnCharge: Boolean
        get() = prefs.getBoolean("open_on_charge", true)
        set(value) = prefs.edit().putBoolean("open_on_charge", value).apply()

    var lastPage: Int
        get() = prefs.getInt("page", 0)
        set(value) = prefs.edit().putInt("page", value).apply()
}
