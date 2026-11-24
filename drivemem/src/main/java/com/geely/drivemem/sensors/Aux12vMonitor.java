package com.geely.drivemem.sensors;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.geely.drivemem.car.Aux12vHub;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.state.CarState;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Monitors 12V auxiliary battery health from {@link Aux12vHub}, stores compact
 * wake/phase readings in {@code aux_12v_reading}, and computes early-warning
 * health states (GOOD / WATCH / WEAK / FAULT) plus a 60-day morning trend.
 *
 * Pure debouncer and health evaluator are package/public static methods so unit
 * tests exercise aging drift, single-frame glitch rejection, DC-DC faults, and
 * baseline reset on 12V replacement without Android dependencies. */
public final class Aux12vMonitor {
    private Aux12vMonitor() {}

    public static final String PHASE_WAKE_SAG = "WAKE_SAG";
    public static final String PHASE_RESTING = "RESTING";
    public static final String PHASE_DCDC = "DCDC_CHARGING";
    public static final String PHASE_WARN = "WARN";

    public static final String SOURCE_VHAL = "vhal";

    /** Thresholds calibrated against standard 12V lead-acid EV auxiliary behavior
     * and EX2 parked/READY captures (12.8-12.9 V parked/float, 13.9-14.2 V DC-DC bulk).
     * Smart EV DC-DC converters routinely drop to ~12.7-13.1 V float once the 12V
     * battery is full, so only genuine under-load collapse (<12.1 V) or overvoltage
     * (>14.9 V) counts as a DC-DC fault. */
    public static final double HEALTHY_RESTING_LIMIT_V = 12.25;
    public static final double WEAK_RESTING_LIMIT_V = 12.05;
    public static final double WEAK_WAKE_SAG_V = 11.60;
    public static final double BASELINE_DROP_WATCH_V = 0.25;
    public static final double DCDC_ON_THRESHOLD_V = 13.30;
    public static final double DCDC_FAULT_LOW_V = 12.10;
    public static final double DCDC_FAULT_HIGH_V = 14.90;

    static final long WAKE_GAP_MS = 4L * 3600_000L;
    /** A DC-DC reading out of band must hold this long before it is a fault:
     * one low poll while the converter ramps up is normal. */
    static final long DCDC_FAULT_HOLD_MS = 60_000L;
    /** The hub delivers changes only, so a jump that then holds steady never
     * comes a second time. After this delay the held value is read again. */
    static final long CONFIRM_DELAY_MS = 12_000L;
    static final long WAKE_WINDOW_MS = 30_000L;
    static final long STEADY_STORE_INTERVAL_MS = 4L * 3600_000L;
    static final long TREND_WINDOW_DAYS = 60L;

    public enum Health { UNKNOWN, GOOD, WATCH, WEAK, FAULT }

    /** One stored sample row. */
    public static final class Sample {
        public final long tsMs;
        public final double voltageV;
        public final String phase;
        public final Double outsideTempC;
        public final Double parkedHours;

        public Sample(long tsMs, double voltageV, String phase, Double outsideTempC, Double parkedHours) {
            this.tsMs = tsMs;
            this.voltageV = voltageV;
            this.phase = phase;
            this.outsideTempC = outsideTempC;
            this.parkedHours = parkedHours;
        }
    }

    /** One daily point on the 60-day morning resting voltage chart. */
    public static final class TrendPoint {
        public final long tsMs;
        public final double voltageV;
        public TrendPoint(long tsMs, double voltageV) {
            this.tsMs = tsMs;
            this.voltageV = voltageV;
        }
    }

    /** Evaluated 12V health summary for the Diary screen & attention dot. */
    public static final class Summary {
        public final Health health;
        public final Double latestRestingV;
        public final Double latestReadyV;
        public final Double baselineV;
        public final Double deltaV;
        public final List<TrendPoint> morningTrend;
        public final Long replacedAtMs;

        public Summary(Health health, Double latestRestingV, Double latestReadyV,
                       Double baselineV, Double deltaV, List<TrendPoint> morningTrend,
                       Long replacedAtMs) {
            this.health = health;
            this.latestRestingV = latestRestingV;
            this.latestReadyV = latestReadyV;
            this.baselineV = baselineV;
            this.deltaV = deltaV;
            this.morningTrend = morningTrend;
            this.replacedAtMs = replacedAtMs;
        }
    }

