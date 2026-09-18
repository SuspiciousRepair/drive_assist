#!/usr/bin/env bash
# Opt into the repository's tracked local safety hooks for this checkout.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
git rev-parse --git-dir >/dev/null
git config core.hooksPath .githooks
echo "Installed repository hooks for this checkout (.githooks/pre-push)."
