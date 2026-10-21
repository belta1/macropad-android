package dev.belta.macropad.model

/** The pages, dock and tablet-button actions a fresh install starts with. */
object DefaultLayout {
    const val CUSTOM_PAGE = "Custom"
    const val TRACKPAD_PAGE = "Trackpad"
    const val TEAMS_PAGE = "Teams"
    const val CITRIX_PAGE = "Citrix"
    const val NUMPAD_PAGE = "Numpad"

    /** Pages added in later versions; inserted once into existing layouts at their default position. */
    val ADDED_PAGES = listOf("Desktop", NUMPAD_PAGE, TEAMS_PAGE, CITRIX_PAGE, TRACKPAD_PAGE)

    // Taskbar positions (Win+1…9, counting pinned apps after Start/Search) on the work PC. Win+number
    // focuses an app even while a Citrix / Remote Desktop window has the keyboard.
    private const val OUTLOOK_SLOT = 2
    private const val REMOTE_SLOT = 4
    private const val EXPLORER_SLOT = 6
    private const val EDGE_SLOT = 7
    private const val TEAMS_SLOT = 8

    /** Sends a Teams shortcut from any window: focus Teams, send it, return to the previous window. */
    private fun inTeams(shortcut: String) = "win+$TEAMS_SLOT\ndelay:300\n$shortcut\ndelay:150\nalt+tab"

    /** Focuses Teams and sends a shortcut that should leave Teams in front (navigation). */
    private fun toTeams(shortcut: String) = "win+$TEAMS_SLOT\ndelay:300\n$shortcut"

    /**
     * Teams keys of 1.0/1.1 (which only worked with Teams focused) -> their replacements; applied
     * once to existing layouts by [MacroStore].
     */
    val TEAMS_FOCUS_UPGRADE: Map<String, String> by lazy {
        val old = listOf("ctrl+shift+m", "ctrl+shift+o", "ctrl+shift+k", "ctrl+shift+e", "ctrl+shift+h",
            "ctrl+space", "ctrl+shift+p", "ctrl+shift+y", "ctrl+shift+a", "ctrl+shift+d",
            "ctrl+1", "ctrl+2", "ctrl+4", "ctrl+e", "ctrl+n")
        val new = layout().pages.first { it.name == TEAMS_PAGE }.buttons.map { it!!.action }
        old.zip(new).filter { (a, b) -> a != b }.toMap()
    }

    private val c = Palette.colors
    private val BLUE = c[0]; private val GREEN = c[1]; private val ORANGE = c[2]; private val RED = c[3]
    private val PURPLE = c[4]; private val TEAL = c[5]; private val YELLOW = c[6]; private val GRAY = c[7]

    private fun b(label: String, action: String, color: Int, icon: String?, toggle: Boolean = false) =
        MacroButton(label, action, color, icon, toggle)

    /** Opens an app through the Windows Run box (Win+R), so it works for anything on the PATH. */
    private fun run(command: String) = "win+r\ndelay:400\ntext:$command\\n"

    /** Media keys (shown as icons only) plus virtual-desktop navigation, below every page. */
    fun dock() = Page("Media", (MEDIA_DOCK + DESKTOP_DOCK).toMutableList())

    private val MEDIA_DOCK get() = listOf(
        b("Previous", "media:prev", GRAY, "prev"),
        b("Play / Pause", "media:playpause", BLUE, "play_pause"),
        b("Next", "media:next", GRAY, "next"),
        b("Volume −", "media:voldown", GRAY, "volume_down"),
        b("Volume +", "media:volup", GRAY, "volume_up"),
    )

    /** Added to existing docks once by [MacroStore] (1.3). */
    val DESKTOP_DOCK get() = listOf(
        b("Prev desktop", "win+ctrl+left", TEAL, "desktop_prev"),
        b("Task view", "win+tab", PURPLE, "task_view"),
        b("Next desktop", "win+ctrl+right", TEAL, "desktop_next"),
    )

    fun hardware() = Page("Tablet buttons", mutableListOf(
        b("Volume +", "media:volup", GRAY, "volume_up"),
        b("Volume −", "media:voldown", GRAY, "volume_down"),
    ))

    // Shortcuts are the Windows/Linux defaults of each app.
    fun layout() = Layout(3, 5, mutableListOf(
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
            b("Prev desktop", "win+ctrl+left", TEAL, "chevron_left"),
            b("Task view", "win+tab", PURPLE, "task_view"),
            b("New desktop", "win+ctrl+d", GREEN, "desktop_add"),
            b("Close desktop", "win+ctrl+f4", RED, "desktop_close"),
            b("Next desktop", "win+ctrl+right", TEAL, "chevron_right"),
        )),
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
        // New Microsoft Teams on Windows. Teams runs on the PC itself, so every key first focuses it
        // (Win+number) — they work while a Citrix session has the keyboard. Mute uses Windows 11's
        // call mute (Win+Alt+K), which works from any window. Push to talk needs Teams in front.
        Page(TEAMS_PAGE, mutableListOf(
            b("Mute / Unmute", "win+alt+k", RED, "mic_off", toggle = true),
            b("Camera on / off", inTeams("ctrl+shift+o"), BLUE, "videocam", toggle = true),
            b("Raise hand", inTeams("ctrl+shift+k"), YELLOW, "hand", toggle = true),
            b("Share screen", toTeams("ctrl+shift+e"), TEAL, "screen_share"),
            b("Leave call", inTeams("ctrl+shift+h"), RED, "call_end"),
            b("Push to talk", "ctrl+space", GREEN, "mic"),
            b("Blur background", inTeams("ctrl+shift+p"), PURPLE, "blur", toggle = true),
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
            b("Teams", "win+$TEAMS_SLOT", PURPLE, "group"),
            b("Outlook", "win+$OUTLOOK_SLOT", BLUE, "mail"),
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
    ), dock(), hardware())
}
