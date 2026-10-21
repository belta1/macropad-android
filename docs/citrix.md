# Citrix, Remote Desktop and Teams on the same PC

Setup this is written for: a Windows 11 PC with Citrix / Remote Desktop sessions on one screen, and
Teams and Outlook running locally on the PC.

## Where keys go

The macropad is a Bluetooth keyboard, so every key goes to **the window that has focus**:

- Session window focused → letters and most shortcuts go into the session.
- **Win+…** shortcuts go to the **local PC** while the session is in a window (Citrix Workspace's
  default "Windows key combinations: full screen only"). That's what the Citrix and Teams pages
  rely on to jump between the session and local apps.
- Citrix hotkeys (Ctrl+F1 etc.) are caught by Citrix Workspace and turned into the real key inside
  the session.

## Teams page

Teams shortcuts only work when Teams has focus, so the keys bring it to the front first:

| Key | Sends | Why |
|---|---|---|
| Mute / Unmute | `win+alt+k` | Windows 11 call mute; works from any window during a Teams call |
| Camera, Raise hand, Blur, Leave, Admit, Accept, Decline | `win+8`, the Teams shortcut, `alt+tab` | Focus Teams, act, return to the previous window (hint shows `↩`) |
| Share screen, Activity, Chat, Calendar, Search, New chat | `win+8`, the Teams shortcut | Leave Teams in front |
| Push to talk | `ctrl+space` (held) | Needs Teams focused |

`win+8` is Teams' position on the taskbar (Win+1…9 count pinned apps, not Start or Search). If the
taskbar changes, edit the keys (✏️). ON/OFF on toggle keys is the macropad's guess; ⚙ → **Reset
ON/OFF keys** when it drifts.

One caveat to check: on some PCs, Win+number on an app that is **already in front** minimizes it
instead of keeping it. If a Teams or Outlook key hides the app when you're already in it, use the
plain shortcut on the keyboard (or edit the key to drop the `win+…` line).

## Outlook page

Keys that start something focus Outlook first (`win+2`) and leave it in front: Open Outlook,
New mail (Ctrl+Shift+M), New meeting (Ctrl+Shift+Q), Mail (Ctrl+1), Calendar (Ctrl+2), Search (Ctrl+E).
The others act on the selected mail and expect Outlook in front: Reply, Reply all, Forward, Send
(Ctrl+Enter; classic Outlook asks once to enable it), Mark read / unread, Flag (Insert), Archive
(Backspace), Delete.

## Monitors page

Window management on the local PC (a windowed Citrix session is a window like any other): snap
left / right (Win+←/→), maximize, restore / minimize, snap layouts (Win+Z), move the window to the
left / right screen (Win+Shift+←/→), show desktop, minimize others (Win+Home), previous window
(Alt+Tab), new / close virtual desktop, Task manager, Remote desktop (Win+4).

**Away** locks the PC (Win+L) and dims the tablet; holding it first mutes the Teams call
(Win+Alt+K, a toggle — don't use the hold version when you're already muted).

## Citrix page

| Row | Keys |
|---|---|
| Local apps (Win+number) | Remote desktop (Win+4), Teams (Win+8), Outlook (Win+2), Edge (Win+7), Explorer (Win+6) |
| Inside the session (Citrix hotkeys) | Ctrl+Alt+Del (Ctrl+F1), Task manager (Ctrl+F3), Start (Ctrl+F2), next / previous app (Alt+F3 / Alt+Shift+F3) |
| Other | Window / full screen (Shift+F2), Copy, Paste, Snip (local, Win+Shift+S), Lock PC (Win+L) |

The session hotkeys are Citrix Workspace defaults; administrators can change or disable them.
Plain **Remote Desktop (RDP)** uses different ones: Ctrl+Alt+End (Ctrl+Alt+Del), Alt+Home (Start),
Alt+PageUp / Alt+PageDown (switch apps), Ctrl+Alt+Break (full screen).

## Typing into a session

- Give the work PC the **Remote session (Citrix / RDP)** key timing (⚙ → Computers → the PC → Key
  timing) if typed text drops characters.
- Typed text uses the keyboard layout set for that computer; it must match the layout **inside the
  session** (US International here).
- The tablet can't read the PC's clipboard, so it can't copy from the PC into a session. If
  pasting into the session is blocked, that is usually company policy.
