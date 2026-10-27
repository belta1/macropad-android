#!/usr/bin/env bash
# Builds the release APK (signed with keystore.properties, like GitHub Releases, so either can
# update the other) and installs it over Wi-Fi adb, keeping the tablet's layout and pairing.
#
#   tools/deploy.sh fire          build, install on the Fire and open the app
#   tools/deploy.sh tab           the same on the Galaxy Tab (do this after testing on the Fire)
#   tools/deploy.sh fire --log    ...then follow the app's log
#   tools/deploy.sh fire --no-build
#
# Addresses come from MACROPAD_FIRE / MACROPAD_TAB (ip:port). The Fire listens on 5555 after one
# `adb tcpip 5555` over USB (until it reboots); the Galaxy's Wireless debugging port changes, so
# pair it once (`adb pair ip:port code`) and pass its current port in MACROPAD_TAB.
set -euo pipefail

cd "$(dirname "$0")/.."
SDK=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)
ADB=${ADB:-${SDK:-$HOME/Android/Sdk}/platform-tools/adb}
export JAVA_HOME=${JAVA_HOME:-$HOME/Android/jdk17}
APK=app/build/outputs/apk/release/app-release.apk
PACKAGE=dev.belta.macropad

case "${1:-}" in
  fire) TARGET=${MACROPAD_FIRE:-192.168.1.251:5555} ;;
  tab)  TARGET=${MACROPAD_TAB:-}
        [ -n "$TARGET" ] || { echo "Set MACROPAD_TAB=ip:port (Galaxy → Developer options → Wireless debugging)"; exit 1; } ;;
  *)    sed -n '2,10p' "$0"; exit 1 ;;
esac
shift
BUILD=1; LOG=0
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --log) LOG=1 ;;
    *) echo "Unknown option $arg"; exit 1 ;;
  esac
done

"$ADB" connect "$TARGET" >/dev/null
"$ADB" -s "$TARGET" get-state >/dev/null 2>&1 || { echo "$TARGET is not reachable over adb"; exit 1; }

[ "$BUILD" = 1 ] && ./gradlew -q assembleRelease
# -r keeps the app's data. A signature mismatch fails here; never uninstall to get past it
# (that wipes the layout and saved values).
"$ADB" -s "$TARGET" install -r "$APK"
# Fire OS Bluetooth workarounds (docs/bluetooth-troubleshooting.md); harmless where unused.
"$ADB" -s "$TARGET" shell pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
"$ADB" -s "$TARGET" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
echo "Installed $(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts) on $TARGET"

if [ "$LOG" = 1 ]; then
  PID=$("$ADB" -s "$TARGET" shell pidof "$PACKAGE" | tr -d '\r')
  "$ADB" -s "$TARGET" logcat --pid="$PID"
fi
