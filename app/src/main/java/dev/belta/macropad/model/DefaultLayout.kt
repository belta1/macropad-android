package dev.belta.macropad.model

/** The pages, dock and tablet-button actions a fresh install starts with. */
object DefaultLayout {
    const val CUSTOM_PAGE = "Custom"
    const val TRACKPAD_PAGE = "Trackpad"
    const val TEAMS_PAGE = "Teams"
    const val CITRIX_PAGE = "Citrix"
    const val NUMPAD_PAGE = "Numpad"
    const val MONITORS_PAGE = "Monitors"
    const val OUTLOOK_PAGE = "Outlook"
    const val PHRASES_PAGE = "Phrases"
    const val PYTHON_PAGE = "Python"
    const val SWITCHER_PAGE = "Switcher"
    const val MIXER_PAGE = "Mixer"
    const val CLIPBOARD_PAGE = "PC clipboard"
    const val SCREEN_PAGE = "Screen"
    const val HOME_PAGE = "Home"
    const val PRESENTER_PAGE = "Presenter"

    /** Pages added in later versions; inserted once into existing layouts at their default position. */
    val ADDED_PAGES = listOf("Desktop", NUMPAD_PAGE, TEAMS_PAGE, CITRIX_PAGE, TRACKPAD_PAGE,
        MONITORS_PAGE, OUTLOOK_PAGE, PHRASES_PAGE, PYTHON_PAGE, SWITCHER_PAGE, MIXER_PAGE, CLIPBOARD_PAGE, SCREEN_PAGE, HOME_PAGE, PRESENTER_PAGE)

    /** Tab icons (drawn by ui.AppIcons), in the order the page menu offers them. */
    val APP_ICONS = listOf("windows", "monitors", "switcher", "screen", "vscode", "python", "chrome", "edge", "excel",
        "word", "powerpoint", "outlook", "teams", "citrix", "mixer", "phrases", "clipboard", "home", "numpad", "trackpad", "custom")

    /** Icon for a page that has none stored (layouts from before 1.3, renamed or new pages). */
    fun iconFor(name: String): String? = when (name.lowercase()) {
        "desktop", "windows", "escritorio" -> "windows"
        "monitors", "screens", "pantallas", "ventanas" -> "monitors"
        "switcher", "open windows", "alt+tab" -> "switcher"
        "screen", "pantalla", "mirror", "espejo" -> "screen"
        "mixer", "volume", "volumen", "mezclador" -> "mixer"
        "pc clipboard", "clipboard", "portapapeles" -> "clipboard"
        "home", "home assistant", "casa", "hogar" -> "home"
        "vs code", "vscode", "code" -> "vscode"
        "python" -> "python"
        "chrome" -> "chrome"
        "edge" -> "edge"
        "excel" -> "excel"
        "word" -> "word"
        "presenter", "powerpoint", "slides", "presentación", "presentacion" -> "powerpoint"
        "outlook", "mail", "correo" -> "outlook"
        "teams" -> "teams"
        "citrix", "remote", "rdp" -> "citrix"
        "phrases", "snippets", "frases", "textos" -> "phrases"
        "numpad", "números", "numeros" -> "numpad"
        "trackpad", "mouse" -> "trackpad"
        "custom" -> "custom"
        else -> null
    }

    // Taskbar positions (Win+1…9, counting pinned apps after Start/Search) on the work PC. Win+number
    // focuses an app even while a Citrix / Remote Desktop window has the keyboard.
    private const val COPILOT_SLOT = 3
    private const val REMOTE_SLOT = 4
    private const val EXPLORER_SLOT = 6
    private const val EDGE_SLOT = 7

    private fun inTeams(shortcut: String) = Macro.inTeams(shortcut)
    private fun toTeams(shortcut: String) = Macro.toTeams(shortcut)

    /**
     * Apps (process names, without ".exe") whose window in front shows a page, by the page's
     * default name; the PC link reports the app in front ([Page.apps]).
     */
    fun appsFor(name: String): String? = when (name.lowercase()) {
        "teams" -> "ms-teams, teams"
        "citrix" -> "wfica32, cdviewer"
        "outlook" -> "outlook, olk"
        "vs code", "vscode" -> "code"
        "chrome" -> "chrome"
        "edge" -> "msedge"
        "excel" -> "excel"
        "word" -> "winword"
        "presenter", "powerpoint" -> "powerpnt"
        else -> null
    }

    /**
     * 1.7: the Teams keys of 1.2–1.6 (shortcuts sent to a focused Teams) -> Teams commands over the
     * PC link, which still send those shortcuts without it ([Macro.TEAMS_FALLBACK]); applied once to
     * existing layouts by [MacroStore], only to keys still set to the old defaults.
     */
    val TEAMS_API_UPGRADE: Map<String, String> =
        Macro.TEAMS_FALLBACK.entries.associate { (action, shortcut) -> shortcut to "pc:teams $action" }

