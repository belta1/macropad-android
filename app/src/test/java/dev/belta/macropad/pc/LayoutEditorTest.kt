package dev.belta.macropad.pc

import dev.belta.macropad.model.DefaultLayout
import dev.belta.macropad.model.Layout
import dev.belta.macropad.model.LayoutJson
import dev.belta.macropad.model.Palette
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditorTest {

    private class FakeHost : LayoutEditor.Host {
        override var layout: Layout = DefaultLayout.layout()
        var replaced = 0
        override fun replaceLayout(layout: Layout) { this.layout = layout; replaced++ }
        override fun asset(name: String) = if (name == "index.html") "<html>editor</html>".toByteArray() else null
        override val colors = Palette.colors.toList()
        override val appIcons = mapOf("excel" to "Excel")
        override val tabletName = "Galaxy Tab"
        override val appVersion = "1.9.0"
    }

    private val host = FakeHost()
    private val editor = LayoutEditor(host)

    private fun request(method: String, path: String, key: String?, body: String = "") =
        editor.handle(PcMessage.Http(1, method, path, key, body))

    @Test
    fun `refuses every request until the editor is opened on the tablet, and with another key`() {
        assertEquals(403, request("GET", "/api/state", "anything").status)
        val key = editor.open()
        assertEquals(403, request("GET", "/api/state", "x$key").status)
        assertEquals(403, request("GET", "/api/state", null).status)
        assertEquals(200, request("GET", "/api/state", key).status)
    }

    @Test
    fun `opening again makes the old key stop working`() {
        val old = editor.open()
        val new = editor.open()
        assertEquals(403, request("GET", "/api/state", old).status)
        assertEquals(200, request("GET", "/api/state", new).status)
    }

    @Test
    fun `serves the page with the key in the address, to keep`() {
        val key = editor.open()
        val reply = request("GET", "/?k=$key", null)
        assertEquals(200, reply.status)
        assertEquals("<html>editor</html>", reply.body)
        assertTrue(reply.cache)
        assertEquals(404, request("GET", "/nothing", key).status)
    }

    @Test
    fun `state carries the layout, its revision and the colours`() {
        val key = editor.open()
        val state = JSONObject(request("GET", "/api/state", key).body)
        assertEquals(LayoutEditor.rev(host.layout), state.getString("rev"))
        assertEquals(host.layout.pages.size, state.getJSONObject("layout").getJSONArray("pages").length())
        assertEquals(8, state.getJSONArray("colors").length())
        assertEquals(Palette.colors[0], state.getJSONArray("colors").getJSONObject(0).getInt("value"))
        assertEquals("#4F8CFF", state.getJSONArray("colors").getJSONObject(0).getString("shown"))
    }

    @Test
    fun `checks actions with the tablet's parser`() {
        val key = editor.open()
        val body = JSONObject().put("actions", JSONArray().put("ctrl+shift+t").put("ctrl+nokey").put("")).toString()
        val results = JSONObject(request("POST", "/api/check", key, body).body).getJSONArray("results")
        assertTrue(results.getJSONObject(0).getBoolean("ok"))
        assertEquals("Ctrl+Shift+T", results.getJSONObject(0).getString("hint"))
        assertFalse(results.getJSONObject(1).getBoolean("ok"))
        assertFalse(results.getJSONObject(2).getBoolean("ok"))
    }

    @Test
    fun `saves an edited layout`() {
        val key = editor.open()
        val json = LayoutJson.toJson(host.layout)
        json.getJSONArray("pages").getJSONObject(0).put("name", "Renamed")
        val reply = request("PUT", "/api/layout", key, JSONObject().put("rev", LayoutEditor.rev(host.layout)).put("layout", json).toString())
        assertEquals(200, reply.status)
        assertEquals("Renamed", host.layout.pages[0].name)
        assertEquals(LayoutEditor.rev(host.layout), JSONObject(reply.body).getString("rev"))
    }

    @Test
    fun `doesn't overwrite changes made on the tablet meanwhile`() {
        val key = editor.open()
        val started = LayoutEditor.rev(host.layout)
        host.layout.pages[0].name = "Changed on the tablet"
        val reply = request("PUT", "/api/layout", key, JSONObject().put("rev", started).put("layout", LayoutJson.toJson(host.layout)).toString())
        assertEquals(409, reply.status)
        assertEquals(0, host.replaced)
    }

    @Test
    fun `refuses keys whose action the tablet can't run, naming them`() {
        val key = editor.open()
        val json = LayoutJson.toJson(host.layout)
        val first = json.getJSONArray("pages").getJSONObject(0).getJSONArray("buttons").getJSONObject(0)
        first.put("action", "ctrl+nokey")
        val reply = request("PUT", "/api/layout", key, JSONObject().put("rev", LayoutEditor.rev(host.layout)).put("layout", json).toString())
        assertEquals(400, reply.status)
        val problem = JSONObject(reply.body).getJSONArray("keys").getJSONObject(0)
        assertEquals(host.layout.pages[0].name, problem.getString("page"))
        assertEquals(0, host.replaced)
    }

    @Test
    fun `refuses what isn't a layout`() {
        val key = editor.open()
        val reply = request("PUT", "/api/layout", key, JSONObject().put("rev", LayoutEditor.rev(host.layout))
            .put("layout", JSONObject().put("pages", JSONArray())).toString())
        assertEquals(400, reply.status)
        assertEquals(400, request("PUT", "/api/layout", key, "not json").status)
        assertNull(null)
    }
}
