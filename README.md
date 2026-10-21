# Macropad for Android

Turns a spare Android tablet into a wireless **touch macropad** for your computer. The tablet
pairs over Bluetooth Low Energy as a standard **BLE keyboard (HID over GATT)**, so nothing has
to be installed on the computer — Windows, macOS, Linux, ChromeOS, iOS and Android all support it
natively. Everything runs on the tablet.

Built and tested on an **Amazon Fire HD 8 (8th gen, KFKAWI, Fire OS 7.3 / Android 9)** paired with
Windows laptops/desktops.

![Keys are dark, accent-tinted tiles with an icon, label and shortcut hint](docs/screenshot.png)

## Features

- **Pages (tabs)** with ready-made shortcuts: **Desktop** (open apps, virtual desktops),
  **VS Code**, **Chrome**, **Excel**, **Word**, **Teams**, plus an empty **Custom** page.
- **Media dock** on every page: Previous · Play/Pause · Next · Volume − · Volume +.
- **Tablet volume buttons** act as macropad keys (PC volume by default; assignable to any action).
- **Edit mode** (✏️): tap any key to change its label, action, accent color and icon (95 icons).
- **Macro language**: key combos, media keys, typed text, delays — see
  [docs/macro-language.md](docs/macro-language.md).
- **Recorder** (●): press keys, type text or send combos — each is sent to the computer live and
  captured with its timing — then save it as a new key on the Custom page.
- Keys behave like real keys: single combos are *held* while touched (auto-repeat, push-to-talk);
  multi-step macros run on tap. Several keys can be held at once.
- Runs full-screen, keeps the screen on, and shows over the lock screen.

## Requirements

| | |
|---|---|
| Tablet | Android 9 (API 28) or newer with BLE **peripheral/advertising** support |
| Build machine | JDK 17, Android SDK (platform 34, build-tools 34). Gradle comes via the wrapper. |
| Computer | Any OS with Bluetooth LE. Text typing assumes a **US keyboard layout** on the computer. |

> Many tablets — including Fire tablets — ship with the *Classic* Bluetooth HID-device profile
> disabled (`profile_supported_hid_device=false`), which is why this app uses BLE HID over GATT
> instead of Android's `BluetoothHidDevice` API.

## Build

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk      # or put sdk.dir in local.properties
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Install and set up the tablet

1. Enable **Developer options → USB debugging** on the tablet and connect it over USB.
   On Linux you may need a udev rule, e.g. for Amazon (vendor `1949`):
   ```bash
   echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="1949", MODE="0666", GROUP="plugdev"' \
     | sudo tee /etc/udev/rules.d/51-android.rules
   sudo udevadm control --reload-rules && sudo udevadm trigger
   ```
2. Install the app:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. **Grant the secure-settings permission** (one time). The app uses it to stop the tablet from
   treating the paired PC as headphones and from accepting Classic Bluetooth connections — both
   of which make Windows drop the keyboard (see [troubleshooting](docs/bluetooth-troubleshooting.md)):
   ```bash
   adb shell pm grant dev.belta.macropad android.permission.WRITE_SECURE_SETTINGS
   ```
4. Optional, recommended for a dedicated macropad:
   ```bash
   adb shell settings put global stay_on_while_plugged_in 7   # stay awake while charging
   ```
   Use a charger of at least 2 A — with the screen always on, a weak USB port can't keep up.

## Pair with a computer

1. Open **Macropad** on the tablet; the status chip shows **Ready to pair**.
2. On the computer, add a Bluetooth device and choose **Fire Macropad**
   (Windows: *Settings → Bluetooth & devices → Add device → Bluetooth*).
3. **Confirm the 6-digit code on both screens.** The tablet shows the code and opens its
   notification shade — tap **Pair** there.
4. Windows connects, disconnects and reconnects within a few seconds (normal for BLE keyboards).
   The chip turns **green** with the computer's name.

The computer reconnects automatically whenever the app is open. One computer is connected at a
time. **Avoid opening Bluetooth settings on the tablet** — its scan re-learns the PC as a Classic
device. If connections start dropping, use ⚙ → **Reset pairing (forget computers)**, then remove
and re-add *Fire Macropad* on the computer.

## Using it

| Control | What it does |
|---|---|
| Tabs | Switch page. Long-press a tab to rename it. |
| Status chip | Connection state; tap for details. |
| ● / ■ | Start / stop recording a macro. |
| ✏️ / ✓ | Edit mode: tap a key to edit, tap an empty slot to add one. |
| ⚙ | Grid size, tablet volume buttons, pages, Bluetooth restart / reset, reset keys to defaults. |
| Tablet volume buttons | Assigned actions (default: PC volume). Clear an assignment to give the button back to the tablet. |

Shortcuts on the app pages are the **Windows** defaults. On macOS, edit keys to use `cmd` instead
of `ctrl`.

## Project layout

```
app/src/main/java/dev/belta/macropad/
  HidPeripheral.kt  BLE HID-over-GATT peripheral: GATT services, advertising, report sending,
                    reconnect handling and Fire OS workarounds
  Macro.kt          Macro language parser, key/media usage tables, key hint text
  MacroStore.kt     Layout model, default pages, JSON persistence, migrations for new pages
  MainActivity.kt   UI (tabs, key grid, dock, editor, recorder, settings), volume-button input
  Icons.kt          Generated icon table
app/src/main/res/drawable/ic_k_*.xml   Generated key icons
app/src/main/res/font/                 Inter font
tools/gen_icons.py                     Regenerates the icons and Icons.kt
docs/                                  Macro language, Bluetooth troubleshooting
licenses/                              Third-party licenses
```

## Debugging

The app logs every GATT request, connection change and workaround under the tag `HidPeripheral`:

```bash
adb logcat -s HidPeripheral
```

## Credits and licenses

- [Inter](https://rsms.me/inter/) font — SIL Open Font License 1.1 ([licenses/Inter-OFL.txt](licenses/Inter-OFL.txt)).
- Most key icons use path data from Google's [Material Icons](https://fonts.google.com/icons) — Apache License 2.0.
