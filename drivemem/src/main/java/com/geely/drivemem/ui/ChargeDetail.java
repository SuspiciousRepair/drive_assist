package com.geely.drivemem.ui;

/** The charge page's arithmetic, apart from Android so it is unit tested. */
final class ChargeDetail {
    /** A charge that reached this battery level counts as full. */
    static final int FULL_SOC = 100;
    /** Two samples further apart than this are not joined by a line, and a
     * value the crosshair finds further than this from its time is not shown. */
    static final long GAP_MS = 5 * 60_000L;

    private ChargeDetail() {}

    /** How long the car stood at 100 % without moving after the charge: from
     * the charge's end to the next drive (nextTripStartMs), or to nowMs when
     * the car has not moved since. -1 when the charge did not reach 100 %,
     * is still running, or the times make no sense. */
    static long idleAtFullMs(int socEnd, long endMs, long nextTripStartMs, long nowMs, boolean active) {
        if (active || socEnd < FULL_SOC || endMs <= 0) return -1;
        long until = nextTripStartMs > 0 ? nextTripStartMs : nowMs;
        return until > endMs ? until - endMs : -1;
    }

    /** Index of the sample nearest to t among those with a value (not NaN),
     * or -1 when there is none within GAP_MS. ts is sorted, oldest first. */
    static int nearest(long[] ts, float[] v, long t) {
        int lo = 0, hi = ts.length - 1;
        if (hi < 0) return -1;
        while (lo < hi) {   // first index with ts >= t
            int mid = (lo + hi) >>> 1;
            if (ts[mid] < t) lo = mid + 1; else hi = mid;
        }
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int i = lo; i < ts.length; i++) {   // forward, to the first value
            if (Float.isNaN(v[i])) continue;
            best = i; bestD = Math.abs(ts[i] - t); break;
        }
        for (int i = lo - 1; i >= 0; i--) {      // back, to the first value
            if (Float.isNaN(v[i])) continue;
            long d = Math.abs(ts[i] - t);
            if (d < bestD) { best = i; bestD = d; }
            break;
        }
        return best >= 0 && bestD <= GAP_MS ? best : -1;
    }

    /** Lowest and highest value that is not NaN, or null when all are NaN. */
    static float[] range(float[] v) {
        float lo = Float.NaN, hi = Float.NaN;
        for (float x : v) {
            if (Float.isNaN(x)) continue;
            if (Float.isNaN(lo) || x < lo) lo = x;
            if (Float.isNaN(hi) || x > hi) hi = x;
        }
        return Float.isNaN(lo) ? null : new float[]{lo, hi};
    }

    /** First and last value that is not NaN, or null when all are NaN. */
    static float[] firstLast(float[] v) {
        float a = Float.NaN, b = Float.NaN;
        for (float x : v) {
            if (Float.isNaN(x)) continue;
            if (Float.isNaN(a)) a = x;
            b = x;
        }
        return Float.isNaN(a) ? null : new float[]{a, b};
    }

    /** The kW axis top: AC and DC get their own fixed tops (8 and 80 kW, as
     * the power curve window), raised to the next step when a value is
     * higher, so a curve is never cut. */
    static float kwAxisMax(float peakKw, boolean dc) {
        float top = dc ? 80f : 8f;
        float step = dc ? 20f : 2f;
        while (!Float.isNaN(peakKw) && peakKw > top) top += step;
        return top;
    }

    /** The °C axis: the range of both temperatures, from a multiple of 5,
     * and a multiple of 20 tall, so its four grid steps (the kW grid's) are
     * whole multiples of 5. Null with no temperature. */
    static float[] tempAxis(float[] a, float[] b) {
        float[] ra = range(a), rb = range(b);
        if (ra == null && rb == null) return null;
        float lo = ra == null ? rb[0] : rb == null ? ra[0] : Math.min(ra[0], rb[0]);
        float hi = ra == null ? rb[1] : rb == null ? ra[1] : Math.max(ra[1], rb[1]);
        lo = (float) Math.floor(lo / 5f) * 5f;
        float span = Math.max(20f, (float) Math.ceil((hi - lo) / 20f) * 20f);
        return new float[]{lo, lo + span};
    }
}
