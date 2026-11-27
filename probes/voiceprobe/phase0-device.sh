#!/usr/bin/env bash
# Repeatable workstation-side Phase 0 runner. Never reboots or modifies /system.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${ANDROID_SERIAL:-}" ]; then
  SERIAL="$ANDROID_SERIAL"
  ACTION="${1:-install}"
else
  SERIAL="${1:-}"
  ACTION="${2:-install}"
fi
if [ -z "$SERIAL" ]; then
  echo "Usage: ANDROID_SERIAL=<adb-serial> $0 [install|collect|watch-keys|inspect-vr]" >&2
  echo "   or: $0 <adb-serial> [install|collect|watch-keys|inspect-vr]" >&2
  exit 2
fi
ADB=(adb -s "$SERIAL")
REMOTE=/sdcard/Android/data/com.geely.voiceprobe/files

case "$ACTION" in
  install)
    ./build-voiceprobe.sh
    "${ADB[@]}" install -r voiceprobe.apk
    "${ADB[@]}" shell pm grant com.geely.voiceprobe android.permission.RECORD_AUDIO || true
    "${ADB[@]}" shell pm grant com.geely.voiceprobe android.permission.READ_PHONE_STATE || true
    "${ADB[@]}" shell am start -n com.geely.voiceprobe/.VoiceProbeActivity
    ;;
  collect)
    mkdir -p results
    stamp=$(date +%Y%m%d-%H%M%S)
    "${ADB[@]}" pull "$REMOTE" "results/$stamp"
    "${ADB[@]}" shell dumpsys media.audio_flinger > "results/$stamp-audio-flinger.txt"
    "${ADB[@]}" shell dumpsys audio > "results/$stamp-audio.txt"
    echo "Collected under voiceprobe/results/$stamp*"
    ;;
  watch-keys)
    echo "Press the steering-wheel voice key; Ctrl-C after several presses."
    "${ADB[@]}" logcat -c
    "${ADB[@]}" logcat -v threadtime keyserver:I InputReader:I InputDispatcher:I VoiceProbe:I '*:S'
    ;;
  inspect-vr)
    mkdir -p results
    stamp=$(date +%Y%m%d-%H%M%S)
    "${ADB[@]}" shell getprop > "results/$stamp-getprop.txt"
    "${ADB[@]}" shell dumpsys package com.flyme.auto.hvac > "results/$stamp-hvac-package.txt"
    "${ADB[@]}" shell ps -A > "results/$stamp-processes.txt"
    "${ADB[@]}" shell ls -lZ /system/bin /system/xbin /vendor/bin /system/etc /vendor/etc \
      > "results/$stamp-vr-files.txt" 2>&1
    "${ADB[@]}" shell "find /system /vendor \\( -iname '*vr*' -o -iname '*voice*' \\)" \
      > "results/$stamp-vr-find.txt" 2>&1
    echo "Collected vr_res and HVAC evidence under voiceprobe/results/$stamp*"
    ;;
  *)
    echo "Unknown action: $ACTION" >&2
    exit 2
    ;;
esac
