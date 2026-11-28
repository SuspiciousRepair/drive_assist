package com.geely.drivemem.ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class SessionTextTest {
    private TimeZone saved;

    @Before public void pinZone() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
    }

    @After public void restoreZone() { TimeZone.setDefault(saved); }

    private static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    @Test public void durationUsesOneFormatForCardsAndParkedRow() {
        assertEquals("0m", SessionText.duration(0));
        assertEquals("44m", SessionText.duration(44 * 60_000L));
        assertEquals("8h 07m", SessionText.duration((8 * 60 + 7) * 60_000L));
        assertEquals("0m", SessionText.duration(-5));
    }

    @Test public void shortDurationShowsSecondsUnderAMinute() {
        assertEquals("44s", SessionText.shortDuration(44_000));
        assertEquals("0s", SessionText.shortDuration(-5));
        assertEquals("1m", SessionText.shortDuration(60_000));
        assertEquals("1h 05m", SessionText.shortDuration(65 * 60_000L));
    }

    @Test public void sameDayStartIsPlainClock() {
        long day = at(2026, 11, 14, 0, 0);
        assertEquals("00:35 – 01:19",
            SessionText.range(at(2026, 11, 14, 0, 35), at(2026, 11, 14, 1, 19), day, "ontem"));
    }

    @Test public void startOnPreviousDaySaysYesterday() {
        long day = at(2026, 11, 14, 0, 0);
        assertEquals("ontem 19:51 – 00:35",
            SessionText.range(at(2026, 11, 13, 19, 51), at(2026, 11, 14, 0, 35), day, "ontem"));
    }

    @Test public void olderStartShowsDate() {
        long day = at(2026, 11, 14, 0, 0);
        assertEquals("12/11 22:00", SessionText.startLabel(at(2026, 11, 12, 22, 0), day, "ontem"));
    }

    @Test public void unknownDayFallsBackToClock() {
        assertEquals("19:51", SessionText.startLabel(at(2026, 11, 13, 19, 51), -1, "ontem"));
    }

    // Issue #210: whole km rounded down, like the car's own panel, with the
    // locale's thousands mark; unknown (0 or -1) gives null so the line goes.
    @Test public void odometerIsWholeKmRoundedDown() {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.US);
            assertEquals("12,398", SessionText.odometer(12398.9));
            assertEquals("87", SessionText.odometer(87.0));
            assertEquals(null, SessionText.odometer(0));
            assertEquals(null, SessionText.odometer(-1));
            assertEquals(null, SessionText.odometer(Double.NaN));
            java.util.Locale.setDefault(new java.util.Locale("pt", "BR"));
            assertEquals("12.398", SessionText.odometer(12398.2));
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    @Test public void odometerSpanShowsWhatIsKnown() {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.US);
            assertEquals("12,345 → 12,398", SessionText.odometerSpan(12345.6, 12398.1));
            assertEquals("12,398", SessionText.odometerSpan(-1, 12398.1));
            assertEquals("12,345", SessionText.odometerSpan(12345.6, -1));
            assertEquals(null, SessionText.odometerSpan(-1, -1));
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }
}
