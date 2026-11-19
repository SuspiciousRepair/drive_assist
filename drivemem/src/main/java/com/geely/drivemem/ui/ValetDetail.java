package com.geely.drivemem.ui;

/** Pure maths for the Valet detail screen (ValetDetailActivity), kept out
 * of the Activity so it can be unit tested. */
final class ValetDetail {
    private ValetDetail() {}

    /** Above this, a Valet top speed is shown in red: parking a car does
     * not need it. */
    static final double FAST_KMH = 20;

    /** A clip whose length is not known yet counts as this long. */
    static final long CLIP_GUESS_MS = 60_000L;

    static boolean fast(double kmh) { return kmh > FAST_KMH; }

    /** Time the car moved: the moves (trip rows) clipped to the Valet
     * interval [vs, ve], overlaps counted once. starts/ends in order of start. */
    static long movingMs(long[] starts, long[] ends, long vs, long ve) {
        long sum = 0, reached = vs;
        for (int i = 0; i < starts.length; i++) {
            long a = Math.max(Math.max(starts[i], vs), reached);
            long b = Math.min(ends[i], ve);
            if (b > a) { sum += b - a; reached = b; }
        }
        return sum;
    }

    /** True when a clip that started at whenMs and lasted seconds (-1 when
     * unknown) overlaps the Valet interval [vs, ve]. */
    static boolean clipInWindow(long whenMs, int seconds, long vs, long ve) {
        long len = seconds > 0 ? seconds * 1000L : CLIP_GUESS_MS;
        return whenMs <= ve && whenMs + len >= vs;
    }

    /** The moves on a w px wide timeline of [vs, ve], as {left, right}
     * pairs in px, each at least minPx wide so a 1 minute move in a night
     * of Valet still shows. Moves outside the interval are clipped or left
     * out. */
    static float[] blocks(long vs, long ve, long[] starts, long[] ends, float w, float minPx) {
        float[] out = new float[starts.length * 2];
        int k = 0;
        double span = Math.max(1, ve - vs);
        for (int i = 0; i < starts.length; i++) {
            long a = Math.max(starts[i], vs), b = Math.min(ends[i], ve);
            if (b < a) continue;
            float l = (float) ((a - vs) / span * w), r = (float) ((b - vs) / span * w);
            if (r - l < minPx) {
                float mid = (l + r) / 2;
                l = Math.max(0, mid - minPx / 2);
                r = Math.min(w, l + minPx);
                l = Math.max(0, r - minPx);
            }
            out[k++] = l;
            out[k++] = r;
        }
        return java.util.Arrays.copyOf(out, k);
    }
}
