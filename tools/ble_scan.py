"""Watch the Bluetooth address the macropad advertises with, from the computer.

Windows reconnects to a bonded BLE keyboard by its address. When the tablet advertises with a
rotating private address and Windows can't resolve it, Windows never reconnects and the keyboard
must be paired again. This script shows which address the tablet uses and when it changes.

    pip install bleak
    python tools/ble_scan.py            # one 10 s scan
    python tools/ble_scan.py --watch    # keep scanning, print a line whenever the address changes
"""

import argparse
import asyncio
import datetime

from bleak import BleakScanner

HID_SERVICE = "00001812-0000-1000-8000-00805f9b34fb"


def address_kind(address: str) -> str:
    """The kind of random address, from its two top bits (a public address can't be told apart)."""
    top = int(address.split(":")[0], 16) >> 6
    return {0b11: "static random / public", 0b01: "resolvable private (rotates)",
            0b00: "non-resolvable private"}.get(top, "reserved")


async def scan(seconds: float) -> dict:
    found = {}

    def seen(device, adv):
        if HID_SERVICE in (adv.service_uuids or []) or "macropad" in (adv.local_name or device.name or "").lower():
            found[device.address] = (adv.local_name or device.name or "?", adv.rssi)

    async with BleakScanner(seen, scanning_mode="active"):
        await asyncio.sleep(seconds)
    return found


async def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--watch", action="store_true", help="keep scanning and log address changes")
    parser.add_argument("--seconds", type=float, default=10, help="length of each scan (default 10)")
    args = parser.parse_args()
    last = None
    while True:
        found = await scan(args.seconds)
        now = datetime.datetime.now().strftime("%H:%M:%S")
        current = set(found)
        if not args.watch or current != last:
            if not found:
                print(f"{now}  no macropad advertising (connected, out of range or off)")
            for address, (name, rssi) in found.items():
                print(f"{now}  {address}  {address_kind(address):30}  {name}  {rssi} dBm")
        last = current
        if not args.watch:
            break


if __name__ == "__main__":
    asyncio.run(main())
