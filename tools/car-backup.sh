#!/usr/bin/env bash
#
# tools/car-backup.sh — full backup and restore of Drive Assist and ModeHelper.
#
#   ./tools/car-backup.sh [CAR_IP]                      back up the car
#   ./tools/car-backup.sh --restore <BACKUP_DIR> [CAR_IP]  restore onto the car
#
# The app does the backup itself (Config > System > Backup): ONE file, locked
# with the user's backup password, the SAME on the USB stick, on the car
# storage and here:
#
#   <stamp>.dabackup   settings, trips (car.db), MQTT certificates; AES-256
#
# This script drives that same code over adb (BackupReceiver), and adds what
# only a computer can keep: the two apks (MANIFEST has versions and checksums).
# Restore merges the trips with any the car recorded since (no doubles), puts
# the settings and certificates back, and restarts the app.
#
# Password: only needed when the app has none set (backup) or did not lock
# that backup itself, e.g. after a reinstall (restore). The script asks for it
# then, or reads BACKUP_PASSWORD. To look inside a backup on this computer:
# tools/backup-open.sh <file> <dir>.
#
# Env: CAR_IP (instead of the argument), BACKUP_ROOT (default ~/drive-assist-backups),
#      BACKUP_PASSWORD.
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

# The log filter has whole-second precision: wait into the next second, so a
# line from the request before is never read as the answer to this one.
car_now() { sleep 1; ash "date '+%m-%d %H:%M:%S.000'" | tr -d '\r'; }

# The password, asked once, never echoed. Passed to the app as an intent
# extra over the root adb shell; the app never logs it.
ask_password() {
  [ -n "${BACKUP_PASSWORD:-}" ] && return 0
  [ -t 0 ] || die "$1 Set BACKUP_PASSWORD."
  read -rsp "Backup password: " BACKUP_PASSWORD; echo >&2
  [ -n "$BACKUP_PASSWORD" ] || die "no password"
}

# Single quotes for the car's shell around a value that may hold any character.
squote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# --- Backup ---------------------------------------------------------------------
backup() {
  local out since line file stamp pkg apk pwarg=()
  installed com.geely.drivemem || die "Drive Assist is not installed"
  since=$(car_now)
  say "Asking the app for a backup"
  ash am broadcast -a com.geely.drivemem.BACKUP -n "$RECV" >/dev/null
  line=$(wait_log "$since" 'backup: (ok|failed)') || die "no answer from the app in 3 minutes"
  if [[ "$line" == *"no backup password set"* ]]; then
    echo "    The app has no backup password yet. Choose one now (6+ characters)."
    echo "    Write it down: without it, nobody can open the backups."
    ask_password "The app has no backup password."
    since=$(car_now)
    pwarg=(--es password "$(squote "$BACKUP_PASSWORD")")
    ash am broadcast -a com.geely.drivemem.BACKUP "${pwarg[@]}" -n "$RECV" >/dev/null
    line=$(wait_log "$since" 'backup: (ok|failed)') || die "no answer from the app in 3 minutes"
  fi
  [[ "$line" == "backup: ok"* ]] || die "$line"

  # "backup: ok [/storage/XXXX-XXXX/Drive Assist/Backups/<stamp>.dabackup, /storage/emulated/0/...]"
  file=$(grep -oE '/storage/emulated/0/Drive Assist/Backups/[0-9_-]+\.dabackup' <<<"$line" | head -1 || true)
  [ -n "$file" ] || file=$(grep -oE '/storage/[^,]+/Drive Assist/Backups/[0-9_-]+\.dabackup' <<<"$line" | head -1 || true)
  [ -n "$file" ] || die "could not read the backup file from: $line"
  stamp=$(basename "$file" .dabackup)

  mkdir -p "$BACKUP_ROOT"; chmod 700 "$BACKUP_ROOT"
  out="$BACKUP_ROOT/$stamp"
  mkdir -m 700 "$out"
  trap 'mv "$out" "$out.INCOMPLETE" 2>/dev/null; echo "ERROR: backup incomplete, kept as $out.INCOMPLETE" >&2' ERR
  say "Copying $file to $out"
  timeout 300 adb pull "$file" "$out/" >/dev/null
  [ -s "$out/$stamp.dabackup" ] || die "the backup file did not arrive"

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

  trap - ERR
  (cd "$out" && sha256sum -- * 2>/dev/null | grep -v ' MANIFEST$') >> "$out/MANIFEST"
  chmod -R go-rwx "$out"
  say "Done: $out ($(du -sh "$out" | cut -f1))."
  echo "    The settings and trips are locked with the backup password. The apks are not."
}

# --- Restore --------------------------------------------------------------------
restore() {
  local pkg file dest since line pwarg=()
  file=$(ls -1 "$SRC"/*.dabackup 2>/dev/null | tail -1 || true)
  [ -n "$file" ] || die "no .dabackup file in $SRC (old .tar backups: use git show 2668e0f:tools/car-backup.sh)"
  say "Restoring from $file"

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

  # Put the file where the app keeps its own backups, then let the app
  # restore it: the same code as the Restore button.
  dest="$CAR_BACKUPS/$(basename "$file")"
  ash "mkdir -p '$CAR_BACKUPS'"
  timeout 300 adb push "$file" "$dest" >/dev/null

  while :; do
    since=$(car_now)
    say "Asking the app to restore it (the app restarts)"
    ash am broadcast -a com.geely.drivemem.RESTORE --es file "'$dest'" "${pwarg[@]}" -n "$RECV" >/dev/null
    line=$(wait_log "$since" 'backup: (restored|failed)') || die "no answer from the app in 3 minutes"
    if [[ "$line" == *"backup password needed"* && ${#pwarg[@]} -eq 0 ]]; then
      ask_password "This backup needs its password."
      pwarg=(--es password "$(squote "$BACKUP_PASSWORD")")
      continue
    fi
    [[ "$line" == *"wrong password"* ]] && die "wrong backup password"
    break
  done
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
  ash "grep -q mqtt_uri /data/data/com.geely.drivemem/shared_prefs/*.xml" >/dev/null 2>&1 \
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
