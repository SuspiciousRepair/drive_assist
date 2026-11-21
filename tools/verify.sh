#!/usr/bin/env bash
# The repeatable local/CI verification contract. This deliberately does not
# deploy, install, connect to a car, or run any hardware action.
set -euo pipefail

./tools/check-active-locales.sh
./tools/check-docs.sh

# modehelper's pure policy tests (plain main() runners; modehelper is not a
# Gradle module). Only classes with no Android dependency are compiled here.
MH_OUT="$(mktemp -d)"
javac -d "$MH_OUT" \
  modehelper/src/com/geely/modehelper/RestorePolicy.java \
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
  modehelper/test/com/geely/modehelper/PowerWatchTest.java \
  modehelper/test/com/geely/modehelper/VttTest.java \
  modehelper/test/com/geely/modehelper/SubtitleLineTest.java \
  modehelper/test/com/geely/modehelper/BrakeSignalTest.java
# The dashcam's own: parked motion (gate, events, pre-roll, the stalled
# decoder), the segment files, the recorder's state and restarts, the
# subtitle line and its brake pedal.
for t in MotionGate MotionEventPolicy MotionStall ParkedMonitorPolicy PreRoll \
         FragmentedMp4 SegmentFiles RecorderState RestartBackoff PowerWatch Vtt \
         SubtitleLine BrakeSignal; do
  java -cp "$MH_OUT" "com.geely.modehelper.${t}Test" >/dev/null
done
java -cp "$MH_OUT" com.geely.modehelper.RestorePolicyTest
java -cp "$MH_OUT" com.geely.modehelper.ModeRestoreTest
java -cp "$MH_OUT" com.geely.modehelper.LockSoundPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.AvasPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.CanReaderPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.ArscPatchTest
java -cp "$MH_OUT" com.geely.modehelper.ApkRewriteTest
java -cp "$MH_OUT" com.geely.modehelper.OemTextPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.BacklightPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.CarCapsTest
rm -rf "$MH_OUT"   # not a trap: the exec below would skip it
echo "modehelper policy tests: OK"

exec ./gradlew \
  :drivemem:testDebugUnitTest \
  :drivemem:jacocoTestReport \
  :drivemem:lint \
  :drivemem:checkstyle \
  :drivemem:spotbugs \
  :drivemem:assembleRelease \
  --no-daemon