    /**
     * 1.8: the Outlook keys of 1.3–1.7 (English shortcuts, Win+2 to focus Outlook) -> Outlook
     * commands over the PC link, which work whatever Outlook's language and still send those
     * shortcuts without it ([Macro.OUTLOOK_FALLBACK]); applied once to existing layouts by
     * [MacroStore], only to keys still set to the old defaults.
     */
    val OUTLOOK_API_UPGRADE: Map<String, String> by lazy {
        Macro.OUTLOOK_FALLBACK.entries.associate { (action, shortcut) -> shortcut to "pc:outlook $action" } +
            (Macro.toOutlook("ctrl+e") to OUTLOOK_SEARCH)
    }

    /** Brings Outlook up and presses F3 (Search in any language; Ctrl+E is English only). */
    private const val OUTLOOK_SEARCH = "pc:outlook open\ndelay:500\nf3"

    /**
     * 1.3: the Desktop page's desktop-switching row (now in the dock) -> new keys; applied once to
     * existing layouts by [MacroStore], only to keys still set to the old defaults.
     */
    val DESKTOP_ROW_UPGRADE: Map<String, MacroButton> by lazy {
        val old = listOf("win+ctrl+left", "win+tab", "win+ctrl+d", "win+ctrl+f4", "win+ctrl+right")
        old.zip(layout().pages.first { it.name == "Desktop" }.buttons.takeLast(old.size).map { it!! }).toMap()
    }

    /** The Teams page's shortcuts of 1.0/1.1, in the page's order. */
    private val TEAMS_SHORTCUTS = listOf("ctrl+shift+m", "ctrl+shift+o", "ctrl+shift+k", "ctrl+shift+e", "ctrl+shift+h",
        "ctrl+space", "ctrl+shift+p", "ctrl+shift+y", "ctrl+shift+a", "ctrl+shift+d",
        "ctrl+1", "ctrl+2", "ctrl+4", "ctrl+e", "ctrl+n")

    /**
     * Teams keys of 1.0/1.1 (which only worked with Teams focused) -> their replacements; applied
     * once to existing layouts by [MacroStore].
     */
    val TEAMS_FOCUS_UPGRADE: Map<String, String> by lazy {
        val new = layout().pages.first { it.name == TEAMS_PAGE }.buttons.map { it!!.action }
        TEAMS_SHORTCUTS.zip(new).filter { (a, b) -> a != b }.toMap()
    }

    /** A Teams key of the versions that focused Teams through the Run box (Win+R, ms-teams), and its shortcut. */
    private val RUN_BOX_TEAMS = Regex("""win\+r\ndelay:\d+\ntext:ms-teams\\n\ndelay:\d+\n([^\n]+)(\ndelay:\d+\nalt\+tab)?""")

    /**
     * 1.8: Teams keys still focusing Teams through the Run box, which the 1.7 upgrade (from Win+8)
     * missed: the ones Teams' commands cover become them (pc:teams camera…, lit from Teams), and
     * Leave call shows the call's time. The rest keep working as they are. Returns whether
     * anything changed.
     */
    fun upgradeRunBoxTeams(pages: List<Page>): Boolean {
        val keys = pages.firstOrNull { it.name == TEAMS_PAGE }?.buttons ?: return false
        // Each command's shortcut, as sent inside Teams (Mute's fallback is Windows' own: none).
        val commands = Macro.TEAMS_FALLBACK.entries.mapNotNull { (action, fallback) ->
            fallback.lines().firstOrNull { "+" in it && !it.startsWith("win+") && it != "alt+tab" }?.let { it to action }
        }.toMap()
        var changed = false
        for (key in keys.filterNotNull()) {
            val shortcut = RUN_BOX_TEAMS.matchEntire(key.action)?.groupValues?.get(1) ?: continue
            val command = commands[shortcut] ?: continue
            key.action = "pc:teams $command"
            if (command == "leave" && key.label == "Leave call") key.label = LEAVE_CALL_LABEL
            changed = true
        }
        return changed
    }

    private val c = Palette.colors
    private val BLUE = c[0]; private val GREEN = c[1]; private val ORANGE = c[2]; private val RED = c[3]
    private val PURPLE = c[4]; private val TEAL = c[5]; private val YELLOW = c[6]; private val GRAY = c[7]

    private fun b(label: String, action: String, color: Int, icon: String?, toggle: Boolean = false) =
        MacroButton(label, action, color, icon, toggle)

