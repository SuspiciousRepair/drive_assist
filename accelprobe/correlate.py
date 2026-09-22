#!/usr/bin/env python3
"""Join accel.log against drivemem's obd2-reading.log by NEAREST timestamp,
not exact match. An exact-string join on the "wall" column looked tempting
but doesn't work: OBD2 only logs once every 2s (Obd2Reader.POLL_MS) while
the accelerometer logs ~50/s, so almost every accel row falls in a second
with no OBD2 line at all and `join` would drop it silently.

This can never be more precise than +-1s anyway -- obd2-reading.log only
ever stores whole seconds (Obd2Reader.LOG_FMT), it has no millisecond
column to match accel.log's epochMs against. Real ceiling, not a bug here.

Usage: ./correlate.py accel.log obd2-reading.log > correlated.tsv
"""
import sys
from datetime import datetime

def load_obd2(path):
    rows = []
    with open(path) as f:
        next(f)  # header
        for line in f:
            wall, soc, v, i, kw, temp, speed = line.rstrip("\n").split("\t")
            t = datetime.strptime(wall, "%Y-%m-%d %H:%M:%S").timestamp() * 1000
            rows.append((t, wall, soc, v, i, kw, temp, speed))
    rows.sort()
    return rows

def nearest(obd2, t_ms):
    # obd2 is sorted by time; obd2 readings are 2s apart so a linear scan
    # from the last match forward is fine at accel.log's row counts.
    best = None
    best_dt = None
    for row in obd2:
        dt = abs(row[0] - t_ms)
        if best_dt is None or dt < best_dt:
            best, best_dt = row, dt
        elif row[0] > t_ms:
            break  # rows are sorted; distance can only grow from here
    return best, best_dt

def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    accel_path, obd2_path = sys.argv[1], sys.argv[2]
    obd2 = load_obd2(obd2_path)
    if not obd2:
        print("obd2-reading.log has no rows -- nothing to correlate against", file=sys.stderr)
        sys.exit(1)

    print("wall\tepochMs\tx\ty\tz\tmagnitude\tmatch_dt_ms\tsoc\tvoltage\tcurrent\tpowerKw\tbattTempC\tspeedKmh")
    with open(accel_path) as f:
        next(f)  # header
        for line in f:
            wall, epoch_ms, sensor_ns, x, y, z, mag = line.rstrip("\n").split("\t")
            row, dt = nearest(obd2, float(epoch_ms))
            print("\t".join([wall, epoch_ms, x, y, z, mag, f"{dt:.0f}",
                              row[2], row[3], row[4], row[5], row[6], row[7]]))

if __name__ == "__main__":
    main()
