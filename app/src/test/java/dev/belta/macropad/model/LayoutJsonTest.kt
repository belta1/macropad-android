package dev.belta.macropad.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutJsonTest {

    @Test
    fun `round trips the default layout`() {
        val original = DefaultLayout.layout()
        val copy = LayoutJson.fromJson(JSONObject(LayoutJson.toJson(original).toString()))
        assertEquals(LayoutJson.toJson(original).toString(), LayoutJson.toJson(copy).toString())
        assertEquals(original.pages.map { it.name }, copy.pages.map { it.name })
        assertEquals(PageKind.TRACKPAD, copy.pages.first { it.name == DefaultLayout.TRACKPAD_PAGE }.kind)
        assertTrue(copy.pages.first { it.name == "Teams" }.buttons[0]!!.toggle)
    }

    @Test
    fun `keeps empty slots and missing icons`() {
        val layout = DefaultLayout.layout()
        val page = Page("Mine", mutableListOf(null, MacroButton("A", "a", 1, null)))
        layout.pages += page
        val copy = LayoutJson.fromJson(LayoutJson.toJson(layout))
        val mine = copy.pages.last()
        assertNull(mine.buttons[0])
        assertNull(mine.buttons[1]!!.icon)
    }

    @Test
    fun `page apps round trip and default by name`() {
        val layout = DefaultLayout.layout()
        layout.pages += Page("Mine", mutableListOf(), apps = "Notepad.exe, code")
        layout.pages += Page("None", mutableListOf(), apps = "")
        val copy = LayoutJson.fromJson(LayoutJson.toJson(layout))
        assertEquals(listOf("notepad", "code"), copy.pages.first { it.name == "Mine" }.appNames)
        assertEquals("", copy.pages.first { it.name == "None" }.apps)
        assertEquals(emptyList<String>(), copy.pages.first { it.name == "None" }.appNames)
        // No "apps" stored: the default for the page's name.
        val teams = copy.pages.first { it.name == DefaultLayout.TEAMS_PAGE }
        assertNull(teams.apps)
        assertEquals(listOf("ms-teams", "teams"), teams.appNames)
    }

    @Test
    fun `dock volume keys become a slider`() {
        val dock = Page("Media", mutableListOf(
            MacroButton("Play", "media:playpause", 1, null),
            MacroButton("Vol -", "media:voldown", 1, null),
            MacroButton("Vol +", "media:volup", 1, null),
            MacroButton("Task view", "win+tab", 1, null),
        ))
        assertTrue(DefaultLayout.upgradeVolumeDock(dock))
        assertEquals(listOf("media:playpause", "slide:volume", null, "win+tab"), dock.buttons.map { it?.action })
        assertEquals(false, DefaultLayout.upgradeVolumeDock(dock)) // nothing left to change
        // New docks already have the slider (its icon mutes), in the same 8 slots.
        val fresh = DefaultLayout.dock()
        assertEquals(Layout.DOCK_SIZE, fresh.buttons.size)
        assertEquals(false, DefaultLayout.upgradeVolumeDock(fresh))
        assertTrue(fresh.buttons.any { it?.action == "slide:volume" } && fresh.buttons.none { it?.action == "media:mute" })
    }

    @Test
    fun `the dock's 1_5 mute key goes once the slider mutes`() {
        val slider = MacroButton("Volume", "slide:volume", 1, "volume_up")
        val mute = MacroButton("Mute", "media:mute", Palette.colors[7], "volume_off")
        val dock = Page("Media", mutableListOf(slider, mute))
        assertTrue(DefaultLayout.dropDockMute(dock))
        assertEquals(listOf("slide:volume", null), dock.buttons.map { it?.action })
        assertEquals(false, DefaultLayout.dropDockMute(dock))
        // A Mute key the user changed stays, and so does any Mute without a volume slider.
        val edited = Page("Media", mutableListOf(slider, mute.copy(label = "Silence")))
        assertEquals(false, DefaultLayout.dropDockMute(edited))
        val noSlider = Page("Media", mutableListOf<MacroButton?>(mute))
        assertEquals(false, DefaultLayout.dropDockMute(noSlider))
    }

    @Test
    fun `reads files without optional fields`() {
        val json = JSONObject("""{"rows":2,"cols":3,"pages":[{"name":"P","buttons":[{"label":"L","action":"f1","color":5}]}]}""")
        val layout = LayoutJson.fromJson(json)
        assertEquals(6, layout.cells)
        assertEquals(PageKind.KEYS, layout.pages[0].kind)
        assertEquals(DefaultLayout.dock().buttons.size, layout.dock.buttons.size)
    }

    @Test
    fun `default dock has every dock slot, desktop keys last, then one free slot`() {
        val dock = DefaultLayout.dock().buttons
        assertEquals(Layout.DOCK_SIZE, dock.size)
        assertNull(dock.last()) // the slider's icon took over Mute; the slot is free for a key
        assertEquals(DefaultLayout.DESKTOP_DOCK.map { it.action },
            dock.dropLast(1).takeLast(DefaultLayout.DESKTOP_DOCK.size).map { it!!.action })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects other files`() {
        LayoutJson.fromJson(JSONObject("""{"format":"something-else","rows":1,"cols":1,"pages":[]}"""))
    }
}
