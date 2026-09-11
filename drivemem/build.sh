#!/usr/bin/env bash
#
# Build, package, and deploy DriveMem (com.geely.drivemem) for the Geely IHU629G.
#
# Prerequisites:
#   - ANDROID_SDK (build-tools 34.0.0, platform-28)
#   - JAVA_HOME (JDK 17)
#   - android.car stubs (car-stubs.jar)
#   - Signing keystore (debug.ks)
#
# Environment variables:
#   GEELY_TOOLS    Path to external tools directory (defaults to $HOME/dev/geely)
#   NO_DEPLOY      Set to 1 to build only (skip HA upload and vehicle install)
#   NO_GUARD       Set to 1 to bypass git integration and ancestor safety checks
#   INTEGRATION    Git branch to verify against HEAD (defaults to main or master)
#   HA_HOST        Override Home Assistant host address
#   CAR            Override vehicle ADB target address (host:port)
#   OTA_TOPIC      Override OTA MQTT topic (defaults to drivemem/geely/update/set)
#
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"

# ============================================================================
# Toolchain & Environment Setup
# ============================================================================
# In a git worktree, toolchain files, keystores, and .ota-env may reside only
# in the primary checkout. Fall back to GEELY_TOOLS if not found locally.
TOOLS="$ROOT"
[ -f "$TOOLS/car-stubs/car-stubs.jar" ] || TOOLS="${GEELY_TOOLS:-$HOME/dev/geely}"
[ "$TOOLS" = "$ROOT" ] || echo "toolchain -> $TOOLS (worktree mode: using external toolchain)"

: "${ANDROID_SDK:=$TOOLS/sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(asdf where java 2>/dev/null || echo "$HOME/.asdf/installs/java/temurin-17.0.20+8")
fi
export ANDROID_SDK JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
command -v adb >/dev/null || export PATH="$ANDROID_SDK/platform-tools:$PATH"

KS="${KS:-$ROOT/keystore/debug.ks}"
[ -f "$KS" ] || KS="${GEELY_TOOLS:-$HOME/dev/geely}/debug.ks"

# ============================================================================
# Pre-requisite Checks
# ============================================================================
# Only what bash itself needs beyond the compile (below): a build-tools dir
# for the post-build `aapt2 dump badging` read, and the keystore (which bash
# auto-generates a dev one for if missing -- Gradle doesn't). car-stubs.jar
# and libs/paho.jar used to be checked here too, redundantly -- build.gradle
# already re-validates both itself (car-stubs.jar explicitly, paho.jar via
# its own `implementation files(...)` dependency), so bash checking them a
# second time, in a different language, was just a second place for the
# same check to go stale -- which it did (2026-09-08, see git history).
#
# ANDROID_SDK checked explicitly, first, with `set -e` off for the fallback
# search -- tested live, 2026-09-08: with a bad/missing SDK path, the old
# `BT=$(ls ... 2>/dev/null | ...)` one-liner died SILENTLY under
# `set -euo pipefail` (the suppressed `ls` error still fails the pipeline,
# and pipefail treats that as fatal) with no message and no clue why. A
# script this size failing with zero output is worse than a wrong message.
[ -d "$ANDROID_SDK" ] || { echo "Missing Android SDK at $ANDROID_SDK — see README"; exit 1; }
BT="${BT:-$ANDROID_SDK/build-tools/34.0.0}"
if [ ! -d "$BT" ]; then
  BT=""
  latest=$(ls -d "$ANDROID_SDK/build-tools/"*/ 2>/dev/null | sort -V | tail -1) || true
  [ -n "$latest" ] && BT="${latest%/}"
fi
[ -n "$BT" ] && [ -d "$BT" ] || { echo "No build-tools found under $ANDROID_SDK/build-tools — see README"; exit 1; }
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias d -storepass android \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=drivemem"

# ============================================================================
# Build & Package (Gradle)
# ============================================================================
# Prepare assets (bundled changelog)
mkdir -p "$ROOT/drivemem/src/main/assets"
[ -f "$ROOT/CHANGELOG.md" ] && cp "$ROOT/CHANGELOG.md" "$ROOT/drivemem/src/main/assets/changelog.txt"

GEELY_TOOLS="$TOOLS" ANDROID_SDK_ROOT="$ANDROID_SDK" "$ROOT/gradlew" -p "$ROOT" :drivemem:assembleRelease
GRADLE_APK="$ROOT/drivemem/build/outputs/apk/release/drive_assist.apk"
[ -f "$GRADLE_APK" ] || GRADLE_APK="$ROOT/drivemem/build/outputs/apk/release/drivemem-release.apk"
[ -f "$GRADLE_APK" ] || { echo "Gradle failed to produce $GRADLE_APK"; exit 1; }
cp "$GRADLE_APK" drive_assist.apk
cp "$GRADLE_APK" "$ROOT/drive_assist.apk"

