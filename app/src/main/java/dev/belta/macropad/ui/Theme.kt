package dev.belta.macropad.ui

import dev.belta.macropad.model.Palette

/** How keycaps are drawn ([KeyCap]). */
enum class Caps {
    /** Sculpted plastic caps on a plate: a lit top face in a darker skirt, with a shadow. */
    SCULPTED,
    /** Dark glass outlined by a glowing tube of the key's color, brighter while pressed. */
    NEON,
    /** Text-mode boxes: a thin outline that turns into inverse video while pressed or ON. */
    TERMINAL,
}

/** What's drawn on the case behind the keys ([ThemeBackdrop]); it shows between them. */
enum class Backdrop { PLAIN, SUNSET_GRID, SCANLINES, AURORA, EMBERS, BLOSSOM }

/**
 * A look for the whole macropad: the case, plate and keycap colors, the legends, the eight key
 * colors, how keycaps are drawn and what sits behind them. Keys store the palette's colors
 * ([Palette.colors]); a theme only changes what each one looks like ([accents], slot by slot), so a
 * layout looks right in every theme and changing theme changes nothing in it.
 */
class Theme(
    val id: String,
    val name: String,
    /** One line for the theme picker. */
    val blurb: String,
    val case: Int,
    val plate: Int,
    val capSkirt: Int,
    val capTop: Int,
    val raised: Int,
    val dialog: Int,
    val hairline: Int,
    val hairlineStrong: Int,
    val text: Int,
    val muted: Int,
    val faint: Int,
    val ink: Int,
    /** What the palette's blue, green, amber, red, purple, cyan, yellow and grey become. */
    val accents: IntArray,
    val caps: Caps = Caps.SCULPTED,
    /** Halo around key legends and icons, in dp, as if they were lit; 0 = printed. */
    val glow: Float = 0f,
    val backdrop: Backdrop = Backdrop.PLAIN,
    /** Legends in a monospaced terminal font instead of Barlow. */
    val mono: Boolean = false,
) {
    init {
        require(accents.size == Palette.colors.size) { "A theme needs one accent per palette color" }
    }
}

object Themes {
    /** The original colorway: graphite caps with warm white legends. */
    val GRAPHITE = Theme(
        "graphite", "Graphite", "Graphite keycaps, warm white legends",
        case = 0xFF1B1C1E.toInt(), plate = 0xFF141516.toInt(), capSkirt = 0xFF1F2023.toInt(), capTop = 0xFF37393D.toInt(),
        raised = 0xFF2A2B2E.toInt(), dialog = 0xFF242528.toInt(), hairline = 0x14FFFFFF, hairlineStrong = 0x2BFFFFFF,
        text = 0xFFEDEAE3.toInt(), muted = 0xFFA29E96.toInt(), faint = 0xFF6F6C67.toInt(), ink = 0xFF18191B.toInt(),
        accents = Palette.colors.copyOf(),
    )

    val SYNTHWAVE = Theme(
        "synthwave", "Synthwave", "Neon tubes over a sunset grid",
        case = 0xFF140A2A.toInt(), plate = 0xFF0B0520.toInt(), capSkirt = 0xFF1C0E38.toInt(), capTop = 0xFF1F1040.toInt(),
        raised = 0xFF2E1856.toInt(), dialog = 0xFF1D0F3C.toInt(), hairline = 0x26FF4FD8, hairlineStrong = 0x4DFF4FD8,
        text = 0xFFFFE8FB.toInt(), muted = 0xFFC9A6E8.toInt(), faint = 0xFF7E62A6.toInt(), ink = 0xFF140A2A.toInt(),
        accents = intArrayOf(
            0xFF3FA9FF.toInt(), 0xFF2BFFB5.toInt(), 0xFFFF9A3C.toInt(), 0xFFFF2E88.toInt(),
            0xFFB967FF.toInt(), 0xFF00F0FF.toInt(), 0xFFFFE45E.toInt(), 0xFF9C8CC9.toInt(),
        ),
        caps = Caps.NEON, glow = 6f, backdrop = Backdrop.SUNSET_GRID,
    )

