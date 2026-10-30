package dev.belta.macropad.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class HomeAssistantTest {
    private val states = """[
        {"entity_id":"light.desk","state":"on","attributes":{"friendly_name":"Desk lamp","brightness":153}},
        {"entity_id":"light.hall","state":"off","attributes":{"friendly_name":"Hall"}},
        {"entity_id":"switch.heater","state":"unavailable","attributes":{}},
        {"entity_id":"sensor.temperature","state":"21","attributes":{"friendly_name":"Temperature"}},
        {"entity_id":"cover.blinds","state":"open","attributes":{"friendly_name":"Blinds","current_position":40}},
        {"entity_id":"automation.morning","state":"on","attributes":{"friendly_name":"Morning","last_triggered":"2026-10-29T10:00:00.123456+00:00"}},
        {"entity_id":"automation.night","state":"off","attributes":{"friendly_name":"Night","last_triggered":null}},
        {"entity_id":"scene.focus","state":"2026-10-28T09:00:00+00:00","attributes":{"friendly_name":"Focus"}},
        {"entity_id":"switch.secret","state":"on","attributes":{"friendly_name":"Hidden in HA"}}
    ]"""
    private val lookup = HomeAssistant.parseLookup(
        """{"areas":[["light.desk","Office"],["cover.blinds","Office"],["light.hall","Hall"]],"hidden":["switch.secret"]}""")
    private val entities = HomeAssistant.parseStates(states, lookup)

    private fun get(id: String) = entities.first { it.id == id }

    @Test
    fun `keeps the domains it controls, with areas, less what Home Assistant hides`() {
        assertEquals(listOf("light.desk", "light.hall", "switch.heater", "cover.blinds", "automation.morning", "automation.night", "scene.focus"),
            entities.map { it.id })
        assertEquals("Office", get("light.desk").area)
        assertEquals("heater", get("switch.heater").name) // no friendly name
    }

    @Test
    fun `lit, state lines and taps`() {
        assertTrue(get("light.desk").active)
        assertFalse(get("switch.heater").active)
        assertTrue(get("cover.blinds").active)
        assertTrue(get("automation.morning").active)
        assertFalse(get("scene.focus").active)
        assertEquals("On · 60%", HomeAssistant.stateText(get("light.desk")))
        assertEquals("Off", HomeAssistant.stateText(get("light.hall")))
        assertEquals("Unavailable", HomeAssistant.stateText(get("switch.heater")))
        assertEquals("Open · 40%", HomeAssistant.stateText(get("cover.blinds")))
        val now = Date.from(java.time.Instant.parse("2026-10-29T10:05:30Z"))
        assertEquals("Ran 5 min ago", HomeAssistant.stateText(get("automation.morning"), now))
        assertEquals("Off", HomeAssistant.stateText(get("automation.night"), now))
        assertEquals("light" to "toggle", HomeAssistant.tap(get("light.desk")))
        assertEquals("scene" to "turn_on", HomeAssistant.tap(get("scene.focus")))
        assertEquals("automation" to "toggle", HomeAssistant.tap(get("automation.morning")))
        assertNull(HomeAssistant.tap(HaEntity("lock.door", "Door", "locked")))
        assertEquals("off", HomeAssistant.tapped(get("light.desk")).state)
        assertEquals("closing", HomeAssistant.tapped(get("cover.blinds")).state)
    }

    @Test
    fun `sections - pinned, areas, no area, then scenes and automations`() {
        val sections = HomeAssistant.sections(entities, pinned = listOf("automation.night", "gone.entity"))
        assertEquals(listOf("Pinned", "Hall", "Office", "Other", "Scenes", "Automations"), sections.map { it.first })
        assertEquals(listOf("light.desk", "cover.blinds"), sections[2].second.map { it.id }) // lights before covers
        assertEquals(listOf("automation.morning"), sections.last().second.map { it.id }) // the pinned one isn't twice
        // A kind or a search shows everything that matches, pinned or not.
        assertEquals(listOf("Hall", "Office"), HomeAssistant.sections(entities, listOf("light.desk"), domain = "light").map { it.first })
        assertEquals(listOf("light.desk", "cover.blinds"),
            HomeAssistant.sections(entities, emptyList(), query = "office").flatMap { it.second }.map { it.id })
        assertEquals(listOf("light.desk"), HomeAssistant.sections(entities, emptyList(), query = "desk LAMP").flatMap { it.second }.map { it.id })
    }

    @Test
    fun `main entities - exposed devices, pinned ones and every routine`() {
        val exposed = HomeAssistant.parseExposed(JSONObject("""{"exposed_entities":{
            "light.desk":{"conversation":true,"cloud.alexa":false},
            "light.hall":{"conversation":false,"cloud.alexa":false}}}"""))
        assertEquals(setOf("light.desk"), exposed)
        assertEquals(listOf("light.desk", "cover.blinds", "automation.morning", "automation.night", "scene.focus"),
            HomeAssistant.main(entities, exposed, pinned = listOf("cover.blinds")).map { it.id })
        // Nothing exposed (or not known): everything.
        assertEquals(entities, HomeAssistant.main(entities, emptySet(), emptyList()))
    }

    @Test
    fun `the lookup template asks only for the page's domains`() {
        assertTrue(HomeAssistant.LOOKUP_TEMPLATE.contains("['light','switch',"))
        assertTrue(JSONObject("""{"areas":[],"hidden":[]}""").let { HomeAssistant.parseLookup(it.toString()).areas.isEmpty() })
    }
}
