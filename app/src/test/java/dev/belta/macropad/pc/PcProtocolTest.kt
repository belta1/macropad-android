package dev.belta.macropad.pc

import dev.belta.macropad.model.Step
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
    fun `encodes recorded clicks`() {
        val click = JSONObject(PcProtocol.click("click", "outlook",
            Step.PcPoint(412, 88, "double", element = "Enviar"), 3))
        assertEquals("click", click.getString("t"))
        assertEquals("double", click.getString("do"))
        assertEquals("outlook", click.getString("app"))
        assertEquals(412, click.getInt("x"))
        assertEquals("Enviar", click.getString("name"))
        assertEquals(3, click.getInt("id"))
        val drag = JSONObject(PcProtocol.click("drag", "excel", Step.PcPoint(1, 2, x2 = 3, y2 = 4), 4))
        assertEquals("drag", drag.getString("do"))
        assertEquals(4, drag.getInt("y2"))
        assertFalse(drag.has("name"))
        val scroll = JSONObject(PcProtocol.click("scroll", "screen", Step.PcPoint(1, 2, amount = -3), 5))
        assertEquals(-3, scroll.getInt("amount"))
    }

    @Test
    fun `PowerPoint - the show, its notes, pictures and commands`() {
        val message = PcProtocol.parse("""{"t":"slides","file":"Clase 3.pptx","show":true,"state":"black","count":24,"v":2,"slide":7,""" +
            """"title":"Arrays","notes":"Primero\rDespués\u000Bmás\r"}""") as PcMessage.Slides
        assertEquals(PcSlides(true, "Clase 3.pptx", 7, 24, "Arrays", "Primero\nDespués\nmás", "black", 2), message.slides)
        assertEquals(message.slides, PcProtocol.apply(PcStatus(), message).slides)
        // PowerPoint closed.
        assertNull((PcProtocol.parse("""{"t":"slides"}""") as PcMessage.Slides).slides)
        // Editing, with no slide selected.
        val editing = (PcProtocol.parse("""{"t":"slides","file":"a.pptx","show":false,"count":3,"v":1}""") as PcMessage.Slides).slides!!
        assertEquals(0, editing.slide)
        assertEquals("", editing.notes)
        val image = PcProtocol.parse("""{"t":"slide-image","file":"a.pptx","n":3,"v":1,"jpg":"AQID"}""") as PcMessage.SlideImage
        assertEquals(3, image.slide)
        assertEquals(listOf<Byte>(1, 2, 3), image.jpeg.toList())

        val goto = JSONObject(PcProtocol.slides("goto", 9, slide = 4))
        assertEquals("goto", goto.getString("do"))
        assertEquals(4, goto.getInt("n"))
        assertEquals(9, goto.getInt("id"))
        val point = JSONObject(PcProtocol.slides("point", null, x = 1.4f, y = 0.25f))
        assertFalse(point.has("id"))
        assertEquals(1.0, point.getDouble("x"), 0.0)
        assertEquals(0.25, point.getDouble("y"), 0.0)
    }

    @Test
    fun `an HTML deck in the browser`() {
        val deck = (PcProtocol.parse("""{"t":"slides","kind":"html","app":"Chrome.exe","file":"202610_workshop_bis.html",""" +
            """"show":true,"state":"running","count":19,"v":3,"slide":2,"title":"El workshop en una lámina","notes":"Uno\nDos"}""")
            as PcMessage.Slides).slides!!
        assertEquals(PcSlides(true, "202610_workshop_bis.html", 2, 19, "El workshop en una lámina", "Uno\nDos", "running", 3,
            html = true, app = "chrome"), deck)
        // Scripts before HTML decks: PowerPoint.
        val ppt = (PcProtocol.parse("""{"t":"slides","file":"a.pptx","show":true,"count":3,"slide":1}""") as PcMessage.Slides).slides!!
        assertFalse(ppt.html)
        assertEquals("powerpnt", ppt.app)
    }

    @Test
    fun `a file to type, kept as written`() {
        assertEquals(JSONObject("""{"t":"typefile","id":4}""").toString(), JSONObject(PcProtocol.typeFile(4)).toString())
        val file = PcProtocol.parse("""{"t":"file","id":4,"name":"main.py","text":"def f():\r\n    return {1: 2}\n\n"}""") as PcMessage.FileText
        assertEquals(PcMessage.FileText(4, "main.py", "def f():\n    return {1: 2}\n\n"), file)
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
    fun `clipboard texts keep one Enter per line, and the history keeps the latest first`() {
        assertEquals(PcMessage.Clip("a\nb"), PcProtocol.parse("""{"t":"clip","text":"a\r\nb"}"""))
        assertNull(PcProtocol.parse("""{"t":"clip"}"""))
        var status = PcStatus()
        for (text in listOf("one", "two", "one")) status = PcProtocol.apply(status, PcMessage.Clip(text))
        assertEquals(listOf("one", "two"), status.clips) // copied again: back to the front, once
        for (i in 1..30) status = PcProtocol.apply(status, PcMessage.Clip("$i"))
        assertEquals(PcProtocol.MAX_CLIPS, status.clips.size)
        assertEquals("30", status.clips.first())
    }

    @Test
    fun `parses windows, icons, volume, media and the shell`() {
        val windows = PcProtocol.parse(
            """{"t":"windows","front":42,"list":[{"id":42,"exe":"Code","app":"Visual Studio Code","title":"main.py"},{"id":7,"title":"no exe"}]}""",
        ) as PcMessage.Windows
        assertEquals(listOf(PcWindow(42, "code", "Visual Studio Code", "main.py")), windows.windows)
        assertEquals(42L, windows.front)

        val icon = PcProtocol.parse("""{"t":"icon","exe":"Code","png":"iVBORw0KGgo="}""") as PcMessage.Icon
        assertEquals("code", icon.exe)
        assertEquals(0x89.toByte(), icon.png[0])
        assertNull(PcProtocol.parse("""{"t":"icon","exe":"code","png":"not base64!"}"""))

        assertEquals(PcMessage.Volume(PcVolume(35, false, listOf(PcAudioApp("spotify", "Spotify", 80, true)))),
            PcProtocol.parse("""{"t":"volume","level":35,"muted":false,"apps":[{"exe":"spotify","app":"Spotify","level":80,"muted":true}]}"""))

        assertEquals(PcMessage.Media(PcMedia("Song", null, playing = true)), PcProtocol.parse("""{"t":"media","title":"Song","playing":true}"""))
        assertEquals(PcMessage.Media(null), PcProtocol.parse("""{"t":"media"}"""))
        assertTrue(PcProtocol.apply(PcStatus(), PcMessage.Media(null)).hasMedia)

        assertEquals(PcMessage.Shell(PcShell("E:\\shop", "E:\\shop\\.venv", "3.12.1")),
            PcProtocol.parse("""{"t":"shell","cwd":"E:\\shop","venv":"E:\\shop\\.venv","python":"3.12.1"}"""))
        assertEquals(PcMessage.Shell(null), PcProtocol.parse("""{"t":"shell"}"""))
    }

    @Test
    fun `parses monitors and pictures of the mirrored screen`() {
        assertEquals(PcMessage.Monitors(listOf(PcMonitor(1, 1920, 1080, true), PcMonitor(2, 2560, 1440, false))),
            PcProtocol.parse("""{"t":"monitors","list":[{"n":1,"w":1920,"h":1080,"primary":true},{"n":2,"w":2560,"h":1440}]}"""))
        val part = PcProtocol.parse("""{"t":"frame","seq":4,"w":1600,"h":900,"tiles":[[0,64,"/9j/"],[128,64,"/9j/"]]}""") as PcMessage.Frame
        assertEquals(4, part.seq)
        assertEquals(listOf(0 to 64, 128 to 64), part.tiles.map { it.x to it.y })
        assertEquals(0xFF.toByte(), part.tiles[0].jpeg[0])
        assertFalse(part.end)
        val last = PcProtocol.parse("""{"t":"frame","seq":4,"w":1600,"h":900,"tiles":[],"end":true,"cx":0.25,"cy":-1}""") as PcMessage.Frame
        assertTrue(last.end)
        assertEquals(0.25f, last.cursorX)
        assertEquals(-1f, last.cursorY)
    }

    @Test
    fun `parses lock, keyboard, badges and a meeting's link`() {
        assertEquals(PcMessage.Lock(true), PcProtocol.parse("""{"t":"lock","locked":true}"""))
        assertEquals(PcMessage.Keyboard("0000080A"), PcProtocol.parse("""{"t":"keyboard","klid":"0000080a"}"""))
        assertEquals(PcMessage.Badges(mapOf("outlook" to 3)), PcProtocol.parse("""{"t":"badges","counts":{"Outlook":3}}"""))
        assertEquals(PcMessage.Badges(emptyMap()), PcProtocol.parse("""{"t":"badges","counts":{}}"""))
        val meeting = PcProtocol.parse("""{"t":"meeting","title":"Standup","start":"2026-10-29T10:00:00-03:00","join":true}""") as PcMessage.Meeting
        assertTrue(meeting.event!!.hasJoin)
        var status = PcProtocol.apply(PcStatus(), PcMessage.Lock(false))
        status = PcProtocol.apply(status, PcMessage.Keyboard("00000409"))
        assertEquals(PcStatus(locked = false, keyboard = "00000409"), status)
        assertEquals("join", JSONObject(PcProtocol.join(9).trim()).getString("t"))
        // Pictures carry the request they answer.
        assertEquals(7, (PcProtocol.parse("""{"t":"frame","seq":1,"view":7,"w":10,"h":10,"tiles":[],"end":true}""") as PcMessage.Frame).view)
    }

    @Test
    fun `new mail, the keyboard recording and its steps`() {
        assertEquals(PcMessage.Mail("Ana Pérez", "Presupuesto", 2), PcProtocol.parse("""{"t":"mail","from":"Ana Pérez","subject":"Presupuesto","count":2}"""))
        val status = PcProtocol.apply(PcStatus(), PcMessage.Mail("Ana", "Hi", 1))
        assertEquals("Ana", status.mail!!.from)
        assertEquals(true, (PcProtocol.parse("""{"t":"hello","host":"PC","capture":true}""") as PcMessage.Hello).capture)
        assertFalse(PcProtocol.apply(PcStatus(), PcProtocol.parse("""{"t":"hello","host":"PC"}""")!!).canCapture)
        assertEquals(PcMessage.Recorded("text:hola\\n"), PcProtocol.parse("""{"t":"recorded","step":"text:hola\\n"}"""))
        assertTrue(JSONObject(PcProtocol.capture(on = true).trim()).getBoolean("on"))
        val pause = JSONObject(PcProtocol.capture(pause = true).trim())
        assertTrue(pause.getBoolean("pause") && !pause.has("on"))
    }

    @Test
    fun `encodes screen requests`() {
        val zoomed = JSONObject(PcProtocol.screen(1, 1600, 1000, ScreenViewport(0.25f, 0.5f, 0.5f), 3).trim())
        assertEquals(3, zoomed.getInt("view"))
        assertEquals(0.25, zoomed.getDouble("vx"), 1e-6)
        assertEquals(0.5, zoomed.getDouble("vw"), 1e-6)
        assertFalse(JSONObject(PcProtocol.screen(1, 1600, 1000).trim()).has("vw")) // all of it
        assertEquals(1, JSONObject(PcProtocol.screen(1, 1600, 1000).trim()).getInt("monitor"))
        assertTrue(JSONObject(PcProtocol.screenStop().trim()).getBoolean("stop"))
        assertEquals(4, JSONObject(PcProtocol.screenAck(4).trim()).getInt("seq"))
        val tap = JSONObject(PcProtocol.pointer(2, 0.5f, 1.4f, "tap").trim())
        assertEquals("tap", tap.getString("do"))
        assertEquals(1.0, tap.getDouble("y"), 0.0) // kept on the monitor
        assertFalse(tap.has("amount"))
        assertEquals(-2, JSONObject(PcProtocol.pointer(1, 0.5f, 0.5f, "scroll", -2).trim()).getInt("amount"))
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
        val focus = JSONObject(PcProtocol.focus(1234567890123L, 6).trim())
        assertEquals("focus", focus.getString("t"))
        assertEquals(1234567890123L, focus.getLong("window"))
        val volume = JSONObject(PcProtocol.volume("spotify", 140, null, 7).trim())
        assertEquals("spotify", volume.getString("app"))
        assertEquals(100, volume.getInt("level"))
        assertFalse(volume.has("mute"))
        val speakers = JSONObject(PcProtocol.volume(null, null, true, 8).trim())
        assertFalse(speakers.has("app") || speakers.has("level"))
        assertTrue(speakers.getBoolean("mute"))
        assertNotNull(JSONObject(PcProtocol.hello("1.7.0").trim()).getString("app"))
        assertEquals(1, JSONObject(PcProtocol.hello("1.7.0").trim()).getInt("v"))
    }
}
