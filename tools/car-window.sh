#!/usr/bin/env bash
# The car wakes rarely and briefly; this packs the highest-value captures into
# one non-interactive run so a window is never again lost to typing. Every adb
# call is under `timeout`: when the car vanishes mid-run the script moves on
# and keeps whatever it already has, instead of hanging on a dead socket.
#
#   ./car-window.sh          # snap: reads only + screenshot (~20 s, safe)
#   ./car-window.sh h2       # snap + direction-ownership test (writes, restores)
#
# h2 is Phase B of docs/historical/hvac-auto-test.md. Phase A (the 20-min AUTO log)
# is NOT here: it needs a long window and a hot cabin, not a brief one.

D=${D:-192.168.0.150:5555}
T=8   # seconds each adb call gets before we assume the car is gone
OUT=~/dev/geely/window-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/log.txt"; }

probe() { # probe <label> <props-csv> [areas-csv]
  timeout $T adb -s $D logcat -c 2>/dev/null
  timeout $T adb -s $D shell "am broadcast -n com.geely.drivemem/.BootReceiver \
    -a com.geely.drivemem.READPROP --es props '$2' --es areas '${3:-75,0,1}'" >/dev/null 2>&1
  sleep 4
  timeout $T adb -s $D logcat -d -s DriveMem 2>/dev/null | grep "rp:" | tee -a "$OUT/$1.txt"
}

hvacflag() { # hvacflag <which> [0|1]  — read (and optionally write) a bool flag
  timeout $T adb -s $D logcat -c 2>/dev/null
  timeout $T adb -s $D shell "am broadcast -n com.geely.drivemem/.BootReceiver \
    -a com.geely.drivemem.HVAC --es p '$1' ${2:+--ei v $2}" >/dev/null 2>&1
  if [ -n "$2" ]; then sleep 4; else sleep 2; fi
  timeout $T adb -s $D logcat -d -s DriveMem 2>/dev/null | grep "hvac\[" | tee -a "$OUT/log.txt"
}

wprop() { # wprop <prop> <area> <val>
  timeout $T adb -s $D logcat -c 2>/dev/null
  timeout $T adb -s $D shell "am broadcast -n com.geely.drivemem/.BootReceiver \
    -a com.geely.drivemem.WRITEPROP --ei prop $1 --ei area $2 --ei val $3" >/dev/null 2>&1
  sleep 5
  timeout $T adb -s $D logcat -d -s DriveMem 2>/dev/null | grep "wp:" | tee -a "$OUT/log.txt"
}

say "window open? ping..."
timeout 5 adb -s $D shell echo ok >/dev/null 2>&1 || { say "car unreachable — nothing burned"; exit 1; }

# --- always: the reads that have been waiting for an awake car ---
say "snapshot: fan / direction / inside-temp / outside-temp"
probe snapshot '356517120,268894464,557884281,557884279'
say "flags: power, ac, auto (bools — READPROP cannot see these)"
hvacflag power; hvacflag ac; hvacflag auto

say "screenshot (verifies the AUTO button, panel must be lit)"
timeout $T adb -s $D exec-out screencap -p > "$OUT/screen.png" 2>/dev/null
[ -s "$OUT/screen.png" ] && say "screenshot: $(stat -c%s "$OUT/screen.png") bytes" || say "screenshot: empty (panel off?)"

# --- h2: who owns airflow direction? writes, but restores what it found ---
if [ "$1" = h2 ]; then
  ORIG=$(grep -m1 -oP '268894464.*= \K[0-9]+' "$OUT/snapshot.txt" || echo 4)
  say "H2: direction baseline = $ORIG; writing FACE=1 with AUTO as-is"
  wprop 356517121 75 1
  say "H2: waiting 30 s to see if AUTO reclaims it"
  sleep 30
  probe h2-after '268894464,356517121'
  say "H2: restoring direction = $ORIG"
  wprop 356517121 75 "$ORIG"
fi

say "done -> $OUT"
