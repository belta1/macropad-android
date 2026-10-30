# PC link

On tablets with Classic Bluetooth (e.g. Galaxy Tab S5e), the macropad offers a **Bluetooth serial
port** next to the keyboard. Windows shows it as a COM port.
[tools/macropad-link.ps1](../tools/macropad-link.ps1) runs on the computer and keeps the port open.

Through it:

- **Teams**: Mute, Camera, Raise hand, Blur and Leave go straight to Teams through its
  third-party app API, so no window comes to the front. The keys light up from Teams' real state,
  including when you click in Teams itself. `pc:teams react like` sends a reaction.
- **Pages follow the app in front.** Teams in front shows the Teams page, Outlook shows Outlook,
  and so on.
- **Next meeting** on the idle clock comes from classic Outlook's calendar, and the sidebar's
  **Join meeting** key shows it, lights up from 5 minutes before until it ends, and opens its
  Teams (or Zoom / Meet / Webex) meeting with a tap, also from Citrix (`pc:join`). The tablet
  never sends a link: the script opens the one in Outlook's invitation.
- **The tablet follows the computer's lock**: Win+L (or locking after a while) dims it,
  unlocking wakes it.
- **Typing uses the computer's keyboard layout**: the script reports the layout of the window in
  front (English US / US International, Spanish Latin America / Spain), so accents and ñ always
  come out right; ⚙ → Computer keyboard layout applies only without the PC link or for other
  layouts.
