#!/usr/bin/env bash
# Samples CPU/RSS around an already-pushed on-device benchmark command.
# Example:
#   ANDROID_SERIAL=<serial> ./benchmark-engine.sh vosk \
#     /data/local/tmp/vosk-bench /sdcard/voice/model /sdcard/voice/sample.wav
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ] || [ "$#" -lt 2 ]; then
  echo "Usage: ANDROID_SERIAL=<adb-serial> $0 <openwakeword|vosk> <command> [args...]" >&2
  exit 2
fi
NAME="$1"
shift
ADB=(adb -s "$SERIAL")
REMOTE_LOG="/data/local/tmp/voice-benchmark-$NAME.log"

quoted=()
for arg in "$@"; do
  printf -v q '%q' "$arg"
  quoted+=("$q")
done
command_line="${quoted[*]}"

"${ADB[@]}" shell sh -c "'time -p $command_line'" 2>&1 | tee "$NAME-time.txt" &
host_pid=$!
sleep 1
device_pid=$("${ADB[@]}" shell pidof "$(basename "$1")" | tr -d '\r' | awk '{print $1}')
if [ -n "$device_pid" ]; then
  while kill -0 "$host_pid" 2>/dev/null; do
    "${ADB[@]}" shell "top -b -n 1 -p $device_pid; cat /proc/$device_pid/status" \
      >> "$NAME-resources.txt" 2>&1 || true
    sleep 1
  done
fi
wait "$host_pid"
echo "Results: $NAME-time.txt and $NAME-resources.txt"
