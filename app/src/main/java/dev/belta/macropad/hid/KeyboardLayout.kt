package dev.belta.macropad.hid

import dev.belta.macropad.hid.HidKeys.ALTGR
import dev.belta.macropad.hid.HidKeys.SHIFT

/** One key press: modifier bits plus a keyboard-page usage. */
data class Stroke(val mods: Int, val key: Int)

/**
 * Keyboard layout configured on the *computer*. A BLE keyboard sends physical key positions, so
 * typing a character means pressing whichever key produces it in the computer's layout. Characters
 * on dead keys (´ ` ^ ¨) are typed as dead key + space; accented letters as dead key + letter.
 */
enum class KeyboardLayout(val label: String) {
    US("English (US)"),
    US_INTL("English (US International)"),
    LATAM("Spanish (Latin America)"),
    SPAIN("Spanish (Spain)");

    /** Strokes that type [c] with this layout, or null if the layout can't produce it. */
    fun strokes(c: Char): List<Stroke>? = table[c]

    private val table: Map<Char, List<Stroke>> by lazy { build(this) }

    private companion object {
        const val SPACE = 0x2C

        fun build(layout: KeyboardLayout): Map<Char, List<Stroke>> {
            val t = HashMap<Char, List<Stroke>>()
            fun key(c: Char, usage: Int, mods: Int = 0) { t[c] = listOf(Stroke(mods, usage)) }
            fun dead(c: Char, usage: Int, mods: Int = 0) { t[c] = listOf(Stroke(mods, usage), Stroke(0, SPACE)) }
            fun accented(chars: String, usage: Int, mods: Int = 0) {
                // chars = accented forms of "aeiou"; uppercase forms are derived.
                "aeiou".forEachIndexed { i, base ->
                    val letter = 0x04 + (base - 'a')
                    t[chars[i]] = listOf(Stroke(mods, usage), Stroke(0, letter))
                    t[chars[i].uppercaseChar()] = listOf(Stroke(mods, usage), Stroke(SHIFT, letter))
                }
            }

            // Shared by all supported layouts.
            for (i in 0 until 26) {
                key('a' + i, 0x04 + i)
                key('A' + i, 0x04 + i, SHIFT)
            }
            for (i in 1..9) key('0' + i, 0x1E + i - 1)
            key('0', 0x27)
            key('\n', 0x28)
            key('\t', 0x2B)
            key(' ', SPACE)

            when (layout) {
                US, US_INTL -> {
                    ")!@#$%^&*(".forEachIndexed { i, c -> key(c, if (i == 0) 0x27 else 0x1E + i - 1, SHIFT) }
                    key('-', 0x2D); key('_', 0x2D, SHIFT)
                    key('=', 0x2E); key('+', 0x2E, SHIFT)
                    key('[', 0x2F); key('{', 0x2F, SHIFT)
                    key(']', 0x30); key('}', 0x30, SHIFT)
                    key('\\', 0x31); key('|', 0x31, SHIFT)
                    key(';', 0x33); key(':', 0x33, SHIFT)
                    key('\'', 0x34); key('"', 0x34, SHIFT)
                    key('`', 0x35); key('~', 0x35, SHIFT)
                    key(',', 0x36); key('<', 0x36, SHIFT)
                    key('.', 0x37); key('>', 0x37, SHIFT)
                    key('/', 0x38); key('?', 0x38, SHIFT)
                    if (layout == US_INTL) {
                        // ' " ` ~ ^ are dead keys; the rest of the extras sit on AltGr.
                        dead('\'', 0x34); dead('"', 0x34, SHIFT)
                        dead('`', 0x35); dead('~', 0x35, SHIFT)
                        dead('^', 0x23, SHIFT)
                        accented("áéíóú", 0x34)
                        accented("äëïöü", 0x34, SHIFT)
                        accented("àèìòù", 0x35)
                        accented("âêîôû", 0x23, SHIFT)
                        t['ñ'] = listOf(Stroke(SHIFT, 0x35), Stroke(0, 0x11))
                        t['Ñ'] = listOf(Stroke(SHIFT, 0x35), Stroke(SHIFT, 0x11))
                        t['ç'] = listOf(Stroke(0, 0x34), Stroke(0, 0x06))
                        t['Ç'] = listOf(Stroke(0, 0x34), Stroke(SHIFT, 0x06))
                        key('¡', 0x1E, ALTGR); key('¿', 0x38, ALTGR)
                        key('€', 0x22, ALTGR)
                    }
                }
                SPAIN -> {
                    key('º', 0x35); key('ª', 0x35, SHIFT); key('\\', 0x35, ALTGR)
                    key('!', 0x1E, SHIFT); key('|', 0x1E, ALTGR)
                    key('"', 0x1F, SHIFT); key('@', 0x1F, ALTGR)
                    key('·', 0x20, SHIFT); key('#', 0x20, ALTGR)
                    key('$', 0x21, SHIFT); key('~', 0x21, ALTGR)
                    key('%', 0x22, SHIFT)
                    key('&', 0x23, SHIFT); key('¬', 0x23, ALTGR)
                    key('/', 0x24, SHIFT); key('(', 0x25, SHIFT); key(')', 0x26, SHIFT); key('=', 0x27, SHIFT)
                    key('\'', 0x2D); key('?', 0x2D, SHIFT)
                    key('¡', 0x2E); key('¿', 0x2E, SHIFT)
                    dead('`', 0x2F); dead('^', 0x2F, SHIFT); key('[', 0x2F, ALTGR)
                    key('+', 0x30); key('*', 0x30, SHIFT); key(']', 0x30, ALTGR)
                    key('ñ', 0x33); key('Ñ', 0x33, SHIFT)
                    dead('´', 0x34); dead('¨', 0x34, SHIFT); key('{', 0x34, ALTGR)
                    key('ç', 0x32); key('Ç', 0x32, SHIFT); key('}', 0x32, ALTGR)
                    key('<', 0x64); key('>', 0x64, SHIFT)
                    key(',', 0x36); key(';', 0x36, SHIFT)
                    key('.', 0x37); key(':', 0x37, SHIFT)
                    key('-', 0x38); key('_', 0x38, SHIFT)
                    key('€', 0x08, ALTGR)
                    accented("áéíóú", 0x34)
                    accented("àèìòù", 0x2F)
                    accented("âêîôû", 0x2F, SHIFT)
                    accented("äëïöü", 0x34, SHIFT)
                }
                LATAM -> {
                    key('|', 0x35); key('°', 0x35, SHIFT); key('¬', 0x35, ALTGR)
                    "!\"#$%&/()=".forEachIndexed { i, c -> key(c, if (i == 9) 0x27 else 0x1E + i, SHIFT) }
                    key('\'', 0x2D); key('?', 0x2D, SHIFT); key('\\', 0x2D, ALTGR)
                    key('¿', 0x2E); key('¡', 0x2E, SHIFT)
                    dead('´', 0x2F); dead('¨', 0x2F, SHIFT)
                    key('+', 0x30); key('*', 0x30, SHIFT); key('~', 0x30, ALTGR)
                    key('ñ', 0x33); key('Ñ', 0x33, SHIFT)
                    key('{', 0x34); key('[', 0x34, SHIFT); dead('^', 0x34, ALTGR)
                    key('}', 0x32); key(']', 0x32, SHIFT); dead('`', 0x32, ALTGR)
                    key('<', 0x64); key('>', 0x64, SHIFT)
                    key(',', 0x36); key(';', 0x36, SHIFT)
                    key('.', 0x37); key(':', 0x37, SHIFT)
                    key('-', 0x38); key('_', 0x38, SHIFT)
                    key('@', 0x14, ALTGR)
                    accented("áéíóú", 0x2F)
                    accented("äëïöü", 0x2F, SHIFT)
                }
            }
            return t
        }
    }
}