# Extract versionCode and versionName directly from the generated APK
badging=$("$BT/aapt2" dump badging drive_assist.apk)
VC=$(echo "$badging" | grep -oP "versionCode='\K[0-9]+")
VN=$(echo "$badging" | grep -oP "versionName='\K[^']+")
echo "OK -> drive_assist.apk ($VN, versionCode $VC)"

# ============================================================================
# Standalone Installer Package
# ============================================================================
# Packages modehelper.apk and drive_assist.apk into drive_assist_installer.apk
if [ -z "${BUILDING_INSTALLER:-}" ] && [ -x "$ROOT/installer/build-installer.sh" ]; then
  BUILDING_INSTALLER=1 "$ROOT/installer/build-installer.sh"
fi

# Build-only exit
[ -n "${NO_DEPLOY:-}" ] && exit 0

# ============================================================================
# Deployment Guard
# ============================================================================
# Ensures the current branch has integrated upstream changes and contains the
# currently deployed commit before releasing, preventing accidental rollbacks.
INTEGRATION="${INTEGRATION:-}"
if [ -z "$INTEGRATION" ]; then
  for b in main master; do
    git rev-parse --verify "$b" >/dev/null 2>&1 && { INTEGRATION="$b"; break; }
  done
fi

guard_fail() {
  echo
  echo "DEPLOYMENT BLOCKED: $1"
  echo
  exit 2
}

if [ -z "${NO_GUARD:-}" ] && git rev-parse --git-dir >/dev/null 2>&1; then
  # Exclude drive_assist.apk itself as it is a build artifact.
  # Uncommitted changes in source files must be committed before deploying.
  [ -z "$(git status --porcelain -- . ':!drive_assist.apk' 2>/dev/null)" ] \
    || { echo "Uncommitted changes:"; git status --short -- . ':!drive_assist.apk' | sed 's/^/    /'
         guard_fail "commit changes before deploying"; }

  if [ -n "$INTEGRATION" ] && ! git merge-base --is-ancestor "$INTEGRATION" HEAD 2>/dev/null; then
    echo "Integration pending: '$INTEGRATION' contains commits not present in HEAD:"
    git log --oneline HEAD.."$INTEGRATION" | sed 's/^/    /'
    guard_fail "run: git merge $INTEGRATION"
  fi
fi

# ============================================================================
# Deploy to Home Assistant (OTA Source)
# ============================================================================
# Load optional local environment overrides (gitignored)
[ -f "$ROOT/.ota-env" ] && . "$ROOT/.ota-env"
[ -f "$TOOLS/.ota-env" ] && . "$TOOLS/.ota-env"

HA_USER="${HA_USER:-root}"
HA_PORT="${HA_PORT:-22}"
SSH_OPTS=(-p "$HA_PORT" -o BatchMode=yes -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new)
HA_DEST="${HA_DEST:-/config/www/drive_assist.apk}"
HA_HOSTS=("${HA_HOSTS[@]}")
[ -n "${HA_HOST:-}" ] && HA_HOSTS+=("$HA_HOST")

# Check which commit is currently deployed to avoid overwriting newer builds
HA_SHA_DEST="${HA_SHA_DEST:-/config/www/drive_assist.sha}"
deployed=""
for H in "${HA_HOSTS[@]}"; do
  deployed=$(ssh "${SSH_OPTS[@]}" \
              "$HA_USER@$H" "cat $HA_SHA_DEST 2>/dev/null" 2>/dev/null | tr -d "\r\n") && break
done

if [ -z "${NO_GUARD:-}" ] && [ -n "$deployed" ] && git rev-parse --git-dir >/dev/null 2>&1; then
  if git cat-file -e "$deployed^{commit}" 2>/dev/null; then
    if ! git merge-base --is-ancestor "$deployed" HEAD; then
      echo "Deployed commit ($(git rev-parse --short "$deployed")) contains commits not present in HEAD:"
      git log --oneline HEAD.."$deployed" | sed 's/^/    /'
      guard_fail "deploying now would overwrite deployed commits. Run: git merge $deployed"
    fi
  else
    echo "Warning: Deployed commit ($deployed) was not found in this checkout; skipping ancestor check."
  fi
fi

