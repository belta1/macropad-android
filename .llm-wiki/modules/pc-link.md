---
updated: 2026-10-30
covers: [pc-link, bluetooth-spp]
status: current
---

# PC link — app side

The tablet's end of the Bluetooth serial channel to `tools/macropad-link.ps1`: serves the port,
decodes messages into `PcStatus`, sends `pc:` requests, mirrors the screen, carries the script.

## Key files & entry points

- `app/src/main/java/dev/belta/macropad/pc/PcLink.kt` — RFCOMM server (`start/stop`,
  `acceptLoop`, `serve`), request senders (`teams`, `outlook`, `run`, `focus`, `volume`,
  `startScreen`, `click`, `slides`, `update`…, each returning the request id), `Listener` callbacks,
  `icons`, `slideImages`, `mirror` (screen bitmap assembled from tiles).
- `app/src/main/java/dev/belta/macropad/pc/PcProtocol.kt` — `PcStatus`, `PcMessage`, `parse`,
  `apply` (reducer), request builders. The contract: [pc-protocol](pc-protocol.md).
- `app/src/main/java/dev/belta/macropad/pc/LinkScript.kt` — bundled script bytes/SHA/version, `newerThan`.
- `app/src/main/java/dev/belta/macropad/pc/PcComputers.kt` — which computers (Bluetooth address)
  are allowed / refused the link (`pc_computers` SharedPreferences, owned by `MacropadService.pcComputers`).
- `app/src/main/java/dev/belta/macropad/MacropadService.kt` — owns the `PcLink` instance (`service.pc`).
- `MainActivity.kt` "PC link" section — `onPcStatus` (lights, follow app/call/slide show, lock,
  mail), `sendPc`, `awaitPc`, `pcFallback`, `offerScriptUpdate`.
- Tests: `app/src/test/java/dev/belta/macropad/pc/PcProtocolTest.kt`, `LinkScriptTest.kt`.

## How it works

After `accept()`, `admit()` gates the computer: allowed → `serve()`; refused → closed; not asked
yet → `pending` + `Listener.onPcApproval`, polled up to `ASK_MS` (80 s, under the script's 90 s
silence timeout) without reading or writing, while `MainActivity.askPcApproval` asks (Allow goes
through `withUnlock`). `decide()` / `recheck()` persist and drop a connected computer no longer
allowed; ⚙ → Connection → PC link computers (`Dialogs.pcComputers`) edits the list. No protocol
change: Bluetooth already encrypts the link (secure RFCOMM), this only limits *which* paired device.

`serve()` reads lines on a socket thread. Most messages go through `PcProtocol.apply` into a new
immutable `PcStatus`, published to the main thread. Heavy ones are handled in place: `icon`,
`slide-image` (decoded to bitmaps), `frame` (drawn off the main thread, then `screen-ack`),
`result` (→ `onPcResult`, which resumes a waiting macro). Writes go through a single-thread
executor. One computer at a time; when it disconnects `publish(null)` and the port waits again.

## Gotchas & constraints

- Every feature must degrade when `PcStatus` is null (no link, Fire tablet, old script): keys fall
  back to shortcuts (`pcFallback`, `Macro.inTeams/toOutlook`) or the page shows a message
  (`ui/WindowSwitcherView.kt` `pcPageMessage`).
- Fields added in later script versions are nullable/"unknown" (e.g. `windows == null` before 1.8) —
  distinguish "unknown" from "empty".
- `LinkScriptTest` reads the real script from `../tools/macropad-link.ps1`; the version regex in
  `LinkScript` must keep matching `$ScriptVersion = '1.2.3'`.

## Related

- [pc-protocol](pc-protocol.md) · [link-script](link-script.md) · [app-shell](app-shell.md) · [ui](ui.md)
