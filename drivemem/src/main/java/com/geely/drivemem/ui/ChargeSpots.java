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
 *   home charger is not on every trip that passes home. */
final class ChargeSpots {
    static final double SAME_PLACE_M = 100;
    static final double NEAR_TRIP_M = 300;
    static final long NEAR_TRIP_MS = 60 * 60_000L;

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

    /** The charges that belong on one trip's map: ended at most NEAR_TRIP_MS
     * before the trip started, within NEAR_TRIP_M of its first point; or
     * started at most NEAR_TRIP_MS after it ended, near its last point. */
    static List<Spot> forTrip(List<Charge> charges, long tripStartMs, long tripEndMs,
                              double startLat, double startLon, double endLat, double endLon) {
        List<Charge> near = new ArrayList<>();
        for (Charge c : charges) {
            boolean before = c.endMs <= tripStartMs && tripStartMs - c.endMs <= NEAR_TRIP_MS
                && distanceM(c.lat, c.lon, startLat, startLon) <= NEAR_TRIP_M;
            boolean after = tripEndMs > 0 && c.startMs >= tripEndMs && c.startMs - tripEndMs <= NEAR_TRIP_MS
                && distanceM(c.lat, c.lon, endLat, endLon) <= NEAR_TRIP_M;
            if (before || after) near.add(c);
        }
        return cluster(near);
    }
}
