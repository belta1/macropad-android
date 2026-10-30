---
updated: 2026-10-30
covers: [architecture]
status: current
---

# Architecture

## Overview

A single-module Android app (`:app`) with no runtime dependencies beyond the Android SDK (views
built in code, no Compose/XML layouts, no DI, no coroutines library). The tablet **is** the
keyboard: the computer needs nothing installed. An optional Windows companion,
`tools/macropad-link.ps1`, adds a second channel (the **PC link**) for things HID can't do.

## Components

- **MacropadService** (`MacropadService.kt`) — foreground service; owns `HidPeripheral` and
  `PcLink` so connections survive the activity being recreated. Activity binds to it.
- **MainActivity** (`MainActivity.kt`, ~2.2k lines) — the whole screen: tabs, key grid, sidebar,
  dock, idle clock, PIN, volume buttons; turns key presses into `Step`s and routes them
  (HID / PC link / network / tablet). Implements `MacropadHost` for `ui/Dialogs.kt`.
- **hid/** — `HidPeripheral` (BLE GATT server + advertising + shared send engine) with
  `ClassicHidLink` as the alternate transport; report descriptor in `HidReports`.
- **model/** — layout data, macro parser, persistence, settings, secrets. Mostly pure Kotlin →
  unit-tested.
- **ui/** — custom views and dialogs; `Ui` holds theme + dp helpers.
- **pc/** — `PcLink` (RFCOMM server socket), `PcProtocol` (JSON line codec + `PcStatus` reducer),
  `LinkScript` (bundled script for self-update).
- **net/** — blocking HTTP helpers (Home Assistant REST + WebSocket, Wake-on-LAN, GitHub updater).
- **link script** (`tools/macropad-link.ps1`) — PowerShell 5.1 loop: opens the COM port, polls
  Windows (Teams API, Outlook COM, Win32/Core Audio, WinRT media, screen capture, PowerPoint COM),
  sends changes, executes tablet requests.

## Data / control flow

```
touch key → KeyViews → MainActivity.pressKey/runAction → Macro.parse(action) → List<Step>
   Combo/Text/Media/Click/Scroll/Delay → HidPeripheral (BLE or Classic) → computer (HID)
   Pc (pc:teams|outlook|focus|wait|click|run…) → PcLink → SPP/COM → macropad-link.ps1 → "result" back
       (no link → MainActivity.pcFallback: shortcut equivalents where one exists)
   Http/Wol/Ha → net/NetActions (background thread)
   Page/Tablet → handled in MainActivity (page switch, dim, dictate)

macropad-link.ps1 ──JSON lines──▶ PcLink ─PcProtocol.apply─▶ PcStatus ─▶ MainActivity.onPcStatus
   (live key lights, page follows front app, live labels, Switcher/Mixer/Screen/Presenter pages)
```

## Boundaries & external dependencies

- **Computer via HID** — standard keyboard/mouse/consumer reports; no host software.
- **PC link seam** — see [pc-protocol](modules/pc-protocol.md). App and script are versioned
  independently; unknown messages/fields are ignored on both sides.
- **Home Assistant** (REST + WebSocket) using `ha_url` / `ha_token` saved values ([net](modules/net.md)).
- **GitHub Releases** for in-app updates (`net/Updater.kt`).
- **Android SpeechRecognizer** (Google) for `tablet:dictate`.

## Key decisions & constraints

- **BLE HID implemented by hand** because Fire OS disables the Classic HID Device profile;
  Classic is used where available (Samsung) for self-reconnect. Fire OS workarounds are gated by
  `hid/DeviceQuirks.kt` — don't apply them to other tablets.
- **PC link needs Classic Bluetooth** (SPP); Fire tablets don't get it. Everything must still work
  without the link (fallbacks to shortcuts).
- **The script ships inside the APK** (Gradle `copyLinkScript` → assets) so the tablet can push
  updates to the computer; versioned by `$ScriptVersion`.
- **Layouts are user data**: never reset them; changes to defaults reach existing users only via
  one-shot upgrades in `MacroStore.load()` ([layout-and-storage](modules/layout-and-storage.md)).
- **Secrets never enter the layout/backup** — `SecretStore` (Android Keystore AES-GCM).
