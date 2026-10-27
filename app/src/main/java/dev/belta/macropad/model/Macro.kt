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
 *   tablet:dim          dims the tablet to its idle clock; not sent
 *   slide:volume        makes the key a slider (a preset, or "up step | down step [| tap step]"); must be the only step
 *   http:POST https://… | {"a":1}   web request from the tablet (GET by default; "| body" is optional)
 *   wol:AA:BB:CC:DD:EE:FF           Wake-on-LAN packet from the tablet
 *   ha:light.toggle light.desk      calls a Home Assistant service (ha_url / ha_token saved values)
 *
 * Network steps run on the tablet at once, beside the keys, and work with no computer connected.
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
    /** Handled on the tablet: "dim". */
    data class Tablet(val command: String) : Step()
    /**
     * A slider key: each tick of drag sends [up] (up / right) or [down]. Tapping its icon sends
     * [tap], if any (Mute for the volume slider). [preset] is its name, if any.
     */
    data class Slide(val up: Step, val down: Step, val preset: String?, val tap: Step? = null) : Step()
    /** Handled on the tablet: a web request. [url] and [body] may hold text variables. */
    data class Http(val method: String, val url: String, val body: String?) : Step()
    /** Handled on the tablet: a Wake-on-LAN magic packet for [mac] ("AA:BB:CC:DD:EE:FF"). */
    data class Wol(val mac: String) : Step()
    /** Handled on the tablet: Home Assistant service [domain].[service], for [entity] if any. */
    data class Ha(val domain: String, val service: String, val entity: String?) : Step()

    /** Steps the tablet sends over the network instead of to the computer. */
    val isNetwork get() = this is Http || this is Wol || this is Ha
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
                lower.startsWith("tablet:") -> when (val command = lower.substring(7).trim()) {
                    in TABLET_COMMANDS -> Step.Tablet(command)
                    else -> throw MacroException("Unknown tablet command '$command' (use ${TABLET_COMMANDS.joinToString()})")
                }
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
                lower.startsWith("slide:") -> parseSlide(line.substring(6).trim())
                lower.startsWith("http:") -> parseHttp(line.substring(5).trim())
                lower.startsWith("wol:") -> parseWol(line.substring(4).trim())
                lower.startsWith("ha:") -> parseHa(line.substring(3).trim())
                else -> parseCombo(lower)
            }
        }
        if (steps.isEmpty()) throw MacroException("Macro is empty")
        if (steps.size > 1 && steps.any { it is Step.Slide }) throw MacroException("A slider must be the key's only step")
        return steps
    }

    /** The key's slider, or null when [action] isn't a (valid) slider. */
    fun slider(action: String) = runCatching { parse(action) }.getOrNull()?.singleOrNull() as? Step.Slide

    /** True when the macro should behave like a physical key (down on touch, up on release). */
    fun isHoldable(steps: List<Step>) =
        steps.size == 1 && (steps[0] is Step.Combo || steps[0] is Step.Media || steps[0] is Step.Click)

    /** Short human-readable hint shown under a key's label, e.g. "Ctrl+Shift+P". */
    fun describe(action: String): String {
        val lines = action.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val dims = lines.any { it.lowercase().startsWith("tablet:") }
        val actions = lines.filterNot {
            val lower = it.lowercase()
            lower.startsWith("delay:") || lower.startsWith("wait:") || lower.startsWith("tablet:")
        }
        if (dims) return if (actions.isEmpty()) "Dims tablet" else describe(actions.joinToString("\n")) + " + dim"
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
            lower.startsWith("slide:") -> {
                val spec = line.substring(6).trim()
                "Slider · " + if (spec.lowercase() in SLIDE_PRESETS) spec.lowercase().replaceFirstChar { it.uppercase() }
                else spec.split('|').take(2).joinToString(" / ") { describe(it.trim()) }
            }
            lower.startsWith("http:") -> "Web request"
            lower.startsWith("wol:") -> "Wake on LAN"
            lower.startsWith("ha:") -> "Home Assistant"
            lower.startsWith("page:") -> when (val target = line.substring(5).trim()) {
                "next" -> "Next page"
                "prev" -> "Previous page"
                else -> "Page $target"
            }
            else -> describeCombo(line)
        }.let { if (lines.size > actions.size) "$it · timed" else it }
    }

    private val TABLET_COMMANDS = listOf("dim")

    /** Slider presets: name to (up step, down step, tap step or null). */
    val SLIDE_PRESETS = linkedMapOf(
        "volume" to Triple("media:volup", "media:voldown", "media:mute"),
        "scroll" to Triple("scroll:1", "scroll:-1", null),
        "zoom" to Triple("ctrl+equal", "ctrl+minus", "ctrl+0"),
        "brightness" to Triple("media:brightup", "media:brightdown", null),
        "tabs" to Triple("ctrl+tab", "ctrl+shift+tab", null),
        "undo" to Triple("ctrl+y", "ctrl+z", null),
    )

    /** A preset name, or "up step | down step [| tap step]" (each one combo, media key or scroll; tap may be a click). */
    private fun parseSlide(spec: String): Step.Slide {
        val name = spec.lowercase()
        SLIDE_PRESETS[name]?.let { (up, down, tap) ->
            return Step.Slide(slideStep(up), slideStep(down), name, tap?.let { slideStep(it, tap = true) })
        }
        val sides = spec.split('|')
        if (sides.size !in 2..3) {
            throw MacroException("Unknown slider '$spec' (use ${SLIDE_PRESETS.keys.joinToString()} or up | down [| tap])")
        }
        return Step.Slide(slideStep(sides[0]), slideStep(sides[1]), null, sides.getOrNull(2)?.let { slideStep(it, tap = true) })
    }

    private fun slideStep(spec: String, tap: Boolean = false): Step {
        if (spec.isBlank()) {
            throw MacroException(if (tap) "The slider's tap step is empty: up | down | tap" else "A slider needs an up and a down step: up | down")
        }
        val step = parse(spec.trim()).single()
        val allowed = step is Step.Combo || step is Step.Media || step is Step.Scroll || (tap && step is Step.Click)
        if (!allowed) {
            throw MacroException(if (tap) "A slider's tap step must be a key combo, media key, scroll or click"
                else "Slider steps must be key combos, media keys or scroll")
        }
        return step
    }

    private val HTTP_METHODS = listOf("GET", "POST", "PUT", "DELETE")

    /** "[METHOD] url [| body]"; the URL is kept as written (text variables are filled in when it runs). */
    private fun parseHttp(spec: String): Step.Http {
        val request = spec.substringBefore('|').trim()
        val body = if ('|' in spec) spec.substringAfter('|').trim() else null
        val first = request.substringBefore(' ')
        val method = first.uppercase().takeIf { it in HTTP_METHODS && ' ' in request }
        val url = if (method != null) request.substringAfter(' ').trim() else request
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            throw MacroException("A web request needs an http:// or https:// address")
        }
        return Step.Http(method ?: if (body != null) "POST" else "GET", url, body)
    }

    private val MAC = Regex("""([0-9a-f]{2})[:-]([0-9a-f]{2})[:-]([0-9a-f]{2})[:-]([0-9a-f]{2})[:-]([0-9a-f]{2})[:-]([0-9a-f]{2})""", RegexOption.IGNORE_CASE)

    private fun parseWol(spec: String): Step.Wol {
        val match = MAC.matchEntire(spec) ?: throw MacroException("Wake on LAN needs the computer's MAC address, e.g. wol:AA:BB:CC:DD:EE:FF")
        return Step.Wol(match.groupValues.drop(1).joinToString(":").uppercase())
    }

    private val HA_SERVICE = Regex("""([a-z0-9_]+)\.([a-z0-9_]+)""")

    /** "domain.service [entity_id]", e.g. "light.toggle light.desk" or "scene.turn_on scene.focus". */
    private fun parseHa(spec: String): Step.Ha {
        val parts = spec.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val service = parts.firstOrNull()?.lowercase()?.let { HA_SERVICE.matchEntire(it) }
        if (service == null || parts.size > 2) {
            throw MacroException("Home Assistant step: ha:domain.service entity, e.g. ha:light.toggle light.desk")
        }
        return Step.Ha(service.groupValues[1], service.groupValues[2], parts.getOrNull(1)?.lowercase())
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
