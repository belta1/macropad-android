package dev.belta.macropad.net

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One command over Home Assistant's WebSocket API, for what its REST API doesn't offer (which
 * entities are exposed to voice assistants). Just enough WebSocket for that: connect, log in with
 * the token, send the command, read its result, close. Blocks; run it off the main thread.
 */
object HaSocket {
    private const val TIMEOUT_MS = 8_000

    /** The `result` of command [type] (e.g. "homeassistant/expose_entity/list"). */
    fun command(baseUrl: String, token: String, type: String): JSONObject {
        val url = URL(baseUrl.trim().trimEnd('/'))
        val secure = url.protocol.equals("https", ignoreCase = true)
        val port = if (url.port > 0) url.port else if (secure) 443 else 80
        val plain = Socket().apply {
            connect(InetSocketAddress(url.host, port), TIMEOUT_MS)
            soTimeout = TIMEOUT_MS
        }
        val socket = if (secure) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, url.host, port, true).also {
                (it as SSLSocket).startHandshake()
            }
        } else plain
        socket.use {
            val output = it.getOutputStream()
            val input = DataInputStream(BufferedInputStream(it.getInputStream()))
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { bytes -> SecureRandom().nextBytes(bytes) })
            val host = if (url.port > 0) "${url.host}:${url.port}" else url.host
            output.write(("GET /api/websocket HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            output.flush()
            val status = readHeaders(input)
            if (!status.contains(" 101")) throw IOException("Home Assistant refused the WebSocket ($status)")

            expect(read(input, output), "auth_required")
            send(output, JSONObject().put("type", "auth").put("access_token", token))
            expect(read(input, output), "auth_ok")
            send(output, JSONObject().put("id", 1).put("type", type))
            while (true) {
                val message = read(input, output)
                if (message.optString("type") != "result" || message.optInt("id") != 1) continue
                if (!message.optBoolean("success")) {
                    throw IOException("Home Assistant: ${message.optJSONObject("error")?.optString("message") ?: "$type failed"}")
                }
                return message.optJSONObject("result") ?: JSONObject()
            }
        }
    }

    private fun expect(message: JSONObject, type: String) {
        if (message.optString("type") != type) {
            throw IOException(if (message.optString("type") == "auth_invalid") "Home Assistant refused the token" else "Unexpected ${message.optString("type")}")
        }
    }

    /** The status line; the rest of the headers are skipped. */
    private fun readHeaders(input: DataInputStream): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("Home Assistant closed the connection")
            text.append(b.toChar())
            if (text.length > 16_384) throw IOException("Home Assistant's answer is too long")
        }
        return text.lineSequence().first()
    }

    /** A client frame: final, text, masked. */
    private fun send(output: OutputStream, message: JSONObject) {
        val payload = message.toString().toByteArray(Charsets.UTF_8)
        val frame = ByteArrayOutputStream()
        frame.write(0x81)
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size < 65_536 -> { frame.write(0x80 or 126); frame.write(payload.size shr 8); frame.write(payload.size and 0xFF) }
            else -> { frame.write(0x80 or 127); for (i in 7 downTo 0) frame.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        frame.write(mask)
        payload.forEachIndexed { i, b -> frame.write(b.toInt() xor mask[i % 4].toInt()) }
        output.write(frame.toByteArray())
        output.flush()
    }

    /** The next text message (its frames joined), answering pings on the way. */
    private fun read(input: DataInputStream, output: OutputStream): JSONObject {
        val message = ByteArrayOutputStream()
        while (true) {
            val first = input.readUnsignedByte()
            val second = input.readUnsignedByte()
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            if (length > 32L * 1024 * 1024) throw IOException("Home Assistant's message is too big")
            val mask = if (second and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (first and 0x0F) {
                0x8 -> throw IOException("Home Assistant closed the connection")
                0x9 -> { output.write(byteArrayOf(0x8A.toByte(), 0x80.toByte(), 0, 0, 0, 0)); output.flush() } // pong, empty
                0x0, 0x1 -> {
                    message.write(payload)
                    if (first and 0x80 != 0) return JSONObject(message.toString("UTF-8"))
                }
            }
        }
    }
}
