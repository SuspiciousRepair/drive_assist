#!/usr/bin/env python3
"""Join accel.log against drivemem's obd2-reading.log by NEAREST timestamp,
not exact match. An exact-string join on the "wall" column looked tempting
but doesn't work: OBD2 only logs once every 2s (Obd2Reader.POLL_MS) while
the accelerometer logs ~50/s, so almost every accel row falls in a second
with no OBD2 line at all and `join` would drop it silently.

This can never be more precise than +-1s anyway -- obd2-reading.log only
ever stores whole seconds (Obd2Reader.LOG_FMT), it has no millisecond
column to match accel.log's epochMs against. Real ceiling, not a bug here.

Also adds a lin_magnitude column: a software-only gravity-removed
acceleration magnitude. AccelProbeActivity's TYPE_LINEAR_ACCELERATION
registers and reports "active" in dumpsys sensorservice but never
actually delivers an event on this head unit (confirmed live,
2026-09-22) -- a real MTK sensor HAL dead end, not a bug here. This is
the standard DIY substitute: estimate gravity as a rolling average of
the raw accelerometer over WINDOW_ROWS samples (~0.5s at accel.log's
~400Hz), then subtract it. Measured live over a real drive: `|magnitude
- 9.8|` (deviation from resting gravity) correlates with OBD2 power at
only 0.053; lin_magnitude improves that to 0.126 -- still weak (see
README: the +-1s OBD2 match window dilutes both), but a real,
reproducible improvement, not noise.

Usage: ./correlate.py accel.log obd2-reading.log > correlated.tsv
"""
import sys
import bisect
from datetime import datetime

WINDOW_ROWS = 200

def linear_magnitudes(xs, ys, zs, window):
    n = len(xs)
    gx = [0.0] * n
    gy = [0.0] * n
    gz = [0.0] * n
    sx = sy = sz = 0.0
    for i in range(n):
        sx += xs[i]; sy += ys[i]; sz += zs[i]
        if i >= window:
            sx -= xs[i - window]; sy -= ys[i - window]; sz -= zs[i - window]
        k = min(i + 1, window)
        gx[i] = sx / k; gy[i] = sy / k; gz[i] = sz / k
    return [((xs[i] - gx[i]) ** 2 + (ys[i] - gy[i]) ** 2 + (zs[i] - gz[i]) ** 2) ** 0.5
            for i in range(n)]

def load_obd2(path):
    # These logs are written on a car that suspends/resumes constantly and
    # rotates at a byte cap (see Obd2Reader/AccelProbeActivity) -- a torn
    # write (app killed, or storage hiccup) can leave a stray run of NUL
    # bytes or a truncated row. Skip anything that doesn't parse instead
    # of crashing the whole correlation over one bad line.
    rows = []
    skipped = 0
    with open(path) as f:
        next(f)  # header
        for line in f:
            try:
                wall, soc, v, i, kw, temp, speed = line.rstrip("\n").split("\t")
                t = datetime.strptime(wall, "%Y-%m-%d %H:%M:%S").timestamp() * 1000
            except ValueError:
                skipped += 1
                continue
            rows.append((t, wall, soc, v, i, kw, temp, speed))
    if skipped:
        print(f"obd2-reading.log: skipped {skipped} unparseable row(s)", file=sys.stderr)
    rows.sort()
    return rows

def nearest(obd2, times, t_ms):
    # obd2-reading.log is cumulative across every past drive, not just
    # today's -- could be hours of history before the window accel.log
    # actually covers. A linear scan from the start of the list on every
    # call (the first draft of this function) is O(rows_in_accel *
    # rows_in_obd2); at real log sizes (tens of thousands of rows each)
    # that's tens of millions of comparisons and takes far too long.
    # Binary search instead: O(log n) per lookup.
    i = bisect.bisect_left(times, t_ms)
    candidates = []
    if i < len(obd2):
        candidates.append(obd2[i])
    if i > 0:
        candidates.append(obd2[i - 1])
    best = min(candidates, key=lambda row: abs(row[0] - t_ms))
    return best, abs(best[0] - t_ms)

def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    accel_path, obd2_path = sys.argv[1], sys.argv[2]
    obd2 = load_obd2(obd2_path)
    if not obd2:
        print("obd2-reading.log has no rows -- nothing to correlate against", file=sys.stderr)
        sys.exit(1)
    times = [row[0] for row in obd2]

    accel_rows = []
    skipped = 0
    with open(accel_path) as f:
        next(f)  # header
        for line in f:
            try:
                wall, epoch_ms, sensor_ns, x, y, z, mag = line.rstrip("\n").split("\t")
                accel_rows.append((wall, float(epoch_ms), float(x), float(y), float(z), mag))
            except ValueError:
                skipped += 1
                continue
    if skipped:
        print(f"accel.log: skipped {skipped} unparseable row(s)", file=sys.stderr)

    lin_mags = linear_magnitudes([r[2] for r in accel_rows],
                                  [r[3] for r in accel_rows],
                                  [r[4] for r in accel_rows], WINDOW_ROWS)

    print("wall\tepochMs\tx\ty\tz\tmagnitude\tlin_magnitude\tmatch_dt_ms"
          "\tsoc\tvoltage\tcurrent\tpowerKw\tbattTempC\tspeedKmh")
    for (wall, epoch_ms, x, y, z, mag), lin_mag in zip(accel_rows, lin_mags):
        row, dt = nearest(obd2, times, epoch_ms)
        print("\t".join([wall, f"{epoch_ms:.0f}", str(x), str(y), str(z), mag,
                          f"{lin_mag:.4f}", f"{dt:.0f}",
                          row[2], row[3], row[4], row[5], row[6], row[7]]))

if __name__ == "__main__":
    main()
