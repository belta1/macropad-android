---
updated: 2026-10-30
covers: [conventions, patterns]
status: current
---

# Conventions

## Naming & structure

- One package per concern under `dev.belta.macropad`: `hid`, `model`, `ui`, `pc`, `net`. Stateless
  helpers are `object`s (`Macro`, `PcProtocol`, `DefaultLayout`, `NetActions`); stateful things are
  classes taking a `Context`.
- Views are built **in code** (no XML layouts); only `res/drawable` (icons, shapes), `res/font`
  (Barlow), `res/values/themes.xml`. Sizes via `Ui.dp/sdp`.
- Tests mirror the main package: `app/src/test/java/dev/belta/macropad/<pkg>/<Class>Test.kt`.

## Tooling & config locations

- **Toolchain:** Gradle wrapper 8.7 (`gradle/wrapper/`), AGP 8.5.2 + Kotlin 1.9.24 (root `build.gradle.kts`), JDK 17.
- **Version:** `versionCode` / `versionName` in `app/build.gradle.kts`; PC script version is
  `$ScriptVersion` in `tools/macropad-link.ps1` (separate).
- **Lint / format:** none configured; follow `kotlin.code.style=official` and surrounding style
  (long lines ~130 chars are normal here).
- **Secrets:** `keystore.properties`, `*.jks`, `local.properties` are git-ignored; CI uses repo secrets.
- **Dependencies:** deliberately none at runtime. Test-only: JUnit 4.13 and `org.json` (Android's
  `org.json` is stubbed in local unit tests). Think hard before adding a library.

## Comment & text style

- KDoc on most classes/functions, in **plain user-facing language** ("the computer", "the tablet",
  "the window in front"), explaining *why* and behaviour, not mechanics. Match that tone.
- Upgrade/migration functions carry a version tag in their KDoc: `/** 1.8: … */`.
- UI strings are inline literals (no `strings.xml` localisation).

## Patterns to reuse

- **Macro parsing:** always go through `Macro.parse(action)` → `List<Step>`; `MacroException`
  carries a message fit for the user.
- **Text expansion:** `TextVariables.expand(...)` for `{date}`, `{clipboard}`, `{secret:x}`…;
  `LiveLabels` for labels updated from `PcStatus`.
- **PC state:** treat `PcStatus` as immutable; new data = new field + `PcProtocol.apply` branch.
- **Blocking network:** `net/*` functions block and throw `IOException` with a dialog/toast-ready
  message — call them off the main thread.
- **Dialogs/menus:** `ui.dialog()` (`MenuDialogBuilder`) and helpers in `ui/Dialogs.kt`.
- **Icons:** `ui/Icons.kt` + `res/drawable/ic_k_*.xml` are generated — edit `tools/gen_icons.py`.

## Error handling

- Parse errors → `MacroException` shown to the user in the key editor.
- PC steps get a `result` (`ok`/`error`) from the script; `pc:focus` / `pc:wait` / pointer steps
  stop the macro on failure or 10 s timeout.
- `runCatching { … }.getOrNull()` is the common idiom for tolerant parsing (protocol, JSON, assets).

## Testing

- JUnit 4, **backtick sentence names** (`` fun `parses a combo with modifiers`() ``), plain
  `assertEquals`. JVM unit tests only — no instrumented/Android tests.
- Covered: macro parser, keyboard layouts, HID reports, layout JSON, computer profiles, live labels,
  text vars, notes commands, page usage, HA parsing, updater, protocol, link script.
- `LinkScriptTest` reads the real `../tools/macropad-link.ps1` (path relative to `app/`).
- New parser/protocol/model logic ships with a test; UI code generally doesn't.

## Docs & changelog

- User-visible changes go into `CHANGELOG.md` under **Unreleased**, written for users (bold
  feature name, what it does). Update the matching `docs/*.md` (macro steps → `docs/macro-language.md`,
  PC link → `docs/pc-link.md`) and the README feature list when relevant.
- Commit subjects: short, user-facing, no prefix convention (e.g. "PC link script: say once why Teams can't be reached").

## Do / don't

- ✅ Keep everything working **without** the PC link and on Fire tablets (BLE only).
- ✅ Bump `$ScriptVersion` whenever `macropad-link.ps1` changes, so tablets offer the update.
- ✅ Ship default-layout changes as a one-shot upgrade in `MacroStore`, guarded by a prefs flag.
- ❌ Don't uninstall the app on a tablet to get past a signature mismatch (wipes layout & secrets).
- ❌ Don't put secrets in the layout, exports or logs.
- ❌ Don't use PowerShell 7-only syntax or modules in the link script (it targets Windows PowerShell 5.1).
