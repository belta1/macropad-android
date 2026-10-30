---
updated: 2026-10-30
covers: [activity, service, lifecycle, key-press, routing]
status: current
---

# App shell (`MainActivity`, `MacropadService`, `BootReceiver`)

The Android entry points: the one screen, the foreground service that owns the connections, and
start-on-boot.

## Key files & entry points

- `app/src/main/java/dev/belta/macropad/MainActivity.kt` (~2.2k lines, sections marked
  `// ------ <name>`): lifecycle · idle clock · PIN · PC link (`onPcStatus`, follow app/call/slides,
  script update, `sendPc`, `pcFallback`, dictation) · tablet volume buttons · UI construction
  (`buildUi`, `renderTabs`, `renderPage`, `buildGrid`, `buildTrackpad`, `buildSwitcher`,
  `buildMixer`, `buildPresenter`, `buildScreen`, `keyView`, live keys) · **pressing**
  (`pressKey`, `releaseKey`, `runAction`, `runNetwork`, `runSteps`, `runInOrder`, `awaitPc`,
  `goToPage`) · status · screen protection · `MacropadHost` impl · editing & recording.
- `MacropadService.kt` — foreground service (`connectedDevice` type) owning `hid: HidPeripheral`,
  `pc: PcLink`, `profiles`; fans HID events out to listeners; notification with Quit.
- `BootReceiver.kt` — `MY_PACKAGE_REPLACED` (always) / `BOOT_COMPLETED` (if `AppSettings.openOnBoot`)
  → `MacropadService.start` + `MainActivity.bringToFront`.
- `app/src/main/AndroidManifest.xml` — permissions with comments explaining each; `singleTask`,
  `sensorLandscape`, handles config changes itself.

## How it works

Activity binds to the service and registers as listener. Key touches arrive via
`KeyViews.Listener` → `pressKey` → `runAction`, which parses the macro and routes steps (see
[macro-language](macro-language.md)). Toggle states are per session (`"page#index"` keys).
`MacropadHost` (in `ui/Dialogs.kt`) is the interface dialogs use to call back (`saveAndRender`,
`selectPage`, `editKey`, `withUnlock`…).

## Gotchas & constraints

- The activity is large and stateful; prefer adding logic to `model/`/`ui/` classes and keep
  `MainActivity` as the wiring. Search by section header rather than reading it whole.
- The waking touch on the idle clock must never press a key (`dispatchTouchEvent`).
- Connection state lives in the service — don't hold Bluetooth objects in the activity.
- `WRITE_SECURE_SETTINGS` is granted by adb, not at runtime; code must cope with it missing.

## Related

- [hid](hid.md) · [pc-link](pc-link.md) · [ui](ui.md) · [layout-and-storage](layout-and-storage.md)
