#!/usr/bin/env bash
# Builds AccelProbe (com.geely.accelprobe) -- discovery tool, not part of
# the main app. See ../accelprobe/README.md for what it's for and how to
# read the log it produces.
#
# Unlike sysprobe, this does NOT need android.uid.system (no platform key,
# no sharedUserId): reading the accelerometer needs no special privilege.
# Signs with a throwaway debug key, generated once into ./debug.ks.
#
# Does not install or deploy anything. Just builds ./accelprobe.apk.
#   adb install -r accelprobe.apk
#   adb shell am start -n com.geely.accelprobe/.AccelProbeActivity
#   adb pull /sdcard/Android/data/com.geely.accelprobe/files/accel.log
#   adb uninstall com.geely.accelprobe
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:=$HOME/.asdf/installs/java/temurin-17.0.20+8}"
export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-$HOME/dev/geely/sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-28/android.jar"

KS=./debug.ks
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias d -storepass android \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=accelprobe"

rm -rf obj *.apk classes.dex; mkdir -p obj
"$BT/aapt2" link -o base.apk -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28
"$JAVA_HOME/bin/javac" -classpath "$AJ" -d obj $(find src -name '*.java')
"$BT/d8" --min-api 28 --lib "$AJ" --output . $(find obj -name '*.class')
cp base.apk unsigned.apk && zip -qj unsigned.apk classes.dex
"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out accelprobe.apk aligned.apk
rm -f base.apk unsigned.apk aligned.apk

echo "OK -> $(pwd)/accelprobe.apk"
"$BT/aapt2" dump badging accelprobe.apk | grep -iE "package" || true
