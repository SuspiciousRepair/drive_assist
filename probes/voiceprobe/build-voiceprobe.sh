#!/usr/bin/env bash
# Build only. This discovery APK is deliberately separate from drivemem.
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:=$HOME/.asdf/installs/java/temurin-17.0.20+8}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-$HOME/dev/geely/sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-28/android.jar"

KS=./debug.ks
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias d -storepass android \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=voiceprobe"
fi

rm -rf obj base.apk unsigned.apk aligned.apk classes.dex voiceprobe.apk
mkdir -p obj
"$BT/aapt2" link -o base.apk -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28
"$JAVA_HOME/bin/javac" -Xlint:all -classpath "$AJ" -d obj $(find src -name '*.java')
"$BT/d8" --min-api 28 --lib "$AJ" --output . $(find obj -name '*.class')
cp base.apk unsigned.apk
zip -qj unsigned.apk classes.dex
"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out voiceprobe.apk aligned.apk
rm -rf obj base.apk unsigned.apk aligned.apk classes.dex
echo "OK -> $(pwd)/voiceprobe.apk"
"$BT/aapt2" dump badging voiceprobe.apk | grep -i package || true
