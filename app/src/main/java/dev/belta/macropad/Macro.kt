package dev.belta.macropad

/**
 * Macro language, one step per line:
 *
 *   ctrl+shift+t        key combo (modifiers + up to 6 keys)
 *   media:volup         consumer/media key
 *   text:Hello world    types text (US layout); "\n" = Enter, "\t" = Tab
 *   delay:250           wait in milliseconds
 *
 * A macro that is a single combo or media key is "held" while the button is touched,
 * so the host sees a real key press (auto-repeat, holding modifiers, etc).
 */
sealed class Step {
    data class Combo(val mods: Int, val keys: List<Int>) : Step()
    data class Media(val usage: Int) : Step()
    data class Text(val text: String) : Step()
    data class Delay(val ms: Long) : Step()
}

class MacroException(message: String) : Exception(message)

object Macro {

    fun parse(action: String): List<Step> {
        val steps = mutableListOf<Step>()
        for (raw in action.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val lower = line.lowercase()
            steps += when {
                lower.startsWith("text:") -> Step.Text(unescape(raw.trimStart().substring(5)))
                lower.startsWith("media:") -> {
                    val name = lower.substring(6).trim()
                    Step.Media(HidKeys.media[name] ?: throw MacroException("Unknown media key '$name'"))
                }
                lower.startsWith("delay:") || lower.startsWith("wait:") -> {
                    val ms = lower.substringAfter(':').trim().toLongOrNull()
                        ?: throw MacroException("Bad delay '$line'")
                    Step.Delay(ms.coerceIn(0, 10_000))
                }
                else -> parseCombo(lower)
            }
        }
        if (steps.isEmpty()) throw MacroException("Macro is empty")
        return steps
    }

    /** True when the macro should behave like a physical key (down on touch, up on release). */
    fun isHoldable(steps: List<Step>) =
        steps.size == 1 && (steps[0] is Step.Combo || steps[0] is Step.Media)

    /** Short human-readable hint shown under a key's label, e.g. "Ctrl+Shift+P". */
    fun describe(action: String): String {
        val lines = action.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val actions = lines.filterNot { it.lowercase().startsWith("delay:") || it.lowercase().startsWith("wait:") }
        // Win+R followed by typed text: an app launcher.
        if (actions.size == 2 && actions[0].lowercase() == "win+r" && actions[1].lowercase().startsWith("text:")) {
            return "Opens " + actions[1].substring(5).removeSuffix("\\n").trim()
        }
        if (actions.size != 1) return "Macro · ${actions.size} steps"
        val line = actions[0]
        val lower = line.lowercase()
        return when {
            lower.startsWith("text:") -> "Types “${line.substring(5).replace("\\n", "⏎").take(18)}”"
            lower.startsWith("media:") -> "Media key"
            else -> lower.split('+').joinToString("+") { prettyKey(it.trim()) }
        }.let { if (lines.size > actions.size) "$it · timed" else it }
    }

    private fun prettyKey(k: String) = when (k) {
        "ctrl", "control", "lctrl" -> "Ctrl"
        "shift", "lshift" -> "Shift"
        "alt", "lalt", "option" -> "Alt"
        "super", "win", "gui", "meta" -> "Win"
        "cmd" -> "Cmd"
        "grave" -> "`"
        "slash" -> "/"
        "backslash" -> "\\"
        "minus" -> "−"
        "equal" -> "="
        "plus" -> "+"
        "semicolon" -> ";"
        "comma" -> ","
        "period" -> "."
        "quote" -> "'"
        "pageup", "pgup" -> "PgUp"
        "pagedown", "pgdn" -> "PgDn"
        "left" -> "←"
        "right" -> "→"
        "up" -> "↑"
        "down" -> "↓"
        "enter", "return" -> "Enter"
        "esc", "escape" -> "Esc"
        "printscreen", "prtsc" -> "PrtSc"
        else -> if (k.length == 1 || k.matches(Regex("f\\d+"))) k.uppercase() else k.replaceFirstChar { it.uppercase() }
    }

    private fun parseCombo(spec: String): Step.Combo {
        var mods = 0
        val keys = mutableListOf<Int>()
        for (part in spec.split('+').map { it.trim() }) {
            if (part.isEmpty()) throw MacroException("Use 'plus' for the + key")
            val mod = HidKeys.modifiers[part]
            if (mod != null) { mods = mods or mod; continue }
            val key = HidKeys.keys[part]
            if (key != null) { keys += key; continue }
            val ch = when {
                part == "plus" -> HidKeys.charToKey('+')
                part.length == 1 -> HidKeys.charToKey(part[0])
                else -> null
            }
            if (ch != null) { mods = mods or ch.first; keys += ch.second; continue }
            throw MacroException("Unknown key '$part'")
        }
        if (keys.size > 6) throw MacroException("At most 6 keys per combo")
        return Step.Combo(mods, keys)
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")
}

object HidKeys {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val GUI = 0x08

