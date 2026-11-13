#!/usr/bin/env bash
#
# tools/car-backup.sh — full backup and restore of Drive Assist and ModeHelper.
#
#   ./tools/car-backup.sh [CAR_IP]                      back up the car
#   ./tools/car-backup.sh --restore <BACKUP_DIR> [CAR_IP]  restore onto the car
#
# WHY: a workshop wipe (2026-10) left only a settings XML from a month earlier
# and a trip database from another day. Putting the car back took hand-picking
# keys out of the XML, finding the client certificate, and fixing ownership and
# SELinux labels by hand. One dated folder now holds all of it:
#
#   <pkg>.tar     shared_prefs/, files/ (MQTT client cert), databases/ (car.db)
#   <pkg>.apk     the exact build that wrote that data, to reinstall it as-is
#   MANIFEST      date, car, app versions, checksums
#
# The folder holds SECRETS (MQTT password, client certificate, ABRP/Spotify
# tokens). It is written outside the repo, mode 0700, and must never be shared.
#
# Needs ADB as root, which this head unit gives over Wi-Fi ADB (port 5555).
# Dashcam clips are not included: they are large and are exported to USB.
#
# Env: CAR_IP (instead of the argument), BACKUP_ROOT (default ~/drive-assist-backups).
set -Eeuo pipefail

PKGS=(com.geely.modehelper com.geely.drivemem)   # install order: helper first
DATA_DIRS=(shared_prefs files databases)
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

[ "$(ash id -u | tr -d '\r')" = "0" ] || die "ADB is not root on this unit; cannot read app data."

# Read the whole output first: piping adb into `grep -q`/`head` closes the pipe
# early, adb then exits non-zero, and ash would count that as a failure.
installed() { local o; o=$(ash pm path "$1" 2>/dev/null) || return 1; [[ "$o" == package:* ]]; }
version()   { local o; o=$(ash dumpsys package "$1") || return 1
              grep -m1 -oE 'versionName=[^ ]+' <<<"$o" | cut -d= -f2 | tr -d '\r'; }

# --- Backup ---------------------------------------------------------------------
backup() {
  local out stamp pkg dirs d apk
  stamp=$(date +%Y%m%d-%H%M%S)
  mkdir -p "$BACKUP_ROOT"; chmod 700 "$BACKUP_ROOT"
  out="$BACKUP_ROOT/$stamp"
  mkdir -m 700 "$out"
  # A backup cut short must never pass for a complete one.
  trap 'echo "INCOMPLETE: stopped before the end" >> "$out/MANIFEST"; mv "$out" "$out.INCOMPLETE"; echo "ERROR: backup incomplete, kept as $out.INCOMPLETE" >&2' ERR
  say "Backing up to $out"

  {
    echo "date:   $(date -Iseconds)"
    echo "car:    $(ash getprop ro.product.model | tr -d '\r') $(ash getprop ro.build.display.id | tr -d '\r')"
  } > "$out/MANIFEST"

  for pkg in "${PKGS[@]}"; do
    if ! installed "$pkg"; then echo "    $pkg not installed, skipped"; continue; fi
    echo "$pkg: $(version "$pkg")" >> "$out/MANIFEST"

    apk=$(ash pm path "$pkg"); apk=$(head -1 <<<"$apk" | tr -d '\r'); apk="${apk#package:}"
    timeout 120 adb pull "$apk" "$out/$pkg.apk" >/dev/null

    dirs=""
    for d in "${DATA_DIRS[@]}"; do
      ash "test -d /data/data/$pkg/$d" && dirs="$dirs $d"
    done
    [ -n "$dirs" ] || continue

    # car.db runs in WAL mode: db, -wal and -shm are only consistent as a set
    # taken while nothing writes. Stop the app for the copy, start it after.
    [ "$pkg" = com.geely.drivemem ] && ash am force-stop "$pkg"
    timeout 300 adb exec-out "tar -cf - -C /data/data/$pkg$dirs" > "$out/$pkg.tar" \
      || { restart_apps; die "could not copy $pkg data"; }
    echo "    $pkg: $(tar -tf "$out/$pkg.tar" | grep -vc '/$' || true) files"
  done

  restart_apps

  if command -v sqlite3 >/dev/null && [ -f "$out/com.geely.drivemem.tar" ]; then
    local tmp; tmp=$(mktemp -d)
    tar -xf "$out/com.geely.drivemem.tar" -C "$tmp" databases 2>/dev/null || true
    if [ -f "$tmp/databases/car.db" ]; then
      [ "$(sqlite3 "$tmp/databases/car.db" 'PRAGMA integrity_check;')" = ok ] \
        && echo "    car.db: integrity ok" || echo "    WARNING: car.db failed its integrity check"
    fi
    rm -rf "$tmp"
  fi

  trap - ERR
  (cd "$out" && sha256sum -- *.tar *.apk 2>/dev/null) >> "$out/MANIFEST"
  chmod -R go-rwx "$out"
  say "Done: $out ($(du -sh "$out" | cut -f1)). It contains secrets; keep it private."
}

# --- Restore --------------------------------------------------------------------
restore() {
  local pkg uid dirs d
  [ -d "$SRC" ] || die "no backup folder at $SRC"
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

  for pkg in "${PKGS[@]}"; do
    [ -f "$SRC/$pkg.tar" ] || continue
    say "Restoring $pkg data"
    dirs=$(tar -tf "$SRC/$pkg.tar" | cut -d/ -f1 | sort -u | tr '\n' ' ')
    for d in $dirs; do
      [[ " ${DATA_DIRS[*]} " == *" $d "* ]] || die "unexpected path '$d' in $pkg.tar"
    done

    # Stop first, or the running app writes its in-memory prefs over ours.
    ash am force-stop "$pkg"
    timeout 120 adb push "$SRC/$pkg.tar" "/data/local/tmp/restore.tar" >/dev/null
    # The app's uid changes on every fresh install: read it, never reuse the old one.
    # Whole directories are replaced: a fresh car.db-wal left beside the old
    # car.db would corrupt it.
    ash "set -e; cd /data/data/$pkg; U=\$(stat -c %u .)
         rm -rf $dirs; tar -xf /data/local/tmp/restore.tar; rm /data/local/tmp/restore.tar
         chown -R \$U:\$U $dirs; restorecon -R $dirs"
  done

  # The saved 'last applied update URL' would make the Updater skip the OTA
  # that is current right now, if the restored apk is older than it.
  # sed -i writes a NEW file, which loses the app's SELinux categories
  # (s0:c32,... becomes plain s0): label it again right after.
  ash "f=/data/data/com.geely.drivemem/shared_prefs/drivemem.xml; [ -f \$f ] || exit 0
       sed -i '/name=\"update_last_url\"/d' \$f && restorecon \$f"

  local since; since=$(ash "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
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
  ash "grep -q 'name=\"mqtt_uri\"' /data/data/com.geely.drivemem/shared_prefs/drivemem.xml" 2>/dev/null \
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
