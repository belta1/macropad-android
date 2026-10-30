package dev.belta.macropad.pc

import android.content.Context
import java.security.MessageDigest

/**
 * The PC link script (tools/macropad-link.ps1) as this app carries it, to update the computer's
 * copy over the link: its contents, SHA-256 and `$ScriptVersion`.
 */
class LinkScript(val bytes: ByteArray) {
    val sha256: String = sha256(bytes)
    val version: String? = VERSION.find(String(bytes, Charsets.UTF_8))?.groupValues?.get(1)

    /**
     * Whether the computer's copy ([PcStatus.scriptVersion], [PcStatus.scriptSha256]) should be
     * replaced by this one: this version is higher, or the same with other contents. Never an older
     * one, and never a script too old to report itself (it can't take updates).
     */
    fun newerThan(version: String?, sha256: String?): Boolean {
        if (version == null || sha256 == null || this.version == null) return false
        val order = compareVersions(this.version, version)
        return order > 0 || (order == 0 && !sha256.equals(this.sha256, ignoreCase = true))
    }

    companion object {
        private val VERSION = Regex("""\${'$'}ScriptVersion\s*=\s*'([0-9.]+)'""")

        fun load(context: Context): LinkScript? =
            runCatching { LinkScript(context.assets.open("macropad-link.ps1").use { it.readBytes() }) }.getOrNull()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** "1.10.0" > "1.9.2". */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
