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

    // A bucket keeps only its last reading: the peak inside it was lost.
    @Test public void rangeKeepsAPeakThatALaterReadingInTheBucketReplaced() {
        TempTrail t = TempTrail.start(T0).record(T0, 24f).record(T0 + MIN, 35f).record(T0 + 2 * MIN, 26f);
        assertEquals(26f, t.get(0), 0f);     // the bucket: its last reading
        assertEquals(24f, t.min(), 0f);
        assertEquals(35f, t.max(), 0f);
    }

    // A long stay merges buckets into their mean: the range got narrower.
    @Test public void rangeDoesNotNarrowWhenBucketsAreMerged() {
        TempTrail t = TempTrail.start(T0);
        for (int i = 0; i < TempTrail.MAX_BUCKETS; i++) {
            t = t.record(T0 + i * TempTrail.BASE_BUCKET_MS, i == 10 ? 38f : i == 11 ? 20f : i == 30 ? 8f : i == 31 ? 20f : 20f);
        }
        t = t.record(T0 + TempTrail.MAX_BUCKETS * TempTrail.BASE_BUCKET_MS, 20f);
        assertEquals(2 * TempTrail.BASE_BUCKET_MS, t.bucketMs);
        assertEquals(29f, t.get(5), 0f);     // mean of 38 and 20: the bucket lost the peak
        assertEquals(14f, t.get(15), 0f);    // mean of 8 and 20
        assertEquals(8f, t.min(), 0f);
        assertEquals(38f, t.max(), 0f);
    }

    @Test public void rangeSurvivesEncodeDecode() {
        TempTrail t = TempTrail.start(T0).record(T0, 24f).record(T0 + MIN, 35.5f).record(T0 + 2 * MIN, 26f);
        TempTrail back = TempTrail.decode(t.encode());
        assertEquals(t, back);
        assertEquals(24f, back.min(), 0f);
        assertEquals(35.5f, back.max(), 0f);
        // and it goes on from there
        assertEquals(35.5f, back.record(T0 + 20 * MIN, 30f).max(), 0f);
    }

    // What an app before this change stored: three parts, no low/high.
    @Test public void oldStoredTrailStillLoads() {
        TempTrail t = TempTrail.decode(T0 + ";600000;24.0,,31.5");
        assertEquals(T0, t.startMs);
        assertEquals(3, t.size());
        assertEquals(24f, t.get(0), 0f);
        assertTrue(Float.isNaN(t.get(1)));
        assertEquals(31.5f, t.get(2), 0f);
        assertEquals(24f, t.min(), 0f);      // from the buckets: the best that is left
        assertEquals(31.5f, t.max(), 0f);
        // one more tick does not shrink it, and a new low widens it
        t = t.record(T0 + 31 * MIN, 19f);
        assertEquals(19f, t.min(), 0f);
        assertEquals(31.5f, t.max(), 0f);

        TempTrail none = TempTrail.decode(T0 + ";600000;");
        assertEquals(0, none.size());
        assertTrue(Float.isNaN(none.min()));
    }

    // A stored range can never be narrower than the buckets it came with.
    @Test public void storedRangeIsWidenedToTheBuckets() {
        TempTrail t = TempTrail.decode(T0 + ";600000;18.0,32.0;20.0;30.0");
        assertEquals(18f, t.min(), 0f);
        assertEquals(32f, t.max(), 0f);
        assertEquals(TempTrail.EMPTY, TempTrail.decode(T0 + ";600000;18.0;x;30.0"));
    }

    @Test public void sameRangeAndBucketsIsStillTheSameTrail() {
        TempTrail a = TempTrail.start(T0).record(T0, 24f).record(T0 + MIN, 30f);
        assertSame(a, a.record(T0 + 2 * MIN, 30.1f));
        assertTrue(!a.equals(a.record(T0 + 2 * MIN, 24f)));   // bucket changed
    }
}
