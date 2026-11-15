#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/roadcast-catchup-test.XXXXXX")"
SOCKET="$TEMP_DIR/roadcast.sock"
DAEMON_PID=""

cleanup() {
  status=$?
  if [ -n "$DAEMON_PID" ] && kill -0 "$DAEMON_PID" 2>/dev/null; then
    kill -TERM "$DAEMON_PID"
    wait "$DAEMON_PID" || true
  fi
  if [ "$status" -ne 0 ]; then
    for log in "$TEMP_DIR"/*.stdout "$TEMP_DIR"/*.stderr; do
      if [ -f "$log" ]; then
        printf '\n--- %s ---\n' "$(basename "$log")" >&2
        tail -n 80 "$log" >&2
      fi
    done
  fi
  rm -rf "$TEMP_DIR"
  return "$status"
}
trap cleanup EXIT INT TERM

"$ROOT/build/roadcastd" --fake --hz 1 --client-queue-slots 1 \
  --socket "$SOCKET" \
  >"$TEMP_DIR/daemon.stdout" 2>"$TEMP_DIR/daemon.stderr" &
DAEMON_PID=$!

for _ in $(seq 1 100); do
  [ -S "$SOCKET" ] && break
  kill -0 "$DAEMON_PID"
  sleep 0.02
done
[ -S "$SOCKET" ]

"$ROOT/build/roadcastctl" --socket "$SOCKET" --delay-before-subscribe 2 \
  --stall-after-subscribe 1 \
  >"$TEMP_DIR/client.stdout" 2>"$TEMP_DIR/client.stderr"

grep -q '^welcome: protocol=3 hz=1 ' "$TEMP_DIR/client.stdout"
grep -q '^stalling: seconds=1$' "$TEMP_DIR/client.stdout"
grep -q 'subscription catch-up resumed after queue capacity became available' \
  "$TEMP_DIR/daemon.stderr"

kill -TERM "$DAEMON_PID"
wait "$DAEMON_PID"
DAEMON_PID=""

echo "catch-up integration tests passed"
