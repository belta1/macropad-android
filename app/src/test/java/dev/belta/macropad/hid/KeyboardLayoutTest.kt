package dev.belta.macropad.hid

import dev.belta.macropad.hid.HidKeys.ALTGR
import dev.belta.macropad.hid.HidKeys.SHIFT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardLayoutTest {

    private fun one(layout: KeyboardLayout, c: Char) = layout.strokes(c)!!.single()

    @Test
    fun `letters digits and whitespace are the same everywhere`() {
        for (layout in KeyboardLayout.values()) {
            assertEquals(Stroke(0, 0x04), one(layout, 'a'))
            assertEquals(Stroke(SHIFT, 0x1D), one(layout, 'Z'))
            assertEquals(Stroke(0, 0x27), one(layout, '0'))
            assertEquals(Stroke(0, 0x28), one(layout, '\n'))
            assertEquals(Stroke(0, 0x2C), one(layout, ' '))
        }
    }

    @Test
    fun `US symbols`() {
        val us = KeyboardLayout.US
        assertEquals(Stroke(SHIFT, 0x1F), one(us, '@'))
        assertEquals(Stroke(0, 0x38), one(us, '/'))
        assertEquals(Stroke(SHIFT, 0x33), one(us, ':'))
        assertEquals(Stroke(SHIFT, 0x27), one(us, ')'))
        assertNull(us.strokes('ñ'))
    }

    @Test
    fun `Spain symbols`() {
        val es = KeyboardLayout.SPAIN
        assertEquals(Stroke(ALTGR, 0x1F), one(es, '@'))
        assertEquals(Stroke(SHIFT, 0x24), one(es, '/'))
        assertEquals(Stroke(SHIFT, 0x37), one(es, ':'))
        assertEquals(Stroke(0, 0x38), one(es, '-'))
        assertEquals(Stroke(0, 0x33), one(es, 'ñ'))
        assertEquals(Stroke(ALTGR, 0x08), one(es, '€'))
    }

    @Test
    fun `Latin American symbols`() {
        val la = KeyboardLayout.LATAM
        assertEquals(Stroke(ALTGR, 0x14), one(la, '@'))
        assertEquals(Stroke(SHIFT, 0x20), one(la, '#'))
        assertEquals(Stroke(SHIFT, 0x24), one(la, '/'))
        assertEquals(Stroke(SHIFT, 0x27), one(la, '='))
        assertEquals(Stroke(0, 0x34), one(la, '{'))
        assertEquals(Stroke(ALTGR, 0x2D), one(la, '\\'))
    }

    @Test
    fun `accents use the dead key then the letter`() {
        assertEquals(listOf(Stroke(0, 0x34), Stroke(0, 0x04)), KeyboardLayout.SPAIN.strokes('á'))
        assertEquals(listOf(Stroke(0, 0x2F), Stroke(SHIFT, 0x08)), KeyboardLayout.LATAM.strokes('É'))
        assertEquals(listOf(Stroke(SHIFT, 0x2F), Stroke(0, 0x18)), KeyboardLayout.LATAM.strokes('ü'))
    }

    @Test
    fun `dead-key symbols are followed by a space`() {
        assertEquals(listOf(Stroke(SHIFT, 0x2F), Stroke(0, 0x2C)), KeyboardLayout.SPAIN.strokes('^'))
        assertEquals(listOf(Stroke(ALTGR, 0x34), Stroke(0, 0x2C)), KeyboardLayout.LATAM.strokes('^'))
    }

    @Test
    fun `printable ASCII is typeable in every layout`() {
        for (layout in KeyboardLayout.values()) {
            for (c in ' '..'~') assertNotNull("${layout.name} can't type '$c'", layout.strokes(c))
        }
    }
}
