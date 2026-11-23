package com.geely.drivemem.ui;

import java.util.ArrayList;
import java.util.List;

/** Where the car charged, for the route maps. Pure maths, unit tested.
 *
 * - All routes: every charge place, charges within {@link #SAME_PLACE_M} of
 *   each other counted as one spot (home, work). A spot that ever had a DC
 *   charge shows the DC icon.
 * - One trip: only the charges right before the trip at its start, or right
 *   after it at its end ({@link #NEAR_TRIP_M}, {@link #NEAR_TRIP_MS}), so the
 *   home charger is not on every trip that passes home. Between the trip and
 *   the charge there may be short moves only ({@link #MANEUVER_KM} each, to
 *   find the charger), and each move adds its length to the distance
 *   allowed. A real trip in between takes the charge for itself. */
final class ChargeSpots {
    static final double SAME_PLACE_M = 100;
    static final double NEAR_TRIP_M = 300;
    static final long NEAR_TRIP_MS = 60 * 60_000L;
    /** A trip shorter than this is a move to find the charger, not a trip. */
    static final double MANEUVER_KM = 1.0;

    /** Another trip near this one in time: when, and how far it went
     * (NaN when the odometer did not say). */
    static final class Hop {
        final long startMs;
        final double km;
        Hop(long startMs, double km) { this.startMs = startMs; this.km = km; }
    }

    /** The trips that started within NEAR_TRIP_MS of [fromMs, toMs], with their
     * odometer distance. */
    static List<Hop> readHops(android.content.Context ctx, long fromMs, long toMs) {
        List<Hop> out = new ArrayList<>();
        android.database.Cursor c = com.geely.drivemem.car.CarDb.get(ctx).db().rawQuery(
            "SELECT t.start_ms, e.odo_km - s.odo_km FROM trip t "
          + "LEFT JOIN telemetry_sample s ON s.id = t.start_sample_id "
          + "LEFT JOIN telemetry_sample e ON e.id = t.end_sample_id "
          + "WHERE t.start_ms BETWEEN ? AND ? ORDER BY t.start_ms",
            new String[]{String.valueOf(fromMs - NEAR_TRIP_MS), String.valueOf(toMs + NEAR_TRIP_MS)});
        try {
            while (c.moveToNext()) out.add(new Hop(c.getLong(0), c.isNull(1) ? Double.NaN : c.getDouble(1)));
        } finally { c.close(); }
        return out;
    }

    /** The metres a charge may be from the trip's end (or start) when the
     * trips that started in [fromMs, toMs) are all short moves: NEAR_TRIP_M
     * plus their length. -1 when one of them is a real trip (or has no
     * distance), so the charge belongs to that trip, not this one. */
    static double reachM(List<Hop> hops, long fromMs, long toMs) {
        double m = NEAR_TRIP_M;
        for (Hop h : hops) {
            if (h.startMs < fromMs || h.startMs >= toMs) continue;
            if (Double.isNaN(h.km) || h.km < 0 || h.km >= MANEUVER_KM) return -1;
            m += h.km * 1000;
        }
        return m;
    }

    /** One spot: where, and whether any charge there was DC. */
    static final class Spot {
        final double lat, lon;
        final boolean dc;
        Spot(double lat, double lon, boolean dc) { this.lat = lat; this.lon = lon; this.dc = dc; }
    }

    /** One saved charge: its place, times and type. */
    static final class Charge {
        final double lat, lon;
        final long startMs, endMs;
        final boolean dc;
        Charge(double lat, double lon, long startMs, long endMs, boolean dc) {
            this.lat = lat; this.lon = lon; this.startMs = startMs; this.endMs = endMs; this.dc = dc;
        }
    }

    private ChargeSpots() {}

    /** Every finished charge that saved its place (DB v28 on). DC by the
     * port voltage, the app's one rule (ChargeSession.isDcfc). */
    static List<Charge> read(android.content.Context ctx) {
        List<Charge> out = new ArrayList<>();
        android.database.Cursor c = com.geely.drivemem.car.CarDb.get(ctx).db().rawQuery(
            "SELECT lat, lon, start_ms, end_ms, max_charge_v FROM charge_session "
          + "WHERE lat IS NOT NULL AND lon IS NOT NULL AND end_ms IS NOT NULL ORDER BY start_ms", null);
        try {
            while (c.moveToNext()) {
                double v = c.isNull(4) ? Double.NaN : c.getDouble(4);
                out.add(new Charge(c.getDouble(0), c.getDouble(1), c.getLong(2), c.getLong(3),
                    com.geely.drivemem.state.ChargeSession.isDcfc(v)));
            }
        } finally { c.close(); }
        return out;
    }

