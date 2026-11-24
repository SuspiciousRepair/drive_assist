package com.geely.drivemem.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricSyncTest {
    @Test public void noPointsLeavesTheTimesAlone() {
        assertEquals(12_000, LyricSync.NONE.map(12_000));
        assertTrue(LyricSync.NONE.isEmpty());
    }

    @Test public void onePointMovesEveryLineByTheSameAmount() {
        LyricSync s = LyricSync.NONE.with(10_000, 10_800);   // sung 0.8 s later
        assertEquals(800, s.map(0));
        assertEquals(10_800, s.map(10_000));
        assertEquals(60_800, s.map(60_000));
    }

    @Test public void twoPointsFixLyricsThatDrift() {
        // Lyrics for a slower version: 1 s late at 10 s, 3 s late at 50 s.
        LyricSync s = LyricSync.NONE.with(10_000, 11_000).with(50_000, 53_000);
        assertEquals(11_000, s.map(10_000));
        assertEquals(32_000, s.map(30_000));   // half way: 2 s late
        assertEquals(53_000, s.map(50_000));
        assertEquals(6_000, s.map(5_000));     // before the first: like the first
        assertEquals(63_000, s.map(60_000));   // after the last: like the last
    }

    @Test public void aSecondTapOnTheSameLineReplacesTheFirst() {
        LyricSync s = LyricSync.NONE.with(10_000, 11_000).with(10_000, 10_500);
        assertEquals(1, s.size());
        assertEquals(10_500, s.map(10_000));
    }

    @Test public void aTapThatDisagreesDropsTheOldPoint() {
        // Line at 20 s marked at 25 s, then the line at 30 s marked at 24 s:
        // the times would run backwards, so the old point goes.
        LyricSync s = LyricSync.NONE.with(20_000, 25_000).with(30_000, 24_000);
        assertEquals(1, s.size());
        assertTrue(s.has(30_000));
        assertFalse(s.has(20_000));
    }

    @Test public void pointsComeBackFromTheirText() {
        LyricSync s = LyricSync.NONE.with(50_000, 53_000).with(10_000, 11_000);
        assertEquals("10000:11000,50000:53000", s.encode());
        assertEquals(s.encode(), LyricSync.decode(s.encode()).encode());
        assertSame(LyricSync.NONE, LyricSync.decode(null));
        assertEquals(1, LyricSync.decode("x,10:20,1:2:3").size());
    }

    @Test public void syncedLyricsKeepTheirOrderAndText() {
        Lrc l = Lrc.parse("[00:10.00]one\n[00:20.00]two\n[00:30.00]three");
        assertSame(l, l.synced(LyricSync.NONE));
        Lrc m = l.synced(LyricSync.NONE.with(20_000, 21_000));
        assertEquals(11_000, m.timeAt(0));
        assertEquals(21_000, m.timeAt(1));
        assertEquals("two", m.textAt(1));
        assertEquals(0, m.indexAt(20_500));   // "two" is not sung yet
        assertEquals(1, m.indexAt(21_000));
    }
}
