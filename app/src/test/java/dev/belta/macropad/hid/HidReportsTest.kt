package dev.belta.macropad.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HidReportsTest {

    @Test
    fun `keyboard report is modifiers, reserved byte and six keys`() {
        assertArrayEquals(byteArrayOf(0x03, 0, 0x17, 0x04, 0, 0, 0, 0), HidReports.keyboard(0x03, listOf(0x17, 0x04)))
    }

    @Test
    fun `keyboard report keeps at most six keys`() {
        val report = HidReports.keyboard(0, (0x04..0x0C).toList())
        assertEquals(8, report.size)
        assertEquals(0x09.toByte(), report[7])
    }

    @Test
    fun `consumer report is a little-endian usage`() {
        assertArrayEquals(byteArrayOf(0x92.toByte(), 0x01), HidReports.consumer(0x192))
        assertArrayEquals(byteArrayOf(0, 0), HidReports.consumer(0))
    }

    @Test
    fun `mouse report clamps motion to a signed byte`() {
        assertArrayEquals(byteArrayOf(1, 127, -127, 3, 0), HidReports.mouse(1, 500, -500, 3))
        assertArrayEquals(byteArrayOf(0, -5, 7, 0, -1), HidReports.mouse(0, -5, 7, pan = -1))
    }

    @Test
    fun `descriptor declares the three report ids and balanced collections`() {
        val d = HidReports.DESCRIPTOR.map { it.toInt() and 0xFF }
        val reportIds = d.indices.filter { d[it] == 0x85 }.map { d[it + 1] }
        assertEquals(listOf(1, 2, 3), reportIds)
        // Count collection open/close items (0xA1 nn / 0xC0) while skipping item data bytes.
        var depth = 0
        var i = 0
        while (i < d.size) {
            val prefix = d[i]
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            if (prefix == 0xA1) depth++
            if (prefix == 0xC0) depth--
            i += 1 + size
        }
        assertEquals(0, depth)
    }
}
