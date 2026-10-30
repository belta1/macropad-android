---
updated: 2026-10-30
covers: [macro, parser, steps, text-variables, live-labels, dictation]
status: current
---

# Macro language (`model/Macro.kt` & friends)

What a key's `action` text means. User reference: `docs/macro-language.md` (keep in sync).

## Key files & entry points

- `app/src/main/java/dev/belta/macropad/model/Macro.kt` — `sealed class Step` (`Combo, Media, Text,
  Delay, Click, Scroll, Page, Tablet, Slide, Http, Wol, Ha, Pc` + `PcPoint`), `Macro.parse(action)`,
  `slider`, `isHoldable`, `describe`, Teams/Outlook shortcut helpers (`inTeams`, `toTeams`,
  `toOutlook`). The header KDoc lists every step syntax.
- `model/TextVariables.kt` — `{date}`, `{time}`, `{date:<pattern>}`, `{clipboard}`,
  `{pcclipboard[:N]}`, `{secret:name}`; expanded when the key runs.
- `model/LiveLabels.kt` — label placeholders refreshed from `PcStatus` (`{venv}`, `{media}`,
  `{volume}`, `{app}`, `{meeting}`, `{call}`…, `{name|fallback}`).
- `model/Dictation.kt` — spoken punctuation (Spanish + English) → marks, capitalisation, joining
  phrases; used by `ui/DictationPanel.kt` for `tablet:dictate`.
- `model/NotesCommands.kt` — commands embedded in PowerPoint speaker notes (Presenter page).
- Tests: `app/src/test/java/dev/belta/macropad/model/MacroTest.kt`, `TextVariablesTest.kt`,
  `LiveLabelsTest.kt`, `NotesCommandsTest.kt`, `DictationTest.kt`.

## How it works

One step per line; blank lines and `#` comments skipped; prefixes are case-insensitive. Parsing is
pure (unit-tested); execution is in `MainActivity.runAction` / `runSteps` / `runInOrder`:
HID steps → `HidPeripheral.run`; `page:`/`tablet:` handled on the tablet; `http:`/`wol:`/`ha:`
run in the background immediately (work with no computer); `pc:` steps go over the PC link and
`pc:focus`/`pc:wait`/pointer steps **block the rest of the macro** until the script answers.
A macro that is a single combo/media/click is **held** for as long as the key is touched.

## Gotchas & constraints

- `slide:` must be the only step in a key.
- Changing parse results for existing syntax changes users' saved keys — add syntax, don't
  repurpose it.
- `text:` uses `\n` / `\t` escapes (`unescape`); raw text after `text:` keeps leading content as-is.
- `TABLET_COMMANDS` is the allow-list for `tablet:`; unknown commands throw `MacroException`.

## Related

- [hid](hid.md) · [pc-protocol](pc-protocol.md) · [net](net.md) · [recipes](../recipes.md#add-a-macro-step-kind)
