#!/usr/bin/env bash
# Constroi e ASSINA o SysProbe (com.geely.sysprobe) com a test-key PUBLICA do
# AOSP + sharedUserId=android.uid.system. Objetivo: provar que, nesta central
# (que usa a test-key do AOSP como plataforma), um app assim ganha uid 1000.
#
# NAO INSTALA NADA. So gera ~/dev/geely/sysprobe/sysprobe.apk.
# Para instalar/depois testar (seus comandos, quando quiser):
#   adb install -r ~/dev/geely/sysprobe/sysprobe.apk
#   adb shell am start -n com.geely.sysprobe/.SysProbeActivity
#   adb shell dumpsys package com.geely.sysprobe | grep userId   # espera userId=1000
#   adb uninstall com.geely.sysprobe                              # remove limpo
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:=$HOME/.asdf/installs/java/temurin-17.0.20+8}"
export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-$HOME/geely/sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-28/android.jar"
CARJAR="${CARJAR:-$HOME/geely/car-stubs/car-stubs.jar}"   # stubs android.car (compile-only)

# 1) chave publica de plataforma do AOSP (a mesma que a central usa)
KEY=./platkey; mkdir -p "$KEY"
if [ ! -s "$KEY/platform.pk8" ] || [ ! -s "$KEY/platform.x509.pem" ]; then
  base="https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security"
  if ! (curl -fsSL --connect-timeout 5 --max-time 15 "$base/platform.pk8?format=TEXT" | base64 -d > "$KEY/platform.pk8" 2>/dev/null && \
        curl -fsSL --connect-timeout 5 --max-time 15 "$base/platform.x509.pem?format=TEXT" | base64 -d > "$KEY/platform.x509.pem" 2>/dev/null); then
    echo "Notice: Network fetch from android.googlesource.com unavailable; using embedded verified AOSP platform test-key."
    cat <<'EOF' | base64 -d > "$KEY/platform.pk8"
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCceAWSrA1dOBzeqmXsyKYAbjZIDG1yB7EgEb5Qhjqr4rVdAJrfcUbW8iAigMfNTXvbJiQ7ioBsJrNLE3UjpJJoIkkE3AFJPnwKzxoFyHT2mwN7YDCdkHTSQoDha60qhzQ2GVHq9ypILQmyBLGHXhKsmMGqdz1oALnq/eVtWL7Y6NoW+aNgCZw3qDSm3+23trRKBJ4Homn8zyxUlvLPNtZN+Qo7jY80o7qrTPUzcasncZs7pYdUrQxT/BTh20XVHiNPu+k8m6Tt+c5UJhNQ7FNWB79pov9KoH219+ogDQmmwbSeIUAvie0RkIk6q1qRgPFS6C+FpFdTz1/BkHHF7sgnAgEDAoIBAGhQA7cdXj4laJRxmUiFxABJeYVdnkwFIMAL1DWu0cfseOirEepLhI9MFWxV2ojeUpIZbX0HAEgZzNy3o20YYZrBhgM9VjDUUrHfZq6Fo08SAlJAIGkK+IwsVeudHhxaIs67i/H6HDAeBnaty6+UDHMQgRxPfkVV0UdT7kjl1JCaMTTchXxr+oGsH5d5EHCPBfEU4v8BqOBSEgkUFJ1Y6Y5AQqbSe2nSZ6bKZpE8YY27IpvqBrZEN0j7SKcHKuJDm81DGfLrh3vOmW6U0ZWy7o9qv7INYXNDvtKUMQigXYYANKUpKvveUSgKJ+ixfFP8Ye92j8mp+d/+16i4Wa7Wu5sCgYEAyCd3wu4wsRAu/8q1abL14/V8M95+dlfhJBl3H9VwUbsH+CP2VRQ9KYkaYdPwOb8hz1h8gqr74ASbEvz+ixMHzI6zel07VDCew+6OUZOBiOxGc8p7SpZuCeDsL+GEZxkOMQ7PXUpDI/p8nx84eEJUZrHc2kv1VcpdAXLhtrpiicUCgYEAyCA3GDc282T3BXj061vqR2rpFaqhdlRy7S2BtA3BJj2Qrh1bgQGq9agC+YWm3xO3F/lJ+phK2WHoFOpR+m6w7Q0jx/L/BYOlJD+4h1zeZJKhLBQToodM1ZktcLGYFkSzNJeUImt5T1sErj5op9Ex97nZfYswCrl0GtGaNIVKJPsCgYEAhW+lLJ7LILV0qocjm8yj7U5SzT7++Y/rbWZPao5K4SdapW1O42LTcQYRlo1K0SoWijr9rHH9QAMSDKipsgyv3bR3pujSOCBp1/Re4Q0BBfLZoob83GRJW+tIH+utmhC0ILSKPjGCF/xTFL96+tbi7yE95t1OOTGTVkyWedGXBoMCgYEAhWrPZXokokNPWPtN8j1G2kdGDnHA+Y2h83OrzV6AxCkLHr49AKvHTnAB+65vP2J6D/uGpxAx5kFFY0bhUZ8gngjChUyqA60YwtUlr5M+7bcWHWK3wa+IjmYeSyEQDth3eGUNbEemNOdYdCmbGot2pSaQ/lzKsdD4EeEReFjcGKcCgYA+JeOt5WFENpv4LT+7P+j1k3xvOZ9sJGuXRXk9HXzsJvRCnc5oScwqku6i5HjzG8gyNVZg1sQGbVbWWmcNtaS8I3XalYAHYQyb8SGxlQP4ctKAN4j2Hbk1OHAMW84dfAQYQwFcBdaJTtMXQlbbX5Rhx90wE4qFuIapx6IKOmDxRw==
EOF
    cat <<'EOF' | base64 -d > "$KEY/platform.x509.pem"
LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUVxRENDQTVDZ0F3SUJBZ0lKQUxPWmdJYlFWcy82TUEwR0NTcUdTSWIzRFFFQkJBVUFNSUdVTVFzd0NRWUQKVlFRR0V3SlZVekVUTUJFR0ExVUVDQk1LUTJGc2FXWnZjbTVwWVRFV01CUUdBMVVFQnhNTlRXOTFiblJoYVc0ZwpWbWxsZHpFUU1BNEdBMVVFQ2hNSFFXNWtjbTlwWkRFUU1BNEdBMVVFQ3hNSFFXNWtjbTlwWkRFUU1BNEdBMVVFCkF4TUhRVzVrY205cFpERWlNQ0FHQ1NxR1NJYjNEUUVKQVJZVFlXNWtjbTlwWkVCaGJtUnliMmxrTG1OdmJUQWUKRncwd09EQTBNVFV5TWpRd05UQmFGdzB6TlRBNU1ERXlNalF3TlRCYU1JR1VNUXN3Q1FZRFZRUUdFd0pWVXpFVApNQkVHQTFVRUNCTUtRMkZzYVdadmNtNXBZVEVXTUJRR0ExVUVCeE1OVFc5MWJuUmhhVzRnVm1sbGR6RVFNQTRHCkExVUVDaE1IUVc1a2NtOXBaREVRTUE0R0ExVUVDeE1IUVc1a2NtOXBaREVRTUE0R0ExVUVBeE1IUVc1a2NtOXAKWkRFaU1DQUdDU3FHU0liM0RRRUpBUllUWVc1a2NtOXBaRUJoYm1SeWIybGtMbU52YlRDQ0FTQXdEUVlKS29aSQpodmNOQVFFQkJRQURnZ0VOQURDQ0FRZ0NnZ0VCQUp4NEJaS3NEVjA0SE42cVpleklwZ0J1TmtnTWJYSUhzU0FSCnZsQ0dPcXZpdFYwQW10OXhSdGJ5SUNLQXg4MU5lOXNtSkR1S2dHd21zMHNUZFNPa2ttZ2lTUVRjQVVrK2ZBclAKR2dYSWRQYWJBM3RnTUoyUWROSkNnT0ZyclNxSE5EWVpVZXIzS2tndENiSUVzWWRlRXF5WXdhcDNQV2dBdWVyOQo1VzFZdnRqbzJoYjVvMkFKbkRlb05LYmY3YmUydEVvRW5nZWlhZnpQTEZTVzhzODIxazM1Q2p1Tmp6U2p1cXRNCjlUTnhxeWR4bXp1bGgxU3RERlA4Rk9IYlJkVWVJMCs3NlR5YnBPMzV6bFFtRTFEc1UxWUh2Mm1pLzBxZ2ZiWDMKNmlBTkNhYkJ0SjRoUUMrSjdSR1FpVHFyV3BHQThWTG9MNFdrVjFQUFg4R1FjY1h1eUNjQ0FRT2pnZnd3Z2ZrdwpIUVlEVlIwT0JCWUVGRS9rb0xQZG5Mb3A5eDF5aDhUbnc0OGdoc0taTUlISkJnTlZIU01FZ2NFd2diNkFGRS9rCm9MUGRuTG9wOXgxeWg4VG53NDhnaHNLWm9ZR2FwSUdYTUlHVU1Rc3dDUVlEVlFRR0V3SlZVekVUTUJFR0ExVUUKQ0JNS1EyRnNhV1p2Y201cFlURVdNQlFHQTFVRUJ4TU5UVzkxYm5SaGFXNGdWbWxsZHpFUU1BNEdBMVVFQ2hNSApRVzVrY205cFpERVFNQTRHQTFVRUN4TUhRVzVrY205cFpERVFNQTRHQTFVRUF4TUhRVzVrY205cFpERWlNQ0FHCkNTcUdTSWIzRFFFSkFSWVRZVzVrY205cFpFQmhibVJ5YjJsa0xtTnZiWUlKQUxPWmdJYlFWcy82TUF3R0ExVWQKRXdRRk1BTUJBZjh3RFFZSktvWklodmNOQVFFRUJRQURnZ0VCQUZjbFVialpPaDl6M2c5dFJwK0cydFp3RkFBcApQSWlnelh6WGVMYzlyOHdaZjZ0MjVpRXVWc0hIWWMvRUw5Y3ozbExGQ3VDSUZNNzhDanRhR2tOR0JVMkNueDJDCnRDc2dTTCtJdGRGSktlK0Y5ZzdkRXRjdFZXVitJdVBvWFFUSU1kWVQwWms0dTRtQ0pIK2pJU1Zyb1MwZGFvK1MKNmgyeHczTXhlNkRBTi9EUnIvWkZydklrbDUrNmJub1V2QUpjY2JtQk9NN3ozZndGbGhmUEpJUmM5N1FOWTRMMwpKMTdYT0VsYXR1V1RHNVFoZGx4SkczTDdhT0NBMjl0WXdnS2ROSHlMTW96a1B2YW9zVlV6N2Z2cGliMXFTTjFMCklDN2FsTWFyamRXNE9aSUQycTR1MUVZakxrL3B2WllUbE1Zd0RsRTQ0OC9TaGViazVJTlRqTGl4czFjPQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCg==
EOF
  fi