- **Unread mail** on the Outlook tab (classic Outlook's inbox). Teams doesn't tell other programs
  its unread count.
- **New mail** shows over the dock for a few seconds (sender and subject; tap to open Outlook) and
  on the idle clock as *New mail from …* (no subject: anyone can read the clock). Only mail
  arriving after the script starts. ⚙ → Connection → *New mail on the tablet* turns it off.
- **Recording the computer's keyboard and mouse** (experimental, off): see [below](#recording-the-computers-keyboard-and-mouse-experimental).
- **Calls bring up the Teams page**: when a Teams call starts the tablet wakes and shows it (mute,
  camera, hand… lit from Teams); it stays during the call, also while you switch apps to share,
  and the page you had comes back when you hang up. *Leave call* shows the call's time. During a
  slide show the Presenter page stays instead, with a Mute button. ⚙ → Connection → *Teams page
  during calls* turns it off.
- **Presenter** page for PowerPoint: the speaker notes, the slide and the next one, ◀ ▶; it comes
  up when a slide show starts. See [below](#the-presenter-page-powerpoint).
- **Outlook keys** (classic Outlook) go to Outlook itself: they work in any language (Spanish
  Outlook's shortcuts differ), from any window, Citrix too, and act on the selected messages:
  `pc:outlook reply`, `new-mail`, `mark-read`, … ([below](#outlook-commands)).
- **`pc:run <name>`** keys start commands you list on the computer, and only those.
- **The computer's clipboard** on the tablet: the **PC clipboard** page lists your last copies
  and types any of them ([below](#the-computers-clipboard)).
- **Switcher** page: the computer's open windows with their app icons; tap one to bring it to
  the front, also while a Citrix session has the keyboard.
- **Screen** page: one of the computer's monitors on the tablet, touchable: tap to click, hold
  to right-click, drag, scroll with two fingers ([below](#the-screen-page)).
- **Real volume**: the dock's volume slider sits at the computer's level and drags to a level. The
  **Mixer** page has a slider per app playing sound (Teams, Spotify, a browser…), like Windows'
  volume mixer.
- **What's playing** (title and artist, from Windows' media controls) on the dock's media card,
  next to its ⏮ ⏯ ⏭.
- **The terminal's venv and folder** on the Python page, with the [shell hook](#the-terminals-venv).

Without the script (or on a Fire tablet, which has no Classic Bluetooth) everything works as
before. Teams keys send their keyboard shortcuts, and their lights are ordinary toggles.

## Set up

1. **Update the macropad to 1.7 and pair it once more.** On the computer: Bluetooth & devices →
   Macropad → Remove device, then add it again (tap the status on the tablet → Make visible).
   Windows only reads the tablet's services when pairing, so the new serial port needs a new
   pairing.
2. **Check the COM port.** Open Bluetooth & devices → More Bluetooth settings → COM Ports. There
   should be an *Outgoing* port for "Macropad 'Macropad PC link'". If there isn't, click Add →
   Outgoing → Macropad → Macropad PC link.
3. **Run the script** from the repo (or copy `tools/macropad-link.ps1` and
   `tools/macropad-commands.example.json` anywhere):

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -Verbose
   ```

   It finds the port by itself; use `-Port COM5` if it doesn't. ⚙ → Diagnostics on the tablet then
   shows `PC link: <computer> ✓`.
4. **Start it with Windows:**

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -Install
   ```

   This adds a hidden shortcut to the Startup folder. Remove it with `-Uninstall`.
5. **Teams** (new Teams): Settings → Privacy → **Third-party app API** → turn on *Manage API*.
   Join a meeting (a test call works) and press Mute on the macropad. Teams asks to allow
   "Macropad": click **Allow**. Teams remembers it; the token is in
   `%APPDATA%\Macropad\teams-token.txt`.

   **No "Third-party app API" in Teams' settings** (organisations can turn it off): the script
   then reads Teams' meeting window instead (UI Automation, like a screen reader). Mute, camera,
   raise hand and leave work this way, and their lights follow Teams; blur and reactions need the
   API. If a meeting isn't recognised, join one and run
   `macropad-link.ps1 -TeamsDump`: it lists Teams' buttons, so the names it looks for can be
   adjusted.
6. **Outlook**: classic Outlook just needs to be open. The script never starts it. The new
   Outlook has no calendar access for scripts; the clock then keeps using Home Assistant's
   calendar (saved value `ha_calendar`), if set.

## Pages for apps

Long-press a tab → **Show for apps…** and list process names, separated by commas. The dialog
shows the app in front right now as a hint. Defaults:

| Page | Apps |
|---|---|
| Teams | `ms-teams, teams` |
| Outlook | `outlook, olk` |
| Citrix | `wfica32, cdviewer` |
| VS Code / Chrome / Edge / Excel / Word | `code` / `chrome` / `msedge` / `excel` / `winword` |

The page changes only when the app in front changes, so a tab you pick yourself stays until you
switch apps. It doesn't change while editing or recording. Turn it off in ⚙ → Connection →
**Follow the computer's app**.

A page can also stay put while it shows, whatever app comes to the front: long-press its tab →
**Stay on this page** (again → **Let apps change the page**). The **Python** page stays by default,
so moving between the terminal, the editor and the browser during a class keeps it up.

## Outlook commands

`pc:outlook <command>` keys (the Outlook page) use classic Outlook's object model, like the idle
clock's calendar, instead of shortcuts:

| Command | Does |
|---|---|
| `open`, `mail`, `calendar` | Outlook's main window in front (on the inbox / calendar) |
| `new-mail`, `new-meeting` | A new message / meeting with attendees, in front |
| `reply`, `reply-all`, `forward` | On the message open in front, else the selected one; the reply opens in front |
| `send` | Sends the message being written (its own window, or a reply in the reading pane) |
| `mark-read`, `mark-unread`, `flag`, `delete` | On the open message, else every selected message (flag toggles) |
| `archive` | Moves them to the mailbox's *Archive* / *Archivo* folder |

They work whatever Outlook's language and whichever window has the keyboard. Without the PC link,
or while classic Outlook is closed (new Outlook has no object model), the keys send English
Outlook's shortcuts instead, focusing Outlook with Win+2 first where needed. *Search* brings
Outlook up and presses F3 (Search in every language). If Outlook asks whether to allow a program
to send mail, that's its security check for out-of-date antivirus; your IT settings decide it.

## The computer's clipboard

The script sends each text you copy on the computer (up to 20,000 characters). The tablet keeps
the last 20, in memory only, newest first. They are lost when the app stops, never saved or
exported.

- The **PC clipboard** page has keys for the last 12 copies, each showing its text. A key
  **types** its copy, so it also reaches a Citrix / remote session where the shared clipboard is
  turned off. Long texts show *Typing N characters… tap here to stop*.
- In any key, `text:{pcclipboard}` types the latest copy and `{pcclipboard:3}` the third latest.
  A label can show one with `{pcclipboard:3|—}` ([live labels](macro-language.md#live-labels)).
- **Private copies stay on the computer**: password managers mark what they copy, and so do
  Chrome and Edge for incognito / InPrivate windows. The script skips those, like Windows'
  clipboard history (Win+V) does. Start it with `-NoClipboard` to send nothing at all (also with
  `-Install`).

## Switcher and Mixer

**Switcher** lists the open windows as Alt+Tab does (on the current virtual desktop), grouped by
app in a fixed order so a window keeps its place. The window in front is lit. A tap brings a
window to the front (restoring it if minimised). Windows of programs running as administrator
show without an icon.

**Mixer** shows the speakers and every app with a sound session, each with its level. Drag a
slider to set it; − / + move it 5%; the speaker icon mutes. A browser's tabs share one slider.

Both pages are page types: ⚙ → **Add window switcher page** / **Add volume mixer page** add
another one.

## The Screen page

The Screen page shows one of the computer's monitors, and touching it works the mouse there:

| On the tablet | On the computer |
|---|---|
| Tap | Left click (two quick taps: double click) |
| Hold | Right click |
| Drag | Drag with the left button down (select text, move a window) |
| Two fingers up / down | Scroll |
| Spread / pinch two fingers | Zoom in / out (keep them down to move around); **Fit** shows all of it again |
| **Keyboard** chip | The tablet's keyboard; what you type goes to the computer (Enter, Backspace too) |

With more than one monitor, chips at the top choose which (numbered from the left; *main* is
Windows' main display); the tablet remembers the choice. A ring shows where the mouse pointer is.

It is a live view, not a video: Bluetooth carries roughly 100–250 KB/s. The computer sends only
the parts of the screen that changed, as JPEG, scaled to fit 1600 × 1000, and the next picture
only once the tablet has the last one. A whole new picture (a page scrolled, a window moved)
takes a second or two; typing or a cursor blinking shows almost at once. The top right shows the
pictures and kilobytes per second. The computer only captures the screen while the page shows and
the tablet isn't dimmed. Zoomed in, the computer sends just that part, at its real sharpness (the
picture so far shows scaled until it arrives).

The page stays put when apps come to the front (clicking in it changes the app in front).
Limits: windows of programs running as administrator don't take clicks from the script (Windows
keeps other programs out of them) unless the script runs as administrator too; screens a program
protects from capture (some Citrix sessions with *App Protection*, DRM video) show black; the
lock screen isn't captured.

## Typing a file (`pc:typefile`)

For a Citrix session that won't take the clipboard or files: select a text file in File Explorer
on the computer (a `.py`, `.sql`, `.txt`…), switch to where it should go, and tap **Type selected
file** (PC clipboard page). The tablet shows its name, lines and characters and asks first; then
it types it line by line into the window in front, with the bar to stop it that long texts show.

- The Explorer window used last counts, also when another window is in front by then.
- Text files up to 200 KB, UTF-8 or Windows' own code page; not binary files.
- Spaces, tabs, blank lines and braces go as they are. Editors that indent new lines by
  themselves (VS Code, IDLE…) add their indent to the file's: type into Notepad, or an editor
  with auto-indent off.
- Characters the computer's keyboard layout can't type are skipped (a message names them).

## The Presenter page (PowerPoint)

With PowerPoint open on the computer (installed there, not inside Citrix), the **Presenter** page
shows:

- the current slide's **speaker notes**, big (A− / A+ change their size), following the show
  however it moves on: the tablet, the keyboard, a clicker;
- **the slide and the next one**, as pictures, with "7 / 24" and the slide's title; tap that (or
  *Slides*) for every slide, and tap one to go there;
- the **time since the show started** (tap it to start again from 0) and the clock;
- **◀ ▶** (the tablet's volume buttons too: up = next), **Black** / **White** screen, **Start /
  End show**;
- **the laser pointer**: drag a finger on the slide and PowerPoint's laser follows it.

When a slide show starts, the tablet wakes and shows this page; it stays during the show (also
while you switch to another app for a demo) and the page you had comes back when the show ends.
Outside a show it shows the notes of the slide selected in PowerPoint, to rehearse.

Without the PC link, or with PowerPoint inside Citrix, the buttons send the slide show's keys
(→ ← B W F5 Esc) instead; the notes need the script to reach PowerPoint.

**Commands for a demo.** A line of the notes starting with `>` shows as a key: tapping it brings
the demo's window to the front and types the line, then Enter (`>>`: without Enter).

```text
Creamos el entorno y lo activamos:
> python -m venv .venv
> .venv\Scripts\activate
Instalamos lo que pide el proyecto:
> pip install -r requirements.txt
```

The demo's window is the last app you had in front other than PowerPoint (a terminal, VS Code…);
*Types in* at the bottom shows it and picks another. The command is typed as written, line by
line like any text (so it reaches Citrix too), with what PowerPoint's AutoCorrect changed in the
notes undone: curly quotes become straight, a dash before an option `--`, `…` three dots.

## Recording the computer's keyboard and mouse (experimental)

Off by default, and it takes two switches: ⚙ → Connection → **Record the computer's keyboard and
mouse** on the tablet, and the script started with `-AllowRecording` (also with `-Install`). Then,
while the tablet records a macro, what you do on the computer itself is recorded too:

- **Keyboard**: letters as `text:` steps (one per burst of typing, with accents and AltGr
  characters as typed), shortcuts as combos (`ctrl+s`, `win+e`), and Esc, arrows, F-keys and the
  like as keys.
- **Mouse**: clicks, right clicks and double clicks as `pc:click`, drags as `pc:drag`, wheel turns
  as `pc:scroll` ([steps](macro-language.md#pc-steps)). Not the pointer's movement.

A click is kept as a point in **its app's window** (a menu or a dialog counts as its app's), so it
still lands when the window has moved or is on another monitor. When Windows knows the name of
what was clicked (a button, a menu item, a tab: what screen readers read) it is kept too:
`pc:click outlook 412,88 | Send`. Replaying, the script brings the app to the front, looks for
that button where it is now (near the recorded point, for up to 3 s while the window fills in) and
clicks it; if it isn't there, it clicks the recorded point. Inside Citrix or a remote desktop
Windows only sees a picture, so those clicks are points: they need the window the same size as
when recorded. Clicks on the taskbar, the desktop and the Start menu are points on the screen
(`pc:click screen …`).

- It runs only while the tablet records, stops after 5 minutes, when the computer locks, and when
  the link drops. The script's log says when it starts and stops.
- The tablet's own keys aren't recorded twice: it tells the script while they go out. Its
  Trackpad page *is* recorded (as the computer's mouse); the Screen page's taps aren't.
- **To watch the keyboard and mouse the script hooks them like a keylogger does.** Security
  software on a work computer (Defender for Endpoint, CrowdStrike, …) may flag that and alert IT.
  Don't use it where that is a problem, and don't type passwords while recording.

## The terminal's venv

The Python page's *Which Python* key shows the active venv, its Python version and the folder of
the terminal you used last (`.venv · 3.12.1` / `shop`), or *No venv*. Install the shell hook once:

```powershell
powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -InstallShellHook
```

It adds a few lines to your PowerShell profile (Windows PowerShell and, if installed, PowerShell
7), so every prompt writes the terminal's folder and `VIRTUAL_ENV` (or conda's environment) to
`%APPDATA%\Macropad\shell.txt`. It works in Windows Terminal, the PowerShell window and VS Code's
terminal; not in cmd. Remove it with `-UninstallShellHook`.

PowerShell only runs profiles when scripts are allowed: if a new terminal says *running scripts
is disabled*, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` (if your organisation
allows it). Any label can show these values: `{venv}`, `{folder}`, `{cwd}`.

## Commands (`pc:run`)

The first run copies `tools/macropad-commands.example.json` to
`%APPDATA%\Macropad\commands.json`. Each entry is a name and a command line, run hidden with
`cmd /c`:

```json
{
  "lock": "rundll32.exe user32.dll,LockWorkStation",
  "downloads": "explorer.exe shell:Downloads",
  "standup-notes": "notepad.exe C:\\Users\\me\\notes\\standup.txt"
}
```

A key with `pc:run lock` runs it. The file is read on every run, so changes apply at once.
Nothing else can be started from the tablet: a name that isn't in the file is refused.

## Updating the script

The app carries the script. When the computer runs an older copy (a lower `$ScriptVersion`, or
the same one with other contents), the tablet offers to update it; after you tap **Update** it
goes over Bluetooth, the computer checks it arrived whole and is valid PowerShell, keeps the old
one as `macropad-link.ps1.bak` beside it, and restarts the script the way it was started (hidden
from the Startup shortcut stays hidden). The link drops for a few seconds and comes back. An older
copy is never offered; ⚙ → Connection → *PC link script* shows the computer's version and updates
by hand. Start the script with `-NoUpdate` (also with `-Install`) to refuse updates.

Scripts from before 1.8 can't update themselves: copy `tools/macropad-link.ps1` to the computer
once more.

## Troubleshooting

- The script's log is in `%APPDATA%\Macropad\link.log`; use `-Verbose` to log every message too.
- *"No outgoing Bluetooth COM port"*: see steps 1–2. The script looks for a paired Bluetooth
  device whose name contains `Macropad`; use `-Device "<name>"` if you renamed it (⚙ →
  Connection → Bluetooth name).
- The port opens but nothing happens: check that ⚙ → Diagnostics → Connection is *Classic
  Bluetooth*. The PC link needs the Classic keyboard.
- Teams keys still jump to Teams: the script isn't connected to Teams (Diagnostics shows
  `Teams —`). Check step 5 and that new Teams is running.
- Only one copy runs at a time. A second one says so and exits.

## Protocol

One JSON object per line, UTF-8; `"t"` names the message. Both sides ignore messages and fields
they don't know, so the app and the script can be updated separately.

| Direction | Message |
|---|---|
| tablet → PC | `{"t":"hello","v":1,"app":"1.7.0"}`: sent when the port opens; the script answers with everything below |
| tablet → PC | `{"t":"teams","do":"mute","id":7}`: `mute`, `camera`, `hand`, `blur`, `leave`, or `react` with `"param":"like"` |
| tablet → PC | `{"t":"outlook","do":"reply","id":11}`: a classic Outlook command (see [Outlook commands](#outlook-commands)) |
| tablet → PC | `{"t":"run","name":"lock","id":8}` |
| tablet → PC | `{"t":"focus","window":66800,"id":9}`: brings that window (`windows` list) to the front |
| tablet → PC | `{"t":"volume","level":40,"id":10}`: the speakers; with `"app":"spotify"` that app; `"mute":true` mutes |
| tablet → PC | `{"t":"screen","monitor":1,"w":1600,"h":1000,"view":3}`: mirror that monitor, scaled to fit; `"vx","vy","vw"` (0–1) just that part (zoom); `{"t":"screen","stop":true}` stops |
| tablet → PC | `{"t":"join","id":12}`: open the next meeting's online meeting |
| tablet → PC | `{"t":"activate","app":"excel","id":13}` (`pc:focus`), `{"t":"wait","for":"save as","id":14}` (`pc:wait`): answered with a result once done, or failed after 10 s |
| tablet → PC | `{"t":"screen-ack","seq":4}`: picture 4 arrived; the script sends the next one only after this |
| tablet → PC | `{"t":"pointer","monitor":1,"x":0.42,"y":0.17,"do":"tap"}`: `tap`, `right`, `down` / `move` / `up` (a drag), `scroll` with `"amount"` (notches, positive = up); x, y 0–1 across the monitor |
| tablet → PC | `{"t":"ping"}`: every 30 s; the script drops the port after 90 s of silence |
| PC → tablet | `{"t":"hello","v":1,"host":"LAPTOP","teams":true,"outlook":true,"script":"1.8.0","sha256":"…","updates":true}`: `script` / `sha256` the script's version and its file's SHA-256; `updates` false with `-NoUpdate` |
| tablet → PC | `{"t":"typefile","id":18}`: the file selected in File Explorer; answered with `{"t":"file","id":18,"name":"main.py","text":"…"}`, or a failed result |
| tablet → PC | `{"t":"update","version":"1.8.0","sha256":"…","ps1":"<base64>","id":17}`: replace the script with this one; answered, then the script restarts |
| PC → tablet | `{"t":"app","exe":"ms-teams","title":"…"}`: when the app in front changes |
| PC → tablet | `{"t":"teams","meeting":true,"muted":false,"camera":true,"hand":false,"blur":false}`, or `{"t":"teams"}` while Teams can't be reached |
| PC → tablet | `{"t":"meeting","title":"Standup","start":"2026-10-29T10:00:00-03:00","end":"…","join":true}`, or `{"t":"meeting"}` when there is none today or tomorrow; `join`: it has an online meeting link (which stays on the computer) |
| PC → tablet | `{"t":"result","id":7,"ok":false,"error":"…"}`: the tablet shows the error |
| PC → tablet | `{"t":"clip","text":"…"}`: a text copied on the computer |
| PC → tablet | `{"t":"windows","front":66800,"list":[{"id":66800,"exe":"winword","app":"Microsoft Word","title":"…"}]}`: when the windows or the one in front change |
| PC → tablet | `{"t":"icon","exe":"winword","png":"<base64>"}`: an app's icon, once per connection, before the first message that lists the app |
| PC → tablet | `{"t":"volume","level":35,"muted":false,"apps":[{"exe":"spotify","app":"Spotify","level":80,"muted":false}]}`: when a level changes |
| PC → tablet | `{"t":"media","title":"…","artist":"…","playing":true}`, or `{"t":"media"}` when nothing plays |
| PC → tablet | `{"t":"shell","cwd":"E:\\shop","venv":"E:\\shop\\.venv","python":"3.12.1"}`, or `{"t":"shell"}` once that terminal is closed |
| PC → tablet | `{"t":"monitors","list":[{"n":1,"w":1920,"h":1080,"primary":true}]}`: numbered from the left |
| PC → tablet | `{"t":"lock","locked":true}`: the computer is locked |
| PC → tablet | `{"t":"mail","from":"…","subject":"…","count":2}`: new mail in classic Outlook |
| PC → tablet | `{"t":"recorded","step":"ctrl+s"}`: a step from the computer's keyboard or mouse while recording (`pc:click outlook 412,88 \| Send`); hello says `"capture":true` when the script allows it |
| tablet → PC | `{"t":"capture","on":true}` / `"on":false`: record the computer's keyboard and mouse; `{"t":"capture","pause":true}` / `false` around the tablet's own keys |
| tablet → PC | `{"t":"click","do":"left","app":"outlook","x":412,"y":88,"name":"Send","id":15}` (`pc:click`; `do` also `right`, `double`, `drag` with `x2`, `y2`, `scroll` with `amount`; `app` `screen` for screen points): answered once done, like `activate` |
| PC → tablet | `{"t":"keyboard","klid":"0000080A"}`: the keyboard layout of the window in front |
| PC → tablet | `{"t":"slides","file":"Clase.pptx","show":true,"state":"running","slide":7,"count":24,"title":"…","notes":"…","v":2}`: PowerPoint's show (`state` also `black`, `white`, `paused`, `done`) or, with `"show":false`, the slide being edited; `{"t":"slides"}` while PowerPoint is closed |
| PC → tablet | `{"t":"slide-image","file":"Clase.pptx","n":7,"v":2,"jpg":"<base64>"}`: a slide's picture, once per `v` (a new show or presentation starts a new one) |
| tablet → PC | `{"t":"slides","do":"next","id":16}`: `next`, `prev`, `goto` (`"n"`), `black` / `white` (on and off), `start`, `end`, `thumbs` (every slide's picture); `{"t":"slides","do":"point","x":0.5,"y":0.3}` / `point-end`: the laser pointer, unanswered |
| PC → tablet | `{"t":"badges","counts":{"outlook":3}}`: counts for the tabs of those apps |
| PC → tablet | `{"t":"frame","seq":4,"view":3,"w":1600,"h":900,"tiles":[[0,64,"<base64 JPEG>"]]}`: changed parts of the picture, drawn at x, y, for request `view`; a picture can take several lines, the last with `"end":true` and the pointer `"cx","cy"` (0–1 of the part shown, or -1 when outside it) |

The serial port is the standard Serial Port Profile (UUID `00001101-0000-1000-8000-00805F9B34FB`),
named "Macropad PC link". Any program that can open a COM port can speak this protocol.
