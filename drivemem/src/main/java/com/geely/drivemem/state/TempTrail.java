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

    TempTrail(long startMs, long bucketMs, float[] values) {
        this.startMs = startMs;
        this.bucketMs = bucketMs;
        this.values = values;
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
        return new TempTrail(startMs, bucket, out);
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

    /** Lowest awake value, or NaN. */
    public float min() {
        float m = Float.NaN;
        for (float x : values) if (!Float.isNaN(x) && (Float.isNaN(m) || x < m)) m = x;
        return m;
    }

    /** Highest awake value, or NaN. */
    public float max() {
        float m = Float.NaN;
        for (float x : values) if (!Float.isNaN(x) && (Float.isNaN(m) || x > m)) m = x;
        return m;
    }

    /** "startMs;bucketMs;v0,v1,,v3" -- empty field = asleep. For SharedPreferences. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(startMs).append(';').append(bucketMs).append(';');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            if (!Float.isNaN(values[i])) sb.append(String.format(Locale.US, "%.1f", values[i]));
        }
        return sb.toString();
    }

    public static TempTrail decode(String s) {
        if (s == null || s.isEmpty()) return EMPTY;
        try {
            String[] parts = s.split(";", -1);
            long start = Long.parseLong(parts[0]);
            long bucket = Long.parseLong(parts[1]);
            if (start <= 0 || bucket <= 0) return EMPTY;
            if (parts[2].isEmpty()) return new TempTrail(start, bucket, new float[0]);
            String[] f = parts[2].split(",", -1);
            float[] v = new float[f.length];
            for (int i = 0; i < f.length; i++) v[i] = f[i].isEmpty() ? Float.NaN : Float.parseFloat(f[i]);
            return new TempTrail(start, bucket, v);
        } catch (RuntimeException e) {
            return EMPTY;
        }
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TempTrail)) return false;
        TempTrail t = (TempTrail) o;
        // Arrays.equals compares floats by bits, so NaN == NaN here, as wanted.
        return startMs == t.startMs && bucketMs == t.bucketMs && Arrays.equals(values, t.values);
    }

    @Override public int hashCode() {
        return 31 * (31 * Long.hashCode(startMs) + Long.hashCode(bucketMs)) + Arrays.hashCode(values);
    }

    @Override public String toString() { return "TempTrail(" + encode() + ")"; }
}
