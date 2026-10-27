package dev.belta.macropad.net

import dev.belta.macropad.model.Step
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Network steps ([Step.Http], [Step.Wol], [Step.Ha]) and Home Assistant reads. Every call blocks,
 * so run them off the main thread. Failures throw [IOException] with a message fit for a toast.
 */
object NetActions {
    const val HA_URL = "ha_url"
    const val HA_TOKEN = "ha_token"
    const val HA_CALENDAR = "ha_calendar"

    private const val TIMEOUT_MS = 6_000

    /** Saved values [step] needs before it can run. */
    fun requiredSecrets(step: Step): List<String> = if (step is Step.Ha) listOf(HA_URL, HA_TOKEN) else emptyList()

    /** [expand] fills in text variables ({secret:…}, {date}, …) in URLs and bodies. */
    fun run(step: Step, secret: (String) -> String?, expand: (String) -> String) {
        when (step) {
            is Step.Http -> request(step.method, expand(step.url), step.body?.let(expand))
            is Step.Wol -> wake(step.mac)
            is Step.Ha -> {
                val data = JSONObject().apply { step.entity?.let { put("entity_id", it) } }
                ha(secret, "POST", "/api/services/${step.domain}/${step.service}", data.toString())
            }
            else -> {}
        }
    }

    /** The next or current event of the Home Assistant calendar [entity], or null when there is none. */
    fun nextEvent(secret: (String) -> String?, entity: String): CalendarEvent? {
        val state = JSONObject(ha(secret, "GET", "/api/states/$entity", null))
        val attributes = state.optJSONObject("attributes") ?: return null
        val title = attributes.optString("message").ifEmpty { return null }
        val start = parseHaTime(attributes.optString("start_time")) ?: return null
        return CalendarEvent(title, start, parseHaTime(attributes.optString("end_time")), state.optString("state") == "on")
    }

    data class CalendarEvent(val title: String, val start: Date, val end: Date?, val now: Boolean)

    /** Home Assistant gives calendar times as local "yyyy-MM-dd HH:mm:ss". */
    private fun parseHaTime(text: String): Date? =
        if (text.isEmpty()) null else runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(text) }.getOrNull()

    private fun ha(secret: (String) -> String?, method: String, path: String, body: String?): String {
        val base = secret(HA_URL)?.trim()?.trimEnd('/') ?: throw IOException("Set $HA_URL in ⚙ → Saved values")
        val token = secret(HA_TOKEN)?.trim() ?: throw IOException("Set $HA_TOKEN in ⚙ → Saved values")
        return request(method, base + path, body, mapOf("Authorization" to "Bearer $token"))
    }

    private fun request(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap()): String {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("Bad address: $url")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                val json = body.trimStart().startsWith("{") || body.trimStart().startsWith("[")
                connection.setRequestProperty("Content-Type", if (json) "application/json" else "text/plain; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw IOException("${connection.url.host} can't be reached")
            }
            if (code == 401 || code == 403) throw IOException("${connection.url.host} refused the token or login ($code)")
            if (code !in 200..299) throw IOException("${connection.url.host} answered $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Magic packet: 6 × 0xFF, then the MAC 16 times, broadcast to UDP port 9. */
    private fun wake(mac: String) {
        val address = mac.split(':').map { it.toInt(16).toByte() }.toByteArray()
        val packet = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { address.toList() }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), 9))
        }
    }
}
