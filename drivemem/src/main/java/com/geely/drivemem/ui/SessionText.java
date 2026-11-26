package com.geely.drivemem.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pure text helpers for the day's session cards, kept free of Android so the
 * rules are unit-tested: one duration format everywhere ("8h 07m", "44m" --
 * the parked row used to say "8h 07min" while the cards said "44m"), and a
 * start time that says when it belongs to an earlier day, and one odometer
 * format for the rows and the trip and charge pages.
 */
final class SessionText {
    private SessionText() {}

    private static final long DAY_MS = 24L * 3600 * 1000;

    /** "8h 07m" / "44m". Same format as DailyStatsProvider.durationLabel. */
    static String duration(long ms) {
        long totalMin = Math.max(0, ms) / 60_000L;
        long h = totalMin / 60, m = totalMin % 60;
        return h > 0 ? String.format(Locale.US, "%dh %02dm", h, m) : m + "m";
    }

    /** "HH:mm" in the device time zone. */
    static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    /**
     * The start of a session as read from the card of the day that begins at
     * {@code dayStartMs}: plain "19:51" on that day, "{yesterday} 19:51" when
     * it began the day before (a Valet interval left on overnight), "d/M 19:51"
     * when older still. Without the prefix a 19:51–00:35 card on today's list
     * read as if it had run backwards.
     */
    static String startLabel(long startMs, long dayStartMs, String yesterdayWord) {
        String t = clock(startMs);
        if (dayStartMs <= 0 || startMs >= dayStartMs) return t;
        if (startMs >= dayStartMs - DAY_MS) return yesterdayWord + " " + t;
        return new SimpleDateFormat("d/M", Locale.US).format(new Date(startMs)) + " " + t;
    }

    /**
     * Time range for a finished session, "{start} – HH:mm"; for a live one
     * (endMs &lt;= 0) the caller formats "since {start}" from startLabel instead.
     */
    static String range(long startMs, long endMs, long dayStartMs, String yesterdayWord) {
        return startLabel(startMs, dayStartMs, yesterdayWord) + " – " + clock(endMs);
    }

    /**
     * An odometer reading as the car's own panel shows it: whole km, rounded
     * down, with the locale's thousands mark ("12,398" / "12.398"). Null when
     * unknown (0 or less), so the caller leaves the line out (issue #210).
     */
    static String odometer(double km) {
        if (!(km > 0)) return null;
        return java.text.NumberFormat.getIntegerInstance().format((long) Math.floor(km));
    }

    /**
     * A trip's odometer, "12,345 → 12,398", without the unit. Only the end
     * when the start is unknown, only the start when the end is; null when
     * both are.
     */
    static String odometerSpan(double startKm, double endKm) {
        String a = odometer(startKm), b = odometer(endKm);
        if (a == null) return b;
        if (b == null) return a;
        return a + " → " + b;
    }
}
