# Log

Append-only record of changes to this wiki. Newest entries at the bottom. Each entry starts with a
fixed prefix so the log is greppable: `grep "^## \[" log.md | tail -5`.

Format: `## [YYYY-MM-DD] <init|update|lint> | <short summary>`

## [2026-10-30] init | scanned Kotlin Android app + PowerShell PC-link script (hybrid); created index, architecture, conventions, glossary, recipes and 10 module pages (app-shell, hid, macro-language, layout-and-storage, ui, pc-link, pc-protocol seam, link-script, net, tooling); includes uncommitted Dictation work
## [2026-10-30] update | Presenter page drives HTML slide decks in the browser (script 1.8.1 deck section, PcSlides.html/app); pc-protocol, link-script, glossary updated
## [2026-10-30] update | media card progress bar (media length/position, script 1.8.2, ui/MediaProgressView.kt); pc-protocol, ui updated
## [2026-10-30] update | PC link admits only computers allowed on the tablet (pc/PcComputers.kt, PcLink.admit/decide/recheck, onPcApproval, ⚙ → Connection → PC link computers); pc-link, pc-protocol updated
## [2026-10-30] update | themes (ui/Theme.kt, ui/ThemeDrawables.kt, ⚙ → Theme, AppSettings.theme): Ui colour tokens now follow Ui.theme, Ui.accent maps palette slots, KeyCap neon/terminal caps, glowing legends, backdrops, runtime dialog colours; gen_icons.py back in sync (8 icons were missing) + palette icon; ui, recipes updated