    /** Opens an app through the Windows Run box (Win+R), so it works for anything on the PATH. */
    private fun run(command: String) = "win+r\ndelay:400\ntext:$command\\n"

    /** Keys on the PC clipboard page for the computer's last copies. */
    private const val PC_CLIPS = 12

    /** Types a command in the terminal in front and runs it (Enter). */
    private fun shell(command: String) = "text:$command\\n"

    /**
     * Below every page, the whole width: virtual-desktop navigation, the volume slider and the
     * media card; the rest is free.
     */
    fun dock() = Page("Media", (DESKTOP_DOCK + VOLUME_SLIDER + MEDIA_CARD).toMutableList<MacroButton?>()
        .apply { while (size < Layout.DOCK_SIZE) add(null) })

    /**
     * 1.8: what's playing on the computer (PC link) with ⏮ ⏯ ⏭, a card at the end of the dock;
     * tapping its text plays / pauses too. Any key with a live label and Play / Pause as its action
     * is drawn this way (ui.KeyViews).
     */
    val MEDIA_CARD get() = b("{title|Now playing}\n{artist}", "media:playpause", BLUE, null)

    private val MEDIA_KEYS = listOf("media:prev", "media:playpause", "media:next")

    /**
     * 1.8: the dock's Previous / Play / Pause / Next keys go, since the media card has them; the
     * desktop keys come first (added if missing, repeats dropped), then the dock's other keys, then
     * the card (added if missing). Returns whether anything changed.
     */
    fun mediaCardDock(dock: Page): Boolean {
        fun action(b: MacroButton) = b.action.trim().lowercase()
        val before = dock.buttons.toList()
        val keys = before.filterNotNull()
        val card = keys.firstOrNull { LiveLabels.isLive(it.label) } ?: MEDIA_CARD
        val desktopActions = DESKTOP_DOCK.map { it.action }
        val desktop = DESKTOP_DOCK.map { d -> keys.firstOrNull { action(it) == d.action } ?: d }
        val others = keys.filter { it !== card && action(it) !in desktopActions && action(it) !in MEDIA_KEYS }
        val after = ((desktop + others).take(Layout.DOCK_SIZE - 1) + card).toMutableList<MacroButton?>()
        while (after.size < Layout.DOCK_SIZE) after += null
        if (after == before) return false
        dock.buttons.clear()
        dock.buttons += after
        return true
    }

    private val VOLUME_SLIDER get() = b("Volume", "slide:volume", GRAY, "volume_up")

    /** The Mute key 1.5 put next to the volume slider. */
    private val DOCK_MUTE get() = b("Mute", "media:mute", GRAY, "volume_off")

    /**
     * Swaps a dock's untouched Volume − / Volume + keys for the volume slider (in the first of the
     * two slots; the second is freed). Returns whether anything changed.
     */
    fun upgradeVolumeDock(dock: Page): Boolean {
        val down = dock.buttons.indexOfFirst { it?.action == "media:voldown" }
        val up = dock.buttons.indexOfFirst { it?.action == "media:volup" }
        if (down < 0 || up < 0) return false
        dock.buttons[minOf(down, up)] = VOLUME_SLIDER
        dock.buttons[maxOf(down, up)] = null
        return true
    }

    /**
     * 1.6: the volume slider's icon mutes, so the dock's Mute key from 1.5 goes, if it is still as
     * 1.5 made it and a volume slider is in the dock. Returns whether anything changed.
     */
    fun dropDockMute(dock: Page): Boolean {
        if (dock.buttons.none { it?.action?.trim()?.lowercase() == "slide:volume" }) return false
        val at = dock.buttons.indexOfFirst { it == DOCK_MUTE }
        if (at < 0) return false
        dock.buttons[at] = null
        return true
    }

    /** Added to existing docks once by [MacroStore] (1.3); first in the dock since 1.8. */
    val DESKTOP_DOCK get() = listOf(
        b("Prev desktop", "win+ctrl+left", TEAL, "desktop_prev"),
        b("Task view", "win+tab", PURPLE, "task_view"),
        b("Next desktop", "win+ctrl+right", TEAL, "desktop_next"),
    )

    /**
     * Beside every page. "Work Citrix" opens the Citrix portal in Chrome and fills in the login; the
     * address, user and password are saved values on the tablet (⚙ → Saved values), not part of the
     * layout, so they never end up in backups or exports.
     */
    fun sidebar() = Page("Sidebar", mutableListOf(
        MacroButton("Away", AWAY, RED, "lock", longAction = AWAY_MUTED),
        b("Lock PC", "win+l", ORANGE, "lock"),
        b("Work Citrix", WORK_CITRIX, BLUE, "desktop"),
        JOIN_MEETING,
    ))

