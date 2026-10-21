package dev.belta.macropad.hid

/**
 * How long tapped keys (macros, typed text, toggle keys) are held down and how far apart they are.
 * Some apps, and remote sessions such as Citrix, miss key presses that are released almost at once.
 */
enum class KeyTiming(val label: String, val holdMs: Long, val gapMs: Long) {
    NORMAL("Normal", 15, 10),
    REMOTE("Remote session (Citrix / RDP)", 40, 30),
}