    /** Rejects isolated single-frame glitches (such as the 5.6 V / 11.4 V spikes
     * seen in EX2 CAN 0x176 captures): any sudden jump > 0.5 V must be confirmed
     * by a second consecutive reading within 0.3 V before acceptance. Pure.
     * The hub sends changes only, so the caller feeds the held value again
     * after CONFIRM_DELAY_MS (see scheduleConfirm). */
    public static final class Debouncer {
        private static final double JUMP_V = 0.5;
        private static final double CONFIRM_TOL_V = 0.3;
        private Double accepted;
        private Double pending;

        public Double accept(double v) {
            if (Double.isNaN(v) || v < 6.0 || v > 18.0) return null;
            if (accepted == null) {
                accepted = v;
                pending = null;
                return v;
            }
            if (Math.abs(v - accepted) <= JUMP_V) {
                accepted = v;
                pending = null;
                return v;
            }
            if (pending != null && Math.abs(v - pending) <= CONFIRM_TOL_V) {
                accepted = v;
                pending = null;
                return v;
            }
            pending = v;
            return null;
        }

        public void reset() { accepted = null; pending = null; }
    }

    // ---- Live Subscription & Persistence ----

    private static volatile boolean subscribed;
    private static final Debouncer debouncer = new Debouncer();
    private static long lastElapsedMs = -1L;
    private static long lastUptimeMs = -1L;
    private static long wakeStartedMs = -1L;
    private static Double wakeParkedHours;
    private static boolean confirmScheduled;
    private static long lastStoredMs = -1L;
    private static String lastStoredPhase;
    private static double lastStoredV = Double.NaN;
    private static Health lastAlertedHealth = Health.UNKNOWN;

