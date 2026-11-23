package com.geely.drivemem.sensors;

/** Throws out GPS heights the car could not have driven to.
 *
 * GPS height sometimes jumps by 100 m or more while the car is stopped or
 * slow: under a roof, when the car starts, right after a tunnel. The GPS
 * still reports a good position (2-8 m), so nothing else warns about it,
 * and the jump goes into the trip's climb and onto the height chart.
 *
 * The rule: a road climbs at most {@link #MAX_GRADE} (15 m of height per
 * 100 m driven). A height change steeper than that, plus a noise margin,
 * is a GPS error. Tested on real trips (2026-11-23): it removed a 143 m
 * jump at 0 km/h and the bad heights at the start of a trip, and it kept
 * every real climb, a mountain road with tunnels included. Pure, so it
 * is unit tested. */
public final class AltitudeGate {
    private AltitudeGate() {}

    /** The steepest road a car drives: 15 m of height per 100 m. */
    public static final double MAX_GRADE = 0.15;

    /** The odometer counts in 0.1 km steps, so two readings that look the
     * same can be up to 100 m apart. */
    public static final double ODO_STEP_M = 100.0;

    /** True when the height can not have changed by deltaM over distM
     * metres of road. noiseM is the GPS height wobble that is allowed on
     * top. */
    public static boolean isJump(double deltaM, double distM, double noiseM) {
        return Math.abs(deltaM) > MAX_GRADE * Math.max(0, distM) + noiseM;
    }

    /** Road distance between two odometer readings, in metres, with the
     * odometer's step added; NaN when either reading is unknown. */
    public static double odoDistM(double fromKm, double toKm) {
        if (!(fromKm >= 0) || !(toKm >= 0)) return Double.NaN;
        return Math.abs(toKm - fromKm) * 1000.0 + ODO_STEP_M;
    }

    /** True when a height step between two odometer readings is a GPS
     * error. Unknown distance: false, the step is kept. */
    public static boolean isOdoJump(double deltaM, double fromKm, double toKm, double noiseM) {
        double d = odoDistM(fromKm, toKm);
        return !Double.isNaN(d) && isJump(deltaM, d, noiseM);
    }

    /** The highest height that is not a jump, over samples in time order
     * (altitude and odometer, NaN where unknown); NaN when there is none.
     * Each sample is compared with the one before it that had a height.
     * A jump is left out, and the next sample is compared with it, so the
     * way back down from a spike is left out too. */
    public static double maxHeight(double[] alt, double[] odoKm, double noiseM) {
        double max = Double.NaN, last = Double.NaN, lastOdo = Double.NaN;
        for (int i = 0; i < alt.length; i++) {
            double a = alt[i];
            if (Double.isNaN(a)) continue;
            double odo = i < odoKm.length ? odoKm[i] : Double.NaN;
            boolean jump = !Double.isNaN(last) && isOdoJump(a - last, lastOdo, odo, noiseM);
            if (!jump && (Double.isNaN(max) || a > max)) max = a;
            last = a;
            lastOdo = odo;
        }
        return max;
    }

    /** GPS height is not trusted for this long after a trip starts. The
     * GPS reports a good position (2 m) at once, but its height can be
     * 75-145 m off and take one to two minutes to settle (owner car,
     * 2026-11-23). Replayed on trips that start and end at home, a
     * 2-minute wait made the climb and the descent match; 1 minute did
     * not. */
    public static final long WARM_UP_MS = 120_000L;

    /** A route point whose position error is larger than this has a height
     * that is not trusted. 99 % of points are at 1-3 m; the bad heights
     * right after a tunnel and under a roof were at 6-10 m. */
    public static final double MAX_ACCURACY_M = 4.0;

    /** Route points: a copy of alt with NaN in the first
     * {@link #WARM_UP_MS} after the first point and where the position
     * error (acc, m) is over {@link #MAX_ACCURACY_M}. An unknown error
     * (NaN) is kept. When that would leave no height at all (a trip
     * shorter than the wait), alt comes back unchanged. */
    public static double[] dropUnsure(double[] alt, long[] ts, double[] acc) {
        int n = alt.length;
        double[] out = alt.clone();
        if (n == 0) return out;
        boolean any = false;
        for (int i = 0; i < n; i++) {
            boolean warming = i < ts.length && ts[i] - ts[0] < WARM_UP_MS;
            boolean vague = i < acc.length && acc[i] > MAX_ACCURACY_M;
            if (warming || vague) out[i] = Double.NaN;
            if (!Double.isNaN(out[i])) any = true;
        }
        return any ? out : alt.clone();
    }

    /** Route points closer than this along the road judge each other. */
    static final double NEIGHBOUR_M = 500.0;
    /** And at most this many points on each side. */
    static final int NEIGHBOURS = 20;
    /** Route points are 5 s apart, and GPS height wobbles by several
     * metres from one fix to the next; a real jump is much bigger. */
    public static final double POINT_NOISE_M = 10.0;

    /** Route points: a copy of alt with NaN at each height that most of
     * its neighbours (points within {@link #NEIGHBOUR_M} of road) say the
     * car could not have driven to. km is the distance from the start of
     * the trip, one per point. A point with no neighbour that close (after
     * a tunnel, for example) is kept: nothing can judge it. */
    public static double[] dropJumps(double[] alt, double[] km) {
        int n = Math.min(alt.length, km.length);
        double[] out = alt.clone();
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(alt[i])) continue;
            int judged = 0, agree = 0;
            int from = Math.max(0, i - NEIGHBOURS), to = Math.min(n - 1, i + NEIGHBOURS);
            for (int j = from; j <= to; j++) {
                if (j == i || Double.isNaN(alt[j])) continue;
                double d = Math.abs(km[j] - km[i]) * 1000.0;
                if (d > NEIGHBOUR_M) continue;
                judged++;
                if (!isJump(alt[j] - alt[i], d, POINT_NOISE_M)) agree++;
            }
            if (judged > 0 && agree * 2 < judged) out[i] = Double.NaN;
        }
        return out;
    }
}
