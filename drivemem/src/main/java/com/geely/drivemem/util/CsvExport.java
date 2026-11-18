package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.DailyStatsProvider.DriveSession;
import com.geely.drivemem.state.ChargeSession;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TimeZone;

/** Charge and trip history as CSV files, for a spreadsheet on a computer or
 * a phone (issue #7). car.db holds the same facts, but a SQLite file needs a
 * tool to open; a CSV opens in Excel, Numbers or Google Sheets.
 *
 * The format is plain RFC 4180: comma between fields, dot as the decimal
 * mark, local time as "yyyy-MM-dd HH:mm", an empty field for an unknown
 * value. Headers and values are ASCII only, so no BOM and no locale guess.
 * The cost is the one the driver typed for that charge; nothing is derived
 * from a saved price. */
public final class CsvExport {
    static final String TAG = CarAccess.TAG;
    public static final String CHARGES_FILE = "drive_assist_charges.csv";
    public static final String TRIPS_FILE = "drive_assist_trips.csv";

    static final String CHARGES_HEADER = "id,start,end,duration_min,type,soc_start_pct,soc_end_pct,"
        + "energy_kwh,avg_power_kw,max_voltage_v,odometer_km,cost,cost_per_kwh,lat,lon";
    static final String TRIPS_HEADER = "id,start,end,duration_min,distance_km,soc_start_pct,soc_end_pct,"
        + "energy_net_kwh,energy_spent_kwh,energy_regen_kwh,efficiency_wh_km,energy_source,"
        + "ascent_m,descent_m,start_lat,start_lon,end_lat,end_lon";

    private CsvExport() {}

