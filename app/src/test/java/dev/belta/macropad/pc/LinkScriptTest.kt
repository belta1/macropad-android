package dev.belta.macropad.pc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class LinkScriptTest {
    private fun script(version: String, body: String = "") = LinkScript("\$ScriptVersion = '$version'\n$body".toByteArray())

    @Test
    fun `offers only a higher version, or the same one with other contents`() {
        val app = script("1.8.0", "new")
        assertEquals("1.8.0", app.version)
        assertTrue(app.newerThan("1.7.2", "aa"))
        assertTrue(app.newerThan("1.8.0", "aa")) // same version, another copy
        assertFalse(app.newerThan("1.8.0", app.sha256.uppercase())) // this very copy
        assertFalse(app.newerThan("1.9.0", "aa")) // never older
        assertFalse(app.newerThan(null, null)) // a script too old to report itself can't take it
        assertTrue(LinkScript.compareVersions("1.10.0", "1.9.2") > 0)
        assertEquals(0, LinkScript.compareVersions("1.8", "1.8.0"))
    }

    @Test
    fun `the real script reports a version, and the hello carries it`() {
        val real = LinkScript(File("../tools/macropad-link.ps1").readBytes())
        assertTrue(real.version!!.matches(Regex("""\d+\.\d+\.\d+""")))
        val hello = PcProtocol.parse("""{"t":"hello","v":1,"host":"PC","script":"1.8.0","sha256":"ab12","updates":true}""") as PcMessage.Hello
        val status = PcProtocol.apply(PcStatus(), hello)
        assertEquals("1.8.0", status.scriptVersion)
        assertEquals("ab12", status.scriptSha256)
        assertTrue(status.canUpdate)
        // The update carries the script whole, with its hash to check.
        val update = JSONObject(PcProtocol.update(real, 5))
        assertEquals("update", update.getString("t"))
        assertEquals(real.sha256, update.getString("sha256"))
        assertEquals(real.sha256, LinkScript.sha256(Base64.getDecoder().decode(update.getString("ps1"))))
    }
}
