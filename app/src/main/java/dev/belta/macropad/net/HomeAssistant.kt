package dev.belta.macropad.net

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Date
import kotlin.math.roundToInt

/**
 * An entity on the Home page: [state] as Home Assistant reports it, [area] its area's name, and
 * [attributes] the few the page shows (brightness, position, temperatures, last run…).
 */
data class HaEntity(
    val id: String, val name: String, val state: String, val area: String? = null,
    val attributes: JSONObject = JSONObject(),
) {
    val domain get() = id.substringBefore('.')
    val available get() = state != "unavailable" && state != "unknown"

    /** Lit on the page: on, open, playing, unlocked, heating…; an automation that's enabled. */
    val active get() = available && when (domain) {
        "cover" -> state != "closed"
        "media_player" -> state == "playing"
        "lock" -> state != "locked"
        "climate" -> state != "off"
        "scene" -> false
        else -> state == "on"
    }

    override fun equals(other: Any?) = other is HaEntity && other.id == id && other.name == name &&
        other.state == state && other.area == area && other.attributes.toString() == attributes.toString()

    override fun hashCode() = id.hashCode()
}

/**
 * The Home page's view of Home Assistant: the entities one controls (lights, switches, covers…,
 * scenes, scripts, automations), what tapping them does and how they're grouped.
 */
object HomeAssistant {
    class Domain(val title: String, val icon: String)

    /** Domains the page shows, in this order. */
    val DOMAINS = linkedMapOf(
        "light" to Domain("Lights", "lightbulb"),
        "switch" to Domain("Switches", "power"),
        "fan" to Domain("Fans", "fan"),
        "cover" to Domain("Covers", "blinds"),
        "climate" to Domain("Climate", "thermostat"),
        "media_player" to Domain("Media", "play"),
        "lock" to Domain("Locks", "lock"),
        "input_boolean" to Domain("Toggles", "power"),
        "scene" to Domain("Scenes", "star"),
        "script" to Domain("Scripts", "code"),
        "automation" to Domain("Automations", "bolt"),
    )

    /** Scenes, scripts and automations have sections of their own; devices go by area. */
    private val ROUTINES = listOf("scene", "script", "automation")

    /**
     * One request for what /api/states leaves out: each entity's area and whether it's hidden in
     * Home Assistant, as {"areas": [[id, area]…], "hidden": [id…]}.
     */
    val LOOKUP_TEMPLATE = """
        {%- set ns = namespace(areas=[], hidden=[]) -%}
        {%- for s in states if s.domain in ${DOMAINS.keys.joinToString(",", "[", "]") { "'$it'" }} -%}
        {%- if is_hidden_entity(s.entity_id) %}{% set ns.hidden = ns.hidden + [s.entity_id] %}{% endif -%}
        {%- set a = area_name(s.entity_id) -%}
        {%- if a %}{% set ns.areas = ns.areas + [[s.entity_id, a]] %}{% endif -%}
        {%- endfor -%}
        {{ {"areas": ns.areas, "hidden": ns.hidden} | tojson }}
    """.trimIndent()

    class Lookup(val areas: Map<String, String> = emptyMap(), val hidden: Set<String> = emptySet())

    fun parseLookup(text: String): Lookup {
        val json = JSONObject(text)
        val areas = json.optJSONArray("areas") ?: JSONArray()
        val hidden = json.optJSONArray("hidden") ?: JSONArray()
        return Lookup(
            (0 until areas.length()).mapNotNull { areas.optJSONArray(it) }.associate { it.optString(0) to it.optString(1) },
            (0 until hidden.length()).map { hidden.optString(it) }.toSet(),
        )
    }

    /** WebSocket command listing which entities each voice assistant (Assist, Alexa, Google) gets. */
    const val EXPOSED_COMMAND = "homeassistant/expose_entity/list"

    /** Entities exposed to at least one voice assistant, from [EXPOSED_COMMAND]'s result. */
    fun parseExposed(result: JSONObject): Set<String> {
        val entities = result.optJSONObject("exposed_entities") ?: return emptySet()
        return entities.keys().asSequence().filter { id ->
            val assistants = entities.optJSONObject(id) ?: return@filter false
            assistants.keys().asSequence().any { assistants.optBoolean(it) }
        }.toSet()
    }

    /**
     * The main entities: devices exposed to a voice assistant (the ones worth a button; a camera's
     * Flip or a router's guest Wi-Fi aren't) or [pinned], and every scene, script and automation.
     * All of them when nothing is known to be exposed.
     */
    fun main(entities: List<HaEntity>, exposed: Set<String>, pinned: Collection<String>): List<HaEntity> {
        if (entities.none { it.domain !in ROUTINES && it.id in exposed }) return entities
        return entities.filter { it.domain in ROUTINES || it.id in exposed || it.id in pinned }
    }

