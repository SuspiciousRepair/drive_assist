package com.geely.drivemem.state;

import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.EnergySource;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** A finished trip, cut into parts with one drive mode and one regen level
 * each, for efficiency statistics per mode (plan/active/TRIP-ROUTES-ROADMAP.md,
 * phase 0). Written once, when the trip is saved, into trip_mode_segment,
 * which outlives the raw telemetry_sample rows (pruned after 90 days).
 *
 * Built from the trip's own driving rows (the same rows and the same
 * DRIVING_ROW_SQL filter as the trip's energy). A row's energy and the
 * odometer change since the row before it belong to the 30 s that ended at
 * that row, and to the modes read at that row. So a mode change is placed
 * at most one sample (30 s) late. Rows from before drive_mode/regen_mode were
 * recorded have null modes and form their own part. */
public final class ModeSegments {
    private ModeSegments() {}

    /** One telemetry_sample row, only what the cut needs. */
    public static final class Row {
        final long tsMs;
        final double odoKm;          // < 0 when unknown
        final Integer drive, regen;
        final double spent, regen_kwh, net;
        final boolean measured, estimated;

        public Row(long tsMs, double odoKm, Integer drive, Integer regen,
                   double spent, double regenKwh, double net, boolean measured, boolean estimated) {
            this.tsMs = tsMs; this.odoKm = odoKm; this.drive = drive; this.regen = regen;
            this.spent = spent; this.regen_kwh = regenKwh; this.net = net;
            this.measured = measured; this.estimated = estimated;
        }
    }

    public static final class Segment {
        public long startMs, endMs;
        public Integer drive, regen;
        public double km, spentKwh, regenKwh, netKwh;
        long measuredRows, estimatedRows;

        public EnergySource source() { return EnergySource.resolve(measuredRows, estimatedRows); }
    }

    /** Cuts rows (oldest first) into parts of equal (drive, regen). The first
     * part starts at tripStartMs. */
    public static List<Segment> build(List<Row> rows, long tripStartMs) {
        List<Segment> out = new ArrayList<>();
        Segment cur = null;
        double prevOdo = -1;
        long prevTs = tripStartMs;
        for (Row r : rows) {
            if (cur == null || !same(cur.drive, r.drive) || !same(cur.regen, r.regen)) {
                cur = new Segment();
                cur.startMs = prevTs;
                cur.drive = r.drive;
                cur.regen = r.regen;
                out.add(cur);
            }
            if (r.odoKm >= 0 && prevOdo >= 0 && r.odoKm >= prevOdo) cur.km += r.odoKm - prevOdo;
            if (r.odoKm >= 0) prevOdo = r.odoKm;
            cur.spentKwh += r.spent;
            cur.regenKwh += r.regen_kwh;
            cur.netKwh += r.net;
            if (r.measured) cur.measuredRows++;
            else if (r.estimated) cur.estimatedRows++;
            cur.endMs = r.tsMs;
            prevTs = r.tsMs;
        }
        return out;
    }

    private static boolean same(Integer a, Integer b) { return a == null ? b == null : a.equals(b); }

    /** Same labels as trip.energy_source. */
    static String label(EnergySource s) {
        switch (s) {
            case ESTIMATED: return "SOC_ESTIMATED";
            case MIXED: return "MIXED";
            case MEASURED: return "OBD_MEASURED";
            default: return null;
        }
    }

    /** Reads the trip's driving rows and writes its parts. Call on the
     * CarDb writer, right after the trip row is inserted. */
    public static void write(SQLiteDatabase db, long tripId, long startMs, long endMs) {
        if (tripId < 0) return;
        List<Row> rows = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT ts_ms, odo_km, drive_mode, regen_mode, "
          + "IFNULL(energy_spent_kwh,0), IFNULL(energy_regen_kwh,0), IFNULL(energy_net_kwh,0), "
          + "CASE WHEN " + CarDb.MEASURED_ROW_SQL + " THEN 1 ELSE 0 END, "
          + "CASE WHEN " + CarDb.ESTIMATED_ENERGY_ROW_SQL + " THEN 1 ELSE 0 END "
          + "FROM telemetry_sample WHERE ts_ms BETWEEN ? AND ? AND " + CarDb.DRIVING_ROW_SQL
          + " ORDER BY ts_ms",
            new String[]{String.valueOf(startMs), String.valueOf(endMs)});
        try {
            while (c.moveToNext()) {
                rows.add(new Row(c.getLong(0), c.isNull(1) ? -1 : c.getDouble(1),
                    c.isNull(2) ? null : c.getInt(2), c.isNull(3) ? null : c.getInt(3),
                    c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getInt(7) == 1, c.getInt(8) == 1));
            }
        } finally { c.close(); }
        for (Segment s : build(rows, startMs)) {
            ContentValues v = new ContentValues();
            v.put("trip_id", tripId);
            v.put("start_ms", s.startMs);
            v.put("end_ms", s.endMs);
            if (s.drive != null) v.put("drive_mode", s.drive);
            if (s.regen != null) v.put("regen_mode", s.regen);
            v.put("km", s.km);
            v.put("spent_kwh", s.spentKwh);
            v.put("regen_kwh", s.regenKwh);
            v.put("net_kwh", s.netKwh);
            String l = label(s.source());
            if (l != null) v.put("energy_source", l);
            db.insert("trip_mode_segment", null, v);
        }
    }
}