    /** Metres between two places (equirectangular: fine at these distances). */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double k = Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dx = Math.toRadians(lon2 - lon1) * k * RouteGeometry.EARTH_M;
        double dy = Math.toRadians(lat2 - lat1) * RouteGeometry.EARTH_M;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Charges grouped into spots: a charge joins the first spot within
     * SAME_PLACE_M of it (the spot keeps its first place), else starts one. */
    static List<Spot> cluster(List<Charge> charges) {
        List<double[]> places = new ArrayList<>();   // lat, lon, dc (0/1)
        for (Charge c : charges) {
            double[] hit = null;
            for (double[] p : places) {
                if (distanceM(p[0], p[1], c.lat, c.lon) <= SAME_PLACE_M) { hit = p; break; }
            }
            if (hit == null) places.add(new double[]{c.lat, c.lon, c.dc ? 1 : 0});
            else if (c.dc) hit[2] = 1;
        }
        List<Spot> out = new ArrayList<>(places.size());
        for (double[] p : places) out.add(new Spot(p[0], p[1], p[2] > 0));
        return out;
    }

    /** One charge of the charging page's map: its id, place and type. */
    static final class Placed {
        final long id;
        final double lat, lon;
        final boolean dc;
        Placed(long id, double lat, double lon, boolean dc) {
            this.id = id; this.lat = lat; this.lon = lon; this.dc = dc;
        }
    }

    /** One place on the charging page's map: a spot, and the charges at it
     * (ids, in the order given). */
    static final class Place {
        final double lat, lon;
        boolean dc;
        final List<Long> ids = new ArrayList<>();
        Place(double lat, double lon) { this.lat = lat; this.lon = lon; }
        int count() { return ids.size(); }
    }

    /** The charges grouped into places by the same rule as {@link #cluster}:
     * a charge joins the first place within SAME_PLACE_M, else starts one.
     * A place that had any DC charge is DC. */
    static List<Place> places(List<Placed> charges) {
        List<Place> out = new ArrayList<>();
        for (Placed c : charges) {
            Place hit = null;
            for (Place p : out) {
                if (distanceM(p.lat, p.lon, c.lat, c.lon) <= SAME_PLACE_M) { hit = p; break; }
            }
            if (hit == null) { hit = new Place(c.lat, c.lon); out.add(hit); }
            hit.ids.add(c.id);
            if (c.dc) hit.dc = true;
        }
        return out;
    }

    /** Index of the place that holds charge id, or -1. */
    static int placeOf(List<Place> places, long id) {
        for (int i = 0; i < places.size(); i++) if (places.get(i).ids.contains(id)) return i;
        return -1;
    }

    /** The spots inside the box around the given route points, widened by
     * marginM on every side. One day of All routes uses this: the spots count
     * in the fit, so one far charge from another month made a day of city
     * trips a dot. An empty route keeps no spot. */
    static List<Spot> inBox(List<Spot> spots, double[] lat, double[] lon, double marginM) {
        List<Spot> out = new ArrayList<>();
        if (lat.length == 0) return out;
        double lo0 = lat[0], hi0 = lat[0], lo1 = lon[0], hi1 = lon[0];
        for (int i = 1; i < lat.length; i++) {
            lo0 = Math.min(lo0, lat[i]); hi0 = Math.max(hi0, lat[i]);
            lo1 = Math.min(lo1, lon[i]); hi1 = Math.max(hi1, lon[i]);
        }
        double dLat = marginM / 111_195.0;
        double dLon = dLat / Math.max(0.01, Math.cos(Math.toRadians((lo0 + hi0) / 2)));
        for (Spot sp : spots) {
            if (sp.lat >= lo0 - dLat && sp.lat <= hi0 + dLat
                    && sp.lon >= lo1 - dLon && sp.lon <= hi1 + dLon) out.add(sp);
        }
        return out;
    }

    /** The charges that belong on one trip's map, with no other trip around. */
    static List<Spot> forTrip(List<Charge> charges, long tripStartMs, long tripEndMs,
                              double startLat, double startLon, double endLat, double endLon) {
        return forTrip(charges, tripStartMs, tripEndMs, startLat, startLon, endLat, endLon,
            new ArrayList<Hop>());
    }

    /** The charges that belong on one trip's map: ended at most NEAR_TRIP_MS
     * before the trip started, near its first point; or started at most
     * NEAR_TRIP_MS after it ended, near its last point. "Near" is
     * {@link #reachM}: only short moves may come between, and they widen it.
     * hops are the other trips around this one ({@link #readHops}). */
    static List<Spot> forTrip(List<Charge> charges, long tripStartMs, long tripEndMs,
                              double startLat, double startLon, double endLat, double endLon,
                              List<Hop> hops) {
        List<Charge> near = new ArrayList<>();
        for (Charge c : charges) {
            boolean before = false, after = false;
            if (c.endMs <= tripStartMs && tripStartMs - c.endMs <= NEAR_TRIP_MS) {
                double reach = reachM(hops, c.endMs, tripStartMs);
                before = reach >= 0 && distanceM(c.lat, c.lon, startLat, startLon) <= reach;
            }
            if (tripEndMs > 0 && c.startMs >= tripEndMs && c.startMs - tripEndMs <= NEAR_TRIP_MS) {
                double reach = reachM(hops, tripEndMs, c.startMs);
                after = reach >= 0 && distanceM(c.lat, c.lon, endLat, endLon) <= reach;
            }
            if (before || after) near.add(c);
        }
        return cluster(near);
    }
}
