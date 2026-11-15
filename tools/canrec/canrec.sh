#!/usr/bin/env bash
# Standalone CAN recorder on the car (see CanRec.java). Survives app installs.
#   tools/canrec/canrec.sh build            compile canrec.dex
#   tools/canrec/canrec.sh start [MINUTES]  push and start on the car (default 600)
#   tools/canrec/canrec.sh status           running? file sizes
#   tools/canrec/canrec.sh stop
#   tools/canrec/canrec.sh pull DEST        copy the recordings
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
DIR=/data/local/tmp/drive_assist/canrec
OUT=$REPO/build/canrec
SDK=${ANDROID_SDK:-$HOME/dev/geely/sdk}
MATCH=com.geely.canrec.CanRec

case "${1:-}" in
build)
  rm -rf "$OUT" && mkdir -p "$OUT/classes"
  javac --release 8 -nowarn -cp "$SDK/platforms/android-28/android.jar" -d "$OUT/classes" \
    "$HERE/CanRec.java" "$REPO/drivemem/src/main/java/com/geely/drivemem/sensors/RoadcastClient.java"
  "$SDK/build-tools/34.0.0/d8" --min-api 28 --lib "$SDK/platforms/android-28/android.jar" \
    --output "$OUT" $(find "$OUT/classes" -name '*.class')
  mv "$OUT/classes.dex" "$OUT/canrec.dex"
  echo "built $OUT/canrec.dex" ;;
start)
  MIN=${2:-600}
  [ -f "$OUT/canrec.dex" ] || "$0" build
  adb shell "mkdir -p $DIR"
  adb push "$OUT/canrec.dex" "$DIR/canrec.dex" >/dev/null
  # Leave adbd's cgroup (/acct) so turning ADB off does not kill it, like roadcastd.
  # Scripts are pushed as files: piping stdin into adb shell, or a trailing &
  # on the adb command line, breaks the adb connection.
  printf '%s\n' 'echo $$ > /acct/cgroup.procs' \
    "exec app_process -Djava.class.path=$DIR/canrec.dex /system/bin $MATCH $DIR $MIN >> $DIR/canrec.err 2>&1" > "$OUT/run.sh"
  printf '%s\n' "pkill -f $MATCH" "setsid sh $DIR/run.sh < /dev/null > /dev/null 2>&1 &" > "$OUT/launch.sh"
  adb push "$OUT/run.sh" "$OUT/launch.sh" "$DIR/" >/dev/null
  adb shell sh $DIR/launch.sh || true
  sleep 3
  "$0" status ;;
status)
  adb shell "pgrep -f $MATCH >/dev/null && echo RUNNING pid \$(pgrep -f $MATCH) || echo STOPPED; ls -l $DIR; tail -3 $DIR/canrec.err 2>/dev/null" ;;
stop)
  adb shell "pkill -f $MATCH; echo stopped" ;;
pull)
  DEST=${2:?dest dir}; mkdir -p "$DEST"
  for f in $(adb shell "ls $DIR/can-explore-*.tsv"); do adb pull "$f" "$DEST/" >/dev/null; echo "$DEST/$(basename "$f")"; done ;;
*) sed -n '2,7p' "$0"; exit 1 ;;
esac
