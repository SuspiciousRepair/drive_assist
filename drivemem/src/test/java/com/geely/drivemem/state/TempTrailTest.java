package com.geely.drivemem.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TempTrailTest {
    private static final long T0 = 1_700_000_000_000L;
    private static final long MIN = 60_000L;

    @Test public void awakeTicksFillTheirBucket() {
        TempTrail t = TempTrail.start(T0).record(T0, 24.2f).record(T0 + 11 * MIN, 25.1f);
        assertEquals(2, t.size());
        assertEquals(24.0f, t.get(0), 0f);   // half-degree steps
        assertEquals(25.0f, t.get(1), 0f);
    }

    @Test public void bucketsWithNoTickStayEmptyAsAsleep() {
        TempTrail t = TempTrail.start(T0).record(T0, 24f).record(T0 + 45 * MIN, 30f);
        assertEquals(5, t.size());
        assertEquals(24f, t.get(0), 0f);
        assertTrue(Float.isNaN(t.get(1)));
        assertTrue(Float.isNaN(t.get(2)));
        assertTrue(Float.isNaN(t.get(3)));
        assertEquals(30f, t.get(4), 0f);
    }

    @Test public void sameValueInSameBucketIsTheSameTrail() {
        TempTrail a = TempTrail.start(T0).record(T0, 24f);
        assertSame(a, a.record(T0 + MIN, 24.1f));
        assertSame(a, a.record(T0 + MIN, null));
    }

    @Test public void awakeWithoutReadingStillMarksLaterBucketsAsleepNotAwake() {
        TempTrail t = TempTrail.start(T0).record(T0, 24f).record(T0 + 25 * MIN, null);
        assertEquals(3, t.size());
        assertTrue(Float.isNaN(t.get(2)));
    }

    @Test public void longStayDoublesBucketWidthAndKeepsGaps() {
        TempTrail t = TempTrail.start(T0);
        for (int i = 0; i < TempTrail.MAX_BUCKETS; i++) {
            // awake only in even buckets
            if (i % 4 < 2) t = t.record(T0 + i * TempTrail.BASE_BUCKET_MS, 20f + (i % 2));
        }
        assertEquals(TempTrail.BASE_BUCKET_MS, t.bucketMs);
        t = t.record(T0 + TempTrail.MAX_BUCKETS * TempTrail.BASE_BUCKET_MS, 22f);
        assertEquals(2 * TempTrail.BASE_BUCKET_MS, t.bucketMs);
        assertEquals(20.5f, t.get(0), 0f);           // mean of 20 and 21
        assertTrue(Float.isNaN(t.get(1)));           // both halves asleep
        assertEquals(22f, t.get(TempTrail.MAX_BUCKETS / 2), 0f);
    }

    @Test public void minAndMaxIgnoreSleep() {
        TempTrail t = TempTrail.start(T0).record(T0, 18f).record(T0 + 60 * MIN, 32f);
        assertEquals(18f, t.min(), 0f);
        assertEquals(32f, t.max(), 0f);
        assertTrue(Float.isNaN(TempTrail.EMPTY.min()));
    }

    @Test public void encodeDecodeRoundTrips() {
        TempTrail t = TempTrail.start(T0).record(T0, 24f).record(T0 + 35 * MIN, 31.5f);
        assertEquals(t, TempTrail.decode(t.encode()));
        assertEquals(TempTrail.EMPTY, TempTrail.decode(null));
        assertEquals(TempTrail.EMPTY, TempTrail.decode("garbage"));
    }
}
