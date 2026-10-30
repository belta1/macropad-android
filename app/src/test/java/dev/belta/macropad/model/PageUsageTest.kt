package dev.belta.macropad.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageUsageTest {
    private val day = PageUsage.DAY_MS.toLong()

    @Test
    fun `lately counts most - use halves every two weeks`() {
        val usage = PageUsage()
        repeat(40) { usage.use("Excel", 0) }
        repeat(30) { usage.use("Citrix", 28 * day) }
        val now = 28 * day
        assertEquals(10.0, usage.score("Excel", now), 0.001) // 40 → 20 → 10 in four weeks
        assertEquals(listOf("Citrix", "Excel", "Word", "Home"), usage.sorted(listOf("Excel", "Word", "Citrix", "Home"), now))
        assertEquals(40.0, usage.total(now), 0.001)
    }

    @Test
    fun `survives saving, and a rename carries the use`() {
        val usage = PageUsage()
        usage.use("Teams", 5)
        usage.rename("Teams", "Reuniones")
        val copy = PageUsage(usage.toJson())
        assertEquals(1.0, copy.score("Reuniones", 5), 0.0)
        assertEquals(0.0, copy.score("Teams", 5), 0.0)
        assertEquals(0.0, PageUsage("not json").total(0), 0.0)
    }

    @Test
    fun `Type selected file takes the PC clipboard page's untouched Paste, once`() {
        assertEquals(listOf(Step.Pc("typefile", "selected")), Macro.parse("pc:typefile"))
        assertEquals("Types the file selected in Explorer", Macro.describe("pc:typefile"))
        val pages = DefaultLayout.layout().pages
        val clipboard = pages.first { it.name == DefaultLayout.CLIPBOARD_PAGE }
        val at = clipboard.buttons.indexOfFirst { it?.action == "pc:typefile" }
        clipboard.buttons[at] = MacroButton("Paste", "ctrl+v", 0, "paste") // an older layout
        assertTrue(DefaultLayout.addTypeFile(pages))
        assertEquals("pc:typefile", clipboard.buttons[at]!!.action)
        assertFalse(DefaultLayout.addTypeFile(pages))
    }

    @Test
    fun `Excel and Word go unless changed, and the tabs take the work order`() {
        val old = DefaultLayout.layout().pages.toMutableList()
        // An old layout: Excel and Word as they came (and one Word key changed in another copy).
        val excel = Page("Excel", mutableListOf(MacroButton("AutoSum", "alt+equal", 1, "sum"), MacroButton("Insert row", "shift+space\nctrl+shift+plus", 2)))
        assertFalse(DefaultLayout.retireAndOrder(old)) // the defaults are already in order
        val pages = (listOf(Page("Mine", mutableListOf())) + old.reversed()).toMutableList()
        assertTrue(DefaultLayout.retireAndOrder(pages))
        assertEquals(DefaultLayout.TAB_ORDER.filter { name -> old.any { it.name == name } } + "Mine", pages.map { it.name })
        // A Word page with its keys changed stays (after the ordered ones).
        val word = Page("Word", mutableListOf(MacroButton("Mi macro", "ctrl+shift+m", 0)))
        val withWord = (old + word + excel).toMutableList()
        DefaultLayout.retireAndOrder(withWord)
        assertTrue(withWord.any { it.name == "Word" })
        assertTrue(withWord.any { it.name == "Excel" }) // only two of its keys: changed
    }
}
