package dev.belta.macropad.model

import android.content.Context
import dev.belta.macropad.hid.DeviceQuirks
import dev.belta.macropad.hid.HidPeripheral
import dev.belta.macropad.hid.KeyTiming
import dev.belta.macropad.hid.KeyboardLayout
import java.security.MessageDigest

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

    /** Bluetooth LE or Classic Bluetooth; Automatic uses Classic where the tablet has it. */
    var transport: HidPeripheral.Transport
        get() = HidPeripheral.Transport.values().firstOrNull { it.name == prefs.getString("transport", null) }
            ?: HidPeripheral.Transport.AUTO
        set(value) = prefs.edit().putString("transport", value.name).apply()

    /** The name computers see when pairing. */
    var bluetoothName: String
        get() = prefs.getString("bluetooth_name", null)?.takeIf { it.isNotBlank() } ?: DeviceQuirks.defaultDeviceName
        set(value) = prefs.edit().putString("bluetooth_name", value.ifBlank { null }).apply()

    /** Move the screen a few pixels every few minutes, so an OLED screen doesn't keep a static image. */
    var screenShift: Boolean
        get() = prefs.getBoolean("screen_shift", DeviceQuirks.defaultScreenShift)
        set(value) = prefs.edit().putBoolean("screen_shift", value).apply()

    /** Start the macropad when the tablet boots. */
    var openOnBoot: Boolean
        get() = prefs.getBoolean("open_on_boot", true)
        set(value) = prefs.edit().putBoolean("open_on_boot", value).apply()

    /** Bring the macropad to the front when the charger is plugged in. */
    var openOnCharge: Boolean
        get() = prefs.getBoolean("open_on_charge", true)
        set(value) = prefs.edit().putBoolean("open_on_charge", value).apply()

    /** Sidebar on the left of the keys instead of the right. */
    var sidebarLeft: Boolean
        get() = prefs.getBoolean("sidebar_left", false)
        set(value) = prefs.edit().putBoolean("sidebar_left", value).apply()

    /** Minutes without a touch before the screen dims to a clock; 0 = never. */
    var idleDimMinutes: Int
        get() = prefs.getInt("idle_dim_minutes", 5)
        set(value) = prefs.edit().putInt("idle_dim_minutes", value).apply()

    /** Hash of the PIN that guards editing and settings; null = no PIN. */
    var pinHash: String?
        get() = prefs.getString("pin_hash", null)
        set(value) = prefs.edit().putString("pin_hash", value).apply()

    /** When the app last looked for an update on its own (ms since epoch). */
    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()

    var lastPage: Int
        get() = prefs.getInt("page", 0)
        set(value) = prefs.edit().putInt("page", value).apply()

    companion object {
        fun hashPin(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest("macropad-pin:$pin".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
