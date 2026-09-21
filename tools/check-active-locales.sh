#!/usr/bin/env bash
# The languages shipped by the app must remain complete relative to the
# default (English) bundle. Android can fall back for an individual missing
# key, but that should be an intentional decision, not silent translation
# drift in a supported locale.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES="$ROOT/drivemem/src/main/res"
DEFAULT="$RES/values/strings.xml"
ACTIVE=(values-pt values-th values-es)

keys() {
  sed -nE 's/.*<string name="([^"]+)".*/\1/p' "$1" | sort -u
}

[[ -f "$DEFAULT" ]] || { echo "Missing default strings bundle: $DEFAULT" >&2; exit 1; }
default_keys=$(mktemp)
trap 'rm -f "$default_keys" "${locale_keys:-}"' EXIT
keys "$DEFAULT" > "$default_keys"

failed=0
for locale in "${ACTIVE[@]}"; do
  file="$RES/$locale/strings.xml"
  if [[ ! -f "$file" ]]; then
    echo "Missing active locale bundle: $file" >&2
    failed=1
    continue
  fi
  locale_keys=$(mktemp)
  keys "$file" > "$locale_keys"
  missing=$(comm -23 "$default_keys" "$locale_keys")
  extra=$(comm -13 "$default_keys" "$locale_keys")
  if [[ -n "$missing" || -n "$extra" ]]; then
    echo "Active locale key mismatch: $locale" >&2
    [[ -z "$missing" ]] || { echo "  Missing:" >&2; printf '%s\n' "$missing" | sed 's/^/    /' >&2; }
    [[ -z "$extra" ]] || { echo "  Not in English default:" >&2; printf '%s\n' "$extra" | sed 's/^/    /' >&2; }
    failed=1
  fi
  rm -f "$locale_keys"
  unset locale_keys
done

[[ "$failed" -eq 0 ]] || exit 1
echo "Active locale keys match English: ${ACTIVE[*]}"
