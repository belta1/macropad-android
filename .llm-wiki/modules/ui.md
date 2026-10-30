---
updated: 2026-10-30
covers: [ui, views, dialogs, editor]
status: current
---

# UI (`ui/`)

Hand-built Android views (no XML layouts, no Compose) for keys, editors, dialogs and the special pages.

## Key files & entry points

All under `app/src/main/java/dev/belta/macropad/ui/`:

- `Ui.kt` — theme colours, fonts, `dp/sdp` scaling, `dialog()`, `toast`, drawable helpers. Start here.
- `KeyViews.kt` + `KeyCap.kt` — key grid cells and the keycap drawing; `KeyViews.Listener` is
  implemented by `MainActivity`.
- `SliderView.kt` — slider keys (`slide:`), volume level, media card.
- `KeyEditor.kt` — edit a key (label, action, color, icon, toggle, long action); shows `MacroException`s.
- `Dialogs.kt` — `MacropadHost` interface + the settings menu and all dialogs behind ⚙ (computers,
  timing, PIN, pages, backup, saved values, diagnostics, updates…). `MenuDialog.kt` — menu rows builder.
- `Recorder.kt`, `RecordingEditor.kt` — macro recording and the steps editor after stopping.
- `TrackpadView.kt` — pointer, taps, two-finger scroll, drag.
- PC link pages: `WindowSwitcherView.kt` (also `PcStatusView`, `pcPageMessage`), `MixerView.kt`,
  `ScreenView.kt` (mirror, pinch zoom, keyboard chip), `PresenterView.kt` (notes, slides, laser).
- `HomeAssistantView.kt` — Home page (areas, filters, pin/hide).
- `DictationPanel.kt` — speech recognizer UI for `tablet:dictate`.
- `Icons.kt` (**generated** by `tools/gen_icons.py`) and `AppIcons.kt` (tab app icons).

## Gotchas & constraints

- Don't hand-edit `Icons.kt` or `res/drawable/ic_k_*.xml`; regenerate ([recipes](../recipes.md#add-a-key-icon)).
- Scale with `ui.sdp(...)` (user-adjustable size), not raw pixels.
- Views that show PC data must render a sensible message when the link is missing.
- No UI tests — verify on a device (`tools/deploy.sh`).

## Related

- [app-shell](app-shell.md) · [layout-and-storage](layout-and-storage.md) · [pc-link](pc-link.md) · [net](net.md)