    /** Idempotent; called once from DriveMemApplication.onCreate(). */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed || ctx == null) return;
        subscribed = true;
        final Context app = ctx.getApplicationContext();

        EntityBus.subscribe(Aux12vHub.VOLTAGE, (key, r) -> {
            if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) return;
            onVoltage(app, ((Number) r.value).doubleValue(), System.currentTimeMillis(), sleptSinceLastMs());
        });
        EntityBus.subscribe(Aux12vHub.LOW_WARN, (key, r) -> {
            if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) return;
            if (((Number) r.value).intValue() != 0) {
                onLowWarn(app, System.currentTimeMillis());
            }
        });
    }

    /** Time the head unit was suspended since the last voltage event. The
     * elapsed clock counts sleep and the uptime clock does not, so a steady
     * voltage on an awake unit is never taken for a wake-up. */
    private static synchronized long sleptSinceLastMs() {
        long el = android.os.SystemClock.elapsedRealtime();
        long up = android.os.SystemClock.uptimeMillis();
        long slept = lastElapsedMs < 0 ? 0L : Math.max(0L, (el - lastElapsedMs) - (up - lastUptimeMs));
        lastElapsedMs = el;
        lastUptimeMs = up;
        return slept;
    }

    private static synchronized void onVoltage(Context ctx, double rawV, long nowMs, long sleptMs) {
        if (sleptMs >= WAKE_GAP_MS) {
            wakeStartedMs = nowMs;
            wakeParkedHours = sleptMs / 3_600_000.0;
        }
        Double v = debouncer.accept(rawV);
        if (v == null) {
            scheduleConfirm(ctx);
            return;
        }
        Double parkedHours = wakeParkedHours;
        wakeParkedHours = null;

        boolean parked = CarState.isParked();
        boolean charging = isChargingNow();
        boolean lowWarn = Aux12vHub.lowWarningActive();
        boolean inWakeWindow = wakeStartedMs > 0 && (nowMs - wakeStartedMs) <= WAKE_WINDOW_MS;
        String phase = classifyPhase(v, parked, charging, lowWarn, inWakeWindow);

        if (!shouldStore(lastStoredPhase, lastStoredV, lastStoredMs, phase, v, nowMs)) return;

        lastStoredPhase = phase;
        lastStoredV = v;
        lastStoredMs = nowMs;

        final Double pHours = parkedHours;
        final Double outC = CarDiary.latestOutsideTempC();
        final int dcdc = PHASE_DCDC.equals(phase) ? 1 : 0;
        if (dcdc == 1 && (v < DCDC_FAULT_LOW_V || v > DCDC_FAULT_HIGH_V)) {
            scheduleFaultRecheck(ctx);
        }

        CarDb.get(ctx).write(() -> {
            try {
                // The odometer can fall back to a database read: keep it off the hub thread.
                Double odo = CarDiary.latestOdometerKm(ctx);
                SQLiteDatabase db = CarDb.get(ctx).db();
                ContentValues cv = new ContentValues();
                cv.put("ts_ms", nowMs);
                if (odo != null && odo > 0) cv.put("odo_km", odo);
                cv.put("voltage_v", v);
                cv.put("phase", phase);
                cv.put("dcdc_active", dcdc);
                if (outC != null) cv.put("outside_temp_c", outC);
                if (pHours != null) cv.put("parked_hours", pHours);
                cv.put("source", SOURCE_VHAL);
                db.insert("aux_12v_reading", null, cv);
                maybeAutoLogHealthTransition(ctx, nowMs);
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "aux12v store failed: " + t);
            }
        });
    }

    private static synchronized void onLowWarn(Context ctx, long nowMs) {
        Float v = Aux12vHub.latestVoltage();
        Double outC = CarDiary.latestOutsideTempC();
        CarDb.get(ctx).write(() -> {
            try {
                Double odo = CarDiary.latestOdometerKm(ctx);
                ContentValues cv = new ContentValues();
                cv.put("ts_ms", nowMs);
                if (odo != null && odo > 0) cv.put("odo_km", odo);
                if (v != null) cv.put("voltage_v", v.doubleValue());
                cv.put("phase", PHASE_WARN);
                cv.put("dcdc_active", 0);
                if (outC != null) cv.put("outside_temp_c", outC);
                cv.put("source", SOURCE_VHAL);
                CarDb.get(ctx).db().insert("aux_12v_reading", null, cv);
                maybeAutoLogHealthTransition(ctx, nowMs);
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "aux12v warn store failed: " + t);
            }
        });
    }

    /** Reads the held value again after a jump, because the hub will not send it twice. */
    private static void scheduleConfirm(Context ctx) {
        CarActor a = CarActor.get();
        if (a == null || confirmScheduled) return;
        confirmScheduled = true;
        a.runOnCarThreadDelayed(() -> {
            synchronized (Aux12vMonitor.class) { confirmScheduled = false; }
            Float live = Aux12vHub.latestVoltage();
            if (live != null) onVoltage(ctx, live, System.currentTimeMillis(), 0L);
        }, CONFIRM_DELAY_MS);
    }

    /** A DC-DC fault counts only after it holds; with a steady voltage no new
     * reading arrives, so look again once the hold time is over. */
    private static void scheduleFaultRecheck(Context ctx) {
        CarActor a = CarActor.get();
        if (a == null) return;
        a.runOnCarThreadDelayed(() -> CarDb.get(ctx).write(() -> {
            try { maybeAutoLogHealthTransition(ctx, System.currentTimeMillis()); }
            catch (Throwable t) { Log.w(CarAccess.TAG, "aux12v recheck failed: " + t); }
        }), DCDC_FAULT_HOLD_MS + 15_000L);
    }

    /** Runs on the DB writer thread. */
    private static void maybeAutoLogHealthTransition(Context ctx, long nowMs) {
        Summary s = readSummary(ctx, nowMs);
        Health h = s.health;
        boolean alertable = (h == Health.WATCH || h == Health.WEAK || h == Health.FAULT);
        if (!alertable) {
            if (h == Health.GOOD) lastAlertedHealth = Health.GOOD;
            return;
        }
        if (h == lastAlertedHealth) return;
        lastAlertedHealth = h;
        if (coveredByExistingAlert(ctx, h, nowMs - 86_400_000L)) return;
        String note = s.latestRestingV != null
            ? String.format(Locale.US, "%s · %.1f V", h.name(), s.latestRestingV)
            : h.name();
        CarDiary.insert(ctx, CarDiary.Category.AUX12V_AUTO, CarDiary.STATUS_OPEN,
            nowMs, CarDiary.latestOdometerKm(ctx), CarDiary.latestOutsideTempC(),
            null, null, note, null, null, null, null, null);
    }

    /** True when an automatic 12V entry that is still open, or newer than
     * sinceMs, already reports this health or a worse one. A worse state
     * (WATCH, then FAULT) is not covered and gets its own entry. */
    private static boolean coveredByExistingAlert(Context ctx, Health h, long sinceMs) {
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT note FROM diary_event WHERE category = ? AND (status = ? OR ts_ms >= ?)",
                new String[]{CarDiary.Category.AUX12V_AUTO.name(), CarDiary.STATUS_OPEN,
                    String.valueOf(sinceMs)})) {
            while (c.moveToNext()) {
                if (noteCovers(c.isNull(0) ? null : c.getString(0), h)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Pure: does an automatic entry's note ("WATCH · 12.1 V") report h or worse? */
    static boolean noteCovers(String note, Health h) {
        if (note == null || h == null) return false;
        String head = note.trim();
        int sp = head.indexOf(' ');
        if (sp > 0) head = head.substring(0, sp);
        try { return Health.valueOf(head).ordinal() >= h.ordinal(); }
        catch (IllegalArgumentException e) { return false; }
    }

    /** Pure phase classifier. While parked & AC charging, the EX2 cycles DC-DC on and off
     * (resting around 12.8-12.9 V between top-ups), so charging only counts as DCDC_CHARGING
     * when bus voltage is actually elevated (>= 13.3 V). */
    public static String classifyPhase(double v, boolean parked, boolean charging,
                                       boolean lowWarn, boolean inWakeWindow) {
        if (lowWarn) return PHASE_WARN;
        if (!parked || v >= DCDC_ON_THRESHOLD_V) return PHASE_DCDC;
        if (inWakeWindow && !charging) return PHASE_WAKE_SAG;
        return PHASE_RESTING;
    }

    /** Pure storage rate limiter: writes on phase change, >= 0.2 V shift, or 4 h heartbeat. */
    public static boolean shouldStore(String lastPhase, double lastV, long lastMs,
                                      String phase, double v, long nowMs) {
        if (lastMs < 0 || lastPhase == null || Double.isNaN(lastV)) return true;
        if (!phase.equals(lastPhase)) return true;
        if (Math.abs(v - lastV) >= 0.2) return true;
        return (nowMs - lastMs) >= STEADY_STORE_INTERVAL_MS;
    }

    // ---- Queries & Pure Health Evaluation ----

    /** Reads aux_12v_reading and computes the current 12V health summary + 60-day trend. */
    public static Summary readSummary(Context ctx, long nowMs) {
        Long replacedAt = CarDiary.lastBattery12vReplacementMs(ctx);
        List<Sample> samples = readSamples(ctx, nowMs - 120L * 86_400_000L);
        ZoneId zone = ZoneId.systemDefault();
        // The baseline comes from the first days after the replacement (or the
        // first days on record), not from the start of the 120-day window: a
        // window start moves with time, and slow ageing would move with it.
        Double baseline = baselineOf(readEarliestResting(ctx, replacedAt), zone);
        Summary s = evaluate(samples, replacedAt, nowMs, zone, baseline);
        // If DB has no rows yet, still surface the live hub voltage cleanly.
        if (s.latestRestingV == null && s.latestReadyV == null) {
            Float live = Aux12vHub.latestVoltage();
            if (live != null) {
                boolean ready = live >= DCDC_ON_THRESHOLD_V || !CarState.isParked();
                return new Summary(Health.GOOD, ready ? null : live.doubleValue(),
                    ready ? live.doubleValue() : null, null, null, s.morningTrend, replacedAt);
            }
        }
        return s;
    }

    private static List<Sample> readSamples(Context ctx, long sinceMs) {
        List<Sample> out = new ArrayList<>();
        if (ctx == null) return out;
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT ts_ms, voltage_v, phase, outside_temp_c, parked_hours "
                + "FROM aux_12v_reading WHERE ts_ms >= ? ORDER BY ts_ms ASC, id ASC",
                new String[]{String.valueOf(sinceMs)})) {
            while (c.moveToNext()) {
                if (c.isNull(1) && !PHASE_WARN.equals(c.getString(2))) continue;
                out.add(new Sample(
                    c.getLong(0),
                    c.isNull(1) ? Double.NaN : c.getDouble(1),
                    c.getString(2),
                    c.isNull(3) ? null : c.getDouble(3),
                    c.isNull(4) ? null : c.getDouble(4)));
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "aux12v read failed: " + t);
        }
        return out;
    }

    /** The earliest resting readings since the replacement, enough for the baseline. */
    private static List<Sample> readEarliestResting(Context ctx, Long replacedAtMs) {
        List<Sample> out = new ArrayList<>();
        if (ctx == null) return out;
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT ts_ms, voltage_v, phase FROM aux_12v_reading "
                + "WHERE phase IN (?, ?) AND voltage_v IS NOT NULL AND ts_ms >= ? "
                + "ORDER BY ts_ms ASC, id ASC LIMIT 600",
                new String[]{PHASE_RESTING, PHASE_WAKE_SAG,
                    String.valueOf(replacedAtMs != null ? replacedAtMs : 0L)})) {
            while (c.moveToNext()) {
                out.add(new Sample(c.getLong(0), c.getDouble(1), c.getString(2), null, null));
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "aux12v baseline read failed: " + t);
        }
        return out;
    }

    /** Pure: median of the first resting reading of each of the earliest 10
     * days (at least 3 days), or null. Input is in time order. */
    static Double baselineOf(List<Sample> restingAsc, ZoneId zone) {
        if (restingAsc == null) return null;
        List<Double> firstOfDay = new ArrayList<>();
        LocalDate lastDay = null;
        for (Sample s : restingAsc) {
            if (Double.isNaN(s.voltageV)) continue;
            LocalDate day = Instant.ofEpochMilli(s.tsMs).atZone(zone).toLocalDate();
            if (day.equals(lastDay)) continue;
            lastDay = day;
            firstOfDay.add(s.voltageV);
            if (firstOfDay.size() == 10) break;
        }
        int n = firstOfDay.size();
        if (n < 3) return null;
        Collections.sort(firstOfDay);
        return (n % 2 == 1) ? firstOfDay.get(n / 2)
            : (firstOfDay.get(n / 2 - 1) + firstOfDay.get(n / 2)) / 2.0;
    }

    /** Pure health evaluator. Filters out readings prior to batteryReplacedAtMs so
     * logging a 12V battery replacement resets the baseline cleanly. */
    public static Summary evaluate(List<Sample> allSamples, Long batteryReplacedAtMs,
                                   long nowMs, ZoneId zone) {
        return evaluate(allSamples, batteryReplacedAtMs, nowMs, zone, null);
    }

    /** As above, with a baseline that was fixed from the earliest readings.
     * Null means: take it from the samples given. */
    public static Summary evaluate(List<Sample> allSamples, Long batteryReplacedAtMs,
                                   long nowMs, ZoneId zone, Double fixedBaseline) {
        if (allSamples == null || allSamples.isEmpty()) {
            return new Summary(Health.UNKNOWN, null, null, null, null,
                Collections.emptyList(), batteryReplacedAtMs);
        }

        List<Sample> resting = new ArrayList<>();
        List<Sample> wakeSags = new ArrayList<>();
        Double latestReady = null;
        boolean warnFault = false;
        long dcdcBadSinceMs = -1L;
        long warnHorizonMs = nowMs - 2L * 86_400_000L;
        long faultHorizonMs = nowMs - 7L * 86_400_000L;

        for (Sample s : allSamples) {
            if (batteryReplacedAtMs != null && s.tsMs < batteryReplacedAtMs) continue;
            if (PHASE_WARN.equals(s.phase) && s.tsMs >= warnHorizonMs) {
                // Ignore spurious warning flags recorded while the 12V bus is healthy (>= 12.6 V),
                // e.g. PEPS power-saving prompts logged during normal 13.9 V operation.
                if (Double.isNaN(s.voltageV) || s.voltageV < 12.60) {
                    warnFault = true;
                }
            } else if (PHASE_DCDC.equals(s.phase) && !Double.isNaN(s.voltageV)) {
                latestReady = s.voltageV;
                if (s.tsMs >= faultHorizonMs) {
                    if (s.voltageV < DCDC_FAULT_LOW_V || s.voltageV > DCDC_FAULT_HIGH_V) {
                        if (dcdcBadSinceMs < 0) dcdcBadSinceMs = s.tsMs;
                    } else if (s.voltageV >= 12.60 && s.voltageV <= 14.70) {
                        // DC-DC recovered to normal float or charging voltage.
                        dcdcBadSinceMs = -1L;
                    }
                }
            } else if (PHASE_WAKE_SAG.equals(s.phase) && !Double.isNaN(s.voltageV)) {
                wakeSags.add(s);
                resting.add(s);
            } else if (PHASE_RESTING.equals(s.phase) && !Double.isNaN(s.voltageV)) {
                resting.add(s);
            }
        }
        // One low reading while the converter ramps up is not a fault: it must hold.
        boolean dcdcFault = dcdcBadSinceMs >= 0 && nowMs - dcdcBadSinceMs >= DCDC_FAULT_HOLD_MS;
        boolean fault = warnFault || dcdcFault;

        // Collapse resting readings to one morning/first-wake point per local day for trend + baseline.
        Map<LocalDate, Sample> dailyFirst = new LinkedHashMap<>();
        for (Sample s : resting) {
            LocalDate day = Instant.ofEpochMilli(s.tsMs).atZone(zone).toLocalDate();
            if (!dailyFirst.containsKey(day)) {
                dailyFirst.put(day, s);
            }
        }
        List<Sample> daily = new ArrayList<>(dailyFirst.values());

        long trendStartMs = nowMs - TREND_WINDOW_DAYS * 86_400_000L;
        List<TrendPoint> trend = new ArrayList<>();
        for (Sample s : daily) {
            if (s.tsMs >= trendStartMs) {
                trend.add(new TrendPoint(s.tsMs, s.voltageV));
            }
        }

        Double latestResting = resting.isEmpty() ? null : resting.get(resting.size() - 1).voltageV;

        // Baseline: median of the earliest up to 10 daily resting readings after replacement.
        Double baseline = fixedBaseline != null ? fixedBaseline : baselineOf(daily, zone);

        // Recent average: last up to 5 daily resting readings.
        Double recentAvg = null;
        if (!daily.isEmpty()) {
            int start = Math.max(0, daily.size() - 5);
            double sum = 0;
            for (int i = start; i < daily.size(); i++) sum += daily.get(i).voltageV;
            recentAvg = sum / (daily.size() - start);
        }
        Double delta = (baseline != null && recentAvg != null) ? (recentAvg - baseline) : null;

        // Rule evaluation (highest severity wins).
        Health health;
        if (fault) {
            health = Health.FAULT;
        } else if (resting.isEmpty() && latestReady == null) {
            health = Health.UNKNOWN;
        } else {
            boolean weak = false;
            int weakCount = 0;
            for (int i = Math.max(0, daily.size() - 5); i < daily.size(); i++) {
                if (daily.get(i).voltageV < WEAK_RESTING_LIMIT_V) weakCount++;
            }
            if (weakCount >= 2) weak = true;
            if (!wakeSags.isEmpty() && wakeSags.get(wakeSags.size() - 1).voltageV < WEAK_WAKE_SAG_V) {
                weak = true;
            }

            boolean watch = false;
            if (daily.size() >= 3) {
                boolean last3Below = true;
                for (int i = daily.size() - 3; i < daily.size(); i++) {
                    if (daily.get(i).voltageV >= HEALTHY_RESTING_LIMIT_V) {
                        last3Below = false;
                        break;
                    }
                }
                if (last3Below) watch = true;
            }
            if (delta != null && delta <= -BASELINE_DROP_WATCH_V) {
                watch = true;
            }

            health = weak ? Health.WEAK : (watch ? Health.WATCH : Health.GOOD);
        }

        return new Summary(health, latestResting, latestReady, baseline, delta, trend, batteryReplacedAtMs);
    }

    private static boolean isChargingNow() {
        CarActor a = CarActor.get();
        if (a == null) return false;
        CarActor.Reading r = a.get("car.is_charging");
        return r != null && r.status == CarActor.Reading.Status.OK
            && r.value instanceof Number && ((Number) r.value).intValue() != 0;
    }
}
