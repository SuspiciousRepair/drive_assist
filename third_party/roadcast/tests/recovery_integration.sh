#!/bin/sh
set -eu

case "$(uname -s)" in
  Linux) ;;
  *) echo "source recovery integration requires Linux/Android /proc (skipped)"; exit 0 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/roadcast-recovery.XXXXXX")"
DAEMON_PID=""
WATCH_PID=""
FIXTURE_PID=""
cleanup() {
  status=$?
  for pid in "$WATCH_PID" "$FIXTURE_PID" "$DAEMON_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid"
      wait "$pid" || true
    fi
  done
  if [ "$status" -ne 0 ]; then
    for log in "$TEMP_DIR"/*.stdout "$TEMP_DIR"/*.stderr; do
      [ ! -f "$log" ] || tail -n 40 "$log" >&2
    done
  fi
  rm -rf "$TEMP_DIR"
  return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

wait_phase() {
  attempts=0
  while ! grep -q "^phase=$1 " "$TEMP_DIR/watch.stdout"; do
    kill -0 "$WATCH_PID"
    attempts=$((attempts + 1))
    [ "$attempts" -lt 160 ] || return 1
    sleep 0.05
  done
}

socket="$TEMP_DIR/socket"
# argv[0] of the fixture is unique; neither the daemon nor shell is a source.
"$ROOT/build/roadcastd" --proc "$ROOT/build/test_vhal_source" --hz 60 \
  --socket "$socket" >"$TEMP_DIR/daemon.stdout" 2>"$TEMP_DIR/daemon.stderr" &
DAEMON_PID=$!
attempts=0
while [ ! -S "$socket" ]; do
  kill -0 "$DAEMON_PID"
  attempts=$((attempts + 1))
  [ "$attempts" -lt 100 ] || exit 1
  sleep 0.02
done
"$ROOT/build/source_recovery_watch" "$socket" \
  >"$TEMP_DIR/watch.stdout" 2>"$TEMP_DIR/watch.stderr" &
WATCH_PID=$!
wait_phase 0

"$ROOT/build/test_vhal_source" --fixture-changing 1 "$DAEMON_PID" >"$TEMP_DIR/fixture.stdout" &
FIXTURE_PID=$!
wait_phase 1
kill -TERM "$FIXTURE_PID"
wait "$FIXTURE_PID" || true
FIXTURE_PID=""
wait_phase 2
sleep 1
"$ROOT/build/test_vhal_source" --fixture-changing 1 "$DAEMON_PID" >"$TEMP_DIR/fixture.stdout" &
FIXTURE_PID=$!
wait_phase 3
wait "$WATCH_PID"
WATCH_PID=""
cat "$TEMP_DIR/watch.stdout"
echo "source recovery integration passed"
