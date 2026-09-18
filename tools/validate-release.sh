#!/usr/bin/env bash
# Produce evidence that one exact source tree passed the release gate.
# Deliberately local/private: it neither publishes nor contacts a vehicle.
set -euo pipefail

usage() {
  echo "Usage: $0 [source-ref]" >&2
  echo "Example: $0 dev" >&2
  exit 2
}

SOURCE_REF="${1:-next}"
[[ $# -le 1 ]] || usage
git rev-parse --verify "${SOURCE_REF}^{commit}" >/dev/null
SOURCE_SHA=$(git rev-parse "${SOURCE_REF}^{commit}")

# Validation must describe the checkout being tested, not merely another ref
# that happened to pass earlier.
if [[ $(git rev-parse HEAD) != "$SOURCE_SHA" ]]; then
  echo "Validation blocked: check out $SOURCE_REF ($SOURCE_SHA) before running this script." >&2
  exit 2
fi
if [[ -n $(git status --porcelain -- . ':!.release') ]]; then
  echo "Validation blocked: commit or stash source changes first." >&2
  exit 2
fi

BASE=$(git rev-parse HEAD^ 2>/dev/null || git rev-parse HEAD)
./tools/check-pii.sh --base "$BASE"
./tools/verify.sh

APK=drivemem/build/outputs/apk/release/drive_assist.apk
[[ -f "$APK" ]] || { echo "Validation failed: release APK was not produced." >&2; exit 1; }

mkdir -p .release
OUT=".release/validation-${SOURCE_SHA}.json"
APK_SHA=$(sha256sum "$APK" | awk '{print $1}')
cat > "$OUT" <<EOF
{
  "source_sha": "$SOURCE_SHA",
  "apk_sha256": "$APK_SHA",
  "validated_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "contract": "tools/verify.sh + tools/check-pii.sh"
}
EOF

echo "Release validation recorded: $OUT"