fi
# confere que o fingerprint bate com o do carro (portao de seguranca)
FP=$(openssl x509 -in "$KEY/platform.x509.pem" -outform DER | sha256sum | cut -d' ' -f1)
CAR="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
[ "$FP" = "$CAR" ] || { echo "ABORTA: fingerprint da chave != do carro ($FP)"; exit 2; }
echo "chave de plataforma confere ($FP)"

# 2) build
rm -rf obj *.apk classes.dex; mkdir -p obj
"$BT/aapt2" link -o base.apk -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28
"$JAVA_HOME/bin/javac" -classpath "$AJ:$CARJAR" -d obj $(find src -name '*.java')
"$BT/d8" --min-api 28 --lib "$AJ" --output . $(find obj -name '*.class')
cp base.apk unsigned.apk && zip -qj unsigned.apk classes.dex
"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --key "$KEY/platform.pk8" --cert "$KEY/platform.x509.pem" \
  --out sysprobe.apk aligned.apk
rm -f base.apk unsigned.apk aligned.apk

echo "OK -> $(pwd)/sysprobe.apk"
"$BT/apksigner" verify --print-certs sysprobe.apk | grep -i "SHA-256" || true
"$BT/aapt2" dump badging sysprobe.apk | grep -iE "package|sharedUserId" || true
