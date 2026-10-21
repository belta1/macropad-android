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

### Text variables

`text:` steps can contain variables, filled in when the key runs:

| Variable | Example result |
|---|---|
| `{date}` | `2026-10-21` |
| `{time}` | `14:05` |
| `{date:dd/MM/yyyy}`, `{time:HH:mm:ss}` | any [SimpleDateFormat](https://developer.android.com/reference/java/text/SimpleDateFormat) pattern |
| `{clipboard}` | the **tablet's** clipboard text (the tablet can't read the computer's clipboard) |
| `{secret:name}` | a saved value (⚙ → **Saved values**), e.g. a login; encrypted on the tablet, never in backups or exports. The key does nothing until every value it uses is set |
| `{{` | a literal `{` |

Dates and times come from the tablet's clock, so keep its date and time correct.

### Typing code and long texts

`text:{clipboard}` (the sidebar's **Type clipboard** key) types exactly what was copied on the tablet:
backslashes, quotes, braces and `{...}` inside the copied text are typed as they are; line breaks
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

**Save** opens the editor with the recorded steps on the first free slot of the **Custom** page,
where they can be tweaked before saving.

The tablet can only *send* keys, so it can't watch what you do on the computer itself. Recording
happens on the tablet.
