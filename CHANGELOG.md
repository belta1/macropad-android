# Changelog

## Unreleased

- **Python page** for classes and demos: create / activate / deactivate a `.venv`, install from
  `requirements.txt`, freeze, pip list / install / upgrade, run a script, the Python shell, clear,
  Ctrl+C. Hold *Create venv* to also activate it, hold *Activate venv* to first allow scripts in
  PowerShell. It stays up whatever app is in front.
- **Type selected file** (PC clipboard page, `pc:typefile`): the text file selected in File
  Explorer, typed line by line into the window in front — into Citrix without its clipboard or
  drives. It asks first, showing the file's name and size. It takes the place of that page's Paste.
- **Tabs in order of use**: the work ones first (Desktop, Citrix, Teams, Outlook, Switcher, PC
  clipboard, Python, Presenter…), once. The **Excel** and **Word** pages go (their shortcuts are
  the apps' own), unless you changed their keys. The app now counts how much each page is used
  (keys pressed, minutes shown; lately counts most) and *Sort tabs by use* (pages menu) orders
  them by it, naming the rarely used ones.
- A page can **stay** while it shows, whatever app comes to the front (long-press a tab → Stay on
  this page).
- **Home** page: Home Assistant's lights, switches, covers, media, scenes, scripts and automations
  by area, over Wi-Fi (`ha_url` / `ha_token`). It starts with the main devices (the ones exposed to
  a voice assistant); tap to toggle, hold to pin, hide or run an automation now; filter by kind or
  search. Added once to existing layouts.

More through the PC link ([docs/pc-link.md](docs/pc-link.md)); update
`tools/macropad-link.ps1` on the computer too.

- **PC clipboard** page: your last copies on the computer, each key showing its text and typing it
  (so it also reaches a Citrix session without a shared clipboard). `{pcclipboard}` /
  `{pcclipboard:N}` in any `text:` step. Copies a password manager or an incognito window marks
  as private are never sent; `-NoClipboard` sends none.
- **Switcher** page: the computer's open windows with their icons; tap one to bring it to the
  front, also from Citrix.
- **Screen** page: a monitor of the computer on the tablet, touchable (tap = click, hold = right
  click, drag, two fingers scroll); choose the monitor at the top. A live view at a few pictures
  per second: only the parts that changed are sent.
- **Real volume**: with the PC link the dock's volume slider sits at the computer's level and drags
  to a level. **Mixer** page: a slider per app playing sound.
- **Media card** in the dock: what's playing (title over artist) with ⏮ ⏯ ⏭; tapping the text
  plays / pauses too. It replaces the dock's Previous / Play / Pause / Next keys.
- **The dock runs along the whole bottom**, under the sidebar too: desktop switching, the volume
  slider, the media card. Existing docks change once (repeated desktop keys go).
- **The terminal's venv and folder** on the Python page's *Which Python* key, with the new shell
  hook (`macropad-link.ps1 -InstallShellHook`).
- **Outlook keys work in any language**: with the PC link they are classic Outlook commands
  (`pc:outlook reply`, `new-mail`, `mark-read`, …) on the selected messages, from any window;
  Spanish Outlook's shortcuts differ from the English ones the keys sent. Without the link they
  send those shortcuts as before. Unchanged Outlook keys switch once.
- **Join meeting** key in the sidebar (added once to a free slot): shows the next Outlook meeting,
  lights from 5 minutes before, and opens its Teams / Zoom / Meet / Webex meeting (`pc:join`).
- **The tablet follows the computer's lock**: locked, it dims; unlocked, it wakes.
- **Typing follows the computer's keyboard layout** (of the window in front), so accents come out
  right without setting it.
- **Unread mail on the Outlook tab.**
- **Screen**: pinch to zoom (the computer sends that part sharp), *Fit*, and a *Keyboard* chip that
  types on the computer from the tablet's keyboard.
- **Macros that find their window**: `pc:focus excel` brings an app to the front (waiting for it
  to open), `pc:wait save as` waits for a window instead of a fixed delay; the steps after them
  wait, and if the window doesn't come the rest doesn't run.
- **Recording**: app switches on the computer are recorded as `pc:focus`; stopping shows the steps
  to reorder, delete, change pauses and try, can start by bringing the app to the front, and saves
  on that app's page.
- **New mail** from classic Outlook over the dock (tap to open Outlook) and on the idle clock.
- **Experimental, off**: recording also captures the computer's own keyboard and mouse (⚙ →
  Connection, and the script's `-AllowRecording`). Clicks become `pc:click` steps in their app's
  window, with the button's name when Windows knows it, so they find it again if it moved;
  `pc:drag` and `pc:scroll` too.
- **Presenter** page for PowerPoint: the current slide's speaker notes in big type, the slide and
  the next one, the show's time and the clock, ◀ ▶ (and the tablet's volume buttons), black /
  white screen, every slide to jump to, and PowerPoint's laser pointer following your finger on
  the slide. It comes up by itself when a slide show starts and goes back when it ends.
