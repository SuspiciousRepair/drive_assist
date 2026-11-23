package com.geely.drivemem.sensors;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * How much battery a day used, in percent of the pack: every drop added up,
 * so a week reads "140 %" where first-to-last SoC would read "+3 %".
 *
 * In percent on purpose: the car reports its SoC in percent, so this needs
 * no pack size and is right on every pack.
 *
 * used = SoC at the day's start - SoC at its end + what the charges put in.
 * The day starts at the last reading BEFORE it, so what the car lost parked
 * overnight is counted, on the day it is found. A charge that crosses
 * midnight gives each day its own share, cut at the SoC read at midnight.
 * No sum of sample-to-sample drops: the SoC wobbles by a percent after a
 * drive, and each wobble would count.
 */
public final class BatteryUse {

    private BatteryUse() {}

    /** One charge that touches the day. A socStart / socEnd outside 1..100 = not known. */
    public static final class Charge {
        final long startMs, endMs;
        final int socStart, socEnd;
        public Charge(long startMs, long endMs, int socStart, int socEnd) {
            this.startMs = startMs; this.endMs = endMs;
            this.socStart = socStart; this.socEnd = socEnd;
        }
    }

    /** Percent of the pack used in [dayStartMs, dayEndMs); -1 when the day
     * has no SoC reading. prevLastPct is the last reading before the day
     * (-1 = none: the day's own first reading is used), lastPct the day's
     * last. Never below 0: a charge the app did not record reads as no use,
     * not as negative use. */
    public static double usedPct(int prevLastPct, int firstPct, int lastPct,
                                 List<Charge> charges, long dayStartMs, long dayEndMs) {
        int start = ok(prevLastPct) ? prevLastPct : firstPct;
        if (!ok(start) || !ok(lastPct)) return -1;
        int gained = 0;
        for (Charge ch : charges) {
            if (ch.startMs >= dayEndMs || ch.endMs <= dayStartMs) continue;
            int from = ch.startMs >= dayStartMs ? ch.socStart : start;
            int to = ch.endMs < dayEndMs && ok(ch.socEnd) ? ch.socEnd : lastPct;
            if (!ok(from)) continue;
            gained += Math.max(0, to - from);
        }
        return Math.max(0, start - lastPct + gained);
    }

    /** A plausible SoC: 0 and above-100 readings are sensor noise. */
    private static boolean ok(int pct) { return pct >= 1 && pct <= 100; }

    /** A plausible battery_pct, as in SocHistory. */
    private static final String VALID = "BETWEEN 1 AND 100";

    /** The day's use from the database. firstPct / lastPct are the day's own
     * first and last readings (today: the live one), as the overview has them. */
    public static double ofDay(SQLiteDatabase db, String dateStr, long dayStartMs, long dayEndMs,
                               int firstPct, int lastPct) {
        if (!ok(lastPct)) return -1;
        return usedPct(lastBefore(db, dateStr, dayStartMs), firstPct, lastPct,
            chargesTouching(db, dayStartMs, dayEndMs), dayStartMs, dayEndMs);
    }

    /** The last SoC read before the day: from the samples, or from the
     * frozen days once the samples are pruned. -1 when there is none. */
    private static int lastBefore(SQLiteDatabase db, String dateStr, long dayStartMs) {
        Cursor c = db.rawQuery(
            "SELECT battery_pct FROM telemetry_sample WHERE ts_ms < ? AND battery_pct " + VALID
          + " ORDER BY ts_ms DESC LIMIT 1", new String[]{String.valueOf(dayStartMs)});
        try { if (c.moveToFirst()) return c.getInt(0); } finally { c.close(); }
        Cursor d = db.rawQuery(
            "SELECT last_battery_pct FROM daily_stat WHERE date < ? AND last_battery_pct " + VALID
          + " ORDER BY date DESC LIMIT 1", new String[]{dateStr});
        try { return d.moveToFirst() ? d.getInt(0) : -1; } finally { d.close(); }
    }

    private static List<Charge> chargesTouching(SQLiteDatabase db, long dayStartMs, long dayEndMs) {
        List<Charge> out = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT start_ms, end_ms, soc_start, soc_end FROM charge_session "
          + "WHERE start_ms > 0 AND start_ms < ? AND end_ms > ?",
            new String[]{String.valueOf(dayEndMs), String.valueOf(dayStartMs)});
        try {
            while (c.moveToNext()) {
                out.add(new Charge(c.getLong(0), c.getLong(1),
                    c.isNull(2) ? -1 : c.getInt(2), c.isNull(3) ? -1 : c.getInt(3)));
            }
        } finally { c.close(); }
        return out;
    }
}
