#!/usr/bin/env bash
# Checks the drive_assist patch to roadcastd: --allow-uid. Uses the host build
# (HOST=1 tools/build-roadcastd.sh) and its fake source, so no car is needed.
set -euo pipefail
B="$(cd "$(dirname "$0")/.." && pwd)/build/roadcast/host"
me=$(id -u)
other=$(( me + 4242 ))
run() { # socket, allow-uid -> prints "ok" or "refused"
  "$B/roadcastd" --fake --hz 10 --socket "$1" --allow-uid "$2" >/dev/null 2>&1 &
  local pid=$!
  sleep 1
  if timeout 5 "$B/roadcastctl" --socket "$1" --seconds 1 >/dev/null 2>&1; then echo ok; else echo refused; fi
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
if [ "$me" = 0 ]; then echo "skip: root is always allowed"; exit 0; fi
a=$(run "@da-allow-$$" "$me")
b=$(run "@da-deny-$$" "$other")
echo "listed uid: $a / other uid: $b"
[ "$a" = ok ] && [ "$b" = refused ]
