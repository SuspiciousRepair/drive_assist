#!/usr/bin/env bash
#
# Drive Assist One-Shot Automated Installer for Geely EX2 (IHU629G)
#
# Automatically connects to the vehicle over Wi-Fi, installs ModeHelper (privileged companion),
# installs Drive Assist, applies the Bluetooth OBD2 PIN fix, and launches the app.
#
# Usage:
#   ./install.sh [CAR_IP]
#
# Example:
#   ./install.sh 192.168.0.150
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CAR="${1:-${CAR_IP:-}}"

echo "========================================================"
echo "  🚗 Drive Assist — Geely EX2 One-Shot Installer"
echo "========================================================"

# 1. Connect via ADB
if [ -n "$CAR" ]; then
  [[ "$CAR" != *":"* ]] && CAR="${CAR}:5555"
  echo "==> Connecting to car at $CAR..."
  adb connect "$CAR"
else
  echo "==> Checking existing ADB connection..."
  if ! adb get-state >/dev/null 2>&1; then
    echo "Error: No car IP supplied and no active ADB device found."
    echo ""
    echo "Usage: $0 <CAR_IP>"
    echo "Example: $0 192.168.0.150"
    exit 1
  fi
fi

echo "==> Verifying target device..."
DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
echo "    Connected device model: ${DEVICE_MODEL:-Unknown}"

# 2. Locate APKs
INSTALLER_APK="$HERE/drive_assist_installer.apk"
DRIVE_ASSIST_APK="$HERE/drive_assist.apk"
[ -f "$DRIVE_ASSIST_APK" ] || DRIVE_ASSIST_APK="$HERE/drivemem/drive_assist.apk"
MODEHELPER_APK="$HERE/modehelper/modehelper.apk"

if [ -f "$INSTALLER_APK" ]; then
  echo "==> Installing Drive Assist via Standalone Installer..."
  adb install -r -g "$INSTALLER_APK"
  echo "==> Triggering setup wizard..."
  adb shell am start -n com.geely.installer/.InstallerActivity
  echo "    Installer started on car screen (will self-clean on completion)."
elif [ -f "$DRIVE_ASSIST_APK" ] && [ -f "$MODEHELPER_APK" ]; then
  # 3. Install ModeHelper (system privilege companion)
  echo "==> Installing ModeHelper (system companion app)..."
  adb install -r -g "$MODEHELPER_APK"
  echo "    ModeHelper installed successfully."

  # 4. Install Drive Assist (main UI application)
  echo "==> Installing Drive Assist..."
  adb install -r -g "$DRIVE_ASSIST_APK"
  echo "    Drive Assist installed successfully."

  # Start Services & Launch App
  echo "==> Starting ModeHelper background service..."
  adb shell am start-foreground-service com.geely.modehelper/.ModeHelperService || true

  echo "==> Launching Drive Assist on the car screen..."
  adb shell am start -n com.geely.drivemem/.ui.ComfortActivity
else
  echo "Error: Neither drive_assist_installer.apk nor drive_assist.apk/modehelper.apk found."
  echo "Run ./build.sh first to compile the project."
  exit 1
fi

# Apply Bluetooth OBD2 PIN Fix (0000 -> 1234)
echo "==> Applying Bluetooth OBD2 PIN fix (btDefSetting.json)..."
if [ -x "$HERE/bt-pin-fix/apply-pin-1234.sh" ]; then
  "$HERE/bt-pin-fix/apply-pin-1234.sh"
else
  echo "    Skipping PIN fix (script not executable)."
fi

echo ""
echo "========================================================"
echo "  ✅ Installation Complete!"
echo "  Drive Assist is now running on your vehicle screen."
echo "========================================================"
