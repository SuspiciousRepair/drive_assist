#!/usr/bin/env bash
# The repeatable local/CI verification contract. This deliberately does not
# deploy, install, connect to a car, or run any hardware action.
set -euo pipefail

exec ./gradlew \
  :drivemem:testDebugUnitTest \
  :drivemem:jacocoTestReport \
  :drivemem:lint \
  :drivemem:checkstyle \
  :drivemem:spotbugs \
  :drivemem:assembleRelease \
  --no-daemon
