#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
./probes/owwprobe/download-models.sh
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK:-}}"
./gradlew :owwprobe:assembleDebug
cp probes/owwprobe/build/outputs/apk/debug/owwprobe-debug.apk probes/owwprobe/owwprobe.apk
echo "OK -> $PWD/probes/owwprobe/owwprobe.apk"