    /** Writes both CSV files, then copies them and car.db to the USB drive,
     * and the trip routes as GPX (one file per day) to
     * "Drive Assist/Routes/<yyyy-MM>/". Runs on its own thread; cb fires once, on THAT
     * thread, as in UsbExport, with the files copied in both folders. */
    public static void exportToUsb(Context ctx, UsbExport.Callback cb) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            File[] csv = new File[0];
            try {
                csv = write(app);
            } catch (Throwable t) {
                Log.w(TAG, "csvexport: " + t);
            }
            // car.db goes as a snapshot: the live file alone misses what is
            // still in its write-ahead log. If the snapshot fails, the live
            // file is still better than no database on the stick.
            File snapDir = new File(app.getCacheDir(), "export-db");
            File db = new File(snapDir, CarDb.file(app).getName());
            try {
                if (!snapDir.isDirectory() && !snapDir.mkdirs()) throw new IllegalStateException("mkdirs " + snapDir);
                AppBackup.snapshotForExport(app, db);
            } catch (Throwable t) {
                Log.w(TAG, "csvexport: db snapshot: " + t);
                db = CarDb.file(app);
            }
            File[] all = new File[csv.length + 1];
            all[0] = db;
            System.arraycopy(csv, 0, all, 1, csv.length);
            final int[] copied = {0};
            final boolean[] ok = {false};
            final File[] drive = {null};
            try {
                UsbExport.exportNow((o, d, n) -> { ok[0] = o; drive[0] = d; copied[0] = n; }, all);
            } finally {
                AppBackup.deleteSnapshot(new File(snapDir, CarDb.file(app).getName()));
            }
            if (drive[0] != null) {
                List<GpxExport.MonthFiles> gpx = java.util.Collections.emptyList();
                try { gpx = GpxExport.writeAllDays(app); }
                catch (Throwable t) { Log.w(TAG, "csvexport: gpx: " + t); }
                for (GpxExport.MonthFiles m : gpx) {
                    UsbExport.exportNow(GpxExport.USB_DIR + "/" + m.month, false, (o, d, n) -> {
                        // Every month must make it: one cut month is a failed export.
                        ok[0] = ok[0] && o; copied[0] += n;
                    }, m.days);
                }
            }
            if (cb != null) cb.onDone(ok[0], drive[0], copied[0]);
        }, "csv-export").start();
    }

    /** The two files {charges, trips}, in the app's cache dir (overwritten
     * every export). Also served to a paired phone (web/PhoneServer). */
    public static File[] write(Context ctx) throws Exception {
        File dir = new File(ctx.getCacheDir(), "csv");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs " + dir);
        TimeZone tz = TimeZone.getDefault();
        long activeChargeId = ChargeSession.isCharging() ? ChargeSession.activeRowId() : -1;
        File charges = new File(dir, CHARGES_FILE);
        SQLiteDatabase db = CarDb.get(ctx).db();
        writeFile(charges, chargesCsv(ChargeSession.readLog(ctx), activeChargeId, chargePlaces(db), tz));
        File trips = new File(dir, TRIPS_FILE);
        writeFile(trips, tripsCsv(DailyStatsProvider.readAllTrips(ctx), tripEnds(db), tz));
        return new File[]{charges, trips};
    }

    /** charge id -> {lat, lon}, for the charges that saved a place. */
    static Map<Long, double[]> chargePlaces(SQLiteDatabase db) {
        Map<Long, double[]> out = new HashMap<>();
        Cursor c = db.rawQuery("SELECT id, lat, lon FROM charge_session WHERE lat IS NOT NULL AND lon IS NOT NULL", null);
        try { while (c.moveToNext()) out.put(c.getLong(0), new double[]{c.getDouble(1), c.getDouble(2)}); }
        finally { c.close(); }
        return out;
    }

    /** trip id -> {start lat, start lon, end lat, end lon}: the first and the
     * last route point. Trips without points are not in the map. (SQLite
     * takes the bare lat/lon from the row that MIN/MAX picked.) */
    static Map<Long, double[]> tripEnds(SQLiteDatabase db) {
        Map<Long, double[]> out = new HashMap<>();
        Cursor c = db.rawQuery("SELECT trip_id, lat, lon, MIN(ts_ms) FROM trip_point "
            + "WHERE trip_id IS NOT NULL GROUP BY trip_id", null);
        try { while (c.moveToNext()) out.put(c.getLong(0), new double[]{c.getDouble(1), c.getDouble(2), Double.NaN, Double.NaN}); }
        finally { c.close(); }
        c = db.rawQuery("SELECT trip_id, lat, lon, MAX(ts_ms) FROM trip_point "
            + "WHERE trip_id IS NOT NULL GROUP BY trip_id", null);
        try {
            while (c.moveToNext()) {
                double[] e = out.get(c.getLong(0));
                if (e != null) { e[2] = c.getDouble(1); e[3] = c.getDouble(2); }
            }
        } finally { c.close(); }
        return out;
    }

    private static void writeFile(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write(text); } finally { w.close(); }
    }

    /** Oldest first, as readLog returns them. The charge still in progress
     * (activeId) is left out: its numbers are not final yet. */
    static String chargesCsv(List<ChargeSession.Summary> sessions, long activeId,
                             Map<Long, double[]> places, TimeZone tz) {
        SimpleDateFormat fmt = dateFormat(tz);
        StringBuilder sb = new StringBuilder(CHARGES_HEADER).append("\r\n");
        for (ChargeSession.Summary s : sessions) {
            if (activeId > 0 && s.id == activeId) continue;
            double[] at = places.get(s.id);
            row(sb,
                String.valueOf(s.id),
                fmt.format(new Date(s.startWallMs)),
                fmt.format(new Date(s.endWallMs)),
                String.valueOf(s.durationS() / 60),
                s.hasChargeVoltage() ? (s.isDcfc() ? "DC" : "AC") : "",
                pct(s.socStart), pct(s.socEnd),
                num(s.kwh, 2),
                num(s.avgPowerW / 1000.0, 2),
                s.hasChargeVoltage() ? num(s.maxChargeV, 0) : "",
                s.odoStart > 0 ? num(s.odoStart, 1) : "",
                s.cost != null ? num(s.cost, 2) : "",
                s.cost != null && s.kwh > 0 ? num(s.cost / s.kwh, 2) : "",
                at != null ? num(at[0], 6) : "",
                at != null ? num(at[1], 6) : "");
        }
        return sb.toString();
    }

    /** Completed trips only; an open trip has no end yet. */
    static String tripsCsv(List<DriveSession> trips, Map<Long, double[]> ends, TimeZone tz) {
        SimpleDateFormat fmt = dateFormat(tz);
        StringBuilder sb = new StringBuilder(TRIPS_HEADER).append("\r\n");
        for (DriveSession t : trips) {
            if (t.endMs <= t.startMs) continue;
            boolean noEnergy = t.energySource == com.geely.drivemem.sensors.EnergySource.NO_DATA;
            double[] e = ends.get(t.tripId);
            row(sb,
                String.valueOf(t.tripId),
                fmt.format(new Date(t.startMs)),
                fmt.format(new Date(t.endMs)),
                String.valueOf((t.endMs - t.startMs) / 60_000),
                num(t.distanceKm, 1),
                pct(t.socStart), pct(t.socEnd),
                noEnergy ? "" : num(t.energyKwh, 2),
                noEnergy ? "" : num(t.spentKwh, 2),
                noEnergy ? "" : num(t.regenKwh, 2),
                noEnergy || t.efficiencyKwh100km <= 0 ? "" : num(EnergyUnits.whPerKm(t.efficiencyKwh100km), 0),
                t.energySource == null ? "" : t.energySource.name().toLowerCase(Locale.US),
                num(t.ascentDPlusM, 0),
                num(t.descentDMinusM, 0),
                e != null ? num(e[0], 6) : "",
                e != null ? num(e[1], 6) : "",
                e != null ? num(e[2], 6) : "",
                e != null ? num(e[3], 6) : "");
        }
        return sb.toString();
    }

    private static SimpleDateFormat dateFormat(TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(tz);
        return f;
    }

    private static void row(StringBuilder sb, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(field(fields[i]));
        }
        sb.append("\r\n");
    }

    // RFC 4180 quoting. Every value written today is a number, a date or an
    // enum name, so this never fires — it is here so a future text column
    // cannot break the file.
    static String field(String v) {
        if (v == null) return "";
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    static String num(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    private static String pct(int v) { return v >= 0 ? String.valueOf(v) : ""; }
}
