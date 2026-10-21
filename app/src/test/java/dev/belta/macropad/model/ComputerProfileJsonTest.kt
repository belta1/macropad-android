package dev.belta.macropad.model

import dev.belta.macropad.hid.KeyTiming
import dev.belta.macropad.hid.KeyboardLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerProfileJsonTest {

    @Test
    fun `round trip keeps every field`() {
        val profile = ComputerProfile(
            name = "Work",
            keyboardLayout = KeyboardLayout.US_INTL,
            keyTiming = KeyTiming.REMOTE,
            hiddenPages = linkedSetOf("VS Code", "Word"),
            startPage = "Teams",
        )
        val json = ComputerProfiles.toJson(profile)
        assertEquals(profile, ComputerProfiles.fromJson(JSONObject(json.toString())))
    }

    @Test
    fun `defaults are left out and read back as null`() {
        val json = ComputerProfiles.toJson(ComputerProfile(name = "Home"))
        assertEquals(setOf("name"), json.keys().asSequence().toSet())
        val back = ComputerProfiles.fromJson(json)
        assertEquals(null, back.keyboardLayout)
        assertTrue(ComputerProfile().isEmpty)
    }

    @Test
    fun `unknown enum values fall back to the default`() {
        val back = ComputerProfiles.fromJson(JSONObject("""{"keyboardLayout":"DVORAK","keyTiming":"FAST"}"""))
        assertEquals(null, back.keyboardLayout)
        assertEquals(null, back.keyTiming)
    }
}
