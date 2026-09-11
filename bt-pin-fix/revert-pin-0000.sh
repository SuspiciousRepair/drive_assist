#!/bin/bash
# Undoes apply-pin-1234.sh: restores the original btDefSetting.json
# (pairingCode back to 0000) exactly as pulled from the car.
#
# Usage:
#   ./revert-pin-0000.sh [CAR_IP]
#   Example: ./revert-pin-0000.sh 192.168.0.150
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET=/system/etc/bluetooth/btDefSetting.json
CAR="${1:-${CAR_IP:-}}"

if [ -n "$CAR" ]; then
  [[ "$CAR" != *":"* ]] && CAR="${CAR}:5555"
  echo "==> connecting to the car at $CAR"
  adb connect "$CAR"
else
  echo "==> checking existing adb connection..."
  if ! adb get-state >/dev/null 2>&1; then
    echo "Error: No car IP supplied and no active ADB connection found."
    echo "Usage: $0 <CAR_IP>"
    echo "Example: $0 192.168.0.150"
    exit 1
  fi
fi

echo "==> remounting /system read-write"
adb shell mount -o rw,remount /system

echo "==> pushing back the original config"
adb push "$HERE/btDefSetting.json.orig" "$TARGET"

echo "==> verifying"
adb shell cat "$TARGET"

echo "==> remounting /system read-only"
adb shell mount -o ro,remount /system

echo "==> restarting the Bluetooth service"
adb shell svc bluetooth disable
sleep 2
adb shell svc bluetooth enable

echo "==> done. Original pairingCode (0000) restored."
