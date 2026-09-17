#!/usr/bin/env bash
# Host-only pure Java checks. Does not build/install APKs or contact a vehicle.
set -euo pipefail
cd "$(dirname "$0")/.."
helper_classes=$(mktemp -d "${TMPDIR:-/tmp}/modehelper-tests.XXXXXX")
trap 'rm -rf "$helper_classes"' EXIT
helper_javac=javac
helper_java=java
if [ -n "${JAVA_HOME:-}" ]; then
  helper_javac="$JAVA_HOME/bin/javac"
  helper_java="$JAVA_HOME/bin/java"
fi
"$helper_javac" -d "$helper_classes" \
  src/com/geely/modehelper/{AnalysisFrameRing,DashcamBudget,DashcamOptions,DashcamPreviewLease,DashcamRunState,DashcamStorage,MotionEventPolicy,MotionGate,ParkedMonitorPolicy,ParkedMonitoringLifecycle,PreviewFrameSlots}.java \
  test/com/geely/modehelper/*Test.java
helper_count=0
for helper_test in test/com/geely/modehelper/*Test.java; do
  helper_name=${helper_test##*/}
  helper_name=${helper_name%.java}
  "$helper_java" -cp "$helper_classes" "com.geely.modehelper.$helper_name"
  printf 'PASS %s\n' "$helper_name"
  helper_count=$((helper_count + 1))
done
printf 'PASS %s helper suites\n' "$helper_count"
