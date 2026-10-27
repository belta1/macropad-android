package dev.belta.macropad.hid

import android.os.Build

/**
 * What differs between the tablets the macropad runs on. The Fire OS workarounds (see
 * docs/bluetooth-troubleshooting.md) are only needed, and only safe, on Amazon Fire tablets; other
 * tablets (e.g. a Galaxy Tab used for other things too) keep their Classic Bluetooth as it is.
 */
object DeviceQuirks {
    val isFire: Boolean = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /**
     * Hide Classic Bluetooth while the macropad runs: Fire OS wipes the keyboard's bond when a PC
     * reaches the tablet's Classic side.
     */
    val hideClassicBluetooth get() = isFire

    /** The name computers see. Fire tablets keep the 1.x name, so existing pairings keep theirs. */
    val defaultDeviceName get() = if (isFire) "Fire Macropad" else "Macropad"

    /** Android 13+ no longer lets apps turn Bluetooth off and on. */
    val canToggleBluetooth get() = Build.VERSION.SDK_INT < 33

    /** OLED screens (e.g. Galaxy Tab S5e) keep a static image; Fire tablets are LCD. */
    val defaultScreenShift get() = !isFire

    /** Android 10+ only lets an app open its screen from the background with "Display over other apps". */
    val needsOverlayToOpenFromBackground get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
