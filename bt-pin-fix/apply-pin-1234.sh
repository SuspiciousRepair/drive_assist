#!/bin/bash
# Changes the head unit's Bluetooth auto-pairing PIN from 0000 to 1234, to
# match the vLinker MC+ dongle's actual default PIN. See docs/field-catalog.md's
# "Bluetooth pairing wall" section for the full investigation this comes from.
#
# Usage:
#   ./apply-pin-1234.sh [CAR_IP]
#   Example: ./apply-pin-1234.sh 192.168.0.150
#
# Reversible: run revert-pin-0000.sh (same folder) to restore the original.
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

echo "==> pushing edited config (pairingCode: 0000 -> 1234)"
adb push "$HERE/btDefSetting.json.new" "$TARGET"

echo "==> verifying"
adb shell cat "$TARGET"

echo "==> remounting /system read-only"
adb shell mount -o ro,remount /system

echo "==> restarting the Bluetooth service (not a full reboot)"
adb shell svc bluetooth disable
sleep 2
adb shell svc bluetooth enable

echo "==> done. Try pairing the dongle now."