    val modifiers = mapOf(
        "ctrl" to 0x01, "control" to 0x01, "lctrl" to 0x01,
        "shift" to 0x02, "lshift" to 0x02,
        "alt" to 0x04, "option" to 0x04, "lalt" to 0x04,
        "gui" to 0x08, "win" to 0x08, "super" to 0x08, "cmd" to 0x08, "meta" to 0x08,
        "rctrl" to 0x10, "rshift" to 0x20, "ralt" to 0x40, "altgr" to 0x40,
        "rgui" to 0x80, "rwin" to 0x80, "rcmd" to 0x80,
    )

    val keys: Map<String, Int> = buildMap {
        for (i in 0 until 26) put(('a' + i).toString(), 0x04 + i)
        for (i in 1..9) put(i.toString(), 0x1E + i - 1)
        put("0", 0x27)
        put("enter", 0x28); put("return", 0x28)
        put("esc", 0x29); put("escape", 0x29)
        put("backspace", 0x2A); put("bksp", 0x2A)
        put("tab", 0x2B)
        put("space", 0x2C)
        put("minus", 0x2D); put("equal", 0x2E)
        put("lbracket", 0x2F); put("rbracket", 0x30); put("backslash", 0x31)
        put("semicolon", 0x33); put("quote", 0x34); put("grave", 0x35)
        put("comma", 0x36); put("period", 0x37); put("slash", 0x38)
        put("capslock", 0x39)
        for (i in 1..12) put("f$i", 0x3A + i - 1)
        put("printscreen", 0x46); put("prtsc", 0x46); put("sysrq", 0x46)
        put("scrolllock", 0x47); put("pause", 0x48)
        put("insert", 0x49); put("ins", 0x49)
        put("home", 0x4A); put("pageup", 0x4B); put("pgup", 0x4B)
        put("delete", 0x4C); put("del", 0x4C)
        put("end", 0x4D); put("pagedown", 0x4E); put("pgdn", 0x4E)
        put("right", 0x4F); put("left", 0x50); put("down", 0x51); put("up", 0x52)
        put("numlock", 0x53)
        put("kpslash", 0x54); put("kpasterisk", 0x55); put("kpminus", 0x56); put("kpplus", 0x57)
        put("kpenter", 0x58)
        for (i in 1..9) put("kp$i", 0x59 + i - 1)
        put("kp0", 0x62); put("kpdot", 0x63)
        put("menu", 0x65); put("app", 0x65)
        for (i in 13..24) put("f$i", 0x68 + i - 13)
    }

    /** Consumer page usages (HID usage page 0x0C). */
    val media = mapOf(
        "playpause" to 0xCD, "play" to 0xCD, "pause" to 0xCD,
        "next" to 0xB5, "prev" to 0xB6, "previous" to 0xB6, "stop" to 0xB7,
        "mute" to 0xE2, "volup" to 0xE9, "voldown" to 0xEA,
        "brightup" to 0x6F, "brightdown" to 0x70,
        "mail" to 0x18A, "calc" to 0x192, "files" to 0x194, "browser" to 0x196,
        "search" to 0x221, "home" to 0x223, "back" to 0x224, "forward" to 0x225, "refresh" to 0x227,
    )

    private const val SHIFTED_DIGITS = ")!@#$%^&*("

    /** Maps a character to (modifiers, usage) on a US keyboard layout. */
    fun charToKey(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> 0 to 0x04 + (c - 'a')
        in 'A'..'Z' -> SHIFT to 0x04 + (c - 'A')
        in '1'..'9' -> 0 to 0x1E + (c - '1')
        '0' -> 0 to 0x27
        in SHIFTED_DIGITS -> SHIFT to (SHIFTED_DIGITS.indexOf(c).let { if (it == 0) 0x27 else 0x1E + it - 1 })
        '\n' -> 0 to 0x28
        '\t' -> 0 to 0x2B
        ' ' -> 0 to 0x2C
        '-' -> 0 to 0x2D; '_' -> SHIFT to 0x2D
        '=' -> 0 to 0x2E; '+' -> SHIFT to 0x2E
        '[' -> 0 to 0x2F; '{' -> SHIFT to 0x2F
        ']' -> 0 to 0x30; '}' -> SHIFT to 0x30
        '\\' -> 0 to 0x31; '|' -> SHIFT to 0x31
        ';' -> 0 to 0x33; ':' -> SHIFT to 0x33
        '\'' -> 0 to 0x34; '"' -> SHIFT to 0x34
        '`' -> 0 to 0x35; '~' -> SHIFT to 0x35
        ',' -> 0 to 0x36; '<' -> SHIFT to 0x36
        '.' -> 0 to 0x37; '>' -> SHIFT to 0x37
        '/' -> 0 to 0x38; '?' -> SHIFT to 0x38
        else -> null
    }
}
