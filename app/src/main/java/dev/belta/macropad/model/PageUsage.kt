package dev.belta.macropad.model

import org.json.JSONObject
import kotlin.math.pow

/**
 * How much each page (by name) is used: a key pressed on it, or a minute it shows, adds one, and
 * scores halve every [HALF_LIFE_DAYS] days, so lately counts most. For sorting the tabs by use
 * (pages menu), never on its own.
 */
class PageUsage(json: String? = null) {
    private class Entry(var score: Double, var at: Long)

    private val entries = HashMap<String, Entry>()

    init {
        json?.let { text ->
            runCatching {
                val o = JSONObject(text)
                for (name in o.keys()) {
                    val e = o.getJSONObject(name)
                    entries[name] = Entry(e.getDouble("s"), e.getLong("t"))
                }
            }
        }
    }

    fun use(page: String, now: Long, amount: Double = 1.0) {
        val e = entries.getOrPut(page) { Entry(0.0, now) }
        e.score = decayed(e, now) + amount
        e.at = now
    }

    fun score(page: String, now: Long): Double = entries[page]?.let { decayed(it, now) } ?: 0.0

    /** Everything counted so far, as of [now]. */
    fun total(now: Long) = entries.values.sumOf { decayed(it, now) }

    /** A page was renamed: its use goes with it. */
    fun rename(from: String, to: String) {
        entries.remove(from)?.let { entries[to] = it }
    }

    /** [names] most used first; ties (pages never used) keep their order. */
    fun sorted(names: List<String>, now: Long): List<String> =
        names.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { score(it.value, now) }.thenBy { it.index }).map { it.value }

    fun toJson(): String = JSONObject().apply {
        entries.forEach { (name, e) -> put(name, JSONObject().put("s", e.score).put("t", e.at)) }
    }.toString()

    private fun decayed(e: Entry, now: Long) = e.score * 0.5.pow((now - e.at).coerceAtLeast(0) / (HALF_LIFE_DAYS * DAY_MS))

    companion object {
        const val HALF_LIFE_DAYS = 14.0
        const val DAY_MS = 86_400_000.0
        /** Below this much use in all, sorting would only shuffle the tabs. */
        const val ENOUGH = 60.0
        /** A page scoring under this is "rarely used" (roughly: not touched for weeks). */
        const val RARE = 2.0
    }
}
