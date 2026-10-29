# Tablets

One APK runs on any Android 9+ tablet. What differs between tablets is the Bluetooth transport,
a few Fire OS workarounds, and what the tablet needs so Android leaves the macropad running.

| | Amazon Fire HD 8 (Fire OS 7) | Samsung Galaxy Tab S5e (Android 11) |
|---|---|---|
| Bluetooth | LE only (Fire OS disables the Classic HID profile) | **Classic** where available (Automatic), else LE |
| Reconnects after a drop | The computer reconnects within seconds | The tablet calls the computer itself, every 5 s for 2 min, then every 30 s |
| Name computers see | Fire Macropad | Macropad (⚙ → Connection → Bluetooth name) |
| Classic Bluetooth while running | Hidden (Fire OS workaround) | Left alone (earbuds etc. keep working) |
| Screen | LCD, 600 dp | OLED, ~800 dp: keys scale up; Screen protection on |
| [PC link](pc-link.md) (Teams, app in front, Outlook) | — (needs Classic Bluetooth) | Yes |

The app detects a Fire tablet by its manufacturer and only applies the Fire OS workarounds
([bluetooth-troubleshooting.md](bluetooth-troubleshooting.md)) there.

## Choosing the Bluetooth transport

⚙ → **Connection → Bluetooth**:

- **Automatic** (default): Classic Bluetooth if the tablet has the HID Device profile, else LE.
- **Bluetooth LE**: works on every tablet that can advertise as a BLE peripheral.
- **Classic Bluetooth**: the tablet has a fixed address and connects to the computer by itself,
  so it comes back as soon as the computer's Bluetooth does.

Changing it makes the macropad a different keyboard: remove it on the computer and pair again.
Diagnostics shows the transport in use.

Pairing over Classic Bluetooth: the computer only finds the tablet while it is *visible*. Tap the
status, then **Make visible** (or ⚙ → Connection → Make visible for pairing), and add the device
on the computer within two minutes.

## Fire HD 8

Set up as in the [README](../README.md#install): the `pm grant … WRITE_SECURE_SETTINGS` command
and *stay awake while charging*.

## Galaxy Tab S5e (and other Samsung tablets)

1. Install the same APK. Allow Bluetooth and notifications when asked.
2. ⚙ → **Connection → Run in the background** → allow. Otherwise Samsung's battery saving can put
   the macropad to sleep. Also: Settings → Apps → Macropad → Battery → **Unrestricted**.
3. ⚙ → **Startup**: to open on boot or when charging, allow **Display over other apps** when
   asked (Android 10+). Without it, a notification offers to open the macropad instead.
4. Settings → Battery → **Protect battery** (85 %), since the tablet stays on the charger.
5. Developer options → **Stay awake**, if the screen should stay on while charging.
6. Optional, as on the Fire: `adb shell pm grant dev.belta.macropad android.permission.WRITE_SECURE_SETTINGS`
   lets the app keep the computer from being treated as headphones.

The S5e's screen is OLED. **Screen protection** (⚙, on by default on non-Fire tablets) moves the
whole screen by a couple of pixels every 3 minutes. **Dim when idle** turns it into a drifting
clock.

When the macropad quits (notification → Quit), the tablet gets its own Bluetooth name back, so
OneNote and everything else on the tablet are as before.

## Moving to another tablet

- ⚙ → **Backup and restore → Export** on the old tablet, **Import** on the new one: pages, keys,
  dock and the per-computer settings.
- **Saved values** (logins) are encrypted with a key that never leaves the tablet. Enter them again
  on the new tablet.
- Each tablet is its own keyboard: pair it with the computer separately. Two tablets can be paired
  with the same computer at once.
