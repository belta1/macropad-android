package dev.belta.macropad.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun `compares versions number by number`() {
        assertTrue(Updater.isNewer("1.7.0", "1.6.0"))
        assertTrue(Updater.isNewer("v1.10.0", "1.9.3"))
        assertTrue(Updater.isNewer("2.0", "1.9.9"))
        assertTrue(Updater.isNewer("1.6.1", "1.6"))
        assertFalse(Updater.isNewer("1.6.0", "1.6.0"))
        assertFalse(Updater.isNewer("1.6", "1.6.0"))
        assertFalse(Updater.isNewer("1.5.9", "1.6.0"))
    }
}
