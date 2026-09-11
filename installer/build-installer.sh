#!/usr/bin/env bash
#
# Builds and signs the Drive Assist Standalone Installer (com.geely.installer)
# signed with the AOSP public platform test-key and sharing android.uid.system.
#
# Bundles modehelper.apk and drive_assist.apk into assets/ for 100% offline installation.
#
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"

# Toolchain & Environment
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(asdf where java 2>/dev/null || dirname $(dirname $(readlink -f $(which javac 2>/dev/null || which java 2>/dev/null || echo ""))))
fi
export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-${ANDROID_HOME:-${GEELY_TOOLS:-$HOME/dev/geely}/sdk}}"
BT="$SDK/build-tools/34.0.0"
if [ ! -d "$BT" ]; then
  latest=$(ls -d "$SDK/build-tools/"*/ 2>/dev/null | sort -V | tail -1) || true
  [ -n "$latest" ] && BT="${latest%/}"
fi
AJ="$SDK/platforms/android-28/android.jar"

# Platform signing key
KEY="$ROOT/modehelper/platkey"
if [ ! -s "$KEY/platform.pk8" ] || [ ! -s "$KEY/platform.x509.pem" ]; then
  mkdir -p "$KEY"
  _b="https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security"
  if ! (curl -fsSL --connect-timeout 5 --max-time 15 "$_b/platform.pk8?format=TEXT" | base64 -d > "$KEY/platform.pk8" 2>/dev/null && \
        curl -fsSL --connect-timeout 5 --max-time 15 "$_b/platform.x509.pem?format=TEXT" | base64 -d > "$KEY/platform.x509.pem" 2>/dev/null); then
    echo "Notice: Network fetch from android.googlesource.com unavailable; using embedded verified AOSP platform test-key."
    cat <<'EOF' | base64 -d > "$KEY/platform.pk8"
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCceAWSrA1dOBzeqmXsyKYAbjZIDG1yB7EgEb5Qhjqr4rVdAJrfcUbW8iAigMfNTXvbJiQ7ioBsJrNLE3UjpJJoIkkE3AFJPnwKzxoFyHT2mwN7YDCdkHTSQoDha60qhzQ2GVHq9ypILQmyBLGHXhKsmMGqdz1oALnq/eVtWL7Y6NoW+aNgCZw3qDSm3+23trRKBJ4Homn8zyxUlvLPNtZN+Qo7jY80o7qrTPUzcasncZs7pYdUrQxT/BTh20XVHiNPu+k8m6Tt+c5UJhNQ7FNWB79pov9KoH219+ogDQmmwbSeIUAvie0RkIk6q1qRgPFS6C+FpFdTz1/BkHHF7sgnAgEDAoIBAGhQA7cdXj4laJRxmUiFxABJeYVdnkwFIMAL1DWu0cfseOirEepLhI9MFWxV2ojeUpIZbX0HAEgZzNy3o20YYZrBhgM9VjDUUrHfZq6Fo08SAlJAIGkK+IwsVeudHhxaIs67i/H6HDAeBnaty6+UDHMQgRxPfkVV0UdT7kjl1JCaMTTchXxr+oGsH5d5EHCPBfEU4v8BqOBSEgkUFJ1Y6Y5AQqbSe2nSZ6bKZpE8YY27IpvqBrZEN0j7SKcHKuJDm81DGfLrh3vOmW6U0ZWy7o9qv7INYXNDvtKUMQigXYYANKUpKvveUSgKJ+ixfFP8Ye92j8mp+d/+16i4Wa7Wu5sCgYEAyCd3wu4wsRAu/8q1abL14/V8M95+dlfhJBl3H9VwUbsH+CP2VRQ9KYkaYdPwOb8hz1h8gqr74ASbEvz+ixMHzI6zel07VDCew+6OUZOBiOxGc8p7SpZuCeDsL+GEZxkOMQ7PXUpDI/p8nx84eEJUZrHc2kv1VcpdAXLhtrpiicUCgYEAyCA3GDc282T3BXj061vqR2rpFaqhdlRy7S2BtA3BJj2Qrh1bgQGq9agC+YWm3xO3F/lJ+phK2WHoFOpR+m6w7Q0jx/L/BYOlJD+4h1zeZJKhLBQToodM1ZktcLGYFkSzNJeUImt5T1sErj5op9Ex97nZfYswCrl0GtGaNIVKJPsCgYEAhW+lLJ7LILV0qocjm8yj7U5SzT7++Y/rbWZPao5K4SdapW1O42LTcQYRlo1K0SoWijr9rHH9QAMSDKipsgyv3bR3pujSOCBp1/Re4Q0BBfLZoob83GRJW+tIH+utmhC0ILSKPjGCF/xTFL96+tbi7yE95t1OOTGTVkyWedGXBoMCgYEAhWrPZXokokNPWPtN8j1G2kdGDnHA+Y2h83OrzV6AxCkLHr49AKvHTnAB+65vP2J6D/uGpxAx5kFFY0bhUZ8gngjChUyqA60YwtUlr5M+7bcWHWK3wa+IjmYeSyEQDth3eGUNbEemNOdYdCmbGot2pSaQ/lzKsdD4EeEReFjcGKcCgYA+JeOt5WFENpv4LT+7P+j1k3xvOZ9sJGuXRXk9HXzsJvRCnc5oScwqku6i5HjzG8gyNVZg1sQGbVbWWmcNtaS8I3XalYAHYQyb8SGxlQP4ctKAN4j2Hbk1OHAMW84dfAQYQwFcBdaJTtMXQlbbX5Rhx90wE4qFuIapx6IKOmDxRw==
EOF
    cat <<'EOF' | base64 -d > "$KEY/platform.x509.pem"
LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUVxRENDQTVDZ0F3SUJBZ0lKQUxPWmdJYlFWcy82TUEwR0NTcUdTSWIzRFFFQkJBVUFNSUdVTVFzd0NRWUQKVlFRR0V3SlZVekVUTUJFR0ExVUVDQk1LUTJGc2FXWnZjbTVwWVRFV01CUUdBMVVFQnhNTlRXOTFiblJoYVc0ZwpWbWxsZHpFUU1BNEdBMVVFQ2hNSFFXNWtjbTlwWkRFUU1BNEdBMVVFQ3hNSFFXNWtjbTlwWkRFUU1BNEdBMVVFCkF4TUhRVzVrY205cFpERWlNQ0FHQ1NxR1NJYjNEUUVKQVJZVFlXNWtjbTlwWkVCaGJtUnliMmxrTG1OdmJUQWUKRncwd09EQTBNVFV5TWpRd05UQmFGdzB6TlRBNU1ERXlNalF3TlRCYU1JR1VNUXN3Q1FZRFZRUUdFd0pWVXpFVApNQkVHQTFVRUNCTUtRMkZzYVdadmNtNXBZVEVXTUJRR0ExVUVCeE1OVFc5MWJuUmhhVzRnVm1sbGR6RVFNQTRHCkExVUVDaE1IUVc1a2NtOXBaREVRTUE0R0ExVUVDeE1IUVc1a2NtOXBaREVRTUE0R0ExVUVBeE1IUVc1a2NtOXAKWkRFaU1DQUdDU3FHU0liM0RRRUpBUllUWVc1a2NtOXBaRUJoYm1SeWIybGtMbU52YlRDQ0FTQXdEUVlKS29aSQpodmNOQVFFQkJRQURnZ0VOQURDQ0FRZ0NnZ0VCQUp4NEJaS3NEVjA0SE42cVpleklwZ0J1TmtnTWJYSUhzU0FSCnZsQ0dPcXZpdFYwQW10OXhSdGJ5SUNLQXg4MU5lOXNtSkR1S2dHd21zMHNUZFNPa2ttZ2lTUVRjQVVrK2ZBclAKR2dYSWRQYWJBM3RnTUoyUWROSkNnT0ZyclNxSE5EWVpVZXIzS2tndENiSUVzWWRlRXF5WXdhcDNQV2dBdWVyOQo1VzFZdnRqbzJoYjVvMkFKbkRlb05LYmY3YmUydEVvRW5nZWlhZnpQTEZTVzhzODIxazM1Q2p1Tmp6U2p1cXRNCjlUTnhxeWR4bXp1bGgxU3RERlA4Rk9IYlJkVWVJMCs3NlR5YnBPMzV6bFFtRTFEc1UxWUh2Mm1pLzBxZ2ZiWDMKNmlBTkNhYkJ0SjRoUUMrSjdSR1FpVHFyV3BHQThWTG9MNFdrVjFQUFg4R1FjY1h1eUNjQ0FRT2pnZnd3Z2ZrdwpIUVlEVlIwT0JCWUVGRS9rb0xQZG5Mb3A5eDF5aDhUbnc0OGdoc0taTUlISkJnTlZIU01FZ2NFd2diNkFGRS9rCm9MUGRuTG9wOXgxeWg4VG53NDhnaHNLWm9ZR2FwSUdYTUlHVU1Rc3dDUVlEVlFRR0V3SlZVekVUTUJFR0ExVUUKQ0JNS1EyRnNhV1p2Y201cFlURVdNQlFHQTFVRUJ4TU5UVzkxYm5SaGFXNGdWbWxsZHpFUU1BNEdBMVVFQ2hNSApRVzVrY205cFpERVFNQTRHQTFVRUN4TUhRVzVrY205cFpERVFNQTRHQTFVRUF4TUhRVzVrY205cFpERWlNQ0FHCkNTcUdTSWIzRFFFSkFSWVRZVzVrY205cFpFQmhibVJ5YjJsa0xtTnZiWUlKQUxPWmdJYlFWcy82TUF3R0ExVWQKRXdRRk1BTUJBZjh3RFFZSktvWklodmNOQVFFRUJRQURnZ0VCQUZjbFVialpPaDl6M2c5dFJwK0cydFp3RkFBcApQSWlnelh6WGVMYzlyOHdaZjZ0MjVpRXVWc0hIWWMvRUw5Y3ozbExGQ3VDSUZNNzhDanRhR2tOR0JVMkNueDJDCnRDc2dTTCtJdGRGSktlK0Y5ZzdkRXRjdFZXVitJdVBvWFFUSU1kWVQwWms0dTRtQ0pIK2pJU1Zyb1MwZGFvK1MKNmgyeHczTXhlNkRBTi9EUnIvWkZydklrbDUrNmJub1V2QUpjY2JtQk9NN3ozZndGbGhmUEpJUmM5N1FOWTRMMwpKMTdYT0VsYXR1V1RHNVFoZGx4SkczTDdhT0NBMjl0WXdnS2ROSHlMTW96a1B2YW9zVlV6N2Z2cGliMXFTTjFMCklDN2FsTWFyamRXNE9aSUQycTR1MUVZakxrL3B2WllUbE1Zd0RsRTQ0OC9TaGViazVJTlRqTGl4czFjPQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCg==
EOF
  fi
