---
updated: 2026-10-30
covers: [glossary, domain]
status: current
---

# Glossary

- **Computer / host** — the PC the tablet types into. Per-computer settings: `ComputerProfile` (`model/ComputerProfiles.kt`), keyed by Bluetooth address.
- **Key / `MacroButton`** — label, `action` (macro text), color, icon, `toggle`, `longAction`. `model/Layout.kt`.
- **Action / macro** — the multi-line text a key runs; parsed by `Macro.parse` into **Steps**. See [macro-language](modules/macro-language.md), `docs/macro-language.md`.
- **Step** — one parsed line: `Combo`, `Media`, `Text`, `Delay`, `Click`, `Scroll`, `Page`, `Tablet`, `Slide`, `Http`, `Wol`, `Ha`, `Pc`. `model/Macro.kt`.
- **Page / tab** — a grid of keys or a special view; `PageKind` = `KEYS, TRACKPAD, WINDOWS (Switcher), MIXER, SCREEN, HOME, SLIDES (Presenter)`. `apps` = process names that bring the page up; `stay` pins it.
- **Sidebar / Dock / Tablet buttons** — fixed pages in `Layout` (`sidebar`, `dock`, `hardware`) built in `DefaultLayout`.
- **Toggle key** — remembers ON/OFF; with the PC link, Teams toggles are lit from real Teams state (`TeamsState`).
- **Slider** — a key that is a single `slide:` step; drag sends one step per tick (`ui/SliderView.kt`).
- **Hold** — a single combo/media/click macro is held while the key is touched (real key-down/up).
- **Key timing** — per-computer hold/gap ms (`hid/KeyTiming.kt`), e.g. slower for Citrix.
- **Keyboard layout** — how text is turned into keystrokes (`hid/KeyboardLayout.kt`); with the PC link follows the Windows KLID of the window in front.
- **PC link** — Bluetooth serial (SPP) channel to `tools/macropad-link.ps1`. See [pc-link](modules/pc-link.md), [pc-protocol](modules/pc-protocol.md).
- **`PcStatus`** — everything the script last reported (front app, Teams, meeting, clips, windows, volume, media, shell, monitors, lock, keyboard, badges, mail, slides). `pc/PcProtocol.kt`.
- **Front app** — process name of the foreground window (lowercase, no `.exe`); pages follow it.
- **Live label** — `{venv}`, `{media}`, `{meeting}`, `{call}`… in a key label, refreshed from `PcStatus`. `model/LiveLabels.kt`.
- **Text variable** — `{date}`, `{time}`, `{clipboard}`, `{pcclipboard}`, `{secret:name}` in `text:` steps. `model/TextVariables.kt`.
- **Saved values / secrets** — encrypted named values (`ha_url`, `ha_token`, logins). `model/SecretStore.kt`.
- **Idle clock / dim** — overlay after inactivity; the waking touch never presses a key. `MainActivity` "idle clock" section.
- **Recorder** — captures keys sent from the tablet (and optionally the PC's own keyboard via `capture`) into a new key. `ui/Recorder.kt`, `ui/RecordingEditor.kt`.
- **Presenter** — the SLIDES page: notes, slide images, laser pointer for PowerPoint or an **HTML deck** in the browser (`PcSlides.html`). `ui/PresenterView.kt`, `model/NotesCommands.kt`.
- **Dictation** — `tablet:dictate`: speech → text with spoken punctuation (es/en) → typed. `model/Dictation.kt`, `ui/DictationPanel.kt`.
- **Fire quirks** — Fire OS Bluetooth workarounds (A2DP/Classic connectable) needing `WRITE_SECURE_SETTINGS`. `hid/DeviceQuirks.kt`.
- **Upgrade flag** — prefs boolean marking a one-shot layout migration as done. `model/MacroStore.kt`.
