package dev.belta.macropad.hid

/** HID usage tables: keyboard page (0x07) key names, modifier bits and consumer page (0x0C) media keys. */
object HidKeys {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val GUI = 0x08
    const val ALTGR = 0x40

    val modifiers = mapOf(
        "ctrl" to 0x01, "control" to 0x01, "lctrl" to 0x01,
        "shift" to 0x02, "lshift" to 0x02,
        "alt" to 0x04, "option" to 0x04, "lalt" to 0x04,
        "gui" to 0x08, "win" to 0x08, "super" to 0x08, "cmd" to 0x08, "meta" to 0x08,
        "rctrl" to 0x10, "rshift" to 0x20, "ralt" to 0x40, "altgr" to 0x40,
        "rgui" to 0x80, "rwin" to 0x80, "rcmd" to 0x80,
    )

    val keys: Map<String, Int> = buildMap {
        for (i in 0 until 26) put(('a' + i).toString(), 0x04 + i)
        for (i in 1..9) put(i.toString(), 0x1E + i - 1)
        put("0", 0x27)
        put("enter", 0x28); put("return", 0x28)
        put("esc", 0x29); put("escape", 0x29)
        put("backspace", 0x2A); put("bksp", 0x2A)
        put("tab", 0x2B)
        put("space", 0x2C)
        put("minus", 0x2D); put("equal", 0x2E)
        put("lbracket", 0x2F); put("rbracket", 0x30); put("backslash", 0x31)
        put("semicolon", 0x33); put("quote", 0x34); put("grave", 0x35)
        put("comma", 0x36); put("period", 0x37); put("slash", 0x38)
        put("capslock", 0x39)
        for (i in 1..12) put("f$i", 0x3A + i - 1)
        put("printscreen", 0x46); put("prtsc", 0x46); put("sysrq", 0x46)
        put("scrolllock", 0x47); put("pause", 0x48)
        put("insert", 0x49); put("ins", 0x49)
        put("home", 0x4A); put("pageup", 0x4B); put("pgup", 0x4B)
        put("delete", 0x4C); put("del", 0x4C)
        put("end", 0x4D); put("pagedown", 0x4E); put("pgdn", 0x4E)
        put("right", 0x4F); put("left", 0x50); put("down", 0x51); put("up", 0x52)
        put("numlock", 0x53)
        put("kpslash", 0x54); put("kpasterisk", 0x55); put("kpminus", 0x56); put("kpplus", 0x57)
        put("kpenter", 0x58)
        for (i in 1..9) put("kp$i", 0x59 + i - 1)
        put("kp0", 0x62); put("kpdot", 0x63)
        put("iso", 0x64) // the extra key left of Z on ISO keyboards
        put("menu", 0x65); put("app", 0x65)
        for (i in 13..24) put("f$i", 0x68 + i - 13)
    }

    /** Consumer page usages (HID usage page 0x0C). */
    val media = mapOf(
        "playpause" to 0xCD, "play" to 0xCD, "pause" to 0xCD,
        "next" to 0xB5, "prev" to 0xB6, "previous" to 0xB6, "stop" to 0xB7,
        "mute" to 0xE2, "volup" to 0xE9, "voldown" to 0xEA,
        "brightup" to 0x6F, "brightdown" to 0x70,
        "mail" to 0x18A, "calc" to 0x192, "files" to 0x194, "browser" to 0x196,
        "search" to 0x221, "home" to 0x223, "back" to 0x224, "forward" to 0x225, "refresh" to 0x227,
    )

    /** Mouse buttons (bit masks in the mouse report). */
    val mouseButtons = mapOf("left" to 0x01, "right" to 0x02, "middle" to 0x04)
}