    val PHOSPHOR = Theme(
        "phosphor", "Phosphor", "A green-screen terminal, scanlines and all",
        case = 0xFF050A06.toInt(), plate = 0xFF020503.toInt(), capSkirt = 0xFF0A140C.toInt(), capTop = 0xFF0C1A0F.toInt(),
        raised = 0xFF12261A.toInt(), dialog = 0xFF0B170E.toInt(), hairline = 0x2633FF66, hairlineStrong = 0x4D33FF66,
        text = 0xFFB8FFC9.toInt(), muted = 0xFF5FD07F.toInt(), faint = 0xFF2F7A45.toInt(), ink = 0xFF041008.toInt(),
        accents = intArrayOf(
            0xFF5CC8FF.toInt(), 0xFF33FF66.toInt(), 0xFFFFB000.toInt(), 0xFFFF5A4E.toInt(),
            0xFFC08CFF.toInt(), 0xFF3DFFE0.toInt(), 0xFFE8FF4D.toInt(), 0xFF7FA88A.toInt(),
        ),
        caps = Caps.TERMINAL, glow = 4f, backdrop = Backdrop.SCANLINES, mono = true,
    )

    val ARCTIC = Theme(
        "arctic", "Arctic", "Frosted slate under the northern lights",
        case = 0xFF1E2430.toInt(), plate = 0xFF171C26.toInt(), capSkirt = 0xFF232A37.toInt(), capTop = 0xFF3A4458.toInt(),
        raised = 0xFF2B3342.toInt(), dialog = 0xFF262D3B.toInt(), hairline = 0x1AD8E6FF, hairlineStrong = 0x33D8E6FF,
        text = 0xFFECF2F8.toInt(), muted = 0xFFA3B1C6.toInt(), faint = 0xFF66738A.toInt(), ink = 0xFF151A23.toInt(),
        accents = intArrayOf(
            0xFF6FA8FF.toInt(), 0xFF5FD4A8.toInt(), 0xFFF0A868.toInt(), 0xFFF07A8C.toInt(),
            0xFFB49CFF.toInt(), 0xFF6FD8EC.toInt(), 0xFFF2D27A.toInt(), 0xFFA7B4C8.toInt(),
        ),
        backdrop = Backdrop.AURORA,
    )

    val EMBER = Theme(
        "ember", "Ember", "Charcoal caps over glowing coals",
        case = 0xFF1A1513.toInt(), plate = 0xFF120E0D.toInt(), capSkirt = 0xFF211A17.toInt(), capTop = 0xFF3A2F2A.toInt(),
        raised = 0xFF2C2320.toInt(), dialog = 0xFF251E1B.toInt(), hairline = 0x1AFFB27A, hairlineStrong = 0x33FFB27A,
        text = 0xFFF5E6DA.toInt(), muted = 0xFFBCA696.toInt(), faint = 0xFF7A6658.toInt(), ink = 0xFF1A1210.toInt(),
        accents = intArrayOf(
            0xFF6C9DFF.toInt(), 0xFF7BD389.toInt(), 0xFFFF8A1F.toInt(), 0xFFFF4B2B.toInt(),
            0xFFC77DFF.toInt(), 0xFF4FC9C0.toInt(), 0xFFFFC93C.toInt(), 0xFFA89A90.toInt(),
        ),
        glow = 3f, backdrop = Backdrop.EMBERS,
    )

    val SAKURA = Theme(
        "sakura", "Sakura", "Plum night with falling blossoms",
        case = 0xFF211821.toInt(), plate = 0xFF191219.toInt(), capSkirt = 0xFF281D28.toInt(), capTop = 0xFF45344A.toInt(),
        raised = 0xFF33263A.toInt(), dialog = 0xFF2B2030.toInt(), hairline = 0x1AFFC4DD, hairlineStrong = 0x33FFC4DD,
        text = 0xFFFCEEF4.toInt(), muted = 0xFFC9AEBE.toInt(), faint = 0xFF846C7D.toInt(), ink = 0xFF1E141C.toInt(),
        accents = intArrayOf(
            0xFF8AA8FF.toInt(), 0xFF7ED8B2.toInt(), 0xFFFFB37A.toInt(), 0xFFFF7AA8.toInt(),
            0xFFC79BFF.toInt(), 0xFF7FD6E8.toInt(), 0xFFFFE08A.toInt(), 0xFFB8A9B6.toInt(),
        ),
        backdrop = Backdrop.BLOSSOM,
    )

    val all = listOf(GRAPHITE, SYNTHWAVE, PHOSPHOR, ARCTIC, EMBER, SAKURA)

    /** The theme saved as [id]; Graphite for none or one this version doesn't have. */
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: GRAPHITE
}
