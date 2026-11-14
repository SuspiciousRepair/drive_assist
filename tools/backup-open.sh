#!/usr/bin/env bash
#
# tools/backup-open.sh — open a Drive Assist backup on a computer.
#
#   ./tools/backup-open.sh <file.dabackup> <out-dir>
#
# Asks the backup password (or reads BACKUP_PASSWORD) and unpacks
# settings.json, car.db and the MQTT certificates into <out-dir>.
# Uses the app's own BackupCrypto.java, so it needs only a JDK (11+).
# The unpacked files hold SECRETS: keep <out-dir> private, delete it after.
set -Eeuo pipefail

[ $# -eq 2 ] || { sed -n '5p' "$0" | sed 's/^# //' >&2; exit 2; }
command -v javac >/dev/null || { echo "ERROR: needs a JDK (javac). Try: source ~/dev/geely/build-env.sh" >&2; exit 1; }

here=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" "$here/drivemem/src/main/java/com/geely/drivemem/util/BackupCrypto.java"
mkdir -p "$2"; chmod 700 "$2"
java -cp "$tmp" com.geely.drivemem.util.BackupCrypto "$1" "$2"