- **Teams keys from 1.2–1.6 that open Teams through Win+R** (missed by 1.7's upgrade) become
  Teams commands: Camera, Raise hand, Blur and Leave call answer at once, from Citrix too, and
  light from Teams. The others keep working as they are.
- **Calls bring up the Teams page** and give the page you had back when you hang up; *Leave call*
  shows the call's time (`{call}` in any label). Presenting, the Presenter page gets a Mute button.
- **Commands in the speaker notes**: a `>` line on the Presenter page is a key that brings the
  demo's window (a terminal…) to the front and types it, then Enter — curly quotes and dashes from
  PowerPoint's AutoCorrect undone.
- **The tablet updates the PC link script**: the app carries it, offers it when the computer's
  copy is older, and the script checks it, keeps a `.bak` and restarts (`-NoUpdate` refuses).
  Scripts from before this one still need copying once.
- **Live labels**: `{venv}`, `{folder}`, `{media}`, `{volume}`, `{pcclipboard:2}`, … in any key's
  label, with a fallback: `{venv|Which Python}`. See
  [docs/macro-language.md](docs/macro-language.md#live-labels).

## 1.7.0

- **PC link** ([docs/pc-link.md](docs/pc-link.md)), on tablets with Classic Bluetooth (Galaxy Tab
  S5e): the macropad adds a Bluetooth serial port next to the keyboard, which Windows shows as a
  COM port, and [tools/macropad-link.ps1](tools/macropad-link.ps1) keeps it open on the computer.
  - **Teams**: Mute, Camera, Raise hand, Blur and Leave are Teams commands (`pc:teams mute`, …)
    through Teams' third-party app API: no window jumping, and their lights follow Teams (also when
    you click in Teams). Without the link they send the shortcuts as before. Reactions:
    `pc:teams react like`. Existing Teams keys change once, if they were unchanged.
  - **Pages follow the app in front**: Teams, Outlook, Citrix, … (long-press a tab → Show for
    apps; ⚙ → Connection → Follow the computer's app).
  - **Next meeting from Outlook** (classic) on the idle clock, in place of Home Assistant's.
  - **`pc:run <name>`** runs a command you list in `%APPDATA%\Macropad\commands.json`.
  - Diagnostics shows the PC link. **Pair once more** after updating, so Windows sees the port.
- **Network steps** (the tablet uses its Wi-Fi): `http:` web requests, `wol:` Wake-on-LAN and
  `ha:` Home Assistant services, with `ha_url` / `ha_token` saved values. They work with no
  computer connected. See [docs/macro-language.md](docs/macro-language.md#network-steps).
- **Next meeting on the idle clock** from a Home Assistant calendar (saved value `ha_calendar`).
- **Updates from GitHub Releases** (⚙ → Updates): checks for a newer version (also on its own
  once a day), downloads it and hands it to Android's installer. It first checks the release is
  signed with the same key as the installed app, else explains instead of failing. After an
  update the macropad starts again by itself.
- `tools/deploy.sh fire|tab`: build a release-signed APK and install it over Wi-Fi adb, keeping
  the tablet's data.

## 1.6.0

- **Reconnects by itself, no more re-pairing.** After the tablet was out of range, the computer
  slept, the app was updated or the tablet's Bluetooth restarted, Windows kept listing the
  keyboard but never used it again until it was removed and paired once more. The app rebuilt its
  Bluetooth services after every disconnect; Windows then rediscovered them and dropped the
  keyboard. It now keeps them as they are and only advertises again: Windows comes back within
  seconds. **Pair once more after updating** (the old pairing has already lost the keyboard).
- **Dock volume slider**: three keys wide, tapping its speaker mutes, and its **−** / **+** are
  buttons (tap for one step, hold to keep going; on every slider). The separate Mute key is
  gone (once, if it was unchanged). Any slider can have a tap step: `slide:up | down | tap`;
  zoom's resets the zoom (Ctrl+0).
- **Other tablets** ([docs/devices.md](docs/devices.md)): on tablets with the Classic HID profile
  (e.g. Galaxy Tab S5e) the keyboard uses Classic Bluetooth and the tablet calls the computer
  itself (⚙ → Connection → Bluetooth: Automatic, Bluetooth LE or Classic). The Fire OS
  workarounds only apply on Fire tablets. Bluetooth name is a setting ("Fire Macropad" on Fire,
  "Macropad" elsewhere), restored when the macropad quits. Larger screens get larger keys.
  **Screen protection** shifts the screen slightly every few minutes (OLED; on by default off
  Fire). Android 10+: opening on boot or charge asks for "Display over other apps" (else a
  notification offers to open). ⚙ → Connection → Run in the background asks to leave the app
  out of battery optimisation. Android 13+: Reset pairing asks to turn Bluetooth off and on.
- Diagnostics shows the Bluetooth transport and the background setting; Bluetooth events are also
  written to `bluetooth-events.log` (see docs/bluetooth-troubleshooting.md), and
  [tools/ble_scan.py](tools/ble_scan.py) shows the tablet's address from the computer.

## 1.5.0

- **Sliders**: a key with the action `slide:volume` (or `scroll`, `zoom`, `brightness`, `tabs`,
  `undo`, or any `up | down` pair such as `slide:right | left`) is a strip you drag. Each tick of
  travel sends one step with a haptic tick, and holding at an end keeps going. Sliders work on
  pages, in the sidebar and in the dock (where they get a wider slot). The key editor has Volume,
  Scroll and Zoom slider buttons under the action. See
  [docs/macro-language.md](docs/macro-language.md#sliders).
- **Dock**: Volume − and Volume + are replaced by a volume slider and a **Mute** key. Existing
  layouts and imported backups from older versions get them once, if the old volume keys are still there.
- **Lock lights**: the status chip shows the computer's **Caps** and **Num** Lock, lit while on.
  `capslock` / `numlock` / `scrolllock` keys show ON/OFF from the computer's real state.
- **New look, graphite keycaps**: keys are drawn as keycaps on a dark case. The icon takes the
  key's color and the label and shortcut are printed on the cap. Pressing a key pushes its top
  down into the cap. Only things that send keys look like keys: tabs, Record / Edit / Settings and
  the banners are flat. New typeface: Barlow (condensed for key labels, so long names fit).
- **On/off is one signal**: toggle and lock keys have an indicator light (like Caps Lock on a
  keyboard) and fill with their color while ON; no other key is filled. The ON/OFF text is gone.
- **Status** has two lines that keep their size: the state (Connected, Waiting, Ready to pair…)
  and the computer, with the Caps / Num lights under them, always in place.
- **Not connected** banner: calmer, with what's happening and a way to the pairing steps.
- **Key editor**: a live preview of the key beside the form, and a wider dialog. Every card
  keeps a line for its shortcut, so labels in a row line up.
- **Trackpad**: a recessed pad with a ridged scroll strip; sliders are a groove with a knob.
- Accessibility: keys can be pressed with TalkBack and read their shortcut and ON/OFF state;
  app buttons have names; touch targets are at least 48 dp.

## 1.4.0

- **New look**: keys are cards with the icon top-left, the name below it and the shortcut in a
  keycap-style pill; flatter dark surfaces with a soft glow of each key's color; the selected tab is
  tinted; the sidebar's buttons are rounded tiles; the dock shows every key as an icon (no more cut
  labels).
- **Menus and dialogs**: rounded sheets with icon rows, the current value of each setting shown on
  the right, chevrons on entries that open more options, a check on the chosen option, a filled main
  button and no all-caps buttons. Text fields are filled and outlined while typing, with labels in
  the key editor.
- Backup and restore: its options were hidden under the note about the auto-saved file; they show again.

## 1.3.0

- **Key editor**: Save, Cancel and Delete moved to a header above the form, so the on-screen
  keyboard can no longer hide them.
- **Sidebar on every page** (right or left: ⚙ → Sidebar): connection status, Record, Edit, Settings and the ⇄
  switch button (moved from the top bar, which now holds only the tabs), plus four action keys:
  **Away**, **Lock PC**, **Work Citrix** and a free slot (editable in edit mode).
- **Typing text is safer**: smart quotes, long dashes and non-breaking spaces type as plain ASCII,
  Windows line endings give one Enter, Caps Lock on the computer is compensated, texts of 60+
  characters show a **tap to stop** banner over the dock, and characters the layout can't type are
  listed and skipped.
- **Work Citrix** opens the Citrix portal in Chrome and fills in user and password. The address,
  user and password are **saved values** (⚙ → Saved values), used as `{secret:work_url}`,
  `{secret:work_user}`, `{secret:work_password}`: encrypted with an Android Keystore key, kept out of
  the layout, backups and exports. A key whose values aren't set does nothing and says which are missing.
- **Desktop switching on every page**: the dock now holds Prev desktop (Win+Ctrl+←), Task view
  (Win+Tab) and Next desktop (Win+Ctrl+→) next to the media keys. Existing layouts get them once,
  in empty dock slots.
- **Cleaner dock**: media keys show only a larger icon; a divider separates them from the desktop
  keys. Dock keys stay editable in edit mode.
- **App icons on the tabs**: Windows, Chrome, VS Code and Office-style tiles; the selected tab also
  shows its name. Long-press a tab to rename it or pick another icon (⚙ → Pages also has it).
- **Monitors page**: snap left/right, maximize, minimize, snap layouts, move the window to the
  left/right screen, show desktop, minimize others, previous window, new/close desktop, Task
  manager, jump to the remote desktop, and **Away**.
- **Away** key: locks the PC and dims the tablet; hold it to mute the Teams call first. New macro
  step `tablet:dim`.
- **Outlook page**: open Outlook, new mail, new meeting, mail, calendar and search focus Outlook
  first (Win+2) so they work from a Citrix window; reply, reply all, forward, send, read/unread,
  flag, archive and delete act on the selected mail.
- **Phrases page**: Spanish replies, signature, date, time and the tablet's clipboard, typed with
  the computer's layout (works inside Citrix too). No trailing Enter, so nothing is sent by accident.
- **Desktop page**: its desktop-switching row (now in the dock) becomes Snip, Clipboard history,
  Emoji, Copilot (Win+3) and Lock PC. Existing layouts change once, only keys still at their defaults.

## 1.2.0

- **Toggle keys fixed**: they are now held while touched like other keys (Teams missed the
  instant press/release before) and only flip their ON/OFF display. ⚙ → **Reset ON/OFF keys**
  clears the display when it drifts from the app's real state.
- **Key timing** (⚙): tapped keys in macros and typed text press modifiers first and are held
  briefly; a slower *Remote session (Citrix / RDP)* preset.
- **English (US International)** keyboard layout for typed text (dead-key quotes and accents).
- **Computers** (⚙ → Computers…): tap a computer to rename it and set its own keyboard layout,
  key timing, pages shown and start page; they apply whenever it connects. The global layout and
  timing become defaults.
- **⇄ switch button** next to the status chip (2+ computers): hands the macropad to the other
  computer in one tap.
- Export/backup files (format version 2) also carry the computer settings; version 1 files still import.
- **Teams keys work from any window** (e.g. while a Citrix session has focus): Mute uses Windows 11's
  call mute (Win+Alt+K); the others focus Teams (Win+8), send the shortcut and Alt+Tab back.
  Existing layouts are upgraded once (only keys still set to the old defaults).
- **Citrix page**: jump between the remote desktop and local apps (Win+number), Citrix session
  hotkeys (Ctrl+Alt+Del, Task manager, Start, switch apps), window/full screen. See
  [docs/citrix.md](docs/citrix.md).
- **Not connected banner** in place of the media dock while no computer is connected.
- **Long-press actions**: a key can have a second action that runs when it is held (0.45 s), e.g.
  tap = toggle mute, hold = push to talk. Shown as "+hold" in the hint.
- **Text variables**: `{date}`, `{time}`, `{date:dd/MM/yyyy}` (any pattern) and `{clipboard}` (the
  tablet's clipboard) in `text:` steps.
- **`page:` steps** (`page:next`, `page:prev`, `page:Teams`) switch the tablet's page — for keys or
  the tablet volume buttons. (Swiping isn't used: keys fire on touch, so a swipe would press one.)
- **Numpad page** (main-row digits, so Num Lock doesn't matter).
- **Idle clock**: after 5 minutes without a touch (⚙ → Dim when idle) the screen dims to a drifting
  clock; the touch that wakes it never presses a key.
- **PIN lock** (⚙ → PIN lock): editing, recording, settings and computer changes ask for a PIN;
  using the keys never does. Asked again after the screen dims.
- Macro hints for "focus app + shortcut" keys (`Win+8 → Ctrl+2`, `Ctrl+Shift+O ↩`); the selected
  tab is kept in view.

## 1.1.0

- **Foreground service**: the Bluetooth keyboard now lives in `MacropadService` with an ongoing
  notification (Quit action), so the connection survives the screen being closed or recreated.
- **Diagnostics** screen: connection state, pairing, Classic Bluetooth / audio-profile workarounds,
  permission check and recent Bluetooth events.
- **Keyboard layouts** for typed text: English (US), Spanish (Latin America), Spanish (Spain),
  including accented letters via dead keys.
- **Trackpad page** and mouse support (new mouse report): pointer, clicks, scrolling, dragging;
  `click:` and `scroll:` macro steps. *Re-pair computers once to pick up the new report.*
- **Toggle keys** with ON/OFF state; Teams mute, camera, raise hand and blur use them.
- **Computers**: remembers served computers; choose one or "any"; forget a computer.
- **Backup**: layout auto-saved to a JSON file; export/import through the file picker.
- **Startup**: open on boot and when the charger is plugged in (both optional).
- Pages can be reordered; new page types (keys / trackpad).
- Code split into `hid`, `model` and `ui` packages; unit tests; verbose GATT logging only in debug
  builds; signed release builds and GitHub Actions CI.

## 1.0.0

- BLE HID-over-GATT keyboard + media keys for Fire OS / Android 9+.
- Pages for Desktop, VS Code, Chrome, Excel, Word, Teams and Custom; media dock; tablet volume
  buttons as keys; key editor with colors and icons; macro recorder.
- Fire OS / Windows workarounds: audio profiles disabled for the host, Classic Bluetooth hidden,
  GATT server rebuilt after disconnects, pairing-code prompt, reset pairing.