ha_ok=""
for H in "${HA_HOSTS[@]}"; do
  if scp -q -P "$HA_PORT" -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new \
       drive_assist.apk "$HA_USER@$H:$HA_DEST" 2>/dev/null; then
    ha_ok="$H"
    # Also upload drivemem.apk for legacy OTA references
    scp -q -P "$HA_PORT" -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new \
      drive_assist.apk "$HA_USER@$H:/config/www/drivemem.apk" 2>/dev/null || true
    # Also upload standalone installer if present
    if [ -f "$ROOT/drive_assist_installer.apk" ]; then
      scp -q -P "$HA_PORT" -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new \
        "$ROOT/drive_assist_installer.apk" "$HA_USER@$H:/config/www/drive_assist_installer.apk" 2>/dev/null || true
    fi
    # Also upload changelog for in-app update review
    if [ -f "$ROOT/CHANGELOG.md" ]; then
      scp -q -P "$HA_PORT" -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new \
        "$ROOT/CHANGELOG.md" "$HA_USER@$H:/config/www/changelog.txt" 2>/dev/null || true
    fi
    break
  fi
done

# Write the SHA marker only after a successful APK upload
if [ -n "$ha_ok" ] && git rev-parse --git-dir >/dev/null 2>&1; then
  git rev-parse HEAD | ssh "${SSH_OPTS[@]}" \
    "$HA_USER@$ha_ok" "cat > $HA_SHA_DEST; cat > /config/www/drivemem.sha" 2>/dev/null || true
fi

if [ -n "$ha_ok" ]; then
  echo "HA    -> $ha_ok:$HA_DEST  (OTA points to this APK)"
else
  echo "HA    -> FAILED: OTA continues serving previous APK"
fi

# ============================================================================
# Vehicle Direct Install (ADB)
# ============================================================================
# Validates target package (com.geely.drivemem) presence to verify device identity
# before installing, preventing pushes to unrelated Android devices on the network.
CAR_HOSTS=("${CAR_HOSTS[@]}")
[ -n "${CAR:-}" ] && CAR_HOSTS+=("$CAR")

adb_ok=""
tried=""
for target in "${CAR_HOSTS[@]}"; do
  adb connect "$target" >/dev/null 2>&1 || true

  # Test if device shell is reachable and authorized
  adb -s "$target" shell true >/dev/null 2>&1 || continue

  model=$(adb -s "$target" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  if ! adb -s "$target" shell pm path com.geely.drivemem >/dev/null 2>&1; then
    echo "car   -> $target responds but does not have drivemem installed ($model) — skipping"
    tried="$tried $target"
    continue
  fi

  out=$(adb -s "$target" install -r drive_assist.apk 2>&1 | tail -1 || true)
  echo "car   -> $target ($model): $out"
  case "$out" in *Success*) adb_ok=1 ;; esac
  break
done

if [ -z "$adb_ok" ]; then
  unauth=""
  for target in "${CAR_HOSTS[@]}"; do
    if adb devices 2>/dev/null | grep -q "^${target}[[:space:]]*unauthorized"; then
      unauth="$target"
      break
    fi
  done

  if [ -n "$unauth" ]; then
    echo "car   -> UNAUTHORIZED: accept USB debugging prompt on vehicle display ($unauth)"
  elif [ -z "$tried" ]; then
    echo "car   -> No ADB device found on network (host reachable? adb tcpip 5555 enabled?)"
  fi
fi

# ============================================================================
# OTA Notification (Retained MQTT)
# ============================================================================
# Always publish the update URL to MQTT so that the vehicle automatically
# downloads and installs the update whenever it connects.
[ -z "$ha_ok" ] && exit 0

MQTT_HOST="${MQTT_HOST:-homeassistant.local}"
if [ -n "${OTA_URL_BASE:-}" ]; then
  OTA_URL="${OTA_URL_BASE}?v=$VC"
else
  OTA_URL=""
fi

OTA_TOPIC="${OTA_TOPIC:-drivemem/geely/update/set}"

if [ -z "$OTA_URL" ]; then
  echo "OTA   -> skipped: set OTA_URL_BASE in .ota-env to publish retained update"
  exit 0
fi

if [ -n "${MQTT_USER:-}" ] && [ -n "${MQTT_PASS:-}" ]; then
  OTA_TOPICS=("${OTA_TOPIC:-drivemem/geely/update/set}" "drivemem/123456/update/set" "drivemem/ihu/update/set")
  for top in "${OTA_TOPICS[@]}"; do
    ssh "${SSH_OPTS[@]}" "$HA_USER@$ha_ok" \
         "mosquitto_pub -h '$MQTT_HOST' -p 1883 -u '$MQTT_USER' -P '$MQTT_PASS' \
          -r -t '$top' -m '$OTA_URL'" 2>/dev/null || true
  done
  echo "OTA   -> announced (retained): $OTA_URL across ${OTA_TOPICS[*]}"
else
  echo "OTA   -> missing credentials: set MQTT_USER and MQTT_PASS in $TOOLS/.ota-env"
  echo "         (or trigger update manually via Home Assistant)"
fi
