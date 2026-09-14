#!/usr/bin/env bash
# Scan additions, rather than historical source, for material that must never
# enter a public commit. Existing documented examples are not silently blessed:
# they should be replaced separately, not copied into new work.
set -euo pipefail

usage() {
  echo "Usage: $0 [--staged | --base <git-revision>]" >&2
  exit 2
}

diff_args=()
case "${1:-}" in
  "") diff_args=(--cached) ;;
  --staged) diff_args=(--cached) ;;
  --base)
    [[ $# -eq 2 ]] || usage
    diff_args=("$2"...HEAD)
    ;;
  *) usage ;;
esac

added=$(git diff --no-ext-diff --unified=0 "${diff_args[@]}" \
  | sed -e '/^+++ /d' -e '/^+/!d' -e 's/^+//')

# A false positive can be documented on the same line with `pii: allow`.
# Never use that for a real secret, identifier, or vehicle/home-network detail.
pii_pattern='-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
pii_pattern+='|\b(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[0-1])\.[0-9]{1,3}\.[0-9]{1,3})\b'
pii_pattern+='|(mqtt|ssl|https?)://[^/[:space:]@]+:[^/[:space:]@]+@'

matches=$(printf '%s\n' "$added" | grep -Pin -e "$pii_pattern" || true)
matches=$(printf '%s\n' "$matches" | grep -Piv 'pii:[[:space:]]*allow' || true)

if [[ -n "$matches" ]]; then
  echo "PII/secrets guard rejected added content:" >&2
  echo "$matches" >&2
  echo "Use a placeholder (<CAR_IP>, <token>) or remove the sensitive value." >&2
  echo "A documented false positive may use 'pii: allow' on that exact line." >&2
  exit 1
fi