    /** The entities of [DOMAINS] in /api/states' [json], less the ones hidden in Home Assistant. */
    fun parseStates(json: String, lookup: Lookup = Lookup()): List<HaEntity> {
        val list = JSONArray(json)
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { state ->
            val id = state.optString("entity_id")
            if (id.substringBefore('.') !in DOMAINS || id in lookup.hidden) return@mapNotNull null
            val attributes = state.optJSONObject("attributes") ?: JSONObject()
            val name = attributes.optString("friendly_name").ifEmpty { id.substringAfter('.').replace('_', ' ') }
            HaEntity(id, name, state.optString("state"), lookup.areas[id], attributes)
        }
    }

    /**
     * The service a tap calls, as (domain, service); null when a tap needs a choice first (a lock
     * asks, a thermostat shows its menu).
     */
    fun tap(entity: HaEntity): Pair<String, String>? = when (entity.domain) {
        "lock", "climate" -> null
        "scene", "script" -> entity.domain to "turn_on"
        "media_player" -> "media_player" to "media_play_pause"
        else -> entity.domain to "toggle"
    }

    /** [entity] as it should look right after a tap, before Home Assistant reports it. */
    fun tapped(entity: HaEntity): HaEntity = when (entity.domain) {
        "light", "switch", "fan", "input_boolean", "automation" ->
            entity.copy(state = if (entity.state == "on") "off" else "on")
        "cover" -> entity.copy(state = if (entity.active) "closing" else "opening")
        "media_player" -> entity.copy(state = if (entity.state == "playing") "paused" else "playing")
        else -> entity
    }

    /** The line under the entity's name: "On · 60%", "Open · 40%", "21.5° → 22°", "Ran 5 min ago"… */
    fun stateText(entity: HaEntity, now: Date = Date()): String {
        val a = entity.attributes
        if (!entity.available) return "Unavailable"
        return when (entity.domain) {
            "light" -> if (entity.state == "on" && a.has("brightness")) {
                "On · ${(a.optDouble("brightness") * 100 / 255).roundToInt()}%"
            } else word(entity.state)
            "cover" -> word(entity.state) + if (a.has("current_position") && entity.state == "open") " · ${a.optInt("current_position")}%" else ""
            "climate" -> listOfNotNull(
                word(entity.state),
                a.optDouble("current_temperature").takeIf { !it.isNaN() }?.let { degrees(it) +
                    (a.optDouble("temperature").takeIf { t -> !t.isNaN() && entity.state != "off" }?.let { t -> " → " + degrees(t) } ?: "") },
            ).joinToString(" · ")
            "media_player" -> word(entity.state) + (a.optString("media_title").takeIf { it.isNotEmpty() && entity.state == "playing" }?.let { " · $it" } ?: "")
            "scene" -> "Scene"
            "script" -> if (entity.state == "on") "Running" else "Script"
            "automation" -> if (entity.state == "off") "Off" else
                parseTime(a.optString("last_triggered"))?.let { "Ran " + ago(it, now) } ?: "Never ran"
            else -> word(entity.state)
        }
    }

    private fun word(state: String) = state.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun degrees(value: Double) = (if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)) + "°"

    private fun parseTime(text: String): Date? =
        if (text.isEmpty() || text == "null") null else runCatching { Date.from(OffsetDateTime.parse(text).toInstant()) }.getOrNull()

    fun ago(then: Date, now: Date): String {
        val minutes = (now.time - then.time) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} d ago"
        }
    }

    /**
     * The page's sections: [pinned] first (all entities, no search), then the devices by area
     * (alphabetically, no area last), then scenes, scripts and automations. [domain] and [query]
     * (part of a name or id) narrow it down.
     */
    fun sections(entities: List<HaEntity>, pinned: List<String>, domain: String? = null, query: String = ""): List<Pair<String, List<HaEntity>>> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shown = entities.filter { e ->
            (domain == null || e.domain == domain) &&
                words.all { e.name.lowercase().contains(it) || e.id.contains(it) || e.area?.lowercase()?.contains(it) == true }
        }
        val sections = mutableListOf<Pair<String, List<HaEntity>>>()
        val byId = shown.associateBy { it.id }
        val top = if (domain == null && words.isEmpty()) pinned.mapNotNull { byId[it] } else emptyList()
        if (top.isNotEmpty()) sections += "Pinned" to top
        val rest = shown - top.toSet()
        val order = compareBy<HaEntity>({ DOMAINS.keys.indexOf(it.domain) }, { it.name.lowercase() })
        val devices = rest.filter { it.domain !in ROUTINES }.groupBy { it.area }
        devices.keys.filterNotNull().sortedBy { it.lowercase() }.forEach { sections += it to devices.getValue(it).sortedWith(order) }
        devices[null]?.let { sections += (if (devices.size > 1) "Other" else "Devices") to it.sortedWith(order) }
        for (routine in ROUTINES) {
            rest.filter { it.domain == routine }.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let {
                sections += DOMAINS.getValue(routine).title to it
            }
        }
        return sections
    }
}