    /**
     * 1.8: the next Outlook meeting (PC link); lit from 5 minutes before it until it ends, a tap
     * opens its Teams (or other online) meeting.
     */
    val JOIN_MEETING get() = b("{meeting|Join meeting}", "pc:join", GREEN, "videocam")

    /** Puts [JOIN_MEETING] in the sidebar's first free slot, unless it has one. Returns whether anything changed. */
    fun addJoinMeeting(sidebar: Page): Boolean {
        if (sidebar.buttons.any { it?.action?.trim()?.lowercase() == "pc:join" }) return false
        val free = sidebar.buttons.indexOf(null).takeIf { it >= 0 }
            ?: sidebar.buttons.size.takeIf { it < Layout.SIDEBAR_SIZE } ?: return false
        if (free == sidebar.buttons.size) sidebar.buttons += JOIN_MEETING else sidebar.buttons[free] = JOIN_MEETING
        return true
    }

    /** 1.8: types the file selected in File Explorer (into Citrix, say), after asking. */
    val TYPE_FILE get() = b("Type selected file", "pc:typefile", ORANGE, "document")

    /**
     * Puts [TYPE_FILE] on the PC clipboard page in place of its untouched Paste key (Paste is on
     * the Citrix page too). Returns whether anything changed.
     */
    fun addTypeFile(pages: List<Page>): Boolean {
        val page = pages.firstOrNull { it.name == CLIPBOARD_PAGE } ?: return false
        if (page.buttons.any { it?.action?.trim() == "pc:typefile" }) return false
        val paste = page.buttons.indexOfFirst { it?.label == "Paste" && it.action == "ctrl+v" }.takeIf { it >= 0 } ?: return false
        page.buttons[paste] = TYPE_FILE
        return true
    }

    /** 1.8: the Teams page's Leave key shows how long the call has lasted. */
    const val LEAVE_CALL_LABEL = "Leave call\n{call}"

    /** Gives an untouched Leave key of the Teams page [LEAVE_CALL_LABEL]. Returns whether anything changed. */
    fun callTimerLabel(pages: List<Page>): Boolean {
        val key = pages.firstOrNull { it.name == TEAMS_PAGE }?.buttons
            ?.firstOrNull { it?.label == "Leave call" && it.action == "pc:teams leave" } ?: return false
        key.label = LEAVE_CALL_LABEL
        return true
    }

    /** Lock the PC, then dim the tablet; [AWAY_MUTED] mutes the Teams call first. */
    private const val AWAY = "win+l\ntablet:dim"
    private const val AWAY_MUTED = "win+alt+k\ndelay:300\n$AWAY"

    private const val WORK_CITRIX = "win+r\ndelay:400\ntext:chrome {secret:work_url}\\n\ndelay:5000\n" +
        "text:{secret:work_user}\ntab\ntext:{secret:work_password}\ntab"

    fun hardware() = Page("Tablet buttons", mutableListOf(
        b("Volume +", "media:volup", GRAY, "volume_up"),
        b("Volume −", "media:voldown", GRAY, "volume_down"),
    ))

    /**
     * 1.8: the tabs in order of (expected) use, the work ones first; pages not listed go after
     * them, as they were. Sort tabs by use (pages menu) orders them by real use instead.
     */
    val TAB_ORDER = listOf("Desktop", CITRIX_PAGE, TEAMS_PAGE, OUTLOOK_PAGE, SWITCHER_PAGE, CLIPBOARD_PAGE, PYTHON_PAGE,
        PRESENTER_PAGE, "VS Code", PHRASES_PAGE, SCREEN_PAGE, MONITORS_PAGE, HOME_PAGE, MIXER_PAGE, "Chrome", NUMPAD_PAGE,
        TRACKPAD_PAGE, CUSTOM_PAGE)

    /** 1.8: pages no longer offered: their shortcuts are the apps' own, worth no tab. */
    val RETIRED_PAGES = listOf("Excel", "Word")

    /** [pages] in [TAB_ORDER], the others after them in their order. */
    fun ordered(pages: List<Page>): MutableList<Page> =
        pages.withIndex().sortedBy { (i, p) -> TAB_ORDER.indexOf(p.name).let { if (it < 0) TAB_ORDER.size + i else it } }
            .map { it.value }.toMutableList()

    /**
     * Drops the retired pages whose keys were never changed, and puts the rest in [TAB_ORDER].
     * Returns whether anything changed.
     */
    fun retireAndOrder(pages: MutableList<Page>): Boolean {
        val defaults = allPages().associateBy { it.name }
        val before = pages.toList()
        // Keys count as changed when their label or what they do differs (not their color).
        fun keys(page: Page?) = page?.buttons?.filterNotNull()?.map { Triple(it.label, it.action, it.longAction) }
        pages.removeAll { page -> page.name in RETIRED_PAGES && keys(page) == keys(defaults[page.name]) }
        val sorted = ordered(pages)
        pages.clear()
        pages.addAll(sorted)
        return pages != before
    }

