package com.geely.drivemem.state;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.LocationHub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What parking cost: a parked gap between two sessions (a car park or paid
 * street parking), or one Valet stay. One row per priced stop in
 * {@code parking_cost} (CarDb v38), never frozen into daily_stat, so a late
 * edit shows on every period at once.
 *
 * <p>Place memory. A <b>spot</b> is every earlier priced stop of one kind that
 * shares one {@code place_name}. A new stop matches a spot when it is within
 * {@link #SPOT_RADIUS_M} of ANY stop of that spot, so a spot grows by itself
 * with each visit to another corner of the same car park. The nearest stop
 * wins when several spots match. Spots with different names never merge, and
 * a car park stop never matches a street stop. Nothing is stored for a spot:
 * it is worked out from the rows each time.
 *
 * <p>The matching is plain Java over {@link Row} lists, unit tested in the JVM;
 * the database calls around it are thin. */
public final class ParkingCost {
    private static final String TAG = "DriveMem";

    public static final String KIND_LOT = "lot";
    public static final String KIND_STREET = "street";

    /** EntityBus key published after every save or delete. */
    public static final String CHANGED = "parking_cost.changed";

    /** One measured point of a spot reaches this far. Two car parks across
     * one street are often 40-60 m apart: a wrong prefill is worse than an
     * empty field. */
    public static final double SPOT_RADIUS_M = 30;
    /** Street prices are set per zone, not per place: a wide circle is safe. */
    public static final double STREET_PRICE_RADIUS_M = 300;
    /** A gap's cost may start this much before the gap: a price typed on
     * the home Parked card starts at the shift to P, and the trip row before
     * it ends up to TripSession.PARK_GRACE_PERIOD_MS (75 s) later. */
    static final long GAP_LEAD_MS = 120_000L;
    /** Parking paid "right after arrival" starts within this of the trip end. */
    static final long ARRIVAL_WINDOW_MS = 30 * 60_000L;
    /** Price chips offered per spot. */
    static final int PRICE_CHIPS = 2;

    private ParkingCost() {}

    /** One parking_cost row. */
    public static final class Row {
        public long id;
        public String uuid;
        public long startMs;
        public Long endMs;
        public Long valetId;
        public double cost;
        public String placeName;
        public String kind = KIND_LOT;
        public String note;
        public Double lat, lon;
        public long updatedMs;

        public Row() {}

        public Row(long id, long startMs, Long endMs, Long valetId, double cost,
                   String placeName, String kind, Double lat, Double lon) {
            this.id = id; this.startMs = startMs; this.endMs = endMs; this.valetId = valetId;
            this.cost = cost; this.placeName = placeName;
            this.kind = KIND_STREET.equals(kind) ? KIND_STREET : KIND_LOT;
            this.lat = lat; this.lon = lon;
        }

        public boolean isStreet() { return KIND_STREET.equals(kind); }
        public boolean hasPlace() { return lat != null && lon != null; }
        boolean named() { return placeName != null && !placeName.trim().isEmpty(); }
        String nameKey() { return placeName.trim().toLowerCase(java.util.Locale.ROOT); }
    }

    /** What the dialog prefills for a new stop. */
    public static final class Suggestion {
        public final String kind;
        /** The matched spot's name, or null. */
        public final String placeName;
        /** Up to two recent distinct prices, newest first. */
        public final List<Double> prices;

        Suggestion(String kind, String placeName, List<Double> prices) {
            this.kind = kind; this.placeName = placeName; this.prices = prices;
        }
    }

    // ---- Matching (pure) ----

    /** The non-Valet row whose start falls in [gapStart - 60 s, gapEnd], or null. */
    public static Row matchGap(List<Row> rows, long gapStartMs, long gapEndMs) {
        if (rows == null) return null;
        Row best = null;
        for (Row r : rows) {
            if (r.valetId != null) continue;
            if (r.startMs < gapStartMs - GAP_LEAD_MS || r.startMs > gapEndMs) continue;
            if (best == null || Math.abs(r.startMs - gapStartMs) < Math.abs(best.startMs - gapStartMs)) best = r;
        }
        return best;
    }

    /** The row of one Valet stay, or null. */
    public static Row matchValet(List<Row> rows, long valetId) {
        if (rows == null || valetId <= 0) return null;
        for (Row r : rows) if (r.valetId != null && r.valetId == valetId) return r;
        return null;
    }

    /** The latest start that still counts as parking right after a trip that
     * ended at tripEndMs: the next trip or charge, else the arrival window. */
    public static long arrivalLimitMs(long tripEndMs, long nextTripStartMs, long nextChargeStartMs) {
        long limit = tripEndMs + ARRIVAL_WINDOW_MS;
        if (nextTripStartMs > tripEndMs) limit = Math.min(limit, nextTripStartMs);
        if (nextChargeStartMs > tripEndMs) limit = Math.min(limit, nextChargeStartMs);
        return limit;
    }

    /** The non-Valet row paid right after a trip: it starts in
     * [tripEnd - 60 s, limitMs] (see {@link #arrivalLimitMs}). */
    public static Row matchAfterTrip(List<Row> rows, long tripEndMs, long limitMs) {
        if (tripEndMs <= 0) return null;
        return matchGap(rows, tripEndMs, Math.max(tripEndMs, limitMs));
    }

    /** Sum of the costs whose start is in [fromMs, toMs]. */
    public static double sum(List<Row> rows, long fromMs, long toMs) {
        double s = 0;
        if (rows == null) return 0;
        for (Row r : rows) if (r.cost > 0 && r.startMs >= fromMs && r.startMs <= toMs) s += r.cost;
        return s;
    }

    /** Named spots of one kind: name (case-insensitive) -> its stops. */
    public static Map<String, List<Row>> spots(List<Row> history, String kind) {
        Map<String, List<Row>> out = new LinkedHashMap<>();
        if (history == null) return out;
        for (Row r : history) {
            if (!r.hasPlace() || !r.named() || !sameKind(r.kind, kind)) continue;
            List<Row> l = out.get(r.nameKey());
            if (l == null) { l = new ArrayList<>(); out.put(r.nameKey(), l); }
            l.add(r);
        }
        return out;
    }

    /** The stops of the spot a new stop at (lat, lon) joins, or null. The
     * spot with the nearest stop within {@link #SPOT_RADIUS_M} wins. */
    public static List<Row> matchSpot(List<Row> history, String kind, double lat, double lon) {
        List<Row> best = null;
        double bestD = Double.MAX_VALUE;
        for (List<Row> spot : spots(history, kind).values()) {
            for (Row r : spot) {
                double d = LocationHub.distanceM(lat, lon, r.lat, r.lon);
                if (d <= SPOT_RADIUS_M && d < bestD) { bestD = d; best = spot; }
            }
        }
        return best;
    }

    /** Kind of the nearest earlier stop within {@link #SPOT_RADIUS_M}, else a car park. */
    public static String defaultKind(List<Row> history, Double lat, Double lon) {
        if (history == null || lat == null || lon == null) return KIND_LOT;
        Row best = null;
        double bestD = Double.MAX_VALUE;
        for (Row r : history) {
            if (!r.hasPlace()) continue;
            double d = LocationHub.distanceM(lat, lon, r.lat, r.lon);
            if (d <= SPOT_RADIUS_M && d < bestD) { bestD = d; best = r; }
        }
        return best != null && best.isStreet() ? KIND_STREET : KIND_LOT;
    }

    /** What to prefill for a stop of {@code kind} (null: pick the default) at
     * (lat, lon). The row with id {@code excludeId} (the one being edited) is
     * left out.
     * <ul>
     * <li>Car park: the matched spot's name and its last prices; with no
     *     named spot, the prices of unnamed car park stops within 30 m.</li>
     * <li>Street: a matched named street spot gives its name; prices always
     *     come from street stops within 300 m.</li>
     * </ul> */
    public static Suggestion suggest(List<Row> history, Double lat, Double lon, String kind, long excludeId) {
        List<Row> h = new ArrayList<>();
        if (history != null) for (Row r : history) if (r.id != excludeId) h.add(r);
        String k = kind != null ? (KIND_STREET.equals(kind) ? KIND_STREET : KIND_LOT) : defaultKind(h, lat, lon);
        if (lat == null || lon == null) return new Suggestion(k, null, Collections.emptyList());
        List<Row> spot = matchSpot(h, k, lat, lon);
        String name = spot != null ? newest(spot).placeName.trim() : null;
        List<Row> priceFrom = new ArrayList<>();
        if (KIND_STREET.equals(k)) {
            for (Row r : h) {
                if (r.hasPlace() && r.isStreet()
                        && LocationHub.distanceM(lat, lon, r.lat, r.lon) <= STREET_PRICE_RADIUS_M) priceFrom.add(r);
            }
        } else if (spot != null) {
            priceFrom.addAll(spot);
        } else {
            for (Row r : h) {
                if (r.hasPlace() && !r.isStreet() && !r.named()
                        && LocationHub.distanceM(lat, lon, r.lat, r.lon) <= SPOT_RADIUS_M) priceFrom.add(r);
            }
        }
        return new Suggestion(k, name, recentPrices(priceFrom));
    }

    /** Up to {@link #PRICE_CHIPS} distinct prices, newest first. */
    static List<Double> recentPrices(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Long.compare(b.startMs, a.startMs));
        List<Double> out = new ArrayList<>();
        for (Row r : sorted) {
            if (r.cost <= 0) continue;
            boolean seen = false;
            for (Double p : out) if (Math.abs(p - r.cost) < 0.005) { seen = true; break; }
            if (!seen) out.add(r.cost);
            if (out.size() >= PRICE_CHIPS) break;
        }
        return out;
    }

    private static Row newest(List<Row> rows) {
        Row n = rows.get(0);
        for (Row r : rows) if (r.startMs > n.startMs) n = r;
        return n;
    }

    private static boolean sameKind(String a, String b) {
        return KIND_STREET.equals(a) == KIND_STREET.equals(b);
    }

    // ---- Database ----

    private static final String COLS =
        "id, uuid, start_ms, end_ms, valet_id, cost, place_name, kind, note, lat, lon, updated_ms";

    private static Row read(Cursor c) {
        Row r = new Row(c.getLong(0), c.getLong(2), c.isNull(3) ? null : c.getLong(3),
            c.isNull(4) ? null : c.getLong(4), c.getDouble(5), c.getString(6), c.getString(7),
            c.isNull(9) ? null : c.getDouble(9), c.isNull(10) ? null : c.getDouble(10));
        r.uuid = c.getString(1);
        r.note = c.getString(8);
        r.updatedMs = c.getLong(11);
        return r;
    }

    private static List<Row> query(Context ctx, String where, String... args) {
        List<Row> out = new ArrayList<>();
        if (ctx == null) return out;
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT " + COLS + " FROM parking_cost" + (where == null ? "" : " WHERE " + where)
                    + " ORDER BY start_ms", args)) {
            while (c.moveToNext()) out.add(read(c));
        } catch (Throwable t) {
            Log.w(TAG, "parking: read: " + t);
        }
        return out;
    }

    /** Rows that start in [fromMs - 60 s, toMs]: one period of the stats page. */
    public static List<Row> between(Context ctx, long fromMs, long toMs) {
        return query(ctx, "start_ms >= ? AND start_ms <= ?",
            String.valueOf(fromMs - GAP_LEAD_MS), String.valueOf(toMs));
    }

    /** Every row, oldest first (the phone page, CSV export). */
    public static List<Row> all(Context ctx) { return query(ctx, null); }

    /** Every row with a place: the place memory. */
    public static List<Row> placed(Context ctx) {
        return query(ctx, "lat IS NOT NULL AND lon IS NOT NULL");
    }

    public static Row forGap(Context ctx, long gapStartMs, long gapEndMs) {
        return matchGap(between(ctx, gapStartMs, gapEndMs), gapStartMs, gapEndMs);
    }

    public static Row forValet(Context ctx, long valetId) {
        List<Row> l = query(ctx, "valet_id = ?", String.valueOf(valetId));
        return l.isEmpty() ? null : l.get(0);
    }

    /** Where the stop the car is in now starts, as the stats page's parked
     * row sees it: the end of the trip or charge that led to it (when that is
     * within 10 min of the shift to P), else the shift to P itself. */
    public static long stopStart(Context ctx, long parkStartMs) {
        return stopStart(ctx, parkStartMs, System.currentTimeMillis());
    }

    /** Same, taking only session ends up to maxEndMs. */
    static long stopStart(Context ctx, long parkStartMs, long maxEndMs) {
        long best = 0;
        for (String table : new String[]{"trip", "charge_session"}) {
            try (Cursor c = CarDb.get(ctx).db().rawQuery(
                    "SELECT MAX(end_ms) FROM " + table + " WHERE end_ms >= ? AND end_ms <= ?",
                    new String[]{String.valueOf(parkStartMs - 10 * 60_000L), String.valueOf(maxEndMs)})) {
                if (c.moveToFirst() && !c.isNull(0)) best = Math.max(best, c.getLong(0));
            } catch (Throwable ignored) {}
        }
        return best > 0 ? best : parkStartMs;
    }

    /** {start, end} of one finished Valet stay, or null. */
    public static long[] valetSpan(Context ctx, long valetId) {
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT start_ms, end_ms FROM valet_session WHERE id = ? AND end_ms IS NOT NULL",
                new String[]{String.valueOf(valetId)})) {
            return c.moveToFirst() ? new long[]{c.getLong(0), c.getLong(1)} : null;
        } catch (Throwable t) { return null; }
    }

    /** Parking paid right after the trip that ended at tripEndMs, or null. */
    public static Row afterTrip(Context ctx, long tripEndMs) {
        return matchAfterTrip(between(ctx, tripEndMs, tripEndMs + ARRIVAL_WINDOW_MS),
            tripEndMs, arrivalLimit(ctx, tripEndMs));
    }

    /** {@link #arrivalLimitMs} with the next trip and charge read from the database. */
    public static long arrivalLimit(Context ctx, long tripEndMs) {
        return arrivalLimitMs(tripEndMs,
            firstStartAfter(ctx, "trip", tripEndMs), firstStartAfter(ctx, "charge_session", tripEndMs));
    }

    /** Start of the next trip after ms (the end of the stop), or 0. */
    public static long nextTripStart(Context ctx, long ms) { return firstStartAfter(ctx, "trip", ms); }

    private static long firstStartAfter(Context ctx, String table, long ms) {
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT MIN(start_ms) FROM " + table + " WHERE start_ms > ?", new String[]{String.valueOf(ms)})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0L;
        } catch (Throwable t) { return 0L; }
    }

    /** Sum of the costs that start in [fromMs, toMs]. */
    public static double sumBetween(Context ctx, long fromMs, long toMs) {
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT COALESCE(SUM(cost), 0) FROM parking_cost WHERE cost > 0 AND start_ms >= ? AND start_ms <= ?",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)})) {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        } catch (Throwable t) { return 0; }
    }

    /** Where the car was at a stop: the live fix while the car is still in
     * that stop (no end, and no trip since), else the last route point
     * before it (the same read as a charge's place). Null when neither is
     * known. Driving away from it does not count as still there. */
    public static double[] placeFor(Context ctx, long startMs, Long endMs) {
        if (endMs == null && CarState.isParked() && nextTripStart(ctx, startMs) == 0) {
            LocationHub.Fix f = LocationHub.get(ctx);
            if (f != null) return new double[]{f.lat, f.lon};
        }
        long at = startMs + GAP_LEAD_MS;
        try (Cursor c = CarDb.get(ctx).db().rawQuery(CarDb.CHARGE_PLACE_SQL,
                new String[]{String.valueOf(at), String.valueOf(at)})) {
            if (c.moveToFirst()) return new double[]{c.getDouble(0), c.getDouble(1)};
        } catch (Throwable t) {
            Log.w(TAG, "parking: place: " + t);
        }
        return null;
    }

    /** Inserts (id 0) or updates the row on the database thread, then tells
     * the stats and diary pages. onDone runs on the main thread. */
    public static void save(Context ctx, Row r, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                if (r.valetId == null && r.endMs == null) {
                    // A stop typed while parked: the trip before it may have
                    // closed since (Park grace), and it may be over by now.
                    // Only the Park grace: never past a charge that began after it.
                    if (r.id <= 0) r.startMs = stopStart(app, r.startMs, r.startMs + 5 * 60_000L);
                    long next = nextTripStart(app, r.startMs);
                    if (next > 0) r.endMs = next;
                }
                if (r.id <= 0) r.id = sameStopId(db, r);
                if (!r.hasPlace()) {
                    double[] p = placeFor(app, r.startMs, r.endMs);
                    if (p != null) { r.lat = p[0]; r.lon = p[1]; }
                }
                ContentValues v = new ContentValues();
                v.put("start_ms", r.startMs);
                if (r.endMs != null) v.put("end_ms", r.endMs); else v.putNull("end_ms");
                if (r.valetId != null) v.put("valet_id", r.valetId); else v.putNull("valet_id");
                v.put("cost", r.cost);
                String name = r.placeName == null ? null : r.placeName.trim();
                if (name != null && !name.isEmpty()) v.put("place_name", name); else v.putNull("place_name");
                v.put("kind", r.isStreet() ? KIND_STREET : KIND_LOT);
                if (r.note != null) v.put("note", r.note); else v.putNull("note");
                if (r.lat != null) v.put("lat", r.lat); else v.putNull("lat");
                if (r.lon != null) v.put("lon", r.lon); else v.putNull("lon");
                v.put("updated_ms", System.currentTimeMillis());
                if (r.id > 0) {
                    db.update("parking_cost", v, "id = ?", new String[]{String.valueOf(r.id)});
                } else {
                    v.put("uuid", r.uuid != null ? r.uuid : UUID.randomUUID().toString());
                    r.id = db.insert("parking_cost", null, v);
                }
            } catch (Throwable t) {
                Log.w(TAG, "parking: save: " + t);
            }
            changed(onDone);
        });
    }

    /** One row per stop: the row already saved for the same Valet stay, or
     * for a stop that starts within {@link #GAP_LEAD_MS}, or 0. A second
     * save then edits it instead of adding a row the screens never show. */
    private static long sameStopId(SQLiteDatabase db, Row r) {
        String sql = r.valetId != null
            ? "SELECT id FROM parking_cost WHERE valet_id = ? LIMIT 1"
            : "SELECT id FROM parking_cost WHERE valet_id IS NULL AND start_ms BETWEEN ? AND ? "
              + "ORDER BY ABS(start_ms - ?) LIMIT 1";
        String[] args;
        if (r.valetId != null) {
            args = new String[]{String.valueOf(r.valetId)};
        } else {
            // The whole gap the stop is in: from the last trip or charge
            // that ended by then, to the next one that starts after it.
            long gapStart = 0, to = Long.MAX_VALUE;
            for (String t : new String[]{"trip", "charge_session"}) {
                gapStart = Math.max(gapStart, lastEndBefore(db, t, r.startMs + GAP_LEAD_MS, 0));
                long next = firstStart(db, t, r.startMs);
                if (next > 0) to = Math.min(to, next);
            }
            long from = gapStart > 0 ? Math.min(gapStart, r.startMs) : r.startMs;
            args = new String[]{String.valueOf(from - GAP_LEAD_MS), String.valueOf(to),
                                String.valueOf(r.startMs)};
        }
        try (Cursor c = db.rawQuery(sql, args)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } catch (Throwable t) { return 0; }
    }

    /** The latest end_ms at or before ms (fallback when none). */
    private static long lastEndBefore(SQLiteDatabase db, String table, long ms, long fallback) {
        try (Cursor c = db.rawQuery("SELECT MAX(end_ms) FROM " + table + " WHERE end_ms <= ?",
                new String[]{String.valueOf(ms)})) {
            return c.moveToFirst() && !c.isNull(0) ? Math.max(c.getLong(0), 0) : fallback;
        } catch (Throwable t) { return fallback; }
    }

    private static long firstStart(SQLiteDatabase db, String table, long ms) {
        try (Cursor c = db.rawQuery("SELECT MIN(start_ms) FROM " + table + " WHERE start_ms > ?",
                new String[]{String.valueOf(ms)})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0L;
        } catch (Throwable t) { return 0L; }
    }

    public static void delete(Context ctx, long id, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                CarDb.get(app).db().delete("parking_cost", "id = ?", new String[]{String.valueOf(id)});
            } catch (Throwable t) {
                Log.w(TAG, "parking: delete: " + t);
            }
            changed(onDone);
        });
    }

    private static void changed(Runnable onDone) {
        EntityBus.publish(CHANGED, CarActor.Reading.ok(System.currentTimeMillis()));
        if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
    }
}
