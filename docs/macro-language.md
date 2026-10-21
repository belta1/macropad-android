# Macro language

Every key has an **action**: one step per line. Lines starting with `#` are comments.

| Step | Example | Meaning |
|---|---|---|
| Key combo | `ctrl+shift+t` | Modifiers plus up to 6 keys, pressed together |
| Media key | `media:volup` | Consumer-control key |
| Text | `text:Hello world` | Types the text (US layout). `\n` = Enter, `\t` = Tab |
| Delay | `delay:250` | Waits, in milliseconds (max 10000). `wait:` also works |

A key whose action is a **single combo or media key** is *held* for as long as it is touched,
like a physical key (auto-repeat, push-to-talk, holding a modifier while pressing another key).
Anything longer runs once per tap.

## Names

**Modifiers:** `ctrl` `shift` `alt` `win` (`super`, `gui`, `cmd`, `meta`), and right-hand
`rctrl` `rshift` `ralt`/`altgr` `rgui`/`rwin`/`rcmd`.

**Keys:** `a`–`z`, `0`–`9`, `f1`–`f24`, `enter`, `esc`, `tab`, `space`, `backspace`, `delete`,
`insert`, `home`, `end`, `pageup`, `pagedown`, `up`, `down`, `left`, `right`, `printscreen`,
`scrolllock`, `pause`, `capslock`, `numlock`, `menu`, `kp0`–`kp9`, `kpplus`, `kpminus`,
`kpasterisk`, `kpslash`, `kpenter`, `kpdot`.

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
