package com.geely.drivemem.ui;

import com.geely.drivemem.sensors.LocationHub;

import java.util.Arrays;

/** Numbers computed from a trip's route points (issue #19), for the route
 * screen. Pure, so it is unit tested. The trip's energy, battery and
 * climb come from the trip card instead, so both screens agree. */
public final class TripRouteStats {
    private TripRouteStats() {}

    /** Two points further apart than this in time had a stop between them:
     * points are written every 5 s only while the car moves (25 m+). */
    public static final long STOP_GAP_MS = 20_000L;

    /** Distance from the first point, km, one value per point. */
    public static double[] cumulativeKm(double[] lat, double[] lon) {
        double[] km = new double[lat.length];
        for (int i = 1; i < lat.length; i++) {
            km[i] = km[i - 1] + LocationHub.distanceM(lat[i - 1], lon[i - 1], lat[i], lon[i]) / 1000.0;
        }
        return km;
    }

    /** Moving median over an odd window: GPS height jumps by several metres
     * from one fix to the next, a median keeps the real slope and drops
     * the spikes. The window shrinks at both ends. */
    public static double[] medianSmooth(double[] v, int window) {
        int half = Math.max(0, window / 2);
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            int a = Math.max(0, i - half), b = Math.min(v.length - 1, i + half);
            double[] w = Arrays.copyOfRange(v, a, b + 1);
            Arrays.sort(w);
            out[i] = w[w.length / 2];
        }
        return out;
    }

    /** A missing height (NaN) takes the one before it, or after it at the
     * start; null when no point has one. */
    public static double[] fillGaps(double[] alt) {
        double[] out = alt.clone();
        int first = -1;
        for (int i = 0; i < out.length; i++) if (!Double.isNaN(out[i])) { first = i; break; }
        if (first < 0) return null;
        for (int i = 0; i < first; i++) out[i] = out[first];
        for (int i = first + 1; i < out.length; i++) if (Double.isNaN(out[i])) out[i] = out[i - 1];
        return out;
    }

    /** Time spent moving: the sum of the gaps between points that are at
     * most STOP_GAP_MS apart. */
    public static long movingMs(long[] ts) {
        long sum = 0;
        for (int i = 1; i < ts.length; i++) {
            long dt = ts[i] - ts[i - 1];
            if (dt > 0 && dt <= STOP_GAP_MS) sum += dt;
        }
        return sum;
    }

    /** Average speed while moving, km/h: the distance between the points
     * over the same short gaps that {@link #movingMs} counts, divided by
     * that time. Distance and time come from the same stretches, so points
     * lost to a GPS dropout cannot inflate the number (the odometer distance
     * of the whole trip over a few minutes of points did). NaN when no two
     * points are close enough in time. */
    public static double avgMovingKmh(double[] lat, double[] lon, long[] ts) {
        double m = 0;
        long ms = 0;
        int n = Math.min(ts.length, Math.min(lat.length, lon.length));
        for (int i = 1; i < n; i++) {
            long dt = ts[i] - ts[i - 1];
            if (dt > 0 && dt <= STOP_GAP_MS) {
                ms += dt;
                m += LocationHub.distanceM(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            }
        }
        return ms > 0 ? (m / 1000.0) / (ms / 3_600_000.0) : Double.NaN;
    }

    public static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : v) m = Math.max(m, x);
        return v.length == 0 ? Double.NaN : m;
    }

    public static double min(double[] v) {
        double m = Double.POSITIVE_INFINITY;
        for (double x : v) m = Math.min(m, x);
        return v.length == 0 ? Double.NaN : m;
    }

    /** Index of the point nearest to a distance along the route. */
    public static int indexAtKm(double[] km, double target) {
        if (km.length == 0) return -1;
        int lo = 0, hi = km.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (km[mid] < target) lo = mid + 1; else hi = mid;
        }
        if (lo > 0 && Math.abs(km[lo - 1] - target) <= Math.abs(km[lo] - target)) lo--;
        return lo;
    }

    /** "1 h 05 min", "12 min", "45 s". */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s";
        long min = s / 60;
        if (min < 60) return min + " min";
        return String.format(java.util.Locale.US, "%d h %02d min", min / 60, min % 60);
    }
}
