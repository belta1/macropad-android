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
        assertEquals("win+alt+k", upgrade["ctrl+shift+m"])
        assertEquals("win+8\ndelay:300\nctrl+shift+o\ndelay:150\nalt+tab", upgrade["ctrl+shift+o"])
        assertEquals(null, upgrade["ctrl+space"]) // push to talk stays a held key
    }
}
