# Bluetooth notes and troubleshooting

Notes from getting a Fire HD 8 (Fire OS 7.3 / Android 9) to work as a keyboard for Windows,
including the reasons for the workarounds in `HidPeripheral.kt`.

## Why BLE HID over GATT

Android 9+ has a public `BluetoothHidDevice` API (Classic Bluetooth HID), but it needs the
Bluetooth stack's HID-device profile. On Fire OS it is compiled out:

```bash
adb pull /system/app/Bluetooth/Bluetooth.apk
aapt2 dump resources Bluetooth.apk | grep -A1 profile_supported_hid_device
#   () false
```

BLE peripheral mode (GATT server + advertising) *is* available, so the app implements the
**HID over GATT profile** itself: HID service (0x1812) with a keyboard report (ID 1, plus LED
output report) and a consumer-control report (ID 2), Battery service and Device Information
(PnP ID). All HID characteristics require an encrypted link, which makes the computer pair.

## Fire OS / Windows problems and fixes

| Symptom | Cause | Fix in the app |
|---|---|---|
| Pairing fails after ~1 s; Windows shows a code but nothing on the tablet | Fire OS puts the numeric-comparison prompt in a notification when another app is in front; the PC times out | The app shows the code itself and pulls down the notification shade so **Pair** is visible |
| Windows disconnects ~3 s after pairing | Right after bonding, Fire OS scans the PC's audio profiles and starts connecting A2DP/Hands-Free to it as if it were headphones | On connect, the app sets `bluetooth_a2dp_sink_priority_<addr>` and `bluetooth_headset_priority_<addr>` to 0 (needs `WRITE_SECURE_SETTINGS`) and disconnects those profiles |
| After a disconnect (out of range, PC asleep, tablet Bluetooth off) Windows still lists the keyboard but never uses it again; only removing and re-adding it helps (1.0–1.5) | Rebuilding the GATT server after each disconnect made the tablet report "services changed"; Windows rediscovered them, came away without the keyboard service (its device then shows only the `1800` / `1801` services) and stopped using the keyboard | Since 1.6 the app keeps one GATT server for as long as it runs and only advertises again after a disconnect. Windows reconnects by itself within seconds, after app updates and Bluetooth restarts too. A keyboard paired while 1.5 or older was running must be removed and paired once more |
| Bond silently deleted; link dies ~15 s later | Windows also tried Classic pairing (the tablet's Classic side has the same name); Fire OS answers "already bonded (removing)" and wipes the LE keys | While running, the app makes Classic Bluetooth non-connectable (`setScanMode(SCAN_MODE_NONE)`, needs `WRITE_SECURE_SETTINGS`); restored when the app stops |
| Keys work, then the link drops every ~15 s ("L2CAP connection rejected") | The tablet has the PC cached as a *dual-mode* device and keeps opening Classic channels to it; the PC refuses, and Fire OS tears down every link to that PC | ⚙ → **Diagnostics… → Reset pairing**: unbonds the computers and restarts Bluetooth to clear the cache; then re-pair on the computer |
| Windows doesn't re-enable notifications on reconnect | Bonded hosts expect CCCD values to persist | CCCD values are stored per host and survive reconnects |

**Don't make the tablet discoverable or open its Bluetooth settings while pairing.** That exposes
the Classic side, and Windows may pair with *that* instead of the keyboard (it has no keys).

## Diagnostics screen

⚙ → **Diagnostics…** (or tap the status chip) shows, without adb:

- connection state, connected computer and whether the tablet is paired with it,
- which computer the macropad is set to (⚙ → Computers),
- whether Classic Bluetooth is hidden and the secure-settings permission is granted (✓/✗),
- whether audio profiles are blocked for the connected computer,
- the last 60 Bluetooth events (connects, disconnects, ignored computers, workaround failures).

It also has **Restart Bluetooth** and **Reset pairing** buttons.

## Updating the app

The Bluetooth keyboard runs in a foreground service (notification "Macropad"); updating the app
restarts it and the computer reconnects by itself. When the HID report map changes (1.1 added the
mouse), Windows keeps using its cached copy until the device is removed and re-added.

## Bluetooth event log

The diagnostics events are also written to a file that survives restarts (on Fire OS, logcat is
overwritten within minutes):

```bash
adb pull /sdcard/Android/data/dev.belta.macropad/files/bluetooth-events.log
```

[tools/ble_scan.py](../tools/ble_scan.py) shows, from the computer, the address the tablet
advertises with (`pip install bleak`, then `python tools/ble_scan.py --watch`).

## Clean re-pair procedure

1. Tablet: ⚙ → **Diagnostics… → Reset pairing**, or over adb:
   ```bash
   adb shell am start -n dev.belta.macropad/.MainActivity --ez reset_pairing true
   ```
2. Computer: remove **every** "Fire Macropad" entry (Windows: Bluetooth & devices, and
   Control Panel → Devices and Printers).
3. Computer: add **Fire Macropad** again and confirm the code on both screens.

## Useful commands

```bash
adb logcat -s HidPeripheral                       # app: GATT requests, connections, workarounds
adb shell dumpsys bluetooth_manager | grep -E "state:|ScanMode"
adb shell dumpsys bluetooth_manager | sed -n '/Bonded devices/,/^$/p'
adb shell settings list global | grep priority_    # per-device profile priorities
adb logcat -G 16M                                  # bigger log buffer (default is tiny on Fire OS)
```

Disconnect reasons in the log: `0x13` = the remote (computer) ended the connection,
`0x16` = the tablet ended it, `0x08` = link timeout (out of range / interference).
