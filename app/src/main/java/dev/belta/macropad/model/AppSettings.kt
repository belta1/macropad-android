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

    /** Show the page for the computer's app in front, as the PC link reports it. */
    var followPcApp: Boolean
        get() = prefs.getBoolean("follow_pc_app", true)
        set(value) = prefs.edit().putBoolean("follow_pc_app", value).apply()

    /** Start the macropad when the tablet boots. */
    var openOnBoot: Boolean
        get() = prefs.getBoolean("open_on_boot", true)
        set(value) = prefs.edit().putBoolean("open_on_boot", value).apply()

    /** Bring the macropad to the front when the charger is plugged in. */
    var openOnCharge: Boolean
        get() = prefs.getBoolean("open_on_charge", true)
        set(value) = prefs.edit().putBoolean("open_on_charge", value).apply()

    /** Sidebar on the left of the keys instead of the right. */
    /** New mail in the computer's classic Outlook shows on the tablet (PC link). */
    var mailNotices: Boolean
        get() = prefs.getBoolean("mail_notices", true)
        set(value) = prefs.edit().putBoolean("mail_notices", value).apply()

    /** How much each page is used ([PageUsage] as JSON). */
    var pageUsage: String?
        get() = prefs.getString("page_usage", null)
        set(value) = prefs.edit().putString("page_usage", value).apply()

    /** A Teams call brings up the Teams page (and the page before comes back after it). */
    var followCalls: Boolean
        get() = prefs.getBoolean("follow_calls", true)
        set(value) = prefs.edit().putBoolean("follow_calls", value).apply()

    /** Size of the speaker notes on the Presenter page, in sp. */
    var notesSize: Float
        get() = prefs.getFloat("notes_size", 26f)
        set(value) = prefs.edit().putFloat("notes_size", value).apply()

    /** Home Assistant entities pinned to the top of the Home page, in the order they were pinned. */
    var haPinned: List<String>
        get() = prefs.getString("ha_pinned", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("ha_pinned", value.joinToString("\n")).apply()

    /** Home Assistant entities left off the Home page. */
    var haHidden: Set<String>
        get() = prefs.getString("ha_hidden", "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()
        set(value) = prefs.edit().putString("ha_hidden", value.sorted().joinToString("\n")).apply()

    /** Experimental, off by default: recording also captures the computer's own keyboard and mouse (PC link). */
    var recordPcKeyboard: Boolean
        get() = prefs.getBoolean("record_pc_keyboard", false)
        set(value) = prefs.edit().putBoolean("record_pc_keyboard", value).apply()

    /** The computer's monitor the Screen page mirrors (1 = leftmost); 0 = its main monitor. */
    var screenMonitor: Int
        get() = prefs.getInt("screen_monitor", 0)
        set(value) = prefs.edit().putInt("screen_monitor", value).apply()

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
