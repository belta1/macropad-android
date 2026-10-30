---
updated: 2026-10-30
covers: [seam, contract, pc-link, protocol, hybrid-boundary]
status: current
---

# Seam — the PC link protocol (app ↔ PowerShell)

**The only coupling between Kotlin and PowerShell.** Implemented by hand on both sides:
`app/src/main/java/dev/belta/macropad/pc/PcProtocol.kt` and `tools/macropad-link.ps1`. User-facing
description: `docs/pc-link.md`. **A change here usually lands on both sides in the same commit.**

## Transport

- Bluetooth **SPP** (UUID `00001101-…-00805F9B34FB`, service name in `PcLink`). The tablet is the
  RFCOMM **server** (`PcLink.acceptLoop`); Windows exposes it as an **Outgoing** COM port that the
  script opens (`Find-Port` / `Open-Link`). Only on Classic-Bluetooth tablets, not Fire.
- Secure RFCOMM: Bluetooth encrypts it and only paired devices connect. The tablet additionally
  admits only computers allowed on it (`PcComputers`, see [pc-link](pc-link.md)) — no handshake
  on the wire; an unknown computer just gets silence until allowed.
- **One JSON object per line, UTF-8; `"t"` names the message.** Requests that want an answer carry
  an integer `id`; the script answers `{"t":"result","id":N,"ok":bool,"error":str?}`.
- `$ProtocolVersion` (script) = `PcProtocol.VERSION` (app) = 1. Compatibility comes from
  **ignoring unknown messages and fields** on both sides — don't break that: parse with `opt*`,
  return `null` for unknown `t`.
- Tablet sends `ping` periodically; on `hello` from the tablet the script replies with `Send-All`.

## Script → tablet

| `t` | Payload (main fields) | App handling |
|-----|-----------------------|--------------|
| `hello` | `host, teams, outlook, capture, script, sha256, updates` | `PcStatus` capabilities; script version for self-update |
| `app` | `exe` | front app → pages follow |
| `teams` | `meeting, muted, camera, hand, blur` (absent `meeting` = not connected) | `TeamsState`, call timer |
| `meeting` | next Outlook event | idle clock, Join key, `{meeting}` |
| `clip` | `text` | clipboard history (max 20) |
| `windows` / `icon` | `list[{id,exe,app,title}], front` / `exe, png`(b64, sent once before first use) | Switcher page |
| `volume` | `level, muted, apps[]` | dock slider, Mixer |
| `media` / `shell` | `title, artist, playing`, `length`+`position` s (only when the app reports a timeline; resent on change/seek, not while playing — the tablet extrapolates via `PcTimeline.positionAt`) / `cwd, venv, python` | media card + `ui/MediaProgressView.kt`, `{venv}` |
| `monitors` / `frame` | `list[{n,w,h,primary}]` / `seq, w, h, tiles[], end, cx, cy, view` | Screen page; tablet acks `screen-ack` with `seq` after `end` (flow control) |
| `lock`, `keyboard`, `badges`, `mail` | `locked` / `klid` / `counts{}` / `from, subject, count` | dim, typing layout, tab badges, mail banner |
| `slides` / `slide-image` | `file, slide, count, title, notes, state, show, v`, `kind` (`"html"` = browser deck, absent = PowerPoint), `app` (exe showing it) / `file, n, v, jpg` | Presenter page (`PcSlides.html`, `.app`) |
| `recorded` | `step` (e.g. `ctrl+s`, `text:hola`) | Recorder (with `-AllowRecording`) |
| `file` | `id, name, text` | answer to `typefile` |
| `result` | `id, ok, error` | completes `pc:` steps |

## Tablet → script

Builders are the functions in `PcProtocol` (`teams`, `outlook`, `run`, `focus`, `volume`, `screen`,
`activate`, `waitFor`, `click`, `capture`, `join`, `slides`, `typeFile`, `update`, `pointer`, …).
The script dispatches them in the `switch ([string]$request.t)` near the end of
`tools/macropad-link.ps1` (`'teams'`, `'outlook'`, `'join'`, `'run'`, `'focus'`, `'capture'`,
`'activate'`, `'wait'`, `'click'`, `'volume'`, `'screen'`, `'screen-ack'`, `'pointer'`, `'slides'`,
`'update'`, `'typefile'`; `default` ignores `ping` and anything newer).

## Script self-update

The APK carries the script (`copyLinkScript` Gradle task → assets; `pc/LinkScript.kt`). If
`LinkScript.newerThan(hello.script, hello.sha256)` the tablet offers an update; on confirm it sends
`update` with base64 `ps1` + `sha256` + `version`. The script verifies hash, parses it as
PowerShell, keeps `.bak`, swaps, restarts with the same options (`Invoke-Update`, `Start-Again`).
⇒ **Bump `$ScriptVersion` whenever the script changes.** `-NoUpdate` refuses.

## Gotchas

- `exe` values are normalised **lowercase without `.exe`** on the app side; keep script output consistent.
- Windows line endings in `clip`/`file` text are normalised to `\n` by the app.
- Screen frames are big: never send without the previous ack (Bluetooth would flood).
- `pc:focus`/`wait`/`click` results come only when done or after ~10 s; the macro waits on them.

## Related

- [pc-link](pc-link.md) (app side) · [link-script](link-script.md) (PC side) · [recipes](../recipes.md#add-a-pc-link-message)
