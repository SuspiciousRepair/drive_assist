#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/roadcast-test.XXXXXX")"
SOCKET="$TEMP_DIR/roadcast.sock"
DAEMON_PID=""
IDLE_CLIENTS_PID=""
CLIENT_A_PID=""
CLIENT_B_PID=""
SLOW_CLIENT_PID=""
RECOVERY_CLIENT_PID=""

cleanup() {
  status=$?
  for pid in "$IDLE_CLIENTS_PID" "$CLIENT_A_PID" "$CLIENT_B_PID" \
    "$SLOW_CLIENT_PID" "$RECOVERY_CLIENT_PID" "$DAEMON_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid"
      wait "$pid" || true
    fi
  done
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

"$ROOT/build/roadcastd" --fake --hz 240 --socket "$SOCKET" \
  >"$TEMP_DIR/daemon.stdout" 2>"$TEMP_DIR/daemon.stderr" &
DAEMON_PID=$!

for _ in $(seq 1 100); do
  [ -S "$SOCKET" ] && break
  kill -0 "$DAEMON_PID"
  sleep 0.02
done
[ -S "$SOCKET" ]

"$ROOT/build/idle_clients" "$SOCKET" 16 5 \
  >"$TEMP_DIR/idle-clients.stdout" 2>"$TEMP_DIR/idle-clients.stderr" &
IDLE_CLIENTS_PID=$!
for _ in $(seq 1 100); do
  grep -q '^idle clients ready: 16$' "$TEMP_DIR/idle-clients.stdout" &&
    break
  kill -0 "$IDLE_CLIENTS_PID"
  sleep 0.02
done
grep -q '^idle clients ready: 16$' "$TEMP_DIR/idle-clients.stdout"
sleep 3
"$ROOT/build/roadcastctl" --socket "$SOCKET" --seconds 1 \
  >"$TEMP_DIR/post-timeout-client.stdout" \
  2>"$TEMP_DIR/post-timeout-client.stderr"
grep -q '^welcome: protocol=3 ' "$TEMP_DIR/post-timeout-client.stdout"
wait "$IDLE_CLIENTS_PID"
IDLE_CLIENTS_PID=""

python3 "$ROOT/tests/protocol_abuse.py" "$SOCKET" \
  >"$TEMP_DIR/protocol-abuse.stdout" \
  2>"$TEMP_DIR/protocol-abuse.stderr"
"$ROOT/build/roadcastctl" --socket "$SOCKET" --seconds 1 \
  >"$TEMP_DIR/post-abuse-client.stdout" \
  2>"$TEMP_DIR/post-abuse-client.stderr"
grep -q '^protocol abuse cases passed: 8$' \
  "$TEMP_DIR/protocol-abuse.stdout"
grep -q '^welcome: protocol=3 ' "$TEMP_DIR/post-abuse-client.stdout"

"$ROOT/build/roadcast_client_smoke" "$SOCKET" ESC_VehicleSpeed 2 \
  >"$TEMP_DIR/client-sdk.stdout" 2>"$TEMP_DIR/client-sdk.stderr"
grep -q '^client-sdk: protocol=3 hz=240 frames=111 signals=815 schema=1$' \
  "$TEMP_DIR/client-sdk.stdout"
grep -q '^client-value: name=ESC_VehicleSpeed .* state=2 ' \
  "$TEMP_DIR/client-sdk.stdout"
grep -Eq '^client-frame: can=0x[0-9a-f]{3} state=2 data=[0-9a-f]{16}$' \
  "$TEMP_DIR/client-sdk.stdout"
grep -Eq '^client-summary: connected=1 sample_seq=[1-9][0-9]* change_seq=[1-9][0-9]* ' \
  "$TEMP_DIR/client-sdk.stdout"

"$ROOT/build/roadcastctl" --socket "$SOCKET" --seconds 2 \
  --signal VehicleSpeed \
  >"$TEMP_DIR/client-a.stdout" 2>"$TEMP_DIR/client-a.stderr" &
CLIENT_A_PID=$!
"$ROOT/build/roadcastctl" --socket "$SOCKET" --seconds 2 \
  >"$TEMP_DIR/client-b.stdout" 2>"$TEMP_DIR/client-b.stderr" &
CLIENT_B_PID=$!
"$ROOT/build/roadcastctl" --socket "$SOCKET" --stall-after-subscribe 4 \
  >"$TEMP_DIR/slow-client.stdout" 2>"$TEMP_DIR/slow-client.stderr" &
SLOW_CLIENT_PID=$!
"$ROOT/build/roadcastctl" --socket "$SOCKET" --pause-after-subscribe 4 \
  --seconds 2 \
  >"$TEMP_DIR/recovery-client.stdout" \
  2>"$TEMP_DIR/recovery-client.stderr" &
RECOVERY_CLIENT_PID=$!

wait "$CLIENT_A_PID"
CLIENT_A_PID=""
wait "$CLIENT_B_PID"
CLIENT_B_PID=""
wait "$SLOW_CLIENT_PID"
SLOW_CLIENT_PID=""
wait "$RECOVERY_CLIENT_PID"
RECOVERY_CLIENT_PID=""

for client in client-a client-b; do
  grep -q '^welcome: protocol=3 hz=240 frames=111 signals=815 max_request=4064 max_response=2016 ' \
    "$TEMP_DIR/$client.stdout"
  grep -q '^schema: version=1 signals=815 ' "$TEMP_DIR/$client.stdout"
  grep -q '^snapshot: frames=111 signals=815 ' "$TEMP_DIR/$client.stdout"
  grep -q '^stream: frame_batches=' "$TEMP_DIR/$client.stdout"
  if grep -Eq 'gaps=[1-9][0-9]*' "$TEMP_DIR/$client.stdout"; then
    echo "$client observed a sequence gap" >&2
    exit 1
  fi
done

grep -q '^schema-entry: .*name=ESC_VehicleSpeed ' \
  "$TEMP_DIR/client-a.stdout"
grep -q '^signal: .*name=ESC_VehicleSpeed ' "$TEMP_DIR/client-a.stdout"
grep -q '^update: .*name=ESC_VehicleSpeed ' "$TEMP_DIR/client-a.stdout"
grep -q '^update: .*name=ESC_VehicleSpeed .*state=2 .*first_ns=[1-9][0-9]* changed_ns=[1-9][0-9]*$' \
  "$TEMP_DIR/client-a.stdout"
grep -q '^stalling: seconds=4$' "$TEMP_DIR/slow-client.stdout"
grep -q '^pausing: seconds=4$' "$TEMP_DIR/recovery-client.stdout"
grep -q '^summary: gaps=0 resyncs=[1-9][0-9]*$' \
  "$TEMP_DIR/recovery-client.stdout"
grep -q 'client queue overflow; resync required' "$TEMP_DIR/daemon.stderr"

kill -TERM "$DAEMON_PID"
wait "$DAEMON_PID"
DAEMON_PID=""

echo "integration tests passed"
