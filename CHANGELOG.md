# Changelog

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
