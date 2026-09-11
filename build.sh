#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

# Ensure modehelper is built and up-to-date
if [ ! -f "$ROOT/modehelper/modehelper.apk" ] || [ -n "$(find "$ROOT/modehelper/src" -newer "$ROOT/modehelper/modehelper.apk" 2>/dev/null)" ]; then
  "$ROOT/modehelper/build-modehelper.sh"
fi

# Build drivemem, package installer, and deploy
exec "$ROOT/drivemem/build.sh" "$@"