fi
_fp=$(openssl x509 -in "$KEY/platform.x509.pem" -outform DER | sha256sum | cut -d' ' -f1)
[ "$_fp" = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8" ] \
  || { echo "ABORT: platform key != the car's ($_fp)"; exit 2; }

# Step 1: Ensure modehelper.apk is built
if [ ! -f "$ROOT/modehelper/modehelper.apk" ]; then
  echo "Building modehelper..."
  "$ROOT/modehelper/build-modehelper.sh"
fi

# Step 2: Ensure drive_assist.apk is built
MAIN_APK="$ROOT/drive_assist.apk"
if [ ! -f "$MAIN_APK" ] && [ -f "$ROOT/drivemem/drive_assist.apk" ]; then
  MAIN_APK="$ROOT/drivemem/drive_assist.apk"
fi
if [ ! -f "$MAIN_APK" ]; then
  echo "Building drive_assist..."
  NO_DEPLOY=1 "$ROOT/drivemem/build.sh"
  MAIN_APK="$ROOT/drive_assist.apk"
  [ -f "$MAIN_APK" ] || MAIN_APK="$ROOT/drivemem/drive_assist.apk"
fi

# Step 3: Prepare bundled assets
rm -rf assets obj classes.dex *.apk
mkdir -p assets obj
cp "$ROOT/modehelper/modehelper.apk" assets/modehelper.apk
cp "$MAIN_APK" assets/drive_assist.apk

# Extract version metadata directly from drive_assist.apk
badging=$("$BT/aapt2" dump badging "$MAIN_APK")
VC=$(echo "$badging" | grep -oP "versionCode='\K[0-9]+")
VN=$(echo "$badging" | grep -oP "versionName='\K[^']+")
GIT_SHA=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")
BUILD_DATE=$(date +"%Y-%m-%d %H:%M")

cat > assets/version.properties <<EOF
versionName=$VN
versionCode=$VC
gitSha=$GIT_SHA
buildDate=$BUILD_DATE
EOF

if [ -f "$ROOT/CHANGELOG.md" ]; then
  cp "$ROOT/CHANGELOG.md" assets/changelog.txt
else
  git -C "$ROOT" log -n 15 --no-merges --pretty=format:"• %s (%cd)" --date=short > assets/changelog.txt
fi

echo "Bundling assets (version: $VN, versionCode: $VC):"
ls -lh assets/

# Step 4: Package and sign installer APK
"$BT/aapt2" link -o base.apk -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28
"$JAVA_HOME/bin/javac" -classpath "$AJ" -d obj $(find src -name '*.java')
"$BT/d8" --min-api 28 --lib "$AJ" --output . $(find obj -name '*.class')

cp base.apk unsigned.apk
zip -qj unsigned.apk classes.dex
zip -qr unsigned.apk assets

"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --key "$KEY/platform.pk8" --cert "$KEY/platform.x509.pem" \
  --out drive_assist_installer.apk aligned.apk

# Clean build artifacts
rm -rf base.apk unsigned.apk aligned.apk classes.dex obj assets

# Also mirror to root repository for convenience
cp drive_assist_installer.apk "$ROOT/drive_assist_installer.apk"

echo "OK -> $(pwd)/drive_assist_installer.apk ($(du -h drive_assist_installer.apk | cut -f1))"
echo "OK -> $ROOT/drive_assist_installer.apk"
"$BT/aapt2" dump xmltree --file AndroidManifest.xml drive_assist_installer.apk 2>/dev/null | grep -i sharedUserId || true
