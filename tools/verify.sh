#!/usr/bin/env bash
# The repeatable local/CI verification contract. This deliberately does not
# deploy, install, connect to a car, or run any hardware action.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./tools/check-active-locales.sh
./tools/check-docs.sh

# Clean up temp dirs on early failure; explicit rm -rf calls still run before
# `exec ./gradlew` replaces the shell process.
MH_OUT=""
APK_CHECK_OUT=""
trap 'rm -rf "${MH_OUT:-}" "${APK_CHECK_OUT:-}"' EXIT

# modehelper's pure policy tests (plain main() runners; modehelper is not a
# Gradle module). Only classes with no Android dependency are compiled here.
MH_OUT="$(mktemp -d)"
javac -d "$MH_OUT" \
  modehelper/src/com/geely/modehelper/RestorePolicy.java \
  modehelper/src/com/geely/modehelper/StayAwakePolicy.java \
  modehelper/test/com/geely/modehelper/StayAwakePolicyTest.java \
  modehelper/src/com/geely/modehelper/ModeRestore.java \
  modehelper/test/com/geely/modehelper/ModeRestoreTest.java \
  modehelper/src/com/geely/modehelper/AvasPolicy.java \
  modehelper/test/com/geely/modehelper/RestorePolicyTest.java \
  modehelper/test/com/geely/modehelper/AvasPolicyTest.java \
  modehelper/src/com/geely/modehelper/CanReaderPolicy.java \
  modehelper/src/com/geely/modehelper/LocalAdbShell.java \
  modehelper/test/com/geely/modehelper/CanReaderPolicyTest.java \
  modehelper/src/com/geely/modehelper/LockSoundPolicy.java \
  modehelper/test/com/geely/modehelper/LockSoundPolicyTest.java \
  modehelper/src/com/geely/modehelper/ArscPatch.java \
  modehelper/src/com/geely/modehelper/ApkRewrite.java \
  modehelper/src/com/geely/modehelper/OemTextPolicy.java \
  modehelper/test/com/geely/modehelper/ArscPatchTest.java \
  modehelper/test/com/geely/modehelper/ApkRewriteTest.java \
  modehelper/test/com/geely/modehelper/OemTextPolicyTest.java \
  modehelper/src/com/geely/modehelper/BacklightPolicy.java \
  modehelper/test/com/geely/modehelper/BacklightPolicyTest.java \
  modehelper/src/com/geely/modehelper/CarCaps.java \
  modehelper/test/com/geely/modehelper/CarCapsTest.java \
  modehelper/src/com/geely/modehelper/MotionGate.java \
  modehelper/src/com/geely/modehelper/MotionEventPolicy.java \
  modehelper/src/com/geely/modehelper/MotionStall.java \
  modehelper/src/com/geely/modehelper/ParkedMonitorPolicy.java \
  modehelper/src/com/geely/modehelper/PreRoll.java \
  modehelper/src/com/geely/modehelper/FragmentedMp4.java \
  modehelper/src/com/geely/modehelper/SegmentFiles.java \
  modehelper/src/com/geely/modehelper/RecorderState.java \
  modehelper/src/com/geely/modehelper/RestartBackoff.java \
  modehelper/src/com/geely/modehelper/PowerWatch.java \
  modehelper/src/com/geely/modehelper/Vtt.java \
  modehelper/src/com/geely/modehelper/SubtitleLine.java \
  modehelper/src/com/geely/modehelper/BrakeSignal.java \
  modehelper/test/com/geely/modehelper/MotionGateTest.java \
  modehelper/test/com/geely/modehelper/MotionEventPolicyTest.java \
  modehelper/test/com/geely/modehelper/MotionStallTest.java \
  modehelper/test/com/geely/modehelper/ParkedMonitorPolicyTest.java \
  modehelper/test/com/geely/modehelper/PreRollTest.java \
  modehelper/test/com/geely/modehelper/FragmentedMp4Test.java \
  modehelper/test/com/geely/modehelper/SegmentFilesTest.java \
  modehelper/test/com/geely/modehelper/RecorderStateTest.java \
  modehelper/test/com/geely/modehelper/RestartBackoffTest.java \
  modehelper/src/com/geely/modehelper/CompiledCode.java \
  modehelper/test/com/geely/modehelper/CompiledCodeTest.java \
  modehelper/test/com/geely/modehelper/PowerWatchTest.java \
  modehelper/test/com/geely/modehelper/VttTest.java \
  modehelper/test/com/geely/modehelper/SubtitleLineTest.java \
  modehelper/test/com/geely/modehelper/BrakeSignalTest.java \
  modehelper/src/com/geely/modehelper/InstallPolicy.java \
  modehelper/test/com/geely/modehelper/InstallPolicyTest.java
# The dashcam's own: parked motion (gate, events, pre-roll, the stalled
# decoder), the segment files, the recorder's state and restarts, the
# subtitle line and its brake pedal.
for t in MotionGate MotionEventPolicy MotionStall ParkedMonitorPolicy PreRoll \
         FragmentedMp4 SegmentFiles RecorderState RestartBackoff PowerWatch Vtt \
         SubtitleLine BrakeSignal InstallPolicy; do
  java -cp "$MH_OUT" "com.geely.modehelper.${t}Test" >/dev/null
