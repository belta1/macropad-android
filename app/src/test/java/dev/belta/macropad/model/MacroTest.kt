package dev.belta.macropad.model

import dev.belta.macropad.hid.HidKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroTest {

    @Test
    fun `parses a combo with modifiers`() {
        assertEquals(listOf(Step.Combo(HidKeys.CTRL or HidKeys.SHIFT, listOf(0x17))), Macro.parse("ctrl+shift+t"))
    }

    @Test
    fun `parses named keys and is case insensitive`() {
        assertEquals(listOf(Step.Combo(HidKeys.GUI or HidKeys.CTRL, listOf(0x50))), Macro.parse("Win+Ctrl+LEFT"))
        assertEquals(listOf(Step.Combo(0, listOf(0x68))), Macro.parse("f13"))
    }

    @Test
    fun `single characters in combos use US key positions`() {
        assertEquals(listOf(Step.Combo(HidKeys.CTRL, listOf(0x38))), Macro.parse("ctrl+/"))
        assertEquals(listOf(Step.Combo(HidKeys.CTRL or HidKeys.SHIFT, listOf(0x2E))), Macro.parse("ctrl+plus"))
    }

    @Test
    fun `parses every step kind`() {
        val steps = Macro.parse("""
            # comment
            win+r
            delay:400
            text:notepad\n
            media:volup
            click:right
            scroll:-3
        """.trimIndent())
        assertEquals(
            listOf(
                Step.Combo(HidKeys.GUI, listOf(0x15)),
                Step.Delay(400),
                Step.Text("notepad\n"),
                Step.Media(0xE9),
                Step.Click(0x02),
                Step.Scroll(-3),
            ),
            steps,
        )
    }

    @Test
    fun `text keeps its case and spaces`() {
        assertEquals(listOf(Step.Text("Hello World")), Macro.parse("text:Hello World"))
    }

    @Test
    fun `clamps delays and scrolls`() {
        assertEquals(listOf(Step.Delay(10_000)), Macro.parse("delay:99999"))
        assertEquals(listOf(Step.Scroll(50)), Macro.parse("scroll:500"))
    }

    @Test
    fun `rejects bad input`() {
        for (bad in listOf("", "ctrl+nosuchkey", "media:nope", "delay:soon", "click:back", "ctrl++", "a+b+c+d+e+f+g")) {
            assertThrows(bad)
        }
    }

    @Test
    fun `single combos media keys and clicks are holdable`() {
        assertTrue(Macro.isHoldable(Macro.parse("ctrl+space")))
        assertTrue(Macro.isHoldable(Macro.parse("media:mute")))
        assertTrue(Macro.isHoldable(Macro.parse("click:left")))
        assertFalse(Macro.isHoldable(Macro.parse("text:hi")))
        assertFalse(Macro.isHoldable(Macro.parse("ctrl+k\ns")))
    }

    @Test
    fun `describes actions for key hints`() {
        assertEquals("Ctrl+Shift+P", Macro.describe("ctrl+shift+p"))
        assertEquals("Win+Ctrl+→", Macro.describe("win+ctrl+right"))
        assertEquals("Ctrl+Shift+Tab", Macro.describe("ctrl+shift+tab"))
        assertEquals("F12", Macro.describe("f12"))
        assertEquals("Media key", Macro.describe("media:playpause"))
        assertEquals("Opens chrome", Macro.describe("win+r\ndelay:400\ntext:chrome\\n"))
        assertEquals("Macro · 2 steps", Macro.describe("ctrl+k\ns"))
        assertEquals("Right click", Macro.describe("click:right"))
    }

    @Test
    fun `default layout actions all parse`() {
        val layout = DefaultLayout.layout()
        val all = layout.pages.flatMap { it.buttons } + layout.dock.buttons + layout.hardware.buttons
        for (button in all.filterNotNull()) {
            Macro.parse(button.action)
            button.longAction?.let { Macro.parse(it) }
        }
    }

    @Test
    fun `tablet dim step runs on the tablet and shows in the hint`() {
        assertEquals(listOf(Step.Combo(HidKeys.GUI, listOf(HidKeys.keys.getValue("l"))), Step.Tablet("dim")),
            Macro.parse("win+l\ntablet:dim"))
        assertEquals("Win+L + dim", Macro.describe("win+l\ntablet:dim"))
        assertEquals("Dims tablet", Macro.describe("tablet:dim"))
        assertThrows("tablet:explode")
    }

    @Test
    fun `every default page has a tab icon and the desktop row upgrade covers the old row`() {
        for (page in DefaultLayout.layout().pages) {
            val icon = DefaultLayout.iconFor(page.name)
            assertTrue("${page.name} has no icon", icon in DefaultLayout.APP_ICONS)
        }
        assertEquals("win+shift+s", DefaultLayout.DESKTOP_ROW_UPGRADE["win+ctrl+left"]?.action)
        assertEquals(5, DefaultLayout.DESKTOP_ROW_UPGRADE.size)
    }

    @Test
    fun `slider presets and custom sliders parse`() {
        assertEquals(listOf(Step.Slide(Step.Media(0xE9), Step.Media(0xEA), "volume", Step.Media(0xE2))), Macro.parse("slide:Volume"))
        assertEquals(Step.Slide(Step.Scroll(1), Step.Scroll(-1), "scroll"), Macro.slider("slide:scroll"))
        for (name in Macro.SLIDE_PRESETS.keys) assertEquals(name, Macro.slider("slide:$name")?.preset)
        val ctrlTab = Step.Combo(HidKeys.CTRL, listOf(HidKeys.keys.getValue("tab")))
        val ctrlShiftTab = Step.Combo(HidKeys.CTRL or HidKeys.SHIFT, listOf(HidKeys.keys.getValue("tab")))
        assertEquals(Step.Slide(ctrlTab, ctrlShiftTab, null), Macro.slider("slide:ctrl+tab|ctrl+shift+tab"))
        assertEquals(Step.Slide(ctrlTab, ctrlShiftTab, null), Macro.slider("slide: ctrl+tab | ctrl+shift+tab "))
        assertEquals(null, Macro.slider("media:volup"))
    }

    @Test
    fun `sliders take an optional tap step`() {
        assertEquals(Step.Media(0xE2), Macro.slider("slide:volume")?.tap) // tap the icon = Mute
        assertEquals(Step.Combo(HidKeys.CTRL, listOf(HidKeys.keys.getValue("0"))), Macro.slider("slide:zoom")?.tap)
        assertEquals(null, Macro.slider("slide:scroll")?.tap)
        assertEquals(null, Macro.slider("slide:right | left")?.tap)
        assertEquals(Step.Combo(0, listOf(HidKeys.keys.getValue("space"))), Macro.slider("slide:right | left | space")?.tap)
        assertEquals(Step.Click(HidKeys.mouseButtons.getValue("middle")), Macro.slider("slide:scroll:1 | scroll:-1 | click:middle")?.tap)
        assertEquals("Slider · → / ←", Macro.describe("slide:right | left | space"))
    }

    @Test
    fun `rejects bad sliders`() {
        for (bad in listOf(
            "slide:loudness", "slide:", "slide:right|", "slide:|left", "slide:a|b|c|d", "slide:a|b|",
            "slide:a|b|text:hi", "slide:a|b|delay:100", "slide:right|click:left",
            "slide:text:hi|left", "slide:page:next|left", "slide:slide:volume|left", "slide:volume\nctrl+c",
        )) assertThrows(bad)
    }

    @Test
    fun `describes sliders`() {
        assertEquals("Slider · Volume", Macro.describe("slide:volume"))
        assertEquals("Slider · → / ←", Macro.describe("slide:right | left"))
    }

    private fun assertThrows(action: String) {
        try {
            Macro.parse(action)
        } catch (e: MacroException) {
            return
        }
        throw AssertionError("expected '$action' to be rejected")
    }

    @Test
    fun `focus-app macros are described by their shortcut`() {
        assertEquals("Ctrl+Shift+O ↩", Macro.describe("win+8\ndelay:300\nctrl+shift+o\ndelay:150\nalt+tab"))
        assertEquals("Win+8 → Ctrl+2", Macro.describe("win+8\ndelay:300\nctrl+2"))
        assertEquals("Macro · 3 steps", Macro.describe("win+8\nctrl+2\nctrl+3"))
    }

    @Test
    fun `old Teams keys upgrade to focus-safe ones`() {
        val upgrade = DefaultLayout.TEAMS_FOCUS_UPGRADE
        assertEquals("pc:teams mute", upgrade["ctrl+shift+m"])
        assertEquals("win+8\ndelay:300\nctrl+shift+e", upgrade["ctrl+shift+e"])
        assertEquals(null, upgrade["ctrl+space"]) // push to talk stays a held key
    }

    @Test
    fun `1_6 Teams keys upgrade to PC link commands`() {
        val upgrade = DefaultLayout.TEAMS_API_UPGRADE
        assertEquals("pc:teams mute", upgrade["win+alt+k"])
        assertEquals("pc:teams camera", upgrade["win+8\ndelay:300\nctrl+shift+o\ndelay:150\nalt+tab"])
        assertEquals("pc:teams leave", upgrade["win+8\ndelay:300\nctrl+shift+h\ndelay:150\nalt+tab"])
        assertEquals(null, upgrade["win+8\ndelay:300\nctrl+shift+e"]) // share screen has no command
    }

    @Test
    fun `Outlook commands - parsed, described, a fallback each, and 1_7's keys upgrade to them`() {
        assertEquals(listOf(Step.Pc("outlook", "reply-all")), Macro.parse("pc:Outlook Reply-All"))
        assertEquals("Outlook · Mark read", Macro.describe("pc:outlook mark-read"))
        assertThrows("pc:outlook")
        assertThrows("pc:outlook dance")
        assertThrows("pc:outlook reply now")
        assertEquals(Macro.OUTLOOK_ACTIONS.keys, Macro.OUTLOOK_FALLBACK.keys)
        Macro.OUTLOOK_FALLBACK.values.forEach { Macro.parse(it) }
        val upgrade = DefaultLayout.OUTLOOK_API_UPGRADE
        assertEquals("pc:outlook open", upgrade["win+2"])
        assertEquals("pc:outlook new-mail", upgrade["win+2\ndelay:300\nctrl+shift+m"])
        assertEquals("pc:outlook reply", upgrade["ctrl+r"])
        assertEquals("pc:outlook delete", upgrade["delete"])
        assertEquals("pc:outlook open\ndelay:500\nf3", upgrade["win+2\ndelay:300\nctrl+e"])
        // The new Outlook page has only commands (and Search's F3).
        val outlook = DefaultLayout.layout().pages.first { it.name == DefaultLayout.OUTLOOK_PAGE }
        assertTrue(outlook.buttons.all { it!!.action.startsWith("pc:outlook") })
    }

    @Test
    fun `pc focus and pc wait hold the macro, the other PC steps don't`() {
        assertEquals(listOf(Step.Pc("focus", "excel")), Macro.parse("pc:focus Excel.exe"))
        assertEquals(listOf(Step.Pc("wait", "save as")), Macro.parse("pc:wait Save As"))
        assertThrows("pc:focus")
        assertThrows("pc:focus two words")
        assertThrows("pc:wait")
        assertTrue(Step.Pc("focus", "excel").waits && Step.Pc("wait", "x").waits)
        assertFalse(Step.Pc("outlook", "reply").waits || Step.Pc("run", "lock").waits)
        assertEquals("Brings excel to the front", Macro.describe("pc:focus excel"))
        assertEquals("Waits for save as", Macro.describe("pc:wait save as"))
        assertEquals("Macro · 3 steps", Macro.describe("pc:focus notepad\ntext:hola\nenter"))
    }

    @Test
    fun `recorded clicks, drags and scrolls hold the macro and keep the button's name as written`() {
        assertEquals(listOf(Step.Pc("click", "outlook", point = Step.PcPoint(412, 88, element = "Enviar ahora"))),
            Macro.parse("pc:click Outlook.exe 412,88 | Enviar ahora"))
        assertEquals(Step.PcPoint(5, -3, "right"), (Macro.parse("pc:click screen 5,-3 right").single() as Step.Pc).point)
        assertEquals(Step.PcPoint(1, 2, "double"), (Macro.parse("pc:click excel 1,2 double").single() as Step.Pc).point)
        assertEquals(Step.PcPoint(100, 200, x2 = 300, y2 = 400), (Macro.parse("pc:drag excel 100,200 300,400").single() as Step.Pc).point)
        assertEquals(Step.PcPoint(10, 20, amount = -3), (Macro.parse("pc:scroll excel 10,20 -3").single() as Step.Pc).point)
        assertTrue((Macro.parse("pc:click excel 1,2").single() as Step.Pc).waits)
        assertThrows("pc:click excel")
        assertThrows("pc:click excel 1;2")
        assertThrows("pc:click excel 1,2 middle")
        assertThrows("pc:drag excel 1,2 | Name")
        assertThrows("pc:drag excel 1,2")
        assertThrows("pc:scroll excel 1,2 0")
        assertEquals("Clicks “Enviar” in outlook", Macro.describe("pc:click outlook 412,88 | Enviar"))
        assertEquals("Right-clicks on the screen", Macro.describe("pc:click screen 5,5 right"))
        assertEquals("Drags in excel", Macro.describe("pc:drag excel 1,2 3,4"))
        assertEquals("Scrolls down in excel", Macro.describe("pc:scroll excel 1,2 -2"))
        // Other PC steps still read lowercase.
        assertEquals(listOf(Step.Pc("run", "lock it")), Macro.parse("pc:run Lock It"))
    }

    @Test
    fun `the join key - pc join, in the sidebar's free slot once`() {
        assertEquals(listOf(Step.Pc("join", "next")), Macro.parse("pc:join"))
        assertThrows("pc:join https://example.com")
        assertEquals("Joins the next meeting", Macro.describe("pc:join"))
        val sidebar = Page("Sidebar", mutableListOf(MacroButton("Away", "win+l", 1, null), null, MacroButton("X", "f1", 1, null)))
        assertTrue(DefaultLayout.addJoinMeeting(sidebar))
        assertEquals("pc:join", sidebar.buttons[1]!!.action)
        assertFalse(DefaultLayout.addJoinMeeting(sidebar)) // already there
        assertEquals("pc:join", DefaultLayout.sidebar().buttons.last()!!.action)
        assertEquals(dev.belta.macropad.hid.KeyboardLayout.LATAM, dev.belta.macropad.hid.keyboardLayoutForWindowsId("0000080a"))
        assertEquals(null, dev.belta.macropad.hid.keyboardLayoutForWindowsId("00000407")) // German: not one this app types
    }

    @Test
    fun `parses PC link steps`() {
        assertEquals(listOf(Step.Pc("teams", "mute")), Macro.parse("pc:teams mute"))
        assertEquals(listOf(Step.Pc("teams", "react", "like")), Macro.parse("pc:Teams  React LIKE"))
        assertEquals(listOf(Step.Pc("run", "lock")), Macro.parse("pc:run lock"))
        assertEquals(listOf(Step.Pc("run", "open notes")), Macro.parse("pc:run open notes"))
        assertFalse(Macro.parse("pc:teams mute").single().isNetwork)
    }

    @Test
    fun `rejects bad PC link steps`() {
        assertThrows("pc:teams")
        assertThrows("pc:teams dance")
        assertThrows("pc:teams react")
        assertThrows("pc:teams react boo")
        assertThrows("pc:teams mute now")
        assertThrows("pc:run")
        assertThrows("pc:lock")
    }

    @Test
    fun `describes PC link steps`() {
        assertEquals("Teams · Mute", Macro.describe("pc:teams mute"))
        assertEquals("Teams · React like", Macro.describe("pc:teams react like"))
        assertEquals("PC · lock", Macro.describe("pc:run lock"))
        assertEquals("PC link", Macro.describe("pc:nonsense"))
    }

    @Test
    fun `Teams fallbacks are valid macros`() {
        for ((action, shortcut) in Macro.TEAMS_FALLBACK) {
            assertTrue(action, Macro.parse(shortcut).isNotEmpty())
        }
        assertEquals(null, Macro.TEAMS_FALLBACK["react"])
    }

    @Test
    fun `parses network steps`() {
        assertEquals(listOf(Step.Http("GET", "https://example.com/a?b={secret:k}", null)),
            Macro.parse("http:https://example.com/a?b={secret:k}"))
        assertEquals(listOf(Step.Http("POST", "https://example.com/hook", """{"on":true}""")),
            Macro.parse("""http:https://example.com/hook | {"on":true}"""))
        assertEquals(listOf(Step.Http("PUT", "http://10.0.0.2/x", null)), Macro.parse("http:put http://10.0.0.2/x"))
        assertEquals(listOf(Step.Wol("AA:BB:CC:0D:0E:0F")), Macro.parse("wol:aa-bb-cc-0d-0e-0f"))
        assertEquals(listOf(Step.Ha("light", "toggle", "light.desk")), Macro.parse("ha:light.toggle light.Desk"))
        assertEquals(listOf(Step.Ha("script", "turn_on", null)), Macro.parse("ha:script.turn_on"))
        assertTrue(Macro.parse("ha:light.toggle light.desk").single().isNetwork)
        assertFalse(Macro.parse("ctrl+c").single().isNetwork)
    }

    @Test
    fun `rejects bad network steps`() {
        assertThrows("http:example.com")
        assertThrows("wol:AA:BB:CC")
        assertThrows("ha:toggle")
        assertThrows("ha:light.toggle light.a light.b")
    }

    @Test
    fun `describes network steps`() {
        assertEquals("Home Assistant", Macro.describe("ha:light.toggle light.desk"))
        assertEquals("Wake on LAN", Macro.describe("wol:AA:BB:CC:DD:EE:FF"))
        assertEquals("Web request", Macro.describe("http:https://example.com"))
    }
}
