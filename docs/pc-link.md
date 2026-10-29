# PC link

On tablets with Classic Bluetooth (e.g. Galaxy Tab S5e), the macropad offers a **Bluetooth serial
port** next to the keyboard. Windows shows it as a COM port.
[tools/macropad-link.ps1](../tools/macropad-link.ps1) runs on the computer and keeps the port open.

Through it:

- **Teams**: Mute, Camera, Raise hand, Blur and Leave go straight to Teams through its
  third-party app API, so no window comes to the front. The keys light up from Teams' real state,
  including when you click in Teams itself. `pc:teams react like` sends a reaction.
- **Pages follow the app in front.** Teams in front shows the Teams page, Outlook shows Outlook,
  and so on.
- **Next meeting** on the idle clock comes from classic Outlook's calendar.
- **`pc:run <name>`** keys start commands you list on the computer, and only those.

Without the script (or on a Fire tablet, which has no Classic Bluetooth) everything works as
before. Teams keys send their keyboard shortcuts, and their lights are ordinary toggles.

## Set up

1. **Update the macropad to 1.7 and pair it once more.** On the computer: Bluetooth & devices →
   Macropad → Remove device, then add it again (tap the status on the tablet → Make visible).
   Windows only reads the tablet's services when pairing, so the new serial port needs a new
   pairing.
2. **Check the COM port.** Open Bluetooth & devices → More Bluetooth settings → COM Ports. There
   should be an *Outgoing* port for "Macropad 'Macropad PC link'". If there isn't, click Add →
   Outgoing → Macropad → Macropad PC link.
3. **Run the script** from the repo (or copy `tools/macropad-link.ps1` and
   `tools/macropad-commands.example.json` anywhere):

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -Verbose
   ```

   It finds the port by itself; use `-Port COM5` if it doesn't. ⚙ → Diagnostics on the tablet then
   shows `PC link: <computer> ✓`.
4. **Start it with Windows:**

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\macropad-link.ps1 -Install
   ```

   This adds a hidden shortcut to the Startup folder. Remove it with `-Uninstall`.
5. **Teams** (new Teams): Settings → Privacy → **Third-party app API** → turn on *Manage API*.
   Join a meeting (a test call works) and press Mute on the macropad. Teams asks to allow
   "Macropad": click **Allow**. Teams remembers it; the token is in
   `%APPDATA%\Macropad\teams-token.txt`.

   **No "Third-party app API" in Teams' settings** (organisations can turn it off): the script
   then reads Teams' meeting window instead (UI Automation, like a screen reader). Mute, camera,
   raise hand and leave work this way, and their lights follow Teams; blur and reactions need the
   API. If a meeting isn't recognised, join one and run
   `macropad-link.ps1 -TeamsDump`: it lists Teams' buttons, so the names it looks for can be
   adjusted.
6. **Outlook**: classic Outlook just needs to be open. The script never starts it. The new
   Outlook has no calendar access for scripts; the clock then keeps using Home Assistant's
   calendar (saved value `ha_calendar`), if set.

## Pages for apps

Long-press a tab → **Show for apps…** and list process names, separated by commas. The dialog
shows the app in front right now as a hint. Defaults:

| Page | Apps |
|---|---|
| Teams | `ms-teams, teams` |
| Outlook | `outlook, olk` |
| Citrix | `wfica32, cdviewer` |
| VS Code / Chrome / Edge / Excel / Word | `code` / `chrome` / `msedge` / `excel` / `winword` |

The page changes only when the app in front changes, so a tab you pick yourself stays until you
switch apps. It doesn't change while editing or recording. Turn it off in ⚙ → Connection →
**Follow the computer's app**.

## Commands (`pc:run`)

The first run copies `tools/macropad-commands.example.json` to
`%APPDATA%\Macropad\commands.json`. Each entry is a name and a command line, run hidden with
`cmd /c`:

```json
{
  "lock": "rundll32.exe user32.dll,LockWorkStation",
  "downloads": "explorer.exe shell:Downloads",
  "standup-notes": "notepad.exe C:\\Users\\me\\notes\\standup.txt"
}
```

A key with `pc:run lock` runs it. The file is read on every run, so changes apply at once.
Nothing else can be started from the tablet: a name that isn't in the file is refused.

## Troubleshooting

- The script's log is in `%APPDATA%\Macropad\link.log`; use `-Verbose` to log every message too.
- *"No outgoing Bluetooth COM port"*: see steps 1–2. The script looks for a paired Bluetooth
  device whose name contains `Macropad`; use `-Device "<name>"` if you renamed it (⚙ →
  Connection → Bluetooth name).
- The port opens but nothing happens: check that ⚙ → Diagnostics → Connection is *Classic
  Bluetooth*. The PC link needs the Classic keyboard.
- Teams keys still jump to Teams: the script isn't connected to Teams (Diagnostics shows
  `Teams —`). Check step 5 and that new Teams is running.
- Only one copy runs at a time. A second one says so and exits.

## Protocol

One JSON object per line, UTF-8; `"t"` names the message. Both sides ignore messages and fields
they don't know, so the app and the script can be updated separately.

| Direction | Message |
|---|---|
| tablet → PC | `{"t":"hello","v":1,"app":"1.7.0"}`: sent when the port opens; the script answers with everything below |
| tablet → PC | `{"t":"teams","do":"mute","id":7}`: `mute`, `camera`, `hand`, `blur`, `leave`, or `react` with `"param":"like"` |
| tablet → PC | `{"t":"run","name":"lock","id":8}` |
| tablet → PC | `{"t":"ping"}`: every 30 s; the script drops the port after 90 s of silence |
| PC → tablet | `{"t":"hello","v":1,"host":"LAPTOP","teams":true,"outlook":true}` |
| PC → tablet | `{"t":"app","exe":"ms-teams","title":"…"}`: when the app in front changes |
| PC → tablet | `{"t":"teams","meeting":true,"muted":false,"camera":true,"hand":false,"blur":false}`, or `{"t":"teams"}` while Teams can't be reached |
| PC → tablet | `{"t":"meeting","title":"Standup","start":"2026-10-29T10:00:00-03:00","end":"…"}`, or `{"t":"meeting"}` when there is none today or tomorrow |
| PC → tablet | `{"t":"result","id":7,"ok":false,"error":"…"}`: the tablet shows the error |

The serial port is the standard Serial Port Profile (UUID `00001101-0000-1000-8000-00805F9B34FB`),
named "Macropad PC link". Any program that can open a COM port can speak this protocol.
