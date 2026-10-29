package dev.belta.macropad.pc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class PcProtocolTest {

    @Test
    fun `parses hello`() {
        assertEquals(PcMessage.Hello("LAPTOP-JF", teams = true, outlook = false),
            PcProtocol.parse("""{"t":"hello","v":1,"host":"LAPTOP-JF","teams":true,"outlook":false}"""))
    }

    @Test
    fun `parses the app in front without exe and in lowercase`() {
        assertEquals(PcMessage.App("ms-teams"), PcProtocol.parse("""{"t":"app","exe":"MS-Teams.exe","title":"Chat"}"""))
        assertEquals(PcMessage.App(null), PcProtocol.parse("""{"t":"app"}"""))
    }

    @Test
    fun `parses Teams state, and its absence`() {
        val message = PcProtocol.parse("""{"t":"teams","meeting":true,"muted":true,"camera":false,"hand":true,"blur":false}""")
        assertEquals(PcMessage.Teams(TeamsState(meeting = true, muted = true, camera = false, hand = true, blur = false)), message)
        assertEquals(PcMessage.Teams(null), PcProtocol.parse("""{"t":"teams"}"""))
    }

    @Test
    fun `parses meetings with an offset`() {
        val message = PcProtocol.parse(
            """{"t":"meeting","title":"Standup","start":"2026-10-29T10:00:00-03:00","end":"2026-10-29T10:15:00.0000000-03:00"}""",
        ) as PcMessage.Meeting
        val event = message.event!!
        assertEquals("Standup", event.title)
        assertEquals(Date.from(java.time.Instant.parse("2026-10-29T13:00:00Z")), event.start)
        assertEquals(Date.from(java.time.Instant.parse("2026-10-29T13:15:00Z")), event.end)
        assertEquals(PcMessage.Meeting(null), PcProtocol.parse("""{"t":"meeting"}"""))
        assertEquals(PcMessage.Meeting(null), PcProtocol.parse("""{"t":"meeting","title":"X","start":"tomorrow"}"""))
    }

    @Test
    fun `a meeting under way is now`() {
        val iso = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        val start = java.time.OffsetDateTime.now().minusMinutes(5)
        val end = start.plusMinutes(30)
        val message = PcProtocol.parse(
            """{"t":"meeting","title":"Sync","start":"${iso.format(start)}","end":"${iso.format(end)}"}""",
        ) as PcMessage.Meeting
        assertTrue(message.event!!.now)
    }

    @Test
    fun `parses results`() {
        assertEquals(PcMessage.Result(7, false, "Teams API not allowed"),
            PcProtocol.parse("""{"t":"result","id":7,"ok":false,"error":"Teams API not allowed"}"""))
        assertEquals(PcMessage.Result(8, true, null), PcProtocol.parse("""{"t":"result","id":8,"ok":true}"""))
    }

    @Test
    fun `ignores unknown messages and bad lines`() {
        assertNull(PcProtocol.parse("""{"t":"weather","temp":20}"""))
        assertNull(PcProtocol.parse("not json"))
        assertNull(PcProtocol.parse(""))
    }

    @Test
    fun `applies messages to the status`() {
        var status = PcStatus(host = "tablet's name for it")
        status = PcProtocol.apply(status, PcMessage.Hello("LAPTOP", teams = true, outlook = true))
        status = PcProtocol.apply(status, PcMessage.App("outlook"))
        status = PcProtocol.apply(status, PcMessage.Teams(TeamsState(muted = true)))
        assertEquals(PcStatus(host = "LAPTOP", hasTeams = true, hasOutlook = true, app = "outlook", teams = TeamsState(muted = true)), status)
        assertEquals(status, PcProtocol.apply(status, PcMessage.Result(1, true, null)))
    }

    @Test
    fun `encodes commands as one JSON line each`() {
        val mute = PcProtocol.teams("mute", null, 3)
        assertTrue(mute.endsWith("\n"))
        val json = JSONObject(mute.trim())
        assertEquals("teams", json.getString("t"))
        assertEquals("mute", json.getString("do"))
        assertEquals(3, json.getInt("id"))
        assertFalse(json.has("param"))
        assertEquals("like", JSONObject(PcProtocol.teams("react", "like", 4).trim()).getString("param"))
        val run = JSONObject(PcProtocol.run("lock", 5).trim())
        assertEquals("run", run.getString("t"))
        assertEquals("lock", run.getString("name"))
        assertNotNull(JSONObject(PcProtocol.hello("1.7.0").trim()).getString("app"))
        assertEquals(1, JSONObject(PcProtocol.hello("1.7.0").trim()).getInt("v"))
    }
}
