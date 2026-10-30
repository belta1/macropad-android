package dev.belta.macropad.ui

import dev.belta.macropad.model.Palette
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {

    @After
    fun restore() {
        Ui.theme = Themes.GRAPHITE
    }

    private fun assertReads(theme: Theme, what: String, fore: Int, back: Int, bar: Double) {
        val ratio = Ui.contrast(fore, back)
        assertTrue("${theme.name}: $what is %.2f:1, needs $bar:1".format(ratio), ratio >= bar)
    }

    @Test
    fun `every theme has its own id`() {
        assertEquals(Themes.all.size, Themes.all.map { it.id }.toSet().size)
    }

    @Test
    fun `an unknown or missing theme is graphite`() {
        assertSame(Themes.GRAPHITE, Themes.byId(null))
        assertSame(Themes.GRAPHITE, Themes.byId("vaporwave-9000"))
        assertSame(Themes.SYNTHWAVE, Themes.byId("synthwave"))
    }

    @Test
    fun `graphite draws keys in the palette's own colors`() {
        for (color in Palette.colors) assertEquals(color, Ui.accent(color))
    }

    @Test
    fun `a theme recolors palette slots and leaves other colors alone`() {
        Ui.theme = Themes.SYNTHWAVE
        for ((slot, color) in Palette.colors.withIndex()) assertEquals(Themes.SYNTHWAVE.accents[slot], Ui.accent(color))
        assertEquals(0xFF123456.toInt(), Ui.accent(0xFF123456.toInt()))
    }

    @Test
    fun `text reads on every theme's caps, case and dialogs`() {
        for (t in Themes.all) {
            assertReads(t, "a legend on a cap", t.text, t.capTop, 4.5)
            assertReads(t, "text on the case", t.text, t.case, 4.5)
            assertReads(t, "text on a raised control", t.text, t.raised, 4.5)
            assertReads(t, "secondary text on the case", t.muted, t.case, 4.5)
            assertReads(t, "secondary text in a dialog", t.muted, t.dialog, 4.5)
            assertReads(t, "the main button", t.ink, t.text, 4.5)
        }
    }

    @Test
    fun `icons in every key color read on every theme's caps`() {
        for (t in Themes.all) {
            Ui.theme = t
            for (accent in t.accents) assertReads(t, "a #%06X icon".format(accent and 0xFFFFFF), Ui.legendTone(accent), t.capTop, 3.0)
        }
    }

    @Test
    fun `legends read on a key that is on`() {
        for (t in Themes.all) {
            Ui.theme = t
            for (accent in t.accents) assertReads(t, "a legend on a lit #%06X key".format(accent and 0xFFFFFF), Ui.inkOn(accent), accent, 3.0)
        }
    }
}
