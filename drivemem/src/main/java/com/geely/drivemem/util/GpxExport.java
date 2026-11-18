package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.DailyStatsProvider.DriveSession;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Trip routes as GPX files (issue #82), for Google Earth, Google My Maps
 * and most map apps. The app has no map of its own (no map API), so a real
 * map app is where a month of driving is seen on streets.
 *
 * One file per day, one track per trip, in the order driven. On the USB
 * stick the days go in a folder per month: Routes/2026-11/...-2026-11-17.gpx. Each point
 * keeps its time (GPX {@code <time>}, UTC) and height ({@code <ele>}); the
 * raw values a map app does not know go in {@code <extensions>}: speed,
 * battery power, drive mode and regen level, as saved in trip_point.
 *
 * Only trips recorded after the route update (DB with trip_point) have
 * points; older trips are not in any file. */
public final class GpxExport {
    static final String TAG = CarAccess.TAG;
    /** Folder on the USB stick, under "Drive Assist/". */
    public static final String USB_DIR = "Routes";
    static final String NS = "https://github.com/SuspiciousRepair/drive_assist/gpx/1";
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final Pattern DAY = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])");

    private GpxExport() {}

    /** One saved route point. NaN / null = not recorded. */
    static final class Point {
        final long tsMs;
        final double lat, lon, altM, speedKmh, powerKw;
        final Integer driveMode, regenMode;
        Point(long tsMs, double lat, double lon, double altM, double speedKmh, double powerKw,
              Integer driveMode, Integer regenMode) {
            this.tsMs = tsMs; this.lat = lat; this.lon = lon; this.altM = altM;
            this.speedKmh = speedKmh; this.powerKw = powerKw;
            this.driveMode = driveMode; this.regenMode = regenMode;
        }
    }

    /** One trip and its points, oldest point first. */
    static final class Track {
        final long tripId, startMs;
        final double distanceKm;
        final List<Point> points = new ArrayList<>();
        Track(long tripId, long startMs, double distanceKm) {
            this.tripId = tripId; this.startMs = startMs; this.distanceKm = distanceKm;
        }
    }

    // ---- files -------------------------------------------------------------

    public static boolean isMonth(String ym) { return ym != null && MONTH.matcher(ym).matches(); }

    public static boolean isDay(String ymd) { return ymd != null && DAY.matcher(ymd).matches(); }

    public static String dayFileName(String ymd) { return "drive_assist_routes_" + ymd + ".gpx"; }

    public static String tripFileName(long tripId) { return "drive_assist_trip_" + tripId + ".gpx"; }

    /** The months ("yyyy-MM", local time) that have at least one finished
     * trip with points, newest first. */
    public static List<String> months(Context ctx) {
        List<String> out = new ArrayList<>();
        Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT DISTINCT strftime('%Y-%m', t.start_ms / 1000, 'unixepoch', 'localtime') FROM trip t "
          + "WHERE t.end_ms IS NOT NULL AND EXISTS (SELECT 1 FROM trip_point p WHERE p.trip_id = t.id) "
          + "ORDER BY 1 DESC", null);
        try { while (c.moveToNext()) if (!c.isNull(0)) out.add(c.getString(0)); }
        finally { c.close(); }
        return out;
    }

    /** The finished trips of one month that have points, oldest first. */
    public static List<DriveSession> tripsOfMonth(Context ctx, String ym) {
        long[] r = monthRange(ym, TimeZone.getDefault());
        Map<Long, Boolean> has = tripsWithPoints(CarDb.get(ctx).db(), r[0], r[1]);
        List<DriveSession> out = new ArrayList<>();
        for (DriveSession t : DailyStatsProvider.readAllTrips(ctx)) {
            if (t.endMs > t.startMs && has.containsKey(t.tripId)) out.add(t);
        }
        return out;
    }

    /** One day's files, grouped by month, for the USB export. */
    public static final class MonthFiles {
        public final String month;
        public final File[] days;
        MonthFiles(String month, File[] days) { this.month = month; this.days = days; }
    }

    /** Every day with a route to its own file, in the cache dir (cleared
     * first, so a day that is gone is not copied again). One month in
     * memory at a time. */
    public static List<MonthFiles> writeAllDays(Context ctx) throws IOException {
        File d = dir(ctx);
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        TimeZone tz = TimeZone.getDefault();
        List<MonthFiles> out = new ArrayList<>();
        for (String ym : months(ctx)) {
            long[] r = monthRange(ym, tz);
            List<File> files = new ArrayList<>();
            for (Map.Entry<String, List<Track>> e : byDay(read(ctx, r[0], r[1], -1), tz).entrySet()) {
                File f = new File(d, dayFileName(e.getKey()));
                writeFile(f, e.getValue());
                files.add(f);
            }
            if (!files.isEmpty()) out.add(new MonthFiles(ym, files.toArray(new File[0])));
        }
        return out;
    }

    /** One day's file ("yyyy-MM-dd", local time), or null when that day
     * has no route. */
    public static File writeDay(Context ctx, String ymd) throws IOException {
        if (!isDay(ymd)) return null;
        TimeZone tz = TimeZone.getDefault();
        long[] r = dayRange(ymd, tz);
        List<Track> tracks = read(ctx, r[0], r[1], -1);
        if (tracks.isEmpty()) return null;
        File f = new File(dir(ctx), dayFileName(ymd));
        writeFile(f, tracks);
        return f;
    }

    /** Tracks grouped by the local day their trip started, days in order. */
    static Map<String, List<Track>> byDay(List<Track> tracks, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(tz);
        Map<String, List<Track>> out = new java.util.TreeMap<>();
        for (Track t : tracks) {
            String k = f.format(new Date(t.startMs));
            List<Track> l = out.get(k);
            if (l == null) { l = new ArrayList<>(); out.put(k, l); }
            l.add(t);
        }
        return out;
    }

    /** One trip's file, or null when the trip has no route. */
    public static File writeTrip(Context ctx, long tripId) throws IOException {
        if (tripId <= 0) return null;
        List<Track> tracks = read(ctx, 0, Long.MAX_VALUE, tripId);
        if (tracks.isEmpty()) return null;
        File f = new File(dir(ctx), tripFileName(tripId));
        writeFile(f, tracks);
        return f;
    }

    private static File dir(Context ctx) throws IOException {
        File d = new File(ctx.getCacheDir(), "gpx");
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("mkdirs " + d);
        return d;
    }

    private static void writeFile(File f, List<Track> tracks) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8), 64 * 1024);
        try { write(w, tracks, TimeZone.getDefault()); } finally { w.close(); }
    }

    // ---- reading -----------------------------------------------------------

    /** [start, end) of a "yyyy-MM" month in ms, in tz. */
    static long[] monthRange(String ym, TimeZone tz) {
        int y = Integer.parseInt(ym.substring(0, 4));
        int m = Integer.parseInt(ym.substring(5, 7)) - 1;
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.clear();
        c.set(y, m, 1, 0, 0, 0);
        long from = c.getTimeInMillis();
        c.add(Calendar.MONTH, 1);
        return new long[]{from, c.getTimeInMillis()};
    }

    /** [start, end) of a "yyyy-MM-dd" day in ms, in tz. */
    static long[] dayRange(String ymd, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.clear();
        c.set(Integer.parseInt(ymd.substring(0, 4)), Integer.parseInt(ymd.substring(5, 7)) - 1,
            Integer.parseInt(ymd.substring(8, 10)), 0, 0, 0);
        long from = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{from, c.getTimeInMillis()};
    }

    private static Map<Long, Boolean> tripsWithPoints(SQLiteDatabase db, long fromMs, long toMs) {
        Map<Long, Boolean> out = new HashMap<>();
        Cursor c = db.rawQuery(
            "SELECT t.id FROM trip t WHERE t.start_ms >= ? AND t.start_ms < ? AND t.end_ms IS NOT NULL "
          + "AND EXISTS (SELECT 1 FROM trip_point p WHERE p.trip_id = t.id)",
            new String[]{String.valueOf(fromMs), String.valueOf(toMs)});
        try { while (c.moveToNext()) out.put(c.getLong(0), Boolean.TRUE); }
        finally { c.close(); }
        return out;
    }

    /** The finished trips that start in [fromMs, toMs) (or only tripId,
     * when > 0) with their points. A trip of fewer than two points is no
     * line and is left out. So is a trip readAllTrips leaves out (a Valet
     * trip): the stats page and All routes hide it, so the export does too. */
    private static List<Track> read(Context ctx, long fromMs, long toMs, long tripId) {
        Map<Long, DriveSession> byId = new HashMap<>();
        for (DriveSession t : DailyStatsProvider.readAllTrips(ctx)) {
            if (t.endMs > t.startMs) byId.put(t.tripId, t);
        }
        String where = tripId > 0 ? "t.id = ?" : "t.start_ms >= ? AND t.start_ms < ?";
        String[] args = tripId > 0 ? new String[]{String.valueOf(tripId)}
                                   : new String[]{String.valueOf(fromMs), String.valueOf(toMs)};
        List<Track> out = new ArrayList<>();
        Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT p.trip_id, p.ts_ms, p.lat, p.lon, p.alt_m, p.speed_kmh, p.power_kw, p.drive_mode, p.regen_mode "
          + "FROM trip_point p JOIN trip t ON t.id = p.trip_id WHERE " + where
          + " AND t.end_ms IS NOT NULL ORDER BY t.start_ms, p.trip_id, p.ts_ms", args);
        try {
            Track cur = null;
            long skip = -1;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (id == skip) continue;
                if (cur == null || cur.tripId != id) {
                    if (cur != null && cur.points.size() >= 2) out.add(cur);
                    DriveSession s = byId.get(id);
                    if (s == null) { skip = id; cur = null; continue; }
                    cur = new Track(id, s.startMs, s.distanceKm);
                }
                cur.points.add(new Point(c.getLong(1), c.getDouble(2), c.getDouble(3),
                    dbl(c, 4), dbl(c, 5), dbl(c, 6),
                    c.isNull(7) ? null : c.getInt(7), c.isNull(8) ? null : c.getInt(8)));
            }
            if (cur != null && cur.points.size() >= 2) out.add(cur);
        } finally { c.close(); }
        return out;
    }

    private static double dbl(Cursor c, int i) { return c.isNull(i) ? Double.NaN : c.getDouble(i); }

    // ---- GPX ---------------------------------------------------------------

    /** The GPX 1.1 document for these tracks. Pure, unit tested. The track
     * name is the trip's local start time and distance; times inside are UTC. */
    static void write(Writer w, List<Track> tracks, TimeZone tz) throws IOException {
        SimpleDateFormat name = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        name.setTimeZone(tz);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<gpx version=\"1.1\" creator=\"Drive Assist\" xmlns=\"http://www.topografix.com/GPX/1/1\" "
            + "xmlns:da=\"" + NS + "\">\n");
        for (Track t : tracks) {
            String n = name.format(new Date(t.startMs));
            if (!Double.isNaN(t.distanceKm) && t.distanceKm > 0) n += " (" + fmt(t.distanceKm, 1) + " km)";
            w.write(" <trk>\n  <name>" + n + "</name>\n");
            if (t.tripId > 0) w.write("  <number>" + t.tripId + "</number>\n");
            w.write("  <trkseg>\n");
            for (Point p : t.points) {
                w.write("   <trkpt lat=\"" + fmt(p.lat, 6) + "\" lon=\"" + fmt(p.lon, 6) + "\">");
                if (!Double.isNaN(p.altM)) w.write("<ele>" + fmt(p.altM, 1) + "</ele>");
                w.write("<time>" + iso.format(new Date(p.tsMs)) + "</time>");
                StringBuilder ext = new StringBuilder();
                if (!Double.isNaN(p.speedKmh)) ext.append("<da:speed_kmh>").append(fmt(p.speedKmh, 1)).append("</da:speed_kmh>");
                if (!Double.isNaN(p.powerKw)) ext.append("<da:power_kw>").append(fmt(p.powerKw, 2)).append("</da:power_kw>");
                if (p.driveMode != null) ext.append("<da:drive_mode>").append(p.driveMode).append("</da:drive_mode>");
                if (p.regenMode != null) ext.append("<da:regen_mode>").append(p.regenMode).append("</da:regen_mode>");
                if (ext.length() > 0) w.write("<extensions>" + ext + "</extensions>");
                w.write("</trkpt>\n");
            }
            w.write("  </trkseg>\n </trk>\n");
        }
        w.write("</gpx>\n");
    }

    static String fmt(double v, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", v);
    }
}
