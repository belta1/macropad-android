# Changelog

## Unreleased (1.2)

- **Toggle keys fixed**: they are now held while touched like other keys (Teams missed the
  instant press/release before) and only flip their ON/OFF display. ⚙ → **Reset ON/OFF keys**
  clears the display when it drifts from the app's real state.
- **Key timing** (⚙): tapped keys in macros and typed text press modifiers first and are held
  briefly; a slower *Remote session (Citrix / RDP)* preset.
- **English (US International)** keyboard layout for typed text (dead-key quotes and accents).

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
