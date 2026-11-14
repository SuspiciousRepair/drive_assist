#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/roadcast-dense.XXXXXX")"
DAEMON_PID=""
CLIENT_PID=""

cleanup() {
  status=$?
  for pid in "$CLIENT_PID" "$DAEMON_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid"
      wait "$pid" || true
    fi
  done
  if [ "$status" -ne 0 ]; then
    for log in "$TEMP_DIR"/*; do
      [ -f "$log" ] && tail -n 40 "$log" >&2
    done
  fi
  rm -rf "$TEMP_DIR"
  return "$status"
}
trap cleanup EXIT INT TERM

for hz in 1 60; do
  socket="$TEMP_DIR/roadcast.sock"
  "$ROOT/build/roadcastd" --fake-dense --hz "$hz" --socket "$socket" \
    >"$TEMP_DIR/daemon-$hz.stdout" 2>"$TEMP_DIR/daemon-$hz.stderr" &
  DAEMON_PID=$!
  for _ in $(seq 1 100); do
    [ -S "$socket" ] && break
    kill -0 "$DAEMON_PID"
    sleep 0.02
  done
  [ -S "$socket" ]

  # Both clients read full dense batches. SDK and CLI use independent readers.
  "$ROOT/build/roadcastctl" --socket "$socket" --seconds 3 \
    >"$TEMP_DIR/client-$hz.stdout" 2>"$TEMP_DIR/client-$hz.stderr" &
  CLIENT_PID=$!
  "$ROOT/build/roadcast_client_smoke" "$socket" ESC_VehicleSpeed 3 \
    >"$TEMP_DIR/sdk-$hz.stdout" 2>"$TEMP_DIR/sdk-$hz.stderr"
  wait "$CLIENT_PID"
  CLIENT_PID=""
  grep -q '^summary: gaps=0 resyncs=0$' "$TEMP_DIR/client-$hz.stdout"
  grep -Eq '^client-summary: connected=1 sample_seq=[1-9][0-9]* change_seq=[1-9][0-9]* resyncs=0 dropped=0 ' \
    "$TEMP_DIR/sdk-$hz.stdout"
  if grep -q 'queue overflow' "$TEMP_DIR/daemon-$hz.stderr"; then
    echo "dense update overflowed a fast client's queue at $hz Hz" >&2
    exit 1
  fi

  kill -TERM "$DAEMON_PID"
  wait "$DAEMON_PID"
  DAEMON_PID=""
done

echo "dense integration tests passed"
