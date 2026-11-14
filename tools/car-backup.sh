#!/usr/bin/env bash
#
# tools/car-backup.sh — full backup and restore of Drive Assist and ModeHelper.
#
#   ./tools/car-backup.sh [CAR_IP]                      back up the car
#   ./tools/car-backup.sh --restore <BACKUP_DIR> [CAR_IP]  restore onto the car
#
# The app does the backup itself (Config > System > Backup): one plain folder,
# the SAME on the USB stick, on the car storage and here:
#
#   settings.json     the user's settings, readable JSON
#   car.db            trips, charges, telemetry (SQLite)
#   mqtt_client.p12   MQTT client certificate, when there is one
#   mqtt_ca.crt       custom MQTT CA, when there is one
#
# This script drives that same code over adb (BackupReceiver), and adds what
# only a computer can keep: the two apks (MANIFEST has versions and checksums).
# Restore merges the trips with any the car recorded since (no doubles), puts
# the settings and certificates back, and restarts the app.
#
# The folder holds SECRETS (MQTT password, ABRP and Spotify tokens). It is
# written outside the repo, mode 0700, and must never be shared.
#
# Env: CAR_IP (instead of the argument), BACKUP_ROOT (default ~/drive-assist-backups).
set -Eeuo pipefail

PKGS=(com.geely.modehelper com.geely.drivemem)   # install order: helper first
RECV=com.geely.drivemem/.util.BackupReceiver
CAR_BACKUPS="/sdcard/Drive Assist/Backups"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/drive-assist-backups}"
CAR=""
MODE="backup"
SRC=""

usage() {
  sed -n '3,6p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --restore) MODE="restore"; SRC="${2:-}"; [ -n "$SRC" ] || usage; shift 2 ;;
    -h|--help) usage ;;
    *) CAR="$1"; shift ;;
  esac
done
CAR="${CAR:-${CAR_IP:-}}"

