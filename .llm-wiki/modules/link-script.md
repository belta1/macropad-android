---
updated: 2026-10-30
covers: [powershell, pc-link, windows, teams, outlook]
status: current
---

# PC link script (`tools/macropad-link.ps1`)

The Windows side of the PC link: a single ~2.6k-line **Windows PowerShell 5.1** script, no modules,
that keeps the tablet's COM port open, reports the computer's state and executes `pc:` requests.
User docs: `docs/pc-link.md`. Contract: [pc-protocol](pc-protocol.md).

## Key files & entry points

- `tools/macropad-link.ps1` — sections are marked `# ------…---- <name>`; jump by grepping them:
  setup switches · shell hook · logging · serial port (`Find-Port`, `Open-Link`, `Send-Tablet`,
  `Send-Changed`) · app in front · Win32/Core Audio C# (`Add-Type`) · windows/icons · waits
  (`pc:focus`/`pc:wait`) · recording (`-AllowRecording`) · recorded clicks (`pc:click/drag/scroll`) ·
  clipboard · volume · media (WinRT) · shell hook · Screen page · lock/keyboard · Outlook (COM) ·
  Outlook commands · Teams (third-party API) · Teams without API (UI Automation) · named commands
  (`pc:run`) · typing a file · PowerPoint · HTML slide decks (`Update-Deck`, `Get-DeckSlides`,
  `Send-Deck`, `Invoke-DeckCommand`) · talking to the tablet (`Send-Hello`) · updates
  (`Invoke-Update`, `Send-All`, dispatch `switch`, `Read-Tablet`) · `-TeamsDump` · **main loop**.
- `tools/macropad-commands.example.json` — template for `%APPDATA%\Macropad\commands.json`
  (`pc:run <name>` allow-list, run with `cmd /c`, hidden).
- Runtime files in `%APPDATA%\Macropad\`: `commands.json`, `teams-token.txt`, `shell.txt`, `link.log`.

## How it works

Main loop: open the link if needed (retry every 10 s), `Send-All` on connect, then each turn read
tablet lines and dispatch, poll sources whose `$Next[...]` time is due (`app`, `meeting`, `clip`,
`windows`, `volume`, `media`, `shell`, `monitors`, `badges`, `slides`), check pending waits/clicks,
send screen frames when acked. `Send-Changed` suppresses duplicates per message type. Reading
errors are caught and logged; **write errors end the link** (reconnect).

Switches: `-Port`, `-Device`, `-Install/-Uninstall` (Startup shortcut), `-NoTeams`, `-NoOutlook`,
`-NoClipboard`, `-AllowRecording`, `-NoUpdate`, `-TeamsDump`, `-InstallShellHook/-UninstallShellHook`.

## Gotchas & constraints

- **PowerShell 5.1 only syntax** (no `??`, `?.`, ternary, `-AsHashtable`); C# via `Add-Type` must
  compile on .NET Framework.
- **Bump `$ScriptVersion`** on every change — the APK bundles this file and offers it to computers.
- One instance at a time (mutex); the Startup shortcut runs it hidden — use `link.log`.
- Teams: prefers the local third-party app API (token in `teams-token.txt`); falls back to UI
  Automation of the meeting window when the API is off (`-TeamsDump` lists buttons to adjust).
- Outlook is **classic Outlook via COM** and is never started just to read it.
- Clipboard items marked private by password managers are never sent (`Test-PrivateClip`).
- HTML decks (`const S=o=>SLIDES.push(o)`, `S({sect, html, notes})`, `#n` = slide): found by reading
  the front browser's address bar via UIA (`Uia.AddressBar`, first Edit control — names are
  localised); "show" = window covers its monitor and isn't maximized. Full screen hides the address
  bar, so the slide is then tracked from the tablet's own commands (`heldUntil` bridges the lag).
  Reported through `Send-Slides` (PowerPoint show > deck > PowerPoint editing); `Invoke-Slides`
  routes to `Invoke-DeckCommand`, which focuses the deck and taps its keys (`Keys.Tap`).
- Recording hooks keyboard/mouse like a keylogger — opt-in, time-limited; keep it that way.
- Must answer every request carrying an `id` with `Send-Result`, or the tablet's macro waits/times out.

## Related

- [pc-protocol](pc-protocol.md) · [pc-link](pc-link.md) · [recipes](../recipes.md#run-the-pc-link-script-windows)
