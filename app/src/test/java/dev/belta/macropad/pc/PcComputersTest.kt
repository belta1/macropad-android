package dev.belta.macropad.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcComputersTest {
    @Test
    fun `a decision reads back as stored, name and all`() {
        for (allowed in listOf(true, false)) {
            val computer = PcComputer("AA:BB:CC:DD:EE:FF", "DESKTOP-1\twith a tab", allowed)
            assertEquals(computer, PcComputer.decode(computer.address, computer.encode()))
        }
    }

    @Test
    fun `values this version can't read count as not asked yet`() {
        assertNull(PcComputer.decode("AA", null))
        assertNull(PcComputer.decode("AA", "laptop"))
        assertNull(PcComputer.decode("AA", "2\tlaptop"))
    }
}
