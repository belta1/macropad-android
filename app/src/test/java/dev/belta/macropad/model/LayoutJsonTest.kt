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
    fun `reads files without optional fields`() {
        val json = JSONObject("""{"rows":2,"cols":3,"pages":[{"name":"P","buttons":[{"label":"L","action":"f1","color":5}]}]}""")
        val layout = LayoutJson.fromJson(json)
        assertEquals(6, layout.cells)
        assertEquals(PageKind.KEYS, layout.pages[0].kind)
        assertEquals(DefaultLayout.dock().buttons.size, layout.dock.buttons.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects other files`() {
        LayoutJson.fromJson(JSONObject("""{"format":"something-else","rows":1,"cols":1,"pages":[]}"""))
    }
}
