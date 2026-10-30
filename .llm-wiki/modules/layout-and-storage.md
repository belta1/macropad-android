---
updated: 2026-10-30
covers: [layout, persistence, settings, migrations, secrets]
status: current
---

# Layout & persistence (`model/`)

The data the user builds (pages and keys), its defaults, how it's stored and upgraded, and the
app/computer settings around it.

## Key files & entry points

- `app/src/main/java/dev/belta/macropad/model/Layout.kt` — `MacroButton`, `PageKind`, `Page`
  (`apps`, `stay`, `icon`), `Layout` (cols/rows, `pages`, `sidebar`, `dock`, `hardware`),
  `Palette`, `LayoutJson` (to/from JSON — the export/backup format).
- `model/DefaultLayout.kt` (~600 lines) — every default page and key, `dock()`, `sidebar()`,
  `hardware()`, `appsFor(name)`, `iconFor(name)`, `RETIRED_PAGES`, and the **upgrade helpers**
  (`addDictate`, `upgradeRunBoxTeams`, `retireAndOrder`, …) that return `true` if they changed something.
- `model/MacroStore.kt` — persists the layout in SharedPreferences (`"macropad"`) and mirrors it to
  `<external files>/macropad-layout.json`; `load()` runs the **one-shot upgrades** in order.
- `model/AppSettings.kt` — app-wide prefs (not exported). `model/ComputerProfiles.kt` — per-computer
  overrides by Bluetooth address (name, layout, timing, pages, start page).
- `model/SecretStore.kt` — `{secret:name}` values, AES-GCM with an Android Keystore key, never in exports.
- `model/PageUsage.kt` — usage counts for *Sort tabs by use*.
- `backup/macropad-layout.json` — the owner's layout, pulled with adb.
- Tests: `LayoutJsonTest.kt`, `ComputerProfileJsonTest.kt`, `PageUsageTest.kt` under `app/src/test/java/dev/belta/macropad/model/`.

## How it works

`MacroStore.load()` reads saved JSON (or `DefaultLayout.layout()`), then applies each upgrade
whose prefs flag isn't set yet; each sets its flag and saves only if it changed something.
`save()` writes prefs and the backup file on every change. Import/export use `LayoutJson` via the
file picker (`MainActivity.exportLayout/importLayout`).

## Gotchas & constraints

- **Never overwrite user customisations.** Upgrades match keys by their *default* label/action and
  leave edited keys alone. New behaviour for existing layouts = new flag + helper (see
  [recipes](../recipes.md#change-default-keys-for-existing-layouts)).
- Upgrade order in `load()` matters; append new ones at the end.
- `LayoutJson` must stay backward compatible (old backups must import); new fields need defaults.
- Secrets and `AppSettings` are deliberately outside `LayoutJson`.

## Related

- [macro-language](macro-language.md) · [ui](ui.md) (key editor, settings dialogs) · [app-shell](app-shell.md)