    fun layout() = Layout(3, 5, ordered(allPages().filterNot { it.name in RETIRED_PAGES }), dock(), hardware())

    // Shortcuts are the Windows/Linux defaults of each app.
    private fun allPages() = mutableListOf(
        // Windows: launch common apps and manage virtual desktops.
        Page("Desktop", mutableListOf(
            b("Chrome", run("chrome"), BLUE, "globe"),
            b("VS Code", run("code"), TEAL, "code"),
            b("Excel", run("excel"), GREEN, "table"),
            b("Word", run("winword"), BLUE, "document"),
            b("Teams", run("ms-teams"), PURPLE, "group"),
            b("File Explorer", "win+e", YELLOW, "folder"),
            b("Terminal", run("wt"), GRAY, "terminal"),
            b("Outlook", run("outlook"), BLUE, "mail"),
            b("Calculator", "media:calc", ORANGE, "calculator"),
            b("Settings", "win+i", GRAY, "settings"),
            // Desktop switching lives in the dock (1.3).
            b("Snip", "win+shift+s", GREEN, "cut"),
            b("Clipboard history", "win+v", TEAL, "paste"),
            b("Emoji", "win+period", YELLOW, "star"),
            b("Copilot", "win+$COPILOT_SLOT", PURPLE, "chat"),
            b("Lock PC", "win+l", RED, "lock"),
        )),
        // Windows 11 window management: snapping and moving windows between screens (a Citrix /
        // remote desktop usually has a screen of its own), plus virtual desktops.
        Page(MONITORS_PAGE, mutableListOf(
            b("Snap left", "win+left", BLUE, "align_left"),
            b("Maximize", "win+up", GREEN, "arrow_up"),
            b("Snap right", "win+right", BLUE, "align_right"),
            b("Restore / minimize", "win+down", GRAY, "arrow_down"),
            b("Snap layouts", "win+z", PURPLE, "task_view"),
            b("Window to left screen", "win+shift+left", TEAL, "chevron_left"),
            b("Window to right screen", "win+shift+right", TEAL, "chevron_right"),
            b("Show desktop", "win+d", YELLOW, "desktop"),
            b("Minimize others", "win+home", GRAY, "sidebar"),
            b("Previous window", "alt+tab", ORANGE, "swap"),
            b("New desktop", "win+ctrl+d", GREEN, "desktop_add"),
            b("Close desktop", "win+ctrl+f4", RED, "desktop_close"),
            b("Task manager", "ctrl+shift+esc", ORANGE, "tune"),
            b("Remote desktop", "win+$REMOTE_SLOT", BLUE, "desktop"),
            // Tap: lock the PC and dim the tablet. Hold: also mute the Teams call first.
            MacroButton("Away", AWAY, RED, "lock", longAction = AWAY_MUTED),
        )),
        // The computer's open windows (PC link); tap one to bring it to the front, even from Citrix.
        Page(SWITCHER_PAGE, mutableListOf(), PageKind.WINDOWS),
        // A monitor of the computer, touchable (PC link). Clicking in it brings apps to the front,
        // so the page stays instead of following them.
        Page(SCREEN_PAGE, mutableListOf(), PageKind.SCREEN, stay = true),
        // Home Assistant's lights, switches, scenes, scripts and automations (over Wi-Fi, ha_url / ha_token).
        Page(HOME_PAGE, mutableListOf(), PageKind.HOME, stay = true),
        // PowerPoint's notes and slides, and ◀ ▶ (PC link). It comes up by itself when a slide show starts.
        Page(PRESENTER_PAGE, mutableListOf(), PageKind.SLIDES),
        Page("VS Code", mutableListOf(
            b("Command palette", "ctrl+shift+p", BLUE, "command"),
            b("Quick open", "ctrl+p", BLUE, "search"),
            b("Terminal", "ctrl+grave", GRAY, "terminal"),
            b("Sidebar", "ctrl+b", GRAY, "sidebar"),
            b("Search files", "ctrl+shift+f", TEAL, "find_replace"),
            b("Go to definition", "f12", BLUE, "open_in_new"),
            b("Rename symbol", "f2", ORANGE, "edit"),
            b("Format document", "shift+alt+f", ORANGE, "align_left"),
            b("Toggle comment", "ctrl+slash", TEAL, "comment"),
            b("Save all", "ctrl+k\ns", GREEN, "save"),
            b("Run / Debug", "f5", GREEN, "play"),
            b("Stop", "shift+f5", RED, "stop"),
            b("Step over", "f10", PURPLE, "step_over"),
            b("Step into", "f11", PURPLE, "step_into"),
            b("Cursor below", "ctrl+alt+down", YELLOW, "cursor"),
        )),
        // Python commands typed into the terminal in front (Windows Terminal, PowerShell, cmd or VS
        // Code's terminal), for classes and demos: virtual environments, packages, running code. The
        // page stays up whatever window comes to the front, so switching to the editor or the browser
        // mid-demo doesn't change it. Keys ending in a space wait for the rest of the command.
        Page(PYTHON_PAGE, mutableListOf(
            b("Terminal", run("wt"), GRAY, "terminal"),
            // Hold: create and activate.
            MacroButton("Create venv", shell("python -m venv .venv"), GREEN, "add",
                longAction = shell("python -m venv .venv") + "\n" + shell(".venv\\Scripts\\activate")),
            // Hold: first allow scripts in this PowerShell session (blocked by default on Windows).
            MacroButton("Activate venv", shell(".venv\\Scripts\\activate"), GREEN, "check",
                longAction = shell("Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass") + "\n" +
                    shell(".venv\\Scripts\\activate")),
            b("Deactivate", shell("deactivate"), ORANGE, "close"),
            // Shows the terminal's venv and folder with the PC link's shell hook; types the check anyway.
            b("{venv|Which Python}\n{folder}", shell("python -c \"import sys; print(sys.version.split()[0], sys.executable)\""),
                BLUE, "search"),
            b("Install requirements", shell("python -m pip install -r requirements.txt"), BLUE, "download"),
            b("Install package", "text:python -m pip install ", BLUE, "add"),
            b("Save requirements", shell("python -m pip freeze > requirements.txt"), TEAL, "save"),
            b("List packages", shell("python -m pip list"), TEAL, "bullets"),
            b("Upgrade pip", shell("python -m pip install --upgrade pip"), PURPLE, "refresh"),
            b("Run script", "text:python ", PURPLE, "play"),
            b("Python shell", shell("python"), YELLOW, "command"),
            b("Exit shell", shell("exit()"), YELLOW, "back"),
            b("Clear screen", shell("cls"), GRAY, "format_clear"),
            b("Stop (Ctrl+C)", "ctrl+c", RED, "stop"),
        ), stay = true),
        Page("Chrome", mutableListOf(
            b("New tab", "ctrl+t", BLUE, "add"),
            b("Close tab", "ctrl+w", RED, "close"),
            b("Reopen tab", "ctrl+shift+t", ORANGE, "undo"),
            b("Prev tab", "ctrl+shift+tab", GRAY, "chevron_left"),
            b("Next tab", "ctrl+tab", GRAY, "chevron_right"),
            b("Back", "alt+left", TEAL, "back"),
            b("Forward", "alt+right", TEAL, "forward"),
            b("Reload", "ctrl+r", GREEN, "refresh"),
            b("Hard reload", "ctrl+shift+r", GREEN, "refresh"),
            b("Address bar", "ctrl+l", BLUE, "link"),
            b("Find", "ctrl+f", PURPLE, "search"),
            b("Bookmark", "ctrl+d", YELLOW, "bookmark"),
            b("History", "ctrl+h", PURPLE, "history"),
            b("Downloads", "ctrl+j", PURPLE, "download"),
            b("DevTools", "f12", GRAY, "code"),
        )),
        Page("Excel", mutableListOf(
            b("AutoSum", "alt+equal", GREEN, "sum"),
            b("Insert row", "shift+space\nctrl+shift+plus", BLUE, "row_add"),
            b("Delete row", "shift+space\nctrl+minus", RED, "row_remove"),
            b("Fill down", "ctrl+d", TEAL, "arrow_down"),
            b("Fill right", "ctrl+r", TEAL, "forward"),
            b("Format cells", "ctrl+1", ORANGE, "tune"),
            b("Edit cell", "f2", BLUE, "edit"),
            b("Toggle \$ ref", "f4", YELLOW, "dollar"),
            b("Today's date", "ctrl+semicolon", PURPLE, "calendar"),
            b("Filter", "ctrl+shift+l", ORANGE, "filter"),
            b("Paste values", "ctrl+alt+v\ndelay:300\nv\nenter", GREEN, "paste"),
            b("Make table", "ctrl+t", TEAL, "table"),
            b("Prev sheet", "ctrl+pageup", GRAY, "chevron_left"),
            b("Next sheet", "ctrl+pagedown", GRAY, "chevron_right"),
            b("New line in cell", "alt+enter", PURPLE, "newline"),
        )),
        // Number entry (e.g. Excel on a laptop without a keypad). Uses the main-row digits so it works
        // whatever the computer's Num Lock state is.
        Page(NUMPAD_PAGE, mutableListOf(
            b("7", "7", GRAY, null), b("8", "8", GRAY, null), b("9", "9", GRAY, null),
            b(".", "period", GRAY, null), b("Backspace", "backspace", RED, "back"),
            b("4", "4", GRAY, null), b("5", "5", GRAY, null), b("6", "6", GRAY, null),
            b("0", "0", GRAY, null), b("Tab", "tab", BLUE, "tab"),
            b("1", "1", GRAY, null), b("2", "2", GRAY, null), b("3", "3", GRAY, null),
            b("−", "minus", GRAY, null), b("Enter", "enter", GREEN, "newline"),
        )),
        Page("Word", mutableListOf(
            b("Bold", "ctrl+b", BLUE, "bold"),
            b("Italic", "ctrl+i", BLUE, "italic"),
            b("Underline", "ctrl+u", BLUE, "underline"),
            b("Heading 1", "ctrl+alt+1", PURPLE, "heading"),
            b("Heading 2", "ctrl+alt+2", PURPLE, "heading"),
            b("Normal style", "ctrl+shift+n", PURPLE, "notes"),
            b("Bullets", "ctrl+shift+l", TEAL, "bullets"),
            b("Align left", "ctrl+l", GRAY, "align_left"),
            b("Center", "ctrl+e", GRAY, "align_center"),
            b("Align right", "ctrl+r", GRAY, "align_right"),
            b("Page break", "ctrl+enter", ORANGE, "page_break"),
            b("Find & replace", "ctrl+h", YELLOW, "find_replace"),
            b("Track changes", "ctrl+shift+e", RED, "track_changes"),
            b("Word count", "ctrl+shift+g", GREEN, "hash"),
            b("Clear formatting", "ctrl+space", RED, "format_clear"),
        )),
        // Classic Outlook commands over the PC link: they go to Outlook itself, so they work in any
        // language and from any window (Citrix too); the ones on mail act on the selected messages.
        // Without the link (or with new Outlook) they send English Outlook's shortcuts.
        Page(OUTLOOK_PAGE, mutableListOf(
            b("Open Outlook", "pc:outlook open", BLUE, "mail"),
            b("New mail", "pc:outlook new-mail", BLUE, "edit"),
            b("New meeting", "pc:outlook new-meeting", PURPLE, "calendar"),
            b("Mail", "pc:outlook mail", TEAL, "mail"),
            b("Calendar", "pc:outlook calendar", PURPLE, "calendar"),
            b("Reply", "pc:outlook reply", GREEN, "back"),
            b("Reply all", "pc:outlook reply-all", GREEN, "group"),
            b("Forward", "pc:outlook forward", TEAL, "forward"),
            b("Send", "pc:outlook send", BLUE, "check"),
            b("Search", OUTLOOK_SEARCH, YELLOW, "search"),
            b("Mark read", "pc:outlook mark-read", GRAY, "check"),
            b("Mark unread", "pc:outlook mark-unread", GRAY, "mail"),
            b("Flag", "pc:outlook flag", ORANGE, "bookmark"),
            b("Archive", "pc:outlook archive", TEAL, "download"),
            b("Delete", "pc:outlook delete", RED, "delete"),
        )),
        // New Microsoft Teams on Windows. Mute, camera, hand, leave and blur are Teams commands over
        // the PC link, and their lights follow Teams; without the link they send the shortcut. Teams
        // runs on the PC itself, so the other keys first focus it (Win+number) — they work while a
        // Citrix session has the keyboard. Push to talk needs Teams in front.
        Page(TEAMS_PAGE, mutableListOf(
            b("Mute / Unmute", "pc:teams mute", RED, "mic_off", toggle = true),
            b("Camera on / off", "pc:teams camera", BLUE, "videocam", toggle = true),
            b("Raise hand", "pc:teams hand", YELLOW, "hand", toggle = true),
            b("Share screen", toTeams("ctrl+shift+e"), TEAL, "screen_share"),
            b(LEAVE_CALL_LABEL, "pc:teams leave", RED, "call_end"),
            b("Push to talk", "ctrl+space", GREEN, "mic"),
            b("Blur background", "pc:teams blur", PURPLE, "blur", toggle = true),
            b("Admit from lobby", inTeams("ctrl+shift+y"), ORANGE, "group"),
            b("Accept call", inTeams("ctrl+shift+a"), GREEN, "call"),
            b("Decline call", inTeams("ctrl+shift+d"), RED, "call_end"),
            b("Activity", toTeams("ctrl+1"), GRAY, "bell"),
            b("Chat", toTeams("ctrl+2"), BLUE, "chat"),
            b("Calendar", toTeams("ctrl+4"), PURPLE, "calendar"),
            b("Search", toTeams("ctrl+e"), TEAL, "search"),
            b("New chat", toTeams("ctrl+n"), BLUE, "edit"),
        )),
        // Citrix / remote desktops on one screen, local apps on the others. Win+… goes to the local PC
        // while the session is in a window; the Ctrl/Alt+F-key hotkeys are Citrix Workspace defaults
        // that it translates into the key inside the session.
        Page(CITRIX_PAGE, mutableListOf(
            b("Remote desktop", "win+$REMOTE_SLOT", BLUE, "desktop"),
            b("Teams", "win+${Macro.TEAMS_SLOT}", PURPLE, "group"),
            b("Outlook", "pc:outlook open", BLUE, "mail"),
            b("Edge", "win+$EDGE_SLOT", TEAL, "globe"),
            b("Explorer (local)", "win+$EXPLORER_SLOT", YELLOW, "folder"),
            b("Ctrl+Alt+Del (session)", "ctrl+f1", RED, "lock"),
            b("Task manager (session)", "ctrl+f3", ORANGE, "tune"),
            b("Start (session)", "ctrl+f2", GRAY, "task_view"),
            b("Next app (session)", "alt+f3", TEAL, "chevron_right"),
            b("Prev app (session)", "alt+shift+f3", TEAL, "chevron_left"),
            b("Window / full screen", "shift+f2", GRAY, "open_in_new"),
            b("Copy", "ctrl+c", GRAY, "copy"),
            b("Paste", "ctrl+v", GRAY, "paste"),
            b("Snip (local)", "win+shift+s", GREEN, "camera"),
            b("Lock PC", "win+l", RED, "lock"),
        )),
        // The computer's volume and each app playing sound (PC link), like Windows' volume mixer.
        Page(MIXER_PAGE, mutableListOf(), PageKind.MIXER),
        // Text typed with the computer's layout, so it also works inside Citrix. No trailing Enter:
        // in Teams chat it would send the message. Edit them to taste.
        Page(PHRASES_PAGE, mutableListOf(
            b("Buenos días", "text:Buenos días, ", YELLOW, "chat"),
            b("Buenas tardes", "text:Buenas tardes, ", ORANGE, "chat"),
            b("Gracias", "text:¡Muchas gracias!", GREEN, "star"),
            b("Recibido", "text:Recibido, gracias.", GREEN, "check"),
            b("Te confirmo", "text:Te confirmo en un momento.", BLUE, "history"),
            b("Lo reviso", "text:Lo reviso y te comento.", BLUE, "search"),
            b("En reunión", "text:Estoy en reunión, te respondo apenas termine.", PURPLE, "group"),
            b("¿5 minutos?", "text:¿Tienes 5 minutos para conversar?", PURPLE, "call"),
            b("Adjunto", "text:Adjunto lo solicitado.", TEAL, "document"),
            b("Quedo atento", "text:Quedo atento a tus comentarios.", TEAL, "comment"),
            b("Firma", "text:Saludos cordiales,\\n[Tu nombre]", GRAY, "edit"),
            b("Fecha", "text:{date:dd/MM/yyyy}", GRAY, "calendar"),
            b("Hora", "text:{time}", GRAY, "history"),
            b("Fecha y hora", "text:{date:dd/MM/yyyy HH:mm}", GRAY, "calendar"),
            b("Tablet clipboard", "text:{clipboard}", GRAY, "paste"),
        )),
        // The computer's last copies (PC link), newest first, each key showing its text. They are
        // typed, not pasted, so they also reach a Citrix session that blocks the shared clipboard.
        Page(CLIPBOARD_PAGE, (1..PC_CLIPS).map { n ->
            b("{pcclipboard:$n|—}", "text:{pcclipboard:$n}", if (n == 1) GREEN else GRAY, "paste")
        }.toMutableList<MacroButton?>().apply {
            add(b("Copy", "ctrl+c", BLUE, "copy"))
            add(TYPE_FILE)
            add(b("Clipboard history", "win+v", PURPLE, "history"))
        }),
        // Touchpad surface with click and scroll keys.
        // The keys are the row under the touchpad (editable like any other keys).
        Page(TRACKPAD_PAGE, mutableListOf(
            b("Left click", "click:left", BLUE, "mouse"),
            b("Middle click", "click:middle", GRAY, "mouse"),
            b("Right click", "click:right", PURPLE, "mouse"),
            b("Scroll up", "scroll:3", TEAL, "arrow_up"),
            b("Scroll down", "scroll:-3", TEAL, "arrow_down"),
        ), PageKind.TRACKPAD),
        // Empty page for the user's own buttons and recorded macros.
        Page(CUSTOM_PAGE, mutableListOf()),
    )
}
