#!/usr/bin/env bash
# Builds the car app translations into modehelper: copies manifest.json and
# the files it lists from one commit of SuspiciousRepair/integration-oem-
# translations into modehelper/assets/oem-text/, and writes that commit to
# SOURCE. Nothing is downloaded on the car: the text a car can apply is the
# text in its signed helper app (the known patch), and only onto the factory
# apks whose SHA-256 the manifest lists (the known file).
#
# usage: tools/pin-oem-translations.sh <path to integration-oem-translations> [commit]
set -euo pipefail
cd "$(dirname "$0")/.."
repo=${1:?usage: $0 <path to integration-oem-translations> [commit]}
commit=$(git -C "$repo" rev-parse "${2:-HEAD}^{commit}")
# Only a commit that is public, so anyone can see where the text came from.
git -C "$repo" fetch -q origin
git -C "$repo" merge-base --is-ancestor "$commit" origin/main \
  || { echo "commit $commit is not on origin/main: push it first" >&2; exit 1; }

out=modehelper/assets/oem-text
files=$(git -C "$repo" show "$commit:manifest.json" | python3 -c '
import json, sys
m = json.load(sys.stdin)
for a in m["apps"]:
    assert a.get("apk_sha256"), "no apk_sha256 for " + a["package"]
    print(a["file"])')

rm -rf "$out"
mkdir -p "$out/apps"
git -C "$repo" show "$commit:manifest.json" > "$out/manifest.json"
for f in $files; do
  git -C "$repo" show "$commit:$f" > "$out/$f"
  echo "bundled $f"
done
echo "$commit" > "$out/SOURCE"
echo "wrote $out (commit $commit)"
