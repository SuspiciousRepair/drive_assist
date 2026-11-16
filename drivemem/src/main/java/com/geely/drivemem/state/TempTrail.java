package com.geely.drivemem.state;

import java.util.Arrays;
import java.util.Locale;

/**
 * Outside temperature over the current stay in Park, as fixed time buckets.
 *
 * Fed from the hub thread: ParkingState records one value per
 * {@code telemetry.tick} (every 30 s while the head unit is awake) and puts
 * the result back into the hub as {@code parking.temp_trail}. A bucket that
 * got no tick at all is NaN -- the head unit was asleep -- so the card can
 * draw those hours as empty instead of joining the line across them.
 *
 * The lowest and highest reading of the stay are kept apart from the
 * buckets. A bucket keeps only its last reading, and a long stay merges
 * buckets into their mean, so the buckets lose a real peak and their range
 * gets narrower with time. {@link #min()} and {@link #max()} do not.
 *
 * Immutable, with value equality, so the hub's change-only delivery only
 * publishes when a bucket actually changes. Pure Java for unit tests.
 */
public final class TempTrail {
    /** First bucket width. Doubles whenever the trail would exceed MAX_BUCKETS. */
    static final long BASE_BUCKET_MS = 10 * 60_000L;
    static final int MAX_BUCKETS = 72;
    /** Values are kept to half a degree, so sensor jitter does not republish. */
    private static final float STEP = 0.5f;

    public static final TempTrail EMPTY = new TempTrail(0, BASE_BUCKET_MS, new float[0]);

    public final long startMs;
    public final long bucketMs;
    private final float[] values;
    /** Lowest and highest reading since startMs, in half degrees; NaN = none yet. */
    private final float lowC;
    private final float highC;

    /** A trail whose range is what its buckets say (a new one, or one stored
     * before the range was kept). */
    TempTrail(long startMs, long bucketMs, float[] values) {
        this(startMs, bucketMs, values, Float.NaN, Float.NaN);
    }

    // The range always covers the buckets: a stored range that is missing or
    // too narrow is widened to them.
    private TempTrail(long startMs, long bucketMs, float[] values, float lowC, float highC) {
        this.startMs = startMs;
        this.bucketMs = bucketMs;
        this.values = values;
        float lo = lowC, hi = highC;
        for (float x : values) {
            if (Float.isNaN(x)) continue;
            if (Float.isNaN(lo) || x < lo) lo = x;
            if (Float.isNaN(hi) || x > hi) hi = x;
        }
        this.lowC = lo;
        this.highC = hi;
    }

    public static TempTrail start(long startMs) {
        return new TempTrail(startMs, BASE_BUCKET_MS, new float[0]);
    }

    public boolean isEmpty() { return startMs <= 0 || values.length == 0; }
    public int size() { return values.length; }
    /** Bucket value in °C, or NaN when the head unit was asleep for that whole bucket. */
    public float get(int i) { return values[i]; }

    /**
     * The trail after one awake tick at {@code nowMs}. Buckets skipped since
     * the last tick stay NaN (asleep). {@code tempC} null means awake but no
     * reading: the bucket is left as it was.
     */
    public TempTrail record(long nowMs, Float tempC) {
        if (startMs <= 0 || nowMs < startMs) return this;
        long bucket = bucketMs;
        float[] v = values;
        int idx = (int) ((nowMs - startMs) / bucket);
        while (idx >= MAX_BUCKETS) {
            v = halve(v);
            bucket *= 2;
            idx = (int) ((nowMs - startMs) / bucket);
        }
        float q = tempC == null ? Float.NaN : Math.round(tempC / STEP) * STEP;
        if (idx < v.length && (tempC == null || v[idx] == q) && bucket == bucketMs) return this;
        float[] out = Arrays.copyOf(v, Math.max(v.length, idx + 1));
        for (int i = v.length; i < out.length; i++) out[i] = Float.NaN;
        if (tempC != null) out[idx] = q;
        // Carried on, not re-read from the buckets: `out` may have just
        // lost a peak to halve(), or to a later reading in the same bucket.
        return new TempTrail(startMs, bucket, out, lowC, highC);
    }

    /** Pairs of buckets merged into one: the mean of the awake ones, NaN if both slept. */
    private static float[] halve(float[] v) {
        float[] out = new float[(v.length + 1) / 2];
        for (int i = 0; i < out.length; i++) {
            float a = v[2 * i];
            float b = 2 * i + 1 < v.length ? v[2 * i + 1] : Float.NaN;
            if (Float.isNaN(a)) out[i] = b;
            else if (Float.isNaN(b)) out[i] = a;
            else out[i] = Math.round((a + b) / 2 / STEP) * STEP;
        }
        return out;
    }

    /** Lowest reading of the stay, or NaN. */
    public float min() { return lowC; }

    /** Highest reading of the stay, or NaN. */
    public float max() { return highC; }

    /** "startMs;bucketMs;v0,v1,,v3;low;high" -- empty field = asleep / none.
     * For SharedPreferences. A trail stored before low and high existed has
     * three parts only: decode() still loads it. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(startMs).append(';').append(bucketMs).append(';');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            if (!Float.isNaN(values[i])) sb.append(String.format(Locale.US, "%.1f", values[i]));
        }
        sb.append(';');
        if (!Float.isNaN(lowC)) sb.append(String.format(Locale.US, "%.1f", lowC));
        sb.append(';');
        if (!Float.isNaN(highC)) sb.append(String.format(Locale.US, "%.1f", highC));
        return sb.toString();
    }

    public static TempTrail decode(String s) {
        if (s == null || s.isEmpty()) return EMPTY;
        try {
            String[] parts = s.split(";", -1);
            long start = Long.parseLong(parts[0]);
            long bucket = Long.parseLong(parts[1]);
            if (start <= 0 || bucket <= 0) return EMPTY;
            float[] v;
            if (parts[2].isEmpty()) {
                v = new float[0];
            } else {
                String[] f = parts[2].split(",", -1);
                v = new float[f.length];
                for (int i = 0; i < f.length; i++) v[i] = f[i].isEmpty() ? Float.NaN : Float.parseFloat(f[i]);
            }
            // Old format: no low/high. The buckets are the best that is left.
            float low = parts.length > 4 && !parts[3].isEmpty() ? Float.parseFloat(parts[3]) : Float.NaN;
            float high = parts.length > 4 && !parts[4].isEmpty() ? Float.parseFloat(parts[4]) : Float.NaN;
            return new TempTrail(start, bucket, v, low, high);
        } catch (RuntimeException e) {
            return EMPTY;
        }
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TempTrail)) return false;
        TempTrail t = (TempTrail) o;
        // Arrays.equals and Float.compare go by bits, so NaN == NaN here, as wanted.
        return startMs == t.startMs && bucketMs == t.bucketMs && Arrays.equals(values, t.values)
            && Float.compare(lowC, t.lowC) == 0 && Float.compare(highC, t.highC) == 0;
    }

    @Override public int hashCode() {
        int h = 31 * (31 * Long.hashCode(startMs) + Long.hashCode(bucketMs)) + Arrays.hashCode(values);
        return 31 * (31 * h + Float.hashCode(lowC)) + Float.hashCode(highC);
    }

    @Override public String toString() { return "TempTrail(" + encode() + ")"; }
}
