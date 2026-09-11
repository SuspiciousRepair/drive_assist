#!/usr/bin/env bash
# Build environment for Drive Assist.
# Usage:  source ./build-env.sh  &&  cd drivemem  &&  ./build.sh

export PATH=/home/linuxbrew/.linuxbrew/bin:$PATH
export JAVA_HOME=$(asdf where java temurin-17.0.20+8 2>/dev/null || asdf where java 2>/dev/null || echo "$JAVA_HOME")
export PATH=$JAVA_HOME/bin:$PATH

GEELY_TOOLS_ROOT="${GEELY_TOOLS:-$HOME/dev/geely}"
export GEELY_TOOLS="$GEELY_TOOLS_ROOT"
export ANDROID_SDK=$GEELY_TOOLS_ROOT/sdk
export CARJAR=$GEELY_TOOLS_ROOT/car-stubs/car-stubs.jar
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export KS="${KS:-$ROOT_DIR/keystore/debug.ks}"
[ -f "$KS" ] || export KS="$GEELY_TOOLS_ROOT/debug.ks"                     # ALWAYS the same key: changing it breaks `install -r` on the car

export PATH=$ANDROID_SDK/build-tools/34.0.0:$PATH

echo "java:   $(java -version 2>&1 | head -1)"
echo "adb:    $(adb version 2>/dev/null | head -1)"
echo "root:   $(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd) (toolchain: $GEELY_TOOLS_ROOT, sdk build-tools 34.0.0, platform-28)"
echo "car:    adb connect ${CAR:-<vehicle-ip>:5555}"