done
java -cp "$MH_OUT" com.geely.modehelper.RestorePolicyTest
java -cp "$MH_OUT" com.geely.modehelper.StayAwakePolicyTest
java -cp "$MH_OUT" com.geely.modehelper.ModeRestoreTest
java -cp "$MH_OUT" com.geely.modehelper.LockSoundPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.AvasPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.CanReaderPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.ArscPatchTest
java -cp "$MH_OUT" com.geely.modehelper.ApkRewriteTest
java -cp "$MH_OUT" com.geely.modehelper.OemTextPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.BacklightPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.CarCapsTest
java -cp "$MH_OUT" com.geely.modehelper.CompiledCodeTest
rm -rf "$MH_OUT"; MH_OUT=""   # explicit before exec replaces this shell
echo "modehelper policy tests: OK"

# Full compile + manifest check for modehelper/src and installer/src (neither
# is a Gradle module, so without this step PR verification and validate-release
# would skip all 26 Android classes in modehelper and all 4 in installer).
TOOLS_ROOT="${GEELY_TOOLS:-$ROOT}"
LOCAL_PROPS="$TOOLS_ROOT/local.properties"
[[ -f "$LOCAL_PROPS" ]] || LOCAL_PROPS="$ROOT/local.properties"
prop_val() {
  [[ -f "$LOCAL_PROPS" ]] || return 0
  sed -nE "s/^${1}=(.*)/\1/p" "$LOCAL_PROPS" | tail -1 | sed 's/\\:/:/g; s/\r$//; s/[[:space:]]*$//'
}

SDK=""
for cand in "${ANDROID_SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
            "$(prop_val 'sdk\.dir')" "${GEELY_TOOLS:-$HOME/dev/geely}/sdk"; do
  if [[ -n "$cand" && -d "$cand" ]]; then
    SDK="$cand"
    break
  fi
done
AJ="$SDK/platforms/android-28/android.jar"
[[ -f "$AJ" ]] || {
  echo "verify: missing $AJ (set ANDROID_HOME or sdk.dir in local.properties)" >&2
  exit 1
}

CARJAR=""
for cand in "${CARJAR_OVERRIDE:-${CARJAR:-}}" "$ROOT/car-stubs/car-stubs.jar" \
            "$(cs_dir="$(prop_val 'carStubs\.dir')"; [[ -z "$cs_dir" ]] || printf '%s/car-stubs.jar' "$cs_dir")" \
            "${GEELY_TOOLS:-$HOME/dev/geely}/car-stubs/car-stubs.jar"; do
  if [[ -n "$cand" && -f "$cand" ]]; then
    CARJAR="$cand"
    break
  fi
done
[[ -f "$CARJAR" ]] || {
  echo "verify: missing car-stubs.jar (checked repo, local.properties, and GEELY_TOOLS)" >&2
  exit 1
}

BT="${BT:-$SDK/build-tools/34.0.0}"
if [[ ! -d "$BT" ]]; then
  latest=$(ls -d "$SDK/build-tools/"*/ 2>/dev/null | sort -V | tail -1 || true)
  [[ -z "$latest" ]] || BT="${latest%/}"
fi
[[ -x "$BT/aapt2" && -x "$BT/d8" ]] || {
  echo "verify: missing aapt2/d8 under ${BT:-<none>} (install build-tools;34.0.0)" >&2
  exit 1
}

APK_CHECK_OUT="$(mktemp -d)"
mkdir -p "$APK_CHECK_OUT/modehelper" "$APK_CHECK_OUT/modehelper-dex" \
         "$APK_CHECK_OUT/installer" "$APK_CHECK_OUT/installer-dex"
mapfile -t MH_ALL_SRCS < <(find modehelper/src -name '*.java' | sort)
javac -classpath "$AJ:$CARJAR" -d "$APK_CHECK_OUT/modehelper" "${MH_ALL_SRCS[@]}"
mapfile -t MH_CLASSES < <(find "$APK_CHECK_OUT/modehelper" -name '*.class' | sort)
"$BT/d8" --min-api 28 --lib "$AJ" --classpath "$CARJAR" --output "$APK_CHECK_OUT/modehelper-dex" "${MH_CLASSES[@]}"

mapfile -t INST_ALL_SRCS < <(find installer/src -name '*.java' | sort)
javac -classpath "$AJ" -d "$APK_CHECK_OUT/installer" "${INST_ALL_SRCS[@]}"
mapfile -t INST_CLASSES < <(find "$APK_CHECK_OUT/installer" -name '*.class' | sort)
"$BT/d8" --min-api 28 --lib "$AJ" --output "$APK_CHECK_OUT/installer-dex" "${INST_CLASSES[@]}"

MH_ASSET_ARGS=()
if [[ -d modehelper/assets ]] && [[ -n "$(ls -A modehelper/assets)" ]]; then
  MH_ASSET_ARGS=(-A modehelper/assets)
fi
"$BT/aapt2" link -o "$APK_CHECK_OUT/modehelper.apk" -I "$AJ" \
  --manifest modehelper/AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28 "${MH_ASSET_ARGS[@]}"
"$BT/aapt2" link -o "$APK_CHECK_OUT/installer.apk" -I "$AJ" \
  --manifest installer/AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28
rm -rf "$APK_CHECK_OUT"; APK_CHECK_OUT=""   # explicit before exec replaces this shell
echo "modehelper + installer compile & dex check: OK"

exec ./gradlew \
  :drivemem:testDebugUnitTest \
  :drivemem:jacocoTestReport \
  :drivemem:lint \
  :drivemem:checkstyle \
  :drivemem:spotbugs \
  :drivemem:assembleRelease \
  --no-daemon
