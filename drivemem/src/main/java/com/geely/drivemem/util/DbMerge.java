package com.geely.drivemem.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges the trip history of a backup into the live car.db.
 *
 * The backup is ATTACHed as schema "old". Its rows keep their own ids. The
 * live rows move up past them, so ids still follow time, and every reference
 * to a moved row moves with it. Only columns present in both are copied: a
 * column the backup lacks stays NULL or takes its default. daily_stat is keyed
 * by date, and the live day wins on a clash.
 *
 * The backup must already be on the live schema version (the caller runs the
 * app's own migrations on it first), so "common columns" only ever covers
 * column ORDER differences between a migrated and a freshly created table.
 *
 * Plain SQL through {@link Sql}, so the same code runs on the car and in the
 * JVM unit tests (sqlite-jdbc). Same algorithm as tools/merge-car-db.py.
 */
public final class DbMerge {
    private DbMerge() {}

    /** The few SQL calls the merge needs. Arguments are bound, never spliced. */
    public interface Sql {
        void exec(String sql, Object... args) throws Exception;
        long queryLong(String sql, Object... args) throws Exception;
        /** Column names of schema.table, empty when the table is missing. */
        List<String> columns(String schema, String table) throws Exception;
    }

    static final String[] ID_TABLES = {
        "telemetry_sample", "trip", "charge_session", "park_session",
        "comfort_event", "battery_health_reading", "charge_stop_event",
        "valet_session", "trip_energy_segment"};

    /** column -> table it points at, per table. */
    static final Map<String, Map<String, String>> REFS = new LinkedHashMap<>();
    static {
        for (String t : new String[]{"trip", "charge_session", "park_session"}) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("start_sample_id", "telemetry_sample");
            m.put("end_sample_id", "telemetry_sample");
            REFS.put(t, m);
        }
        Map<String, String> seg = new LinkedHashMap<>();
        seg.put("trip_id", "trip");
        REFS.put("trip_energy_segment", seg);
        Map<String, String> stop = new LinkedHashMap<>();
        stop.put("session_id", "charge_session");
        REFS.put("charge_stop_event", stop);
    }

    /** What happened, per table. offsets: how far each table's live ids moved,
     * which the caller also applies to ids it keeps outside the database. */
    public static final class Result {
        public final Map<String, Long> offsets = new LinkedHashMap<>();
        public final Map<String, Long> added = new LinkedHashMap<>();
    }

    /** True when the backup's history reaches into the live one: the same
     * trips are already here (a restore pressed twice, or a backup of this
     * very database). Merging then would double them, so the caller skips
     * the trips. A wiped car has only rows newer than its backup. */
    public static boolean overlaps(Sql db) throws Exception {
        for (String[] tc : new String[][]{{"telemetry_sample", "ts_ms"}, {"trip", "start_ms"}}) {
            if (db.columns("main", tc[0]).isEmpty() || db.columns("old", tc[0]).isEmpty()) continue;
            if (db.queryLong("SELECT COUNT(*) FROM main." + tc[0]) == 0) continue;
            if (db.queryLong("SELECT COUNT(*) FROM old." + tc[0]) == 0) continue;
            long liveFirst = db.queryLong("SELECT MIN(" + tc[1] + ") FROM main." + tc[0]);
            long oldLast = db.queryLong("SELECT MAX(" + tc[1] + ") FROM old." + tc[0]);
            if (liveFirst <= oldLast) return true;
        }
        return false;
    }

    /** Runs inside the caller's transaction. Throws on any count mismatch, so
     * the caller's rollback leaves the live database untouched. */
    public static Result merge(Sql db) throws Exception {
        Result r = new Result();
        List<String> tables = new ArrayList<>();
        for (String t : ID_TABLES) {
            if (!db.columns("main", t).isEmpty() && !db.columns("old", t).isEmpty()) tables.add(t);
        }
        Map<String, Long> before = new LinkedHashMap<>();
        for (String t : tables) {
            r.offsets.put(t, db.queryLong("SELECT COALESCE(MAX(id),0) FROM old." + t));
            before.put(t, db.queryLong("SELECT COUNT(*) FROM main." + t));
            r.added.put(t, db.queryLong("SELECT COUNT(*) FROM old." + t));
        }

        // 1. Move live rows up past the backup's ids. Two steps (negate, then
        //    flip back) so a moved id never clashes with one not yet moved.
        for (String t : tables) {
            long off = r.offsets.get(t);
            if (off == 0) continue;
            db.exec("UPDATE main." + t + " SET id = -(id + ?)", off);
            db.exec("UPDATE main." + t + " SET id = -id");
        }
        // 2. Live references follow the rows they point at.
        for (Map.Entry<String, Map<String, String>> e : REFS.entrySet()) {
            String t = e.getKey();
            if (!tables.contains(t)) continue;
            List<String> here = db.columns("main", t);
            for (Map.Entry<String, String> ref : e.getValue().entrySet()) {
                Long off = r.offsets.get(ref.getValue());
                if (!here.contains(ref.getKey()) || off == null || off == 0) continue;
                db.exec("UPDATE main." + t + " SET " + ref.getKey() + " = " + ref.getKey()
                    + " + ? WHERE " + ref.getKey() + " IS NOT NULL", off);
            }
        }
        // 3. The backup's rows go in with their ids unchanged.
        for (String t : tables) {
            String cl = commonColumns(db, t);
            db.exec("INSERT INTO main." + t + " (" + cl + ") SELECT " + cl + " FROM old." + t);
        }
        if (!db.columns("main", "daily_stat").isEmpty() && !db.columns("old", "daily_stat").isEmpty()) {
            String cl = commonColumns(db, "daily_stat");
            db.exec("INSERT OR IGNORE INTO main.daily_stat (" + cl + ") SELECT " + cl + " FROM old.daily_stat");
        }
        // 4. AUTOINCREMENT must hand out ids above everything now present.
        for (String t : tables) {
            long mx = db.queryLong("SELECT COALESCE(MAX(id),0) FROM main." + t);
            if (db.queryLong("SELECT COUNT(*) FROM main.sqlite_sequence WHERE name = ?", t) > 0) {
                db.exec("UPDATE main.sqlite_sequence SET seq = ? WHERE name = ?", mx, t);
            } else {
                db.exec("INSERT INTO main.sqlite_sequence(name, seq) VALUES (?, ?)", t, mx);
            }
        }
        for (String t : tables) {
            long after = db.queryLong("SELECT COUNT(*) FROM main." + t);
            if (after != before.get(t) + r.added.get(t)) {
                throw new IllegalStateException(t + ": " + before.get(t) + " live + "
                    + r.added.get(t) + " backup != " + after + " merged");
            }
        }
        return r;
    }

    private static String commonColumns(Sql db, String t) throws Exception {
        List<String> main = db.columns("main", t);
        StringBuilder sb = new StringBuilder();
        for (String c : db.columns("old", t)) {
            if (!main.contains(c)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(c).append('"');
        }
        return sb.toString();
    }
}
