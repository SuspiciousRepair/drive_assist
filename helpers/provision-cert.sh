#!/usr/bin/env bash
# Helper script to install the MQTT client certificate into the app's private storage.
#
# NOTE: This is a helper script for now. Certificate provisioning will be handled
# directly via the app in the future.
#
# WHY THIS EXISTS: The client certificate is provisioned into private storage
# rather than bundled inside the APK. It lives in the app's private files
# directory (/data/data/$PKG/files), which `install -r` preserves across OTA
# updates. Do this ONCE per unit; a plain reinstall keeps it, an uninstall does not.
#
# It carries no password. The protection is ownership: 0600, owned by the app's
# uid, inside /data/data. A password in the code that opens the file protects
# nothing from anyone who can read the file.
#
# NEEDS adb, therefore needs the adb window OPEN — the switch on the Config
# screen, or via modehelper while the car is on the home network.
# See modehelper/AdbControl.java.
set -euo pipefail
cd "$(dirname "$0")"

PKG="${PKG:-com.geely.drivemem}"
CAR="${CAR:-}"
DEFAULT_P12="${GEELY_TOOLS:-$HOME/dev/geely}/mqtt-certs/drivemem-geely.p12"
[ -s "$DEFAULT_P12" ] || DEFAULT_P12="../../mqtt-certs/drivemem-geely.p12"
P12="${1:-$DEFAULT_P12}"

[ -s "$P12" ] || { echo "no certificate at $P12 — run mqtt-certs/gerar-certs.sh first"; exit 1; }

adb connect "$CAR" >/dev/null 2>&1 || true
adb -s "$CAR" shell true >/dev/null 2>&1 \
  || { echo "car unreachable at $CAR — is the adb window open?"; exit 1; }

# The app's uid, read rather than assumed: it changes on a fresh install.
UID_APP=$(adb -s "$CAR" shell stat -c %u "/data/data/$PKG" 2>/dev/null | tr -d '\r')
[ -n "$UID_APP" ] || { echo "$PKG is not installed on the car"; exit 1; }
echo "==> $PKG runs as uid $UID_APP"

# Staged through /data/local/tmp because `adb push` writes as the shell user and
# cannot write into another app's private directory directly.
adb -s "$CAR" push "$P12" /data/local/tmp/mqtt_client.p12 >/dev/null
adb -s "$CAR" shell "
  set -e
  mkdir -p /data/data/$PKG/files
  cp /data/local/tmp/mqtt_client.p12 /data/data/$PKG/files/mqtt_client.p12
  chown $UID_APP:$UID_APP /data/data/$PKG/files /data/data/$PKG/files/mqtt_client.p12
  chmod 700 /data/data/$PKG/files
  chmod 600 /data/data/$PKG/files/mqtt_client.p12
  # Copied as root, so the file inherits the shell's label rather than the app's.
  # Without this the app is denied its own certificate by SELinux, and the
  # symptom is a TLS handshake failure that looks nothing like a permission
  # problem.
  restorecon -F /data/data/$PKG/files/mqtt_client.p12 2>/dev/null || true
  rm -f /data/local/tmp/mqtt_client.p12
"

echo "==> installed:"
adb -s "$CAR" shell "ls -lZ /data/data/$PKG/files/mqtt_client.p12"
echo
echo "Restart the app and look for 'tls: client certificate loaded from' in the log."
echo "Only once that appears is it safe to set require_certificate: true on the broker —"
echo "before that, the car cannot present a certificate and loses the remote path."
