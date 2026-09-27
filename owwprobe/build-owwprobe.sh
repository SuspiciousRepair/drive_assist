#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./owwprobe/download-models.sh
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK:-}}"
./gradlew :owwprobe:assembleDebug
cp owwprobe/build/outputs/apk/debug/owwprobe-debug.apk owwprobe/owwprobe.apk
echo "OK -> $PWD/owwprobe/owwprobe.apk"
