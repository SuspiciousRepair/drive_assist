#!/usr/bin/env python3
"""Merge an older Drive Assist car.db (from before a wipe) into the current one.

    python3 -I tools/merge-car-db.py OLD.db CURRENT.db OUT.db

OUT keeps CURRENT's schema, because the running app already migrated it. The
old rows keep their own ids. The current rows move up past them, so ids still
follow time, and every reference to a moved row moves with it:

    trip / charge_session / park_session .start_sample_id/.end_sample_id -> telemetry_sample
    trip_energy_segment.trip_id                                           -> trip
    charge_stop_event.session_id                                          -> charge_session

Only columns that exist in both files are copied. A column the old schema
lacks stays NULL or takes its default. daily_stat is keyed by date, and the
current day wins on a clash. Both inputs are opened read-only, with their
-wal/-shm beside them, so they must sit together.
"""
import sqlite3
import sys

ID_TABLES = ["telemetry_sample", "trip", "charge_session", "park_session",
             "comfort_event", "battery_health_reading", "charge_stop_event",
             "valet_session", "trip_energy_segment"]
# column -> table it points at
REFS = {
    "trip": {"start_sample_id": "telemetry_sample", "end_sample_id": "telemetry_sample"},
    "charge_session": {"start_sample_id": "telemetry_sample", "end_sample_id": "telemetry_sample"},
    "park_session": {"start_sample_id": "telemetry_sample", "end_sample_id": "telemetry_sample"},
    "trip_energy_segment": {"trip_id": "trip"},
    "charge_stop_event": {"session_id": "charge_session"},
}


def cols(db, schema, table):
    return [r[1] for r in db.execute(f"PRAGMA {schema}.table_info({table})")]


def has_table(db, schema, table):
    return db.execute(f"SELECT 1 FROM {schema}.sqlite_master WHERE type='table' AND name=?",
                      (table,)).fetchone() is not None


def main(old_path, cur_path, out_path):
    # Copy CURRENT through sqlite's own backup, so its WAL is folded in.
    src = sqlite3.connect(f"file:{cur_path}?mode=ro", uri=True)
    db = sqlite3.connect(out_path)
    src.backup(db)
    src.close()
    db.execute(f"ATTACH 'file:{old_path}?mode=ro' AS old")
    db.execute("PRAGMA foreign_keys=OFF")

    tables = [t for t in ID_TABLES if has_table(db, "main", t) and has_table(db, "old", t)]
    offset = {t: db.execute(f"SELECT COALESCE(MAX(id),0) FROM old.{t}").fetchone()[0] for t in tables}
    before = {t: db.execute(f"SELECT COUNT(*) FROM main.{t}").fetchone()[0] for t in tables}
    old_n = {t: db.execute(f"SELECT COUNT(*) FROM old.{t}").fetchone()[0] for t in tables}

    with db:
        # 1. Move current rows up past the old ids. The two steps (negate, then
        #    flip back) keep a shifted id from clashing with one not yet moved.
        for t in tables:
            if offset[t]:
                db.execute(f"UPDATE main.{t} SET id = -(id + ?)", (offset[t],))
                db.execute(f"UPDATE main.{t} SET id = -id")
        # 2. Make the current rows' references follow.
        for t, refs in REFS.items():
            if t not in tables:
                continue
            here = cols(db, "main", t)
            for col, target in refs.items():
                if col in here and offset.get(target):
                    db.execute(f"UPDATE main.{t} SET {col} = {col} + ? WHERE {col} IS NOT NULL",
                               (offset[target],))
        # 3. Copy the old rows in, ids unchanged.
        for t in tables:
            common = [c for c in cols(db, "old", t) if c in cols(db, "main", t)]
            cl = ", ".join(common)
            db.execute(f"INSERT INTO main.{t} ({cl}) SELECT {cl} FROM old.{t}")
        if has_table(db, "main", "daily_stat") and has_table(db, "old", "daily_stat"):
            common = [c for c in cols(db, "old", "daily_stat") if c in cols(db, "main", "daily_stat")]
            cl = ", ".join(common)
            db.execute(f"INSERT OR IGNORE INTO main.daily_stat ({cl}) SELECT {cl} FROM old.daily_stat")
        # 4. AUTOINCREMENT must hand out ids above everything now present.
        for t in tables:
            mx = db.execute(f"SELECT COALESCE(MAX(id),0) FROM main.{t}").fetchone()[0]
            if db.execute("SELECT 1 FROM main.sqlite_sequence WHERE name=?", (t,)).fetchone():
                db.execute("UPDATE main.sqlite_sequence SET seq=? WHERE name=?", (mx, t))
            else:
                db.execute("INSERT INTO main.sqlite_sequence(name, seq) VALUES (?, ?)", (t, mx))

    for t in tables:
        after = db.execute(f"SELECT COUNT(*) FROM main.{t}").fetchone()[0]
        if after != before[t] + old_n[t]:
            sys.exit(f"ERROR: {t}: {before[t]} current + {old_n[t]} old != {after} merged")
        print(f"{t:24} {old_n[t]:>7} old + {before[t]:>6} current = {after:>7}")
    db.execute("DETACH old")
    ok = db.execute("PRAGMA integrity_check").fetchone()[0]
    db.execute("PRAGMA journal_mode=DELETE")  # one self-contained file to push
    db.close()
    if ok != "ok":
        sys.exit(f"ERROR: integrity_check: {ok}")
    print(f"integrity ok -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
