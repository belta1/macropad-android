# Changelog

## Unreleased (1.2)

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
