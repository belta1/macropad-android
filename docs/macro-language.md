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

A key whose action is a **single combo, media key or click** is *held* for as long as it is
touched, like a physical key (auto-repeat, push-to-talk, holding a modifier, dragging with a held
click). Anything longer runs once per tap. Keys marked **Toggle** in the editor fire once per tap
and show ON/OFF (the state resets when the connection drops, since the computer's real state is
unknown).

## Keyboard layouts

A keyboard sends *key positions*, not characters, so:

- **Combos use US key names / positions** — `ctrl+/` presses the key that is `/` on a US keyboard.
  If the computer uses another layout and a shortcut involves punctuation, name the key by where
  it sits on a US keyboard (letters, digits, F-keys and named keys are the same everywhere).
  `iso` is the extra key left of Z on ISO keyboards.
- **`text:` uses the layout set in ⚙ → Computer keyboard layout** (English US, Spanish Latin
  America, Spanish Spain). Accented letters are typed with dead keys (´ + a → á). Characters a
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
