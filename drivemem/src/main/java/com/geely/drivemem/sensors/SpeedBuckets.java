package com.geely.drivemem.sensors;

/** The speed buckets, in one place. The stats page (consumption by speed,
 * distance per hour) and the trip route screen (line colours) all use
 * these, so a speed has the same bucket and colour everywhere. */
public final class SpeedBuckets {
    private SpeedBuckets() {}

    /** Upper limit of each bucket, km/h. A limit belongs to the bucket
     * above it (40 is in 40-80). The last bucket has no limit. */
    private static final double[] LIMITS = {40, 80, 120};

    public static final int COUNT = LIMITS.length + 1;

    /** Text of each bucket, km/h, without the unit. */
    public static final String[] LABELS = {"0–40", "40–80", "80–120", "120+"};

    /** Bucket index of a speed in km/h, 0 to COUNT - 1. */
    public static int of(double speedKmh) {
        for (int i = 0; i < LIMITS.length; i++) {
            if (speedKmh < LIMITS[i]) return i;
        }
        return LIMITS.length;
    }
}
