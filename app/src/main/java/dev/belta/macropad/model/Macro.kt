package dev.belta.macropad.model

import dev.belta.macropad.hid.HidKeys
import dev.belta.macropad.hid.KeyboardLayout

/**
 * Macro language, one step per line (see docs/macro-language.md):
 *
 *   ctrl+shift+t        key combo (modifiers + up to 6 keys, US key names)
 *   media:volup         consumer/media key
 *   text:Hello world    types text using the computer's keyboard layout; "\n" = Enter, "\t" = Tab
 *   delay:250           wait in milliseconds
 *   click:left          mouse button (left, right, middle)
 *   scroll:3            mouse wheel; positive = up, negative = down
 *   page:next           switches the tablet's page (next, prev or a page name); not sent
 *
 * Text may contain {date}, {time}, {date:<pattern>} and {clipboard}, filled in when the key runs
 * ([TextVariables]).
 *
 * A macro that is a single combo, media key or click is "held" while the key is touched,
 * so the computer sees a real key press (auto-repeat, holding modifiers, dragging, ...).
 */
sealed class Step {
    data class Combo(val mods: Int, val keys: List<Int>) : Step()
    data class Media(val usage: Int) : Step()
    data class Text(val text: String) : Step()
    data class Delay(val ms: Long) : Step()
    data class Click(val buttons: Int) : Step()
    data class Scroll(val amount: Int) : Step()
    /** Handled on the tablet: "next", "prev" or a page name. */
    data class Page(val target: String) : Step()
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
                lower.startsWith("page:") -> Step.Page(
                    line.substring(5).trim().ifEmpty { throw MacroException("Page step needs next, prev or a page name") })
                lower.startsWith("media:") -> {
                    val name = lower.substring(6).trim()
                    Step.Media(HidKeys.media[name] ?: throw MacroException("Unknown media key '$name'"))
                }
                lower.startsWith("delay:") || lower.startsWith("wait:") -> {
                    val ms = lower.substringAfter(':').trim().toLongOrNull()
                        ?: throw MacroException("Bad delay '$line'")
                    Step.Delay(ms.coerceIn(0, 10_000))
                }
                lower.startsWith("click:") -> {
                    val name = lower.substring(6).trim()
                    Step.Click(HidKeys.mouseButtons[name] ?: throw MacroException("Unknown mouse button '$name'"))
                }
                lower.startsWith("scroll:") -> {
                    val amount = lower.substring(7).trim().toIntOrNull()
                        ?: throw MacroException("Bad scroll amount '$line'")
                    Step.Scroll(amount.coerceIn(-50, 50))
                }
                else -> parseCombo(lower)
            }
        }
        if (steps.isEmpty()) throw MacroException("Macro is empty")
        return steps
    }

    /** True when the macro should behave like a physical key (down on touch, up on release). */
    fun isHoldable(steps: List<Step>) =
        steps.size == 1 && (steps[0] is Step.Combo || steps[0] is Step.Media || steps[0] is Step.Click)

    /** Short human-readable hint shown under a key's label, e.g. "Ctrl+Shift+P". */
    fun describe(action: String): String {
        val lines = action.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val actions = lines.filterNot { it.lowercase().startsWith("delay:") || it.lowercase().startsWith("wait:") }
        // Win+R followed by typed text: an app launcher.
        if (actions.size == 2 && actions[0].lowercase() == "win+r" && actions[1].lowercase().startsWith("text:")) {
            return "Opens " + actions[1].substring(5).removeSuffix("\\n").trim()
        }
        // Win+number (focus a taskbar app) and a shortcut: "Win+8 → Ctrl+2"; with Alt+Tab back
        // to the previous window just the shortcut and ↩ ("Ctrl+Shift+O ↩"), which fits on a key.
        if (actions.size in 2..3 && FOCUS_APP.matches(actions[0].lowercase()) && !actions[1].contains(':') &&
            (actions.size == 2 || actions[2].lowercase() == "alt+tab")
        ) {
            return if (actions.size == 3) describeCombo(actions[1]) + " ↩"
            else describeCombo(actions[0]) + " → " + describeCombo(actions[1])
        }
        if (actions.size != 1) return "Macro · ${actions.size} steps"
        val line = actions[0]
        val lower = line.lowercase()
        return when {
            lower.startsWith("text:") -> "Types “${line.substring(5).replace("\\n", "⏎").take(18)}”"
            lower.startsWith("media:") -> "Media key"
            lower.startsWith("click:") -> lower.substring(6).trim().replaceFirstChar { it.uppercase() } + " click"
            lower.startsWith("scroll:") -> "Scroll " + lower.substring(7).trim()
            lower.startsWith("page:") -> when (val target = line.substring(5).trim()) {
                "next" -> "Next page"
                "prev" -> "Previous page"
                else -> "Page $target"
            }
            else -> describeCombo(line)
        }.let { if (lines.size > actions.size) "$it · timed" else it }
    }

    private val FOCUS_APP = Regex("""(win|super|gui|meta)\+[0-9]""")

    private fun describeCombo(line: String) = line.lowercase().split('+').joinToString("+") { prettyKey(it.trim()) }

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

    /** Combos name physical keys, so single characters resolve through the US layout. */
    private fun parseCombo(spec: String): Step.Combo {
        var mods = 0
        val keys = mutableListOf<Int>()
        for (part in spec.split('+').map { it.trim() }) {
            if (part.isEmpty()) throw MacroException("Use 'plus' for the + key")
            val mod = HidKeys.modifiers[part]
            if (mod != null) { mods = mods or mod; continue }
            val key = HidKeys.keys[part]
            if (key != null) { keys += key; continue }
            val stroke = when {
                part == "plus" -> KeyboardLayout.US.strokes('+')
                part.length == 1 -> KeyboardLayout.US.strokes(part[0])
                else -> null
            }?.singleOrNull()
            if (stroke != null) { mods = mods or stroke.mods; keys += stroke.key; continue }
            throw MacroException("Unknown key '$part'")
        }
        if (keys.size > 6) throw MacroException("At most 6 keys per combo")
        return Step.Combo(mods, keys)
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")
}
