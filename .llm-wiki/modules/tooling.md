---
updated: 2026-10-30
covers: [tools, build, ci, deploy]
status: current
---

# Build, CI & dev tools

## Key files & entry points

- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/` — single `:app`
  module; plugin versions pinned in the root file.
- `app/build.gradle.kts` — SDK levels, `versionCode`/`versionName`, release signing from
  `keystore.properties` or `MACROPAD_*` env vars (falls back to debug key), `copyLinkScript` task
  (bundles `tools/macropad-link.ps1` into assets before `preBuild`), test deps.
- `.github/workflows/android.yml` — tests + debug APK artifact on push/PR; on tag `v*` signed
  release APK (`MACROPAD_KEYSTORE_BASE64` etc. secrets) + GitHub Release.
- `tools/deploy.sh` — `fire|tab [--log] [--no-build]`: release build, `adb install -r` over Wi-Fi,
  grants `WRITE_SECURE_SETTINGS`, starts the app. Env: `MACROPAD_FIRE`, `MACROPAD_TAB`, `ADB`, `JAVA_HOME`.
- `tools/gen_icons.py` — the icon source of truth → `res/drawable/ic_k_*.xml` + `ui/Icons.kt`.
- `tools/ble_scan.py` — run on the computer (`pip install bleak`) to watch the tablet's BLE address.
- `tools/macropad-link.ps1` — see [link-script](link-script.md).

## Gotchas & constraints

- Python tools are standalone scripts (no `pyproject`, no lockfile); stdlib except `bleak`.
- `local.properties` (`sdk.dir`) and `keystore.properties` are git-ignored and machine-specific.
- The release build is what `deploy.sh` installs; switching a tablet between debug and release
  signatures requires uninstall (data loss) — avoid.

## Related

- [recipes](../recipes.md) · [conventions](../conventions.md)
