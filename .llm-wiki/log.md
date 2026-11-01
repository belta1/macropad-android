# Log

Append-only record of changes to this wiki. Newest entries at the bottom. Each entry starts with a
fixed prefix so the log is greppable: `grep "^## \[" log.md | tail -5`.

Format: `## [YYYY-MM-DD] <init|update|lint> | <short summary>`

## [2026-10-30] init | scanned Kotlin Android app + PowerShell PC-link script (hybrid); created index, architecture, conventions, glossary, recipes and 10 module pages (app-shell, hid, macro-language, layout-and-storage, ui, pc-link, pc-protocol seam, link-script, net, tooling); includes uncommitted Dictation work
## [2026-10-30] update | Presenter page drives HTML slide decks in the browser (script 1.8.1 deck section, PcSlides.html/app); pc-protocol, link-script, glossary updated
## [2026-10-30] update | media card progress bar (media length/position, script 1.8.2, ui/MediaProgressView.kt); pc-protocol, ui updated
## [2026-10-30] update | PC link admits only computers allowed on the tablet (pc/PcComputers.kt, PcLink.admit/decide/recheck, onPcApproval, ⚙ → Connection → PC link computers); pc-link, pc-protocol updated
## [2026-10-30] update | themes (ui/Theme.kt, ui/ThemeDrawables.kt, ⚙ → Theme, AppSettings.theme): Ui colour tokens now follow Ui.theme, Ui.accent maps palette slots, KeyCap neon/terminal caps, glowing legends, backdrops, runtime dialog colours; gen_icons.py back in sync (8 icons were missing) + palette icon; ui, recipes updated
## [2026-11-01] update | layout editor in the computer's browser over the PC link (pc/LayoutEditor.kt, assets/editor/, script EditorRequest proxy on 127.0.0.1) and notice cards for Teams messages / chosen senders' mail (pc/NoticeFilter.kt, ui/NoticeCard.kt, script ToastStore); script 1.9.0; pc-protocol, pc-link, link-script updated
## [2026-11-01] update | music: song picture (media-art), tap-to-seek, YouTube Music like/dislike/shuffle/repeat via UIA (pc:ytmusic), pc:media play/pause/skip, YouTube Music page (added once), music paused during Teams calls; script 1.9.1; pc-protocol, link-script updated
