package dev.belta.macropad.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/** HaSocket against a tiny fake Home Assistant: login, a ping, other messages, a big fragmented result. */
class HaSocketTest {
    private fun frame(out: OutputStream, opcode: Int, payload: ByteArray, final: Boolean = true) {
        out.write((if (final) 0x80 else 0) or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65_536 -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        out.write(payload)
        out.flush()
    }

    private fun send(out: OutputStream, json: String) = frame(out, 1, json.toByteArray())

    /** A masked client frame's text. */
    private fun receive(input: DataInputStream): JSONObject {
        val first = input.readUnsignedByte()
        val second = input.readUnsignedByte()
        assertTrue("client frames are masked", second and 0x80 != 0)
        assertEquals(0x81, first)
        var length = second and 0x7F
        if (length == 126) length = input.readUnsignedShort()
        val mask = ByteArray(4).also { input.readFully(it) }
        val payload = ByteArray(length).also { input.readFully(it) }
        return JSONObject(String(ByteArray(length) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }))
    }

    private fun fakeHa(token: String, onCommand: (OutputStream, JSONObject) -> Unit): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val out = socket.getOutputStream()
                val request = StringBuilder()
                while (!request.endsWith("\r\n\r\n")) request.append(input.read().toChar())
                assertTrue(request.startsWith("GET /api/websocket HTTP/1.1"))
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
                send(out, """{"type":"auth_required","ha_version":"2026.10.0"}""")
                val auth = receive(input)
                if (auth.optString("access_token") != token) {
                    send(out, """{"type":"auth_invalid","message":"Invalid access token"}""")
                    return@use
                }
                send(out, """{"type":"auth_ok"}""")
                onCommand(out, receive(input))
                Thread.sleep(200)
            }
            server.close()
        }
        return server.localPort
    }

    @Test
    fun `logs in, runs the command and reads its result`() {
        val many = (1..3000).joinToString(",") { """"switch.s$it":{"conversation":false}""" }
        val port = fakeHa("secret") { out, command ->
            assertEquals(HomeAssistant.EXPOSED_COMMAND, command.getString("type"))
            val id = command.getInt("id")
            frame(out, 0x9, ByteArray(0)) // a ping
            send(out, """{"id":99,"type":"event"}""")
            val result = """{"id":$id,"type":"result","success":true,"result":{"exposed_entities":{
                "light.living":{"conversation":true,"cloud.alexa":true},$many}}}""".toByteArray()
            // Two frames, the second over 64 KB long.
            frame(out, 1, result.copyOfRange(0, 100), final = false)
            frame(out, 0, result.copyOfRange(100, result.size))
        }
        val result = HaSocket.command("http://127.0.0.1:$port/", "secret", HomeAssistant.EXPOSED_COMMAND)
        assertEquals(setOf("light.living"), HomeAssistant.parseExposed(result))
    }

    @Test
    fun `a wrong token says so`() {
        val port = fakeHa("secret") { _, _ -> }
        try {
            HaSocket.command("http://127.0.0.1:$port", "wrong", HomeAssistant.EXPOSED_COMMAND)
            fail()
        } catch (e: IOException) {
            assertEquals("Home Assistant refused the token", e.message)
        }
    }

    @Test
    fun `a failed command says why`() {
        val port = fakeHa("secret") { out, command ->
            send(out, """{"id":${command.getInt("id")},"type":"result","success":false,"error":{"code":"unauthorized","message":"Unauthorized"}}""")
        }
        try {
            HaSocket.command("http://127.0.0.1:$port", "secret", HomeAssistant.EXPOSED_COMMAND)
            fail()
        } catch (e: IOException) {
            assertEquals("Home Assistant: Unauthorized", e.message)
        }
    }
}
