#!/usr/bin/env bash
# The repeatable local/CI verification contract. This deliberately does not
# deploy, install, connect to a car, or run any hardware action.
set -euo pipefail

./tools/check-active-locales.sh

# modehelper's pure policy tests (plain main() runners; modehelper is not a
# Gradle module). Only classes with no Android dependency are compiled here.
MH_OUT="$(mktemp -d)"
javac -d "$MH_OUT" \
  modehelper/src/com/geely/modehelper/RestorePolicy.java \
  modehelper/src/com/geely/modehelper/AvasPolicy.java \
  modehelper/test/com/geely/modehelper/RestorePolicyTest.java \
  modehelper/test/com/geely/modehelper/AvasPolicyTest.java \
  modehelper/src/com/geely/modehelper/CanReaderPolicy.java \
  modehelper/src/com/geely/modehelper/LocalAdbShell.java \
  modehelper/test/com/geely/modehelper/CanReaderPolicyTest.java
java -cp "$MH_OUT" com.geely.modehelper.RestorePolicyTest
java -cp "$MH_OUT" com.geely.modehelper.AvasPolicyTest
java -cp "$MH_OUT" com.geely.modehelper.CanReaderPolicyTest
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
