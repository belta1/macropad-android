---
updated: 2026-10-30
covers: [overview, navigation, hybrid]
status: current
---

# Index — Macropad for Android

An Android app that turns a tablet into a **Bluetooth keyboard + mouse macropad** for a computer
(BLE HID over GATT, or Classic HID on Samsung). Optional **PC link**: a PowerShell script on the
Windows computer talks to the app over a Bluetooth serial port (Teams/Outlook state, windows,
volume, screen mirror, PowerPoint…). **Read this first, then open the files it points to.**

## Components

| Component | Path | Toolchain | Purpose | Module page |
|-----------|------|-----------|---------|-------------|
| app | `app/` (`dev.belta.macropad`) | Gradle 8.7 wrapper · AGP 8.5 · Kotlin 1.9 · JDK 17 | the tablet app (HID, layout, UI) | [app-shell](modules/app-shell.md) + below |
| link script | `tools/macropad-link.ps1` | Windows PowerShell 5.1, no modules | PC side of the PC link | [link-script](modules/link-script.md) |
| seam | Bluetooth SPP (RFCOMM ↔ COM port) | newline-delimited JSON | the app ↔ script contract | [pc-protocol](modules/pc-protocol.md) |
| dev tools | `tools/*.py` `tools/deploy.sh` | Python 3 (no project), bash | icon generator, BLE scan, adb deploy | [tooling](modules/tooling.md) |

## Stack

- **Language / runtime:** Kotlin, JVM target 17; Android minSdk 28, target/compile 34 (`app/build.gradle.kts`).
- **Build:** `./gradlew assembleDebug` (→ `app/build/outputs/apk/debug/app-debug.apk`) · `./gradlew assembleRelease`
  (signed via git-ignored `keystore.properties`, else debug key). Windows: `gradlew.bat`. Needs `JAVA_HOME`=JDK 17 and `sdk.dir` in `local.properties`.
- **Install on a tablet:** `tools/deploy.sh fire|tab [--log] [--no-build]` (release build over Wi-Fi adb).
- **Test:** `./gradlew testDebugUnitTest` · single class: `./gradlew testDebugUnitTest --tests "dev.belta.macropad.model.MacroTest"`
- **Lint / format / typecheck:** none configured (no ktlint/detekt); `kotlin.code.style=official`. The compiler is the typecheck.
- **Logs:** `adb logcat -s HidPeripheral`; PC script logs to `%APPDATA%\Macropad\link.log`.
- **CI (ground truth):** `.github/workflows/android.yml` — `testDebugUnitTest` + `assembleDebug` on push/PR; tag `v*` → signed release APK on GitHub Releases.

## Map

| Area | Key files / dirs | Purpose | More |
|------|------------------|---------|------|
| Activity / service | `app/src/main/java/dev/belta/macropad/MainActivity.kt` `MacropadService.kt` `BootReceiver.kt` | screen, key presses, routing steps; foreground service owning Bluetooth | [app-shell](modules/app-shell.md) |
| Bluetooth HID | `…/hid/` (`HidPeripheral.kt`, `ClassicHidLink.kt`, `HidReports.kt`, `KeyboardLayout.kt`) | BLE/Classic HID transports, send engine, typing per keyboard layout | [hid](modules/hid.md) |
| Macro language | `…/model/Macro.kt` `TextVariables.kt` `LiveLabels.kt` `Dictation.kt` `NotesCommands.kt` | parse key actions into `Step`s; `{vars}`; live labels | [macro-language](modules/macro-language.md) |
| Layout & persistence | `…/model/Layout.kt` `DefaultLayout.kt` `MacroStore.kt` `AppSettings.kt` `ComputerProfiles.kt` `SecretStore.kt` `PageUsage.kt` | pages/keys model, defaults, JSON store + one-shot upgrades, settings, secrets | [layout-and-storage](modules/layout-and-storage.md) |
| UI views | `…/ui/` | key grid, editor, dialogs, trackpad, sliders, PC pages (Switcher, Mixer, Screen, Presenter), Home | [ui](modules/ui.md) |
| PC link (app side) | `…/pc/PcLink.kt` `PcProtocol.kt` `LinkScript.kt` | SPP server, protocol parse/build, script self-update | [pc-link](modules/pc-link.md) |
| Network | `…/net/` (`NetActions.kt`, `HomeAssistant.kt`, `HaSocket.kt`, `Updater.kt`) | `http:`/`wol:`/`ha:` steps, Home page, calendar, GitHub updates | [net](modules/net.md) |
| User docs | `docs/macro-language.md` `docs/pc-link.md` `docs/bluetooth-troubleshooting.md` `docs/devices.md` `docs/citrix.md` | end-user reference; keep in sync with features | [conventions](conventions.md#docs--changelog) |
| Layout backup | `backup/macropad-layout.json` | the owner's current layout (pulled from tablet) | [layout-and-storage](modules/layout-and-storage.md) |

`…` = `app/src/main/java/dev/belta/macropad`. Tests mirror packages under `app/src/test/java/dev/belta/macropad/`.

## Where to start by task

- **Add / change a macro step kind** → [recipes](recipes.md#add-a-macro-step-kind), `model/Macro.kt`, `MainActivity.runAction`
- **Add a PC link feature** (both sides) → [recipes](recipes.md#add-a-pc-link-message), [pc-protocol](modules/pc-protocol.md)
- **Change a default key/page for existing users** → [recipes](recipes.md#change-default-keys-for-existing-layouts), `model/DefaultLayout.kt`, `model/MacroStore.kt`
- **Fix a Bluetooth / pairing bug** → [hid](modules/hid.md), `docs/bluetooth-troubleshooting.md`
- **Fix a PC-side bug (Teams, Outlook, screen…)** → [link-script](modules/link-script.md)
- **Add a key icon** → [recipes](recipes.md#add-a-key-icon)
- **Ship a release** → [recipes](recipes.md#release)
- **Build & try on the tablet** → [recipes](recipes.md#build-and-install-on-a-tablet)

## Also see

- [architecture](architecture.md) · [conventions](conventions.md) · [glossary](glossary.md) · [recipes](recipes.md) · [log](log.md)
