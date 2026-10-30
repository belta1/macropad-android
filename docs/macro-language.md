# Macro language

Every key has an **action**: one step per line. Lines starting with `#` are comments.

| Step | Example | Meaning |
|---|---|---|
| Key combo | `ctrl+shift+t` | Modifiers plus up to 6 keys, pressed together |
| Media key | `media:volup` | Consumer-control key |
| Text | `text:Hello world` | Types the text using the computer's keyboard layout. `\n` = Enter, `\t` = Tab |
| Delay | `delay:250` | Waits, in milliseconds (max 10000). `wait:` also works |
| Mouse click | `click:left` | `left`, `right` or `middle` |
| Scroll | `scroll:3` | Mouse wheel notches; positive = up, negative = down (max ±50) |
| Page | `page:next` | Switches the tablet's page: `next`, `prev` or a page name. Runs on the tablet, even when no computer is connected |
| Tablet | `tablet:dim` | Dims the tablet to its idle clock (after the other steps have been sent). Runs on the tablet |
| Slider | `slide:volume` | Makes the key a **slider** you drag instead of tap (see [Sliders](#sliders)). Must be the key's only step |
| Web request | `http:POST https://… \| {"on":true}` | Sent by the tablet over Wi-Fi (see [Network steps](#network-steps)) |
| Wake on LAN | `wol:AA:BB:CC:DD:EE:FF` | Wakes a computer on the tablet's network |
| Home Assistant | `ha:light.toggle light.desk` | Calls a Home Assistant service |
| Teams (PC link) | `pc:teams mute` | A Teams command through the [PC link](pc-link.md): `mute`, `camera`, `hand`, `blur`, `leave`, `react like` (`love`, `applause`, `laugh`, `wow`). Without the link, sends the Teams shortcut |
| Outlook (PC link) | `pc:outlook reply` | A classic Outlook command through the [PC link](pc-link.md#outlook-commands): `open`, `new-mail`, `new-meeting`, `mail`, `calendar`, `reply`, `reply-all`, `forward`, `send`, `mark-read`, `mark-unread`, `flag`, `archive`, `delete`. Without the link, sends English Outlook's shortcut |
| Join meeting (PC link) | `pc:join` | Opens the next Outlook meeting's Teams / Zoom / Meet / Webex meeting on the computer. A key that is just `pc:join` lights from 5 minutes before the meeting until it ends |
| Bring an app to the front (PC link) | `pc:focus excel` | Brings that app's window (process name) to the front, waiting up to 10 s for it to open. The steps after it wait for it |
| Wait for a window (PC link) | `pc:wait save as` | Waits until the window in front is that app or has that in its title (up to 10 s), instead of a fixed `delay:`. The steps after it wait for it |
| PC command (PC link) | `pc:run lock` | Runs a command named in the computer's `commands.json` ([PC link](pc-link.md)) |

### Text variables

`text:` steps can contain variables, filled in when the key runs:

| Variable | Example result |
|---|---|
| `{date}` | `2026-10-21` |
| `{time}` | `14:05` |
| `{date:dd/MM/yyyy}`, `{time:HH:mm:ss}` | any [SimpleDateFormat](https://developer.android.com/reference/java/text/SimpleDateFormat) pattern |
| `{clipboard}` | the **tablet's** clipboard text |
| `{pcclipboard}`, `{pcclipboard:2}` | the **computer's** latest copied text, the one before, … ([PC link](pc-link.md#the-computers-clipboard)). The key does nothing while that copy is missing |
| `{secret:name}` | a saved value (⚙ → **Saved values**), e.g. a login; encrypted on the tablet, never in backups or exports. The key does nothing until every value it uses is set |
| `{{` | a literal `{` |

Dates and times come from the tablet's clock, so keep its date and time correct.

### Live labels

A key's **label** can show what the [PC link](pc-link.md) reports, kept up to date while the key
is on screen (edit mode shows the label as written):

| In a label | Shows |
|---|---|
| `{pcclipboard}`, `{pcclipboard:2}` | the computer's latest copy (the one before, …) on one line |
| `{venv}` | the terminal's venv and Python version (`.venv · 3.12.1`), or *No venv* ([shell hook](pc-link.md#the-terminals-venv)) |
| `{folder}`, `{cwd}` | the terminal's folder: its name, or the full path |
| `{media}`, `{title}`, `{artist}` | what's playing (`Title — Artist`), or *Nothing playing* |
| `{volume}` | the computer's volume (`42%`, *Muted*) |
| `{app}` | the app in front |
| `{meeting}` | the next Outlook meeting (`Standup · 10:30`, `Standup · now`) |
| `{call}` | how long the Teams call under way has lasted (`12:04`), every second; nothing outside a call |

`{name|text}` shows *text* while the value is unknown (no PC link, nothing reported yet, an empty
clipboard slot): the Python page's `{venv|Which Python}`. A label can have two lines (the dock's
`{title|Now playing}` over `{artist}`); lines left empty are dropped. In the dock, a key with a
live label is a card six keys wide; when its action is `media:playpause` it is the media card,
with ⏮ ⏯ ⏭ at its end.

### Typing code and long texts

`text:` steps (including `{clipboard}`, the tablet's clipboard) type characters as they are:
backslashes, quotes and braces inside a variable's value are typed as they are; line breaks
press Enter and tabs press Tab. Smart quotes, long dashes and non-breaking spaces are typed as
their ASCII forms; other characters the layout lacks are skipped (a message lists them). While a
text of 60+ characters is typed, the dock shows **Typing N characters… tap here to stop**.

Editors that **auto-indent or auto-close brackets** (VS Code, PowerShell ISE, IDEs) add their own
indentation and closing brackets as the code is typed, which doubles them. Type into a plain editor
(Notepad) and copy from there, or turn those features off. In chat apps (Teams) every line break
is an Enter, which sends the message.

### Long-press action

In the editor, **Long-press action** gives a key a second action. A short tap runs the normal action
when the finger lifts; holding for 0.45 s runs the long-press action instead (held while touched if
it is a single combo). Example: action `win+alt+k` (toggle mute), long-press `ctrl+space` (push to talk).

A key whose action is a **single combo, media key or click** is *held* for as long as it is
touched, like a physical key (auto-repeat, push-to-talk, holding a modifier, dragging with a held
click). Anything longer runs once per tap. Keys marked **Toggle** in the editor are held the same way
and flip an ON/OFF display on every press (the state resets when the connection drops, since the computer's real state is
unknown).

A key whose action is just `capslock`, `numlock` or `scrolllock` shows ON/OFF too, from the
computer's own keyboard lights, so it stays right even when the lock is changed on the computer's
keyboard. The status chip shows Caps and Num Lock the same way.

### Sliders

A key whose action is `slide:` is drawn as a strip with a thumb. Dragging it sends one step for
every short stretch of travel (about 18 dp, each with a haptic tick): dragging **right or up** sends the
*up* step, **left or down** the *down* step. It drags along the key's longer side, so a slider in
the dock or a wide cell is horizontal and one in a tall cell is vertical. Holding the finger at
either end keeps sending steps. The thumb springs back to the middle on release: the slider
is relative, because the tablet can't know the computer's volume, scroll position, etc.

| Preset | Up / right | Down / left | Tap the icon |
|---|---|---|---|
| `slide:volume` | `media:volup` | `media:voldown` | `media:mute` |
| `slide:scroll` | `scroll:1` | `scroll:-1` | — |
| `slide:zoom` | `ctrl+equal` | `ctrl+minus` | `ctrl+0` (reset zoom) |
| `slide:brightness` | `media:brightup` | `media:brightdown` | — |
| `slide:tabs` | `ctrl+tab` | `ctrl+shift+tab` | — |
| `slide:undo` | `ctrl+y` (redo) | `ctrl+z` (undo) | — |

Any other pair is written `slide:<up> | <down>`, each side one key combo, media key or scroll,
e.g. `slide:right | left` to step through a video or a timeline. An optional third part is what
tapping the slider's icon sends: `slide:right | left | space` (a key combo, media key, scroll or
`click:`). A slider with a tap step shows its icon on a small disc; a touch that starts there and
lifts without sliding taps, and a drag that starts there still slides. The **−** and **+** at the
ends are buttons as well: a tap sends one down / up step, holding keeps stepping. The dock's volume
slider is three keys wide, and tapping its speaker mutes the computer. Use `backslash` rather than `|`
inside a combo. In edit mode a slider shows as a normal key, so tapping it opens the editor,
which has **Volume / Scroll / Zoom slider** buttons under the action. Long-press actions and
Toggle don't apply to sliders. A slider can't be assigned to the tablet's volume buttons.

### Network steps

`http:`, `wol:` and `ha:` steps are sent by the **tablet** over its Wi-Fi, not typed on the
computer, so they work with no computer connected. They start as soon as the key is pressed,
beside its keys (one after another, in order; if one fails, a message says why and the rest are
skipped).

| Step | Meaning |
|---|---|
| `http:https://example.com/hook` | GET request |
| `http:POST https://example.com/hook \| {"on":true}` | POST with a body (JSON when it starts with `{` or `[`, else plain text). `GET`, `POST`, `PUT` and `DELETE`; with a body and no method, POST |
| `wol:AA:BB:CC:DD:EE:FF` | Wake-on-LAN magic packet (broadcast, UDP port 9). The computer must allow wake on LAN in its BIOS and network adapter settings |
| `ha:light.toggle light.desk` | Home Assistant service `domain.service`, optionally for one entity: `ha:scene.turn_on scene.focus`, `ha:script.turn_on script.away` |

URLs and bodies can use [text variables](#text-variables), e.g. `{secret:webhook_key}`, so keys
stay out of backups.

**Home Assistant** needs two saved values (⚙ → **Saved values**): `ha_url` (e.g.
`https://xxxx.ui.nabu.casa` or `http://homeassistant.local:8123`) and `ha_token` (a long-lived
access token: Home Assistant → your profile → Security → Long-lived access tokens).

The **Home** page uses the same two values to show Home Assistant itself: the lights, switches,
covers, media players, scenes, scripts and automations, by area. At first only the main devices
show, the ones exposed to a voice assistant (Assist, Alexa, Google: a camera's *Flip* switch or a
router's guest Wi-Fi usually aren't); **Everything** shows the rest. Tap to toggle (run a scene or
script, enable or disable an automation; a lock asks first), hold to pin it to the top, hide it,
open / close / stop a cover, turn something on or off, or run an automation now. Chips filter by
kind, the search box by name, area or entity id. It reads Home Assistant every 10 s while it
shows. Entities hidden in Home Assistant stay hidden; reading which ones are exposed needs an
administrator's token (without it, everything shows).

**Next meeting on the idle clock**: set the saved value `ha_calendar` to a Home Assistant calendar
entity (e.g. `calendar.work`). The clock shows `Next: Standup · 10:30 (in 4 min)`, `Now: …` or
tomorrow's first event, refreshed every 5 minutes. Any calendar Home Assistant can read works.
With the [PC link](pc-link.md) and classic Outlook open on the computer, the clock shows Outlook's
next meeting instead.

## PC steps

`pc:` steps go to the computer over the [PC link](pc-link.md) (a Bluetooth serial port next to
the keyboard, Classic Bluetooth tablets only) instead of being typed:

| Step | Without the PC link |
|---|---|
| `pc:teams mute` (Windows 11 call mute) | `win+alt+k` |
| `pc:teams camera` / `hand` / `blur` / `leave` | Focus Teams (Win+8), its shortcut, Alt+Tab back |
| `pc:teams react like` (`love`, `applause`, `laugh`, `wow`) | — (a message says it needs the link) |
| `pc:run <name>` | — |
| `pc:click outlook 412,88` | — (needs the link) |
| `pc:typefile` (types the file selected in File Explorer, after asking; [details](pc-link.md#typing-a-file-pctypefile)) | — |

**Clicks**, usually [recorded](#recording) rather than written: `pc:click <app> <x>,<y>` clicks
that many pixels from the top-left of the app's window (process name; it is brought to the front
first), so it works wherever the window is. After the point, `right` or `double`; at the end,
`| <name>` clicks the button with that name wherever it is now (the point says where to look
first, and is clicked if the button isn't found within 3 s): `pc:click outlook 412,88 | Send`.
`pc:drag excel 100,200 300,400` drags from one point to the other, `pc:scroll excel 412,88 -3`
turns the wheel there (positive = up). `screen` in place of an app means points on the screen
(the taskbar, the desktop). Like `pc:focus`, the steps after them wait until they're done.

A key that is just `pc:teams mute`, `camera`, `hand` or `blur` lights up from Teams' real state
while the PC link reaches Teams, like Caps Lock; otherwise it is an ordinary toggle key.

## Keyboard layouts

A keyboard sends *key positions*, not characters, so:

- **Combos use US key names / positions** — `ctrl+/` presses the key that is `/` on a US keyboard.
  If the computer uses another layout and a shortcut involves punctuation, name the key by where
  it sits on a US keyboard (letters, digits, F-keys and named keys are the same everywhere).
  `iso` is the extra key left of Z on ISO keyboards.
- **`text:` uses the layout set in ⚙ → Computer keyboard layout** (English US, English US
  International, Spanish Latin America, Spanish Spain). Accented letters are typed with dead keys (´ + a → á). Characters a
  layout can't produce are skipped.

## Names

**Modifiers:** `ctrl` `shift` `alt` `win` (`super`, `gui`, `cmd`, `meta`), and right-hand
`rctrl` `rshift` `ralt`/`altgr` `rgui`/`rwin`/`rcmd`.

**Keys:** `a`–`z`, `0`–`9`, `f1`–`f24`, `enter`, `esc`, `tab`, `space`, `backspace`, `delete`,
`insert`, `home`, `end`, `pageup`, `pagedown`, `up`, `down`, `left`, `right`, `printscreen`,
`scrolllock`, `pause`, `capslock`, `numlock`, `menu`, `kp0`–`kp9`, `kpplus`, `kpminus`,
`kpasterisk`, `kpslash`, `kpenter`, `kpdot`, `iso`.

Punctuation: `minus` `equal` `plus` `lbracket` `rbracket` `backslash` `semicolon` `quote`
`grave` `comma` `period` `slash` — or the character itself (`ctrl+/`). Use `plus` for the `+` key,
because `+` separates keys in a combo.

**Media:** `playpause` `next` `prev` `stop` `mute` `volup` `voldown` `brightup` `brightdown`
`calc` `browser` `mail` `files` `search` `home` `back` `forward` `refresh`.

## Examples

```text
# Open an app through the Windows Run box
win+r
delay:400
text:notepad\n
```

```text
# Excel: paste values only
ctrl+alt+v
delay:300
v
enter
```

```text
# VS Code chord: Save all (Ctrl+K, then S)
ctrl+k
s
```

`F13`–`F24` are handy for app-specific shortcuts: most apps let you bind them, and no physical
keyboard sends them.

## Recording

Tap **●** to record. While recording:

- tapping keys on any page sends them **and** records them,
- **Type text** sends and records a `text:` step,
- **Key combo** sends and records a combo (with quick buttons for common ones),
- pauses between actions are kept as `delay:` steps (300 ms–2 s),
- **Undo** removes the last step, **Discard** throws the recording away.

With the [PC link](pc-link.md), switching apps on the computer while recording (by any means: the
tablet, its keyboard, the mouse) is recorded as `pc:focus <app>` once the app stays in front for a
second, so the macro switches the same way when it runs. Experimental and off by default: what
you type and click on the computer itself can be recorded too, clicks as [`pc:click`](#pc-steps)
([details](pc-link.md#recording-the-computers-keyboard-and-mouse-experimental)).

**Save** (●) shows the recorded steps first: move (↑ ↓) or delete (✕) any, tap a pause to change
it, **Try it** to run them on the computer, and, with the PC link, **Start by bringing … to the
front** (on unless the recording already starts so) to add `pc:focus <app>` for the app that was
in front when recording started. **Save…** then opens the key editor on the first free slot of
that app's page (the page shown for it, see [pages for apps](pc-link.md#pages-for-apps)), else of
the **Custom** page.

A macro with `pc:focus`, `pc:wait` or a recorded click runs in order: the keys before such a step
go out, the macro waits for the computer to say it's done, then goes on. If it isn't within 10 s, or the
PC link is down, the rest doesn't run (a message says why), so nothing is typed into the wrong
window.

Without that option the tablet can only *send* keys, so it can't watch what you do on the
computer itself: recording happens on the tablet.
