package dev.belta.macropad.pc

import android.content.Context

/** A computer that opened the PC link port: [allowed] true / false once decided on the tablet, null before. */
data class PcComputer(val address: String, val name: String, val allowed: Boolean? = null) {
    internal fun encode() = (if (allowed == true) "1" else "0") + "\t" + name

    internal companion object {
        /** Null for a value this version can't read. */
        fun decode(address: String, value: String?): PcComputer? {
            val parts = value?.split('\t', limit = 2) ?: return null
            if (parts.size != 2 || parts[0] !in setOf("0", "1")) return null
            return PcComputer(address, parts[1], parts[0] == "1")
        }
    }
}

/**
 * Which computers may use the PC link, by Bluetooth address. Bluetooth already encrypts the link
 * and only lets paired devices open it, but any device paired with the tablet could: a computer
 * not asked about yet waits while the tablet asks, refused ones are turned away.
 */
class PcComputers(context: Context) {
    private val prefs = context.getSharedPreferences("pc_computers", Context.MODE_PRIVATE)

    /** True / false once decided, null for a computer not asked about yet. */
    fun allowed(address: String): Boolean? = PcComputer.decode(address, prefs.getString(address, null))?.allowed

    fun set(address: String, name: String, allowed: Boolean) =
        prefs.edit().putString(address, PcComputer(address, name, allowed).encode()).apply()

    /** [address] is asked about again the next time it opens the port. */
    fun forget(address: String) = prefs.edit().remove(address).apply()

    /** Allowed ones first, then by name. */
    fun all(): List<PcComputer> = prefs.all.mapNotNull { (address, value) -> PcComputer.decode(address, value as? String) }
        .sortedWith(compareBy({ it.allowed != true }, { it.name.lowercase() }))
}
