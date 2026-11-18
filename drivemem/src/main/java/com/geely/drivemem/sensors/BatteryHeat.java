package com.geely.drivemem.sensors;

import android.content.Context;
import android.database.Cursor;

import com.geely.drivemem.car.CarDb;

import java.util.ArrayList;
import java.util.List;

/**
 * The charge page's battery temperature card: how warm the pack got, and how
 * long it sat at 100 % and at what temperature. For LFP, heat and a full
 * pack together are what age it.
 *
 * Read from telemetry_sample only: battery_temp_c comes from the OBD2 dongle
 * and daily_stat does not keep it, so this covers what the samples still hold
 * (TelemetryRollup keeps ~90 days). {@link Stats#firstMs} says where the data
 * starts, for the card to say so.
 *
 * A stay at 100 % runs from the first sample at 100 % to the last one before
 * the SoC drops; a gap while the car was off counts (the pack showed 100 %
 * on both sides of it), but has no temperature of its own.
 */
public final class BatteryHeat {

    private BatteryHeat() {}

    /** Shorter stays are a charge ending and a drive starting, not a stay. */
    public static final long MIN_STAY_MS = 30 * 60_000L;

    public static final class Stay {
        public final long fromMs, toMs;
        public final double maxTemp, sumTemp;   // NaN / 0 when no reading
        public final int nTemp;
        Stay(long fromMs, long toMs, double maxTemp, double sumTemp, int nTemp) {
            this.fromMs = fromMs; this.toMs = toMs;
            this.maxTemp = maxTemp; this.sumTemp = sumTemp; this.nTemp = nTemp;
        }
        public long durationMs() { return toMs - fromMs; }
    }

    public static final class Stats {
        public double minTemp = Double.NaN, maxTemp = Double.NaN, avgTemp = Double.NaN;
        public long maxTempMs = -1;          // when the maximum was read
        public long firstMs = -1;            // first sample with a temperature
        public final List<Stay> stays = new ArrayList<>();

        public boolean hasTemp() { return !Double.isNaN(maxTemp); }
        public long totalFullMs() { long t = 0; for (Stay s : stays) t += s.durationMs(); return t; }
        public long avgFullMs() { return stays.isEmpty() ? 0 : totalFullMs() / stays.size(); }
        public Stay longestStay() {
            Stay best = null;
            for (Stay s : stays) if (best == null || s.durationMs() > best.durationMs()) best = s;
            return best;
        }
        /** Highest temperature read while at 100 %, NaN without one. */
        public double maxTempAtFull() {
            double m = Double.NaN;
            for (Stay s : stays) if (s.nTemp > 0 && !(s.maxTemp <= m)) m = s.maxTemp;
            return m;
        }
        public double avgTempAtFull() {
            double sum = 0; int n = 0;
            for (Stay s : stays) { sum += s.sumTemp; n += s.nTemp; }
            return n > 0 ? sum / n : Double.NaN;
        }
    }

    /** Reads [fromMs, toMs). Run off the UI thread: a year is every sample. */
    public static Stats read(Context ctx, long fromMs, long toMs) {
        List<long[]> rows = new ArrayList<>();
        List<Double> temps = new ArrayList<>();
        Cursor c = CarDb.get(ctx).db().rawQuery(
            "SELECT ts_ms, battery_pct, battery_temp_c FROM telemetry_sample "
          + "WHERE ts_ms >= ? AND ts_ms < ? AND battery_pct BETWEEN 1 AND 100 ORDER BY ts_ms",
            new String[]{String.valueOf(fromMs), String.valueOf(toMs)});
        try {
            while (c.moveToNext()) {
                rows.add(new long[]{c.getLong(0), c.getInt(1)});
                temps.add(c.isNull(2) ? null : c.getDouble(2));
            }
        } finally { c.close(); }
        long[] ts = new long[rows.size()];
        int[] pct = new int[rows.size()];
        for (int i = 0; i < ts.length; i++) { ts[i] = rows.get(i)[0]; pct[i] = (int) rows.get(i)[1]; }
        return compute(ts, pct, temps);
    }

    /** Pure: samples in time order, temperature null when not read. */
    public static Stats compute(long[] ts, int[] pct, List<Double> temp) {
        Stats st = new Stats();
        double sum = 0; int n = 0;
        long runFrom = -1, runTo = -1; double runMax = Double.NaN, runSum = 0; int runN = 0;
        for (int i = 0; i < ts.length; i++) {
            Double t = temp.get(i);
            if (t != null) {
                if (st.firstMs < 0) st.firstMs = ts[i];
                if (n == 0 || t < st.minTemp) st.minTemp = t;
                if (n == 0 || t >= st.maxTemp) { st.maxTemp = t; st.maxTempMs = ts[i]; }
                sum += t; n++;
            }
            if (pct[i] >= 100) {
                if (runFrom < 0) { runFrom = ts[i]; runMax = Double.NaN; runSum = 0; runN = 0; }
                runTo = ts[i];
                if (t != null) { if (!(t <= runMax)) runMax = t; runSum += t; runN++; }
            } else if (runFrom >= 0) {
                if (runTo - runFrom >= MIN_STAY_MS) st.stays.add(new Stay(runFrom, runTo, runMax, runSum, runN));
                runFrom = -1;
            }
        }
        if (runFrom >= 0 && runTo - runFrom >= MIN_STAY_MS) st.stays.add(new Stay(runFrom, runTo, runMax, runSum, runN));
        if (n > 0) st.avgTemp = sum / n;
        return st;
    }
}
