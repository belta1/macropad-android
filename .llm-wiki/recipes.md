---
updated: 2026-10-30
covers: [recipes, how-to]
status: current
---

# Recipes

Commands assume a POSIX shell at the repo root (Git Bash on Windows works; else `gradlew.bat`).
`JAVA_HOME` must point at a JDK 17 and `local.properties` must have `sdk.dir=…`.

## Run tests

```bash
./gradlew testDebugUnitTest                                                   # all (what CI runs)
./gradlew testDebugUnitTest --tests "dev.belta.macropad.model.MacroTest"      # one class
./gradlew testDebugUnitTest --tests "dev.belta.macropad.model.MacroTest.parses*"   # by name pattern
```

## Build and install on a tablet

```bash
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
tools/deploy.sh fire                    # release build → Fire over Wi-Fi adb (MACROPAD_FIRE=ip:port)
tools/deploy.sh tab --log               # Galaxy Tab (MACROPAD_TAB=ip:port), then follow logcat
adb logcat -s HidPeripheral             # Bluetooth events
```

Debug and release are signed with different keys; `deploy.sh` uses release so it can update a
GitHub-installed app. Never uninstall to fix a signature mismatch (wipes layout + secrets).

## Run the PC link script (Windows)

```powershell
powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -Verbose   # foreground, logs traffic
powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -Install   # start hidden at sign-in
```

Config/logs in `%APPDATA%\Macropad\` (`commands.json`, `link.log`, …). See `docs/pc-link.md`.

## Add a macro step kind

1. `app/src/main/java/dev/belta/macropad/model/Macro.kt`: add a `Step` subclass, a prefix branch in
   `Macro.parse`, a `describe` case if it should read nicely, and document it in the header KDoc.
2. Route it in `MainActivity.runAction` / `runSteps` (HID steps go to `HidPeripheral`; tablet-side
   steps are handled before sending). Network steps → `net/NetActions.kt`.
3. If the key editor offers it, update `ui/KeyEditor.kt`.
4. Test in `app/src/test/java/dev/belta/macropad/model/MacroTest.kt`.
5. Document in `docs/macro-language.md`; add a CHANGELOG "Unreleased" line.

## Add a PC link message

Both sides, same change — see [pc-protocol](modules/pc-protocol.md).

1. **Script → tablet data:** in `tools/macropad-link.ps1` add a `Send-<Thing>` (use `Send-Changed`
   to only send changes; add its key to `$Next` in the main loop if polled) and call it from
   `Send-All`. **Tablet → script request:** add a `'<t>' { … }` case to the dispatch `switch` near
   the main loop and reply with a `result` carrying the request `id`.
2. Bump `$ScriptVersion` in the script.
3. `pc/PcProtocol.kt`: `PcMessage` subclass + `parse` branch (+ `PcStatus` field and `apply` branch),
   or a builder function for requests. Tolerate missing fields (`optX`, `text()`).
4. `pc/PcLink.kt`: expose a sender if needed; react in `MainActivity.onPcStatus` / listener.
5. Without the link: keep a fallback (`MainActivity.pcFallback`) or hide the feature.
6. Tests: `app/src/test/java/dev/belta/macropad/pc/PcProtocolTest.kt`. Docs: `docs/pc-link.md`.

## Change default keys for existing layouts

New installs get `DefaultLayout.layout()`; existing users keep their saved layout.

1. Change the defaults in `model/DefaultLayout.kt`.
2. Add a `DefaultLayout.<upgrade>(pages|dock|sidebar): Boolean` that edits a saved layout only where
   the user hasn't customised it, returning whether anything changed.
3. In `model/MacroStore.kt`: a `<NAME>_FLAG` constant, a private `/** 1.x: … */` function guarded by
   that prefs flag (pattern: `addDictate`), and a call in `load()` (order matters — append).
4. Test in `model/LayoutJsonTest.kt`; CHANGELOG line ("…, once.").

## Add a key icon

1. Add `"name": "<24x24 SVG path>"` to `icons` in `tools/gen_icons.py`.
2. `python3 tools/gen_icons.py` → regenerates `app/src/main/res/drawable/ic_k_*.xml` and `ui/Icons.kt`.

## Debug a Bluetooth reconnect problem

- Tablet: ⚙ → Diagnostics, `adb logcat -s HidPeripheral`; `docs/bluetooth-troubleshooting.md`.
- Computer: `pip install bleak && python tools/ble_scan.py --watch` (does the advertised address rotate?).

## Release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`; move CHANGELOG "Unreleased" under the version.
2. If the script changed, its `$ScriptVersion` is already bumped (it travels inside the APK).
3. Tag `v<version>` and push → CI builds the signed release APK and creates the GitHub Release
   (the in-app updater reads it).
