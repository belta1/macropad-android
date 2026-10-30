---
updated: 2026-10-30
covers: [bluetooth, hid, ble, keyboard]
status: current
---

# Bluetooth HID (`hid/`)

Makes the tablet a keyboard + mouse + media-key device, and turns `Step`s into HID reports.

## Key files & entry points

- `app/src/main/java/dev/belta/macropad/hid/HidPeripheral.kt` — the core (~1k lines): `State`
  (`UNAVAILABLE, OFF, STARTING, ADVERTISING, CONNECTED`), `Transport` (`AUTO`, `BLE`, `CLASSIC`),
  BLE GATT server + advertising, bonding/reconnect, Fire OS workarounds, and the shared **send
  engine**: `press/release` (held keys), `run(steps, onDone)` (macros, typing, delays),
  `moveMouse`/`scroll`/`setMouseButton` (batched mouse). `Listener` reports state to the UI.
- `hid/ClassicHidLink.kt` — Android `BluetoothHidDevice` transport (Samsung etc.); tablet can
  reconnect by itself.
- `hid/HidReports.kt` — report descriptor + report builders (keyboard, consumer, mouse).
- `hid/HidKeys.kt` — modifier bits and key-name → usage map used by the macro parser.
- `hid/KeyboardLayout.kt` — text → `Stroke`s for US, US-International, ES-LatAm, ES-Spain;
  `keyboardLayoutForWindowsId(klid)` maps the PC link's KLID.
- `hid/KeyTiming.kt` — hold/gap presets (per computer; slower for Citrix).
- `hid/DeviceQuirks.kt` — `isFire` etc.; gates Fire-only workarounds.
- Tests: `app/src/test/java/dev/belta/macropad/hid/HidReportsTest.kt`, `KeyboardLayoutTest.kt`.

## How it works

`MacropadService` creates `HidPeripheral`; `start` picks Classic when the setting is CLASSIC, or
AUTO on a non-Fire tablet with the profile, else BLE (Classic failure under AUTO falls back to BLE).
Sending runs on a dedicated handler thread (`sender`); both transports share it and differ only in
how a report is written. Text is typed through the active `KeyboardLayout` with `KeyTiming` gaps.

## Gotchas & constraints

- Changing the **report descriptor** forces users to remove and re-pair the device on each
  computer (happened in 1.0 → mouse). Avoid unless necessary; call it out in the CHANGELOG.
- Fire OS: treats the paired PC as headphones and accepts Classic connections that make Windows
  drop the keyboard — fixed via secure settings (`WRITE_SECURE_SETTINGS`, granted by adb). Only on
  Fire (`DeviceQuirks`). Background: `docs/bluetooth-troubleshooting.md`, `docs/devices.md`.
- Opening the tablet's Bluetooth settings re-learns the PC as Classic — a known user footgun.
- Detailed GATT logging only in debug builds (`BuildConfig.DEBUG`); tag `HidPeripheral`.
- Combo keys in macros use **US key positions** regardless of layout; only `text:` is layout-aware.

## Related

- [app-shell](app-shell.md) · [macro-language](macro-language.md) · [tooling](tooling.md) (`ble_scan.py`)
