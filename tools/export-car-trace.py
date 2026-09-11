#!/usr/bin/env python3
"""Convert a local, consistent car.db snapshot to a numeric test fixture (read-only)."""
import argparse
import csv
from pathlib import Path
import sqlite3

COLUMNS = ('ts_ms', 'odo_km', 'speed_kmh', 'gear', 'is_charging',
           'energy_spent_kwh', 'energy_regen_kwh', 'instant_power_kw_est')


def export_trace(database, output, start_ms, end_ms):
    if start_ms >= end_ms:
        raise ValueError('start-ms must be less than end-ms')
    with sqlite3.connect(Path(database).resolve().as_uri() + '?mode=ro', uri=True) as db:
        rows = db.execute('SELECT ' + ','.join(COLUMNS) +
                          ' FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ? ORDER BY ts_ms,id',
                          (start_ms, end_ms)).fetchall()
    if not rows:
        raise ValueError('No telemetry samples in requested interval')
    base_ts = rows[0][0]
    base_odo = next((r[1] for r in rows if r[1] is not None and r[1] > 0), None)
    with Path(output).open('x', newline='') as stream:
        writer = csv.writer(stream, lineterminator='\n')
        writer.writerow(('elapsed_ms',) + COLUMNS[1:])
        for row in rows:
            row = list(row)
            row[0] -= base_ts
            # Preserve zero/negative startup sentinels. Positive readings retain distances,
            # but no longer expose the owner's actual lifetime odometer.
            if base_odo is not None and row[1] is not None and row[1] > 0:
                row[1] = round(row[1] - base_odo + 10000, 6)
            writer.writerow(row)
    return len(rows)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--start-ms', type=int, required=True)
    parser.add_argument('--end-ms', type=int, required=True)
    args = parser.parse_args()
    try:
        count = export_trace(args.database, args.output, args.start_ms, args.end_ms)
    except (OSError, sqlite3.Error, ValueError) as error:
        parser.exit(1, f'{error}\n')
    print(f'Exported {count} samples to {args.output}')