say()  { printf '==> %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# --- Connect ------------------------------------------------------------------
if [ -n "$CAR" ]; then
  [[ "$CAR" == *:* ]] || CAR="$CAR:5555"
  adb connect "$CAR" >/dev/null 2>&1 || true
  export ANDROID_SERIAL="$CAR"
fi
adb get-state >/dev/null 2>&1 || die "no car on ADB${CAR:+ at $CAR}. Is Wi-Fi ADB on?"

# Right after a boot the head unit runs at load 10+ for several minutes and
# adb calls can stall. Every call gets a timeout and one retry.
ash() {
  local try
  for try in 1 2 3; do
    if timeout 60 adb shell "$@"; then return 0; fi
    [ "$try" -lt 3 ] && { echo "    (car slow to answer, retrying)" >&2; sleep 5; }
  done
  echo "ERROR: the car stopped answering (asleep, off Wi-Fi, or ADB closed)." >&2
  return 1
}


# Read the whole output first: piping adb into `grep -q`/`head` closes the pipe
# early, adb then exits non-zero, and ash would count that as a failure.
installed() { local o; o=$(ash pm path "$1" 2>/dev/null) || return 1; [[ "$o" == package:* ]]; }
version()   { local o; o=$(ash dumpsys package "$1") || return 1
              grep -m1 -oE 'versionName=[^ ]+' <<<"$o" | cut -d= -f2 | tr -d '\r'; }

# Waits for the app's "backup: ..." line in logcat after $since. Prints it.
wait_log() {
  local since="$1" pattern="$2" i out line
  for i in $(seq 1 36); do
    out=$(ash "logcat -d -T '$since' -s DriveMem" 2>/dev/null || true)
    line=$(grep -E "$pattern" <<<"$out" | tail -1 || true)
    [ -n "$line" ] && { echo "${line#*DriveMem: }"; return 0; }
    sleep 5
  done
  return 1
}

car_now() { ash "date '+%m-%d %H:%M:%S.000'" | tr -d '\r'; }

# --- Backup ---------------------------------------------------------------------
backup() {
  local out since line dir stamp pkg apk
  installed com.geely.drivemem || die "Drive Assist is not installed"
  since=$(car_now)
  say "Asking the app for a backup"
  ash am broadcast -a com.geely.drivemem.BACKUP -n "$RECV" >/dev/null
  line=$(wait_log "$since" 'backup: (ok|failed)') || die "no answer from the app in 3 minutes"
  [[ "$line" == "backup: ok"* ]] || die "$line"

  # "backup: ok [/storage/XXXX-XXXX/Drive Assist/Backups/<stamp>, /storage/emulated/0/...]"
  dir=$(grep -oE '/storage/emulated/0/Drive Assist/Backups/[0-9_-]+' <<<"$line" | head -1 || true)
  [ -n "$dir" ] || dir=$(grep -oE '/storage/[^,]+/Drive Assist/Backups/[0-9_-]+' <<<"$line" | head -1 || true)
  [ -n "$dir" ] || die "could not read the backup folder from: $line"
  stamp=$(basename "$dir")

  mkdir -p "$BACKUP_ROOT"; chmod 700 "$BACKUP_ROOT"
  out="$BACKUP_ROOT/$stamp"
  mkdir -m 700 "$out"
  trap 'mv "$out" "$out.INCOMPLETE" 2>/dev/null; echo "ERROR: backup incomplete, kept as $out.INCOMPLETE" >&2' ERR
  say "Copying $dir to $out"
  timeout 300 adb pull "$dir/." "$out/" >/dev/null
  [ -f "$out/settings.json" ] || die "settings.json missing in the pulled backup"

  {
    echo "date:   $(date -Iseconds)"
    echo "car:    $(ash getprop ro.product.model | tr -d '\r') $(ash getprop ro.build.display.id | tr -d '\r')"
  } > "$out/MANIFEST"
  for pkg in "${PKGS[@]}"; do
    installed "$pkg" || continue
    echo "$pkg: $(version "$pkg")" >> "$out/MANIFEST"
    apk=$(ash pm path "$pkg"); apk=$(head -1 <<<"$apk" | tr -d '\r'); apk="${apk#package:}"
    timeout 120 adb pull "$apk" "$out/$pkg.apk" >/dev/null
  done

  if command -v sqlite3 >/dev/null && [ -f "$out/car.db" ]; then
    [ "$(sqlite3 "$out/car.db" 'PRAGMA integrity_check;')" = ok ] \
      && echo "    car.db: integrity ok, $(sqlite3 "$out/car.db" 'SELECT COUNT(*) FROM trip') trips" \
      || echo "    WARNING: car.db failed its integrity check"
  fi
  trap - ERR
  (cd "$out" && sha256sum -- * 2>/dev/null | grep -v ' MANIFEST$') >> "$out/MANIFEST"
  chmod -R go-rwx "$out"
  say "Done: $out ($(du -sh "$out" | cut -f1)). It contains secrets; keep it private."
}

# --- Restore --------------------------------------------------------------------
restore() {
  local pkg stamp dest since line f
  [ -f "$SRC/settings.json" ] || die "no settings.json in $SRC (old .tar backups: use git show 2668e0f:tools/car-backup.sh)"
  say "Restoring from $SRC"

  if grep -qE '^[0-9a-f]{64}  ' "$SRC/MANIFEST" 2>/dev/null; then
    (cd "$SRC" && grep -E '^[0-9a-f]{64}  ' MANIFEST | sha256sum -c --quiet) \
      || die "backup files do not match their checksums"
  fi

  for pkg in "${PKGS[@]}"; do
    if installed "$pkg"; then
      echo "    $pkg already installed ($(version "$pkg")), kept"
    elif [ -f "$SRC/$pkg.apk" ]; then
      say "Installing $pkg from the backup"
      timeout 300 adb install -r -g "$SRC/$pkg.apk" \
        || timeout 300 adb install -r -g "$SRC/$pkg.apk" \
        || die "could not install $pkg"
    else
      die "$pkg is not installed and the backup has no apk for it"
    fi
  done

  # Put the folder where the app keeps its own backups, then let the app
  # restore it: the same code as the Restore button.
  stamp=$(basename "$SRC")
  dest="$CAR_BACKUPS/$stamp"
  ash "mkdir -p '$dest'"
  for f in settings.json car.db mqtt_client.p12 mqtt_ca.crt; do
    [ -f "$SRC/$f" ] && timeout 300 adb push "$SRC/$f" "$dest/$f" >/dev/null
  done

  since=$(car_now)
  say "Asking the app to restore it (the app restarts)"
  ash am broadcast -a com.geely.drivemem.RESTORE --es dir "'$dest'" -n "$RECV" >/dev/null
  line=$(wait_log "$since" 'backup: (restored|failed)') || die "no answer from the app in 3 minutes"
  [[ "$line" == "backup: restored"* ]] || die "$line"
  echo "    $line"
  sleep 5
  restart_apps
  check_mqtt "$since"
  say "Restore done."
}

restart_apps() {
  installed com.geely.modehelper && ash am start-foreground-service com.geely.modehelper/.ModeHelperService >/dev/null 2>&1 || true
  # Opening the screen also re-arms the telemetry watchdog (see BootReceiver).
  installed com.geely.drivemem && ash am start -n com.geely.drivemem/.ui.ComfortActivity >/dev/null 2>&1 || true
}

check_mqtt() {
  local since="$1" i line
  grep -q '"mqtt_uri"' "$SRC/settings.json" 2>/dev/null \
    || { echo "    no MQTT broker in the restored settings"; return 0; }
  for i in $(seq 1 12); do
    line=$(ash "logcat -d -T '$since' -s DriveMem" 2>/dev/null || true)
    line=$(grep -E 'MQTT connected' <<<"$line" | tail -1 || true)
    [ -n "$line" ] && { echo "    ${line#*DriveMem: }"; return 0; }
    sleep 5
  done
  echo "    WARNING: no 'MQTT connected' within 60 s. Check: adb logcat -s DriveMem"
}

"$MODE"
