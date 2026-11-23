package com.geely.drivemem.sensors;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;

/** High-frequency continuous energy integrator.
 *
 * Decomposes real-time electrical power from the battery BMS (via Obd2Reader)
 * into three distinct metrics:
 * 1. Power (net energy: kWh = spent - regen; net power: kW)
 * 2. Power spent (gross positive energy consumed: kWh >= 0; positive power: kW)
 * 3. Regen (energy recovered via regenerative braking: kWh >= 0; regen power: kW)
 *
 * Integrates power trapezoidally on every incoming OBD2 reading (~2s cadence)
 * rather than point-sampling or averaging across the telemetry tick (CarActor.TICK_INTERVAL_MS).
 * Accumulates exact window energy for telemetry_sample persistence, as well as
 * continuous trip totals for TripSession.
 */
public final class EnergyIntegrator {
    static final String TAG = CarAccess.TAG;

    // Reject gaps longer than 10 seconds (e.g. system suspend, app pause, or BT disconnect)
    // from integrating ghost energy.
    public static final long MAX_GAP_MS = 10_000;

    private static final Object LOCK = new Object();
    private static volatile boolean subscribed = false;

    /** Where a power sample came from. Tier: CAN, then OBD2, then the SoC
     * estimate (which never reaches onPowerReading: it is drainWindow's
     * fallback). Both measured sources read the same thing, battery V x I. */
    public static final int SOURCE_OBD2 = 1;
    public static final int SOURCE_CAN = 2;

    // Most recent sample state
    private static long lastSampleMonoMs = 0;
    private static Double lastPowerKw = null;
    private static int lastSource = 0;
    // CAN's drive and climate values at the last sample (raw signals; null =
    // unknown, counted as 0 kW). Only CAN samples carry them.
    private static Double lastDriveKw = null;
    private static Double lastClimateKw = null;
    private static double lastFanKw = 0;

    // Recent samples, for meanPowerKw (ABRP). Every sample, charging too.
    static final int MEAN_RING = 256;
    private static final long[] meanT = new long[MEAN_RING];
    private static final double[] meanP = new double[MEAN_RING];
    private static int meanHead = 0, meanCount = 0;
    /** The last sample is held at most this long when computing a mean. */
    static final long MEAN_HOLD_MS = 3_000;

    // Window accumulators (drained on each telemetry tick by Telemetry / TelemetrySampler)
    private static double windowSpentKwh = 0.0;
    private static double windowRegenKwh = 0.0;
    private static double windowNetKwh = 0.0;
    private static long windowDurationMs = 0;
    private static int windowSampleCount = 0;
    private static long windowStartMonoMs = 0;

    // Active trip accumulators (managed by TripSession)
    private static boolean tripActive = false;
    private static double tripSpentKwh = 0.0;
    private static double tripRegenKwh = 0.0;
    private static double tripNetKwh = 0.0;
    private static int tripSampleCount = 0;
    // Where the trip's energy went. All from the SAME CAN samples, so the
    // parts add up whatever the trip's own source is (CAN, or the dongle
    // while CAN dropped out): battery net over those samples, drive motor
    // out and back, the raw climate signal and how long it ran. Valid when
    // CAN covered at least SPLIT_MIN_COVERAGE of the integrated time and
    // nothing was seeded.
    static final double SPLIT_MIN_COVERAGE = 0.9;
    private static double tripCanNetKwh = 0.0;
    private static long tripMeasuredMs = 0;
    private static long tripCanMs = 0;
    private static double tripDriveSpentKwh = 0.0;
    private static double tripDriveRegenKwh = 0.0;
    private static double tripClimateRawKwh = 0.0;
    private static double tripClimateOnMs = 0.0;
    private static double tripFanKwh = 0.0;
    /** Time covered by seedTrip (recovery after a restart), not by samples.
     * Long.MAX_VALUE when unknown: then the split is never valid. */
    private static long tripSeededMs = 0;

    /** Snapshot of energy accumulated over a sampling window (e.g. CarActor.TICK_INTERVAL_MS). */
    public static final class WindowSnapshot {
        public final Double instantPowerKw;
        public final Double instantPowerSpentKw;
        public final Double instantPowerRegenKw;
        public final double spentKwh;
        public final double regenKwh;
        public final double netKwh;
        public final int sampleCount;
        public final double avgPowerKw;

        public WindowSnapshot(Double instantPowerKw, Double instantPowerSpentKw, Double instantPowerRegenKw,
                              double spentKwh, double regenKwh, double netKwh,
                              int sampleCount, double avgPowerKw) {
            this.instantPowerKw = instantPowerKw;
            this.instantPowerSpentKw = instantPowerSpentKw;
            this.instantPowerRegenKw = instantPowerRegenKw;
            this.spentKwh = spentKwh;
            this.regenKwh = regenKwh;
            this.netKwh = netKwh;
            this.sampleCount = sampleCount;
            this.avgPowerKw = avgPowerKw;
        }
    }

    /** Snapshot of energy accumulated across an entire trip. */
    public static final class TripSnapshot {
        public final double spentKwh;
        public final double regenKwh;
        public final double netKwh;
        public final int sampleCount;
        /** True when CAN covered the trip well enough for the split below. */
        public final boolean splitValid;
        /** Battery net over the CAN samples the split was taken from. */
        public final double canNetKwh;
        public final double driveSpentKwh;
        public final double driveRegenKwh;
        /** Integral of the RAW climate signal; see ClimatePower. */
        public final double climateRawKwh;
        public final double climateOnSeconds;
        /** The fan (FanPower), kWh. Part of climate. */
        public final double fanKwh;
        /** Why there is no split (null when there is one), for can-events.log. */
        public final String splitWhy;

        public TripSnapshot(double spentKwh, double regenKwh, double netKwh, int sampleCount) {
            this(spentKwh, regenKwh, netKwh, sampleCount, false, 0, 0, 0, 0, 0, 0);
        }

        public TripSnapshot(double spentKwh, double regenKwh, double netKwh, int sampleCount,
                            boolean splitValid, double canNetKwh, double driveSpentKwh, double driveRegenKwh,
                            double climateRawKwh, double climateOnSeconds, double fanKwh) {
            this(spentKwh, regenKwh, netKwh, sampleCount, splitValid ? null : "no split", canNetKwh,
                driveSpentKwh, driveRegenKwh, climateRawKwh, climateOnSeconds, fanKwh);
        }

        /** {@code splitWhy} null = the split is valid. */
        public TripSnapshot(double spentKwh, double regenKwh, double netKwh, int sampleCount,
                            String splitWhy, double canNetKwh, double driveSpentKwh, double driveRegenKwh,
                            double climateRawKwh, double climateOnSeconds, double fanKwh) {
            this.splitWhy = splitWhy;
            boolean splitValid = splitWhy == null;
            this.spentKwh = spentKwh;
            this.regenKwh = regenKwh;
            this.netKwh = netKwh;
            this.sampleCount = sampleCount;
            this.splitValid = splitValid;
            this.canNetKwh = canNetKwh;
            this.driveSpentKwh = driveSpentKwh;
            this.driveRegenKwh = driveRegenKwh;
            this.climateRawKwh = climateRawKwh;
            this.climateOnSeconds = climateOnSeconds;
            this.fanKwh = fanKwh;
        }

        /** Climate's real energy (ClimatePower's fit, plus the fan), kWh. */
        public double climateKwh() { return ClimatePower.correctedKwh(climateRawKwh, climateOnSeconds) + fanKwh; }
    }

    /** Subscribes to CanEnergyReader and Obd2Reader updates (idempotent). */
    public static void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        synchronized (LOCK) {
            if (subscribed) return;
            subscribed = true;
            windowStartMonoMs = nowMono();
            // CAN (roadcastd) first. While the hub's energy.source is "can"
            // the OBD2 readings are dropped (onObd2Power), so the two never
            // integrate the same energy twice.
            CanEnergyReader.setPowerListener((now, kw, drive, climate) ->
                onPowerReading(now, kw, SOURCE_CAN, drive, climate, fanKwNow()));
            Obd2Reader.subscribe(new Obd2Reader.Listener() {
                @Override public void onObd2ConnectedChanged(boolean connected) {
                    if (!connected) {
                        synchronized (LOCK) {
                            lastSampleMonoMs = 0;
                            lastPowerKw = null;
                            lastDriveKw = null;
                            lastClimateKw = null;
                        }
                    }
                }

                @Override public void onObd2Reading(Obd2Reader.Reading r) {
                    if (r != null && r.powerKw != null) {
                        onObd2Power(nowMono(), r.powerKw, com.geely.drivemem.car.CarActor.get());
                    }
                }
            });
        }
    }

    /** One OBD2 power reading. Dropped only while CAN is the measured source
     * by EnergyHub's own choice (CAN live AND with a power value). The gate
     * used to be "the CAN reader has a heartbeat": with the daemon up but no
     * usable volts or amps, CAN gave no sample and the OBD2 ones were thrown
     * away, so trip energy fell back to the estimate while energy.source
     * said obd2. */
    static void onObd2Power(long nowMonoMs, double kw, com.geely.drivemem.car.CarActor hub) {
        if (EnergyHub.canIsSource(hub)) return;
        onPowerReading(nowMonoMs, kw, SOURCE_OBD2);
    }

    /** Ingests a power reading (kW) and performs trapezoidal integration.
     * Accessible package-private / for testing. */
    public static void onPowerReading(long nowMonoMs, double kw) {
        onPowerReading(nowMonoMs, kw, SOURCE_OBD2);
    }

    /** As above, tagged with its source. A sample from a different source
     * than the previous one starts a new trapezoid instead of joining the
     * two: they are sampled on different clocks, so a step between them
     * (CAN dropping out, OBD2 taking over) is not a real power ramp. */
    public static void onPowerReading(long nowMonoMs, double kw, int source) {
        onPowerReading(nowMonoMs, kw, source, null, null, 0);
    }

    /** The fan's power now (FanPower, from the hub's car.hvac_fan). One map
     * read: the hub cache is concurrent. */
    static double fanKwNow() {
        com.geely.drivemem.car.CarActor a = com.geely.drivemem.car.CarActor.get();
        if (a == null) return 0;
        com.geely.drivemem.car.CarActor.Reading r = a.get(com.geely.drivemem.car.CarActor.HVAC_FAN);
        return r.status == com.geely.drivemem.car.CarActor.Reading.Status.OK && r.value instanceof Number
            ? FanPower.kw(((Number) r.value).intValue()) : 0;
    }

    /** As above, with CAN's raw drive and climate kW at the same moment
     * (null for OBD2 samples, or when CAN has no value), and the fan's kW. */
    public static void onPowerReading(long nowMonoMs, double kw, int source, Double driveKw, Double climateKw,
                                      double fanKw) {
        synchronized (LOCK) {
            if (source != lastSource) {
                lastSampleMonoMs = 0;
                lastPowerKw = null;
                lastDriveKw = null;
                lastClimateKw = null;
                lastFanKw = 0;
                lastSource = source;
                meanCount = 0;
            }
            addMeanSample(nowMonoMs, kw);
            // Rule: only suppress energy integration when both parked and actively charging.
            // Invariant: driving energy must never be suppressed by a lingering charging state.
            // ChargeSession.isCharging() tracks active session state (fed by CarActor's
            // cross-checked poll and plug/gear events), but gating on CarState.isParked()
            // ensures a drive's consumption is never zeroed out if a sensor or state latches.
            // See docs/incidents.md#2026-09-14-charge-a-latch
            if (com.geely.drivemem.state.CarState.isParked()
                    && com.geely.drivemem.state.ChargeSession.isCharging()) {
                lastSampleMonoMs = 0;
                lastPowerKw = null;
                lastDriveKw = null;
                lastClimateKw = null;
                return;
            }

            if (lastSampleMonoMs > 0 && lastPowerKw != null) {
                long deltaMs = nowMonoMs - lastSampleMonoMs;
                if (deltaMs > 0 && deltaMs <= MAX_GAP_MS) {
                    double hours = deltaMs / 3_600_000.0;
                    double p0 = lastPowerKw;
                    double p1 = kw;
                    double[] step = trapezoid(p0, p1, hours);
                    double stepSpentKwh = step[0];
                    double stepRegenKwh = step[1];

                    double stepNetKwh = stepSpentKwh - stepRegenKwh;

                    windowSpentKwh += stepSpentKwh;
                    windowRegenKwh += stepRegenKwh;
                    windowNetKwh += stepNetKwh;
                    windowDurationMs += deltaMs;
                    windowSampleCount++;

                    // A trip stays "active" through TripSession's 75s park-grace
                    // period (and however much longer a flickering gear signal
                    // stretches it) so a brief stop doesn't split the trip in two —
                    // but that grace window is about NOT LOSING the trip, not about
                    // still being on the move. Gating this on CarState.isParked()
                    // (the same authoritative signal DrivingConsumption.isDriving()
                    // trusts) keeps idle/HVAC draw after the driver has actually
                    // parked out of the trip's own spent/regen totals — otherwise a
                    // short trip's efficiency reads worse the longer its parked tail
                    // happens to run, which is exactly backwards.
                    if (tripActive && !com.geely.drivemem.state.CarState.isParked()) {
                        tripSpentKwh += stepSpentKwh;
                        tripRegenKwh += stepRegenKwh;
                        tripNetKwh += stepNetKwh;
                        tripSampleCount++;
                        tripMeasuredMs += deltaMs;
                        if (source == SOURCE_CAN) {
                            tripCanMs += deltaMs;
                            tripCanNetKwh += stepNetKwh;
                            double[] d = trapezoid(orZero(lastDriveKw), orZero(driveKw), hours);
                            tripDriveSpentKwh += d[0];
                            tripDriveRegenKwh += d[1];
                            double c0 = Math.max(0, orZero(lastClimateKw)), c1 = Math.max(0, orZero(climateKw));
                            tripClimateRawKwh += (c0 + c1) / 2.0 * hours;
                            tripClimateOnMs += deltaMs * ((c0 > 0 ? 0.5 : 0) + (c1 > 0 ? 0.5 : 0));
                            tripFanKwh += (lastFanKw + fanKw) / 2.0 * hours;
                        }
                    }
                }
            }

            lastSampleMonoMs = nowMonoMs;
            lastPowerKw = kw;
            lastDriveKw = driveKw;
            lastClimateKw = climateKw;
            lastFanKw = fanKw;
        }
    }

    private static double orZero(Double d) { return d == null ? 0.0 : d; }

    /** Energy of one trapezoid step, split by sign: {spent, regen}, both
     * kWh >= 0. A step that crosses zero is cut into two triangles. */
    static double[] trapezoid(double p0, double p1, double hours) {
        double spent = 0.0, regen = 0.0;
        if (p0 >= 0.0 && p1 >= 0.0) {
            spent = ((p0 + p1) / 2.0) * hours;
        } else if (p0 <= 0.0 && p1 <= 0.0) {
            regen = ((-p0 - p1) / 2.0) * hours;
        } else {
            double range = Math.abs(p1 - p0);
            if (range > 0.0) {
                double frac = Math.abs(p0) / range;
                double h0 = hours * frac;
                double h1 = hours * (1.0 - frac);
                if (p0 > 0.0) {
                    spent = (p0 / 2.0) * h0;
                    regen = (-p1 / 2.0) * h1;
                } else {
                    regen = (-p0 / 2.0) * h0;
                    spent = (p1 / 2.0) * h1;
                }
            }
        }
        return new double[]{spent, regen};
    }

    private static void addMeanSample(long t, double kw) {
        meanT[meanHead] = t;
        meanP[meanHead] = kw;
        meanHead = (meanHead + 1) % MEAN_RING;
        if (meanCount < MEAN_RING) meanCount++;
    }

    /** Mean battery power (kW, + = out) over the last {@code windowMs}, each
     * sample held until the next one (the last for at most MEAN_HOLD_MS).
     * Charging included. Null when samples cover under half the window, so
     * the caller falls back to the instant value. For ABRP: one mean per
     * sample is a steadier calibration point than one instant reading. */
    public static Double meanPowerKw(long nowMono, long windowMs) {
        synchronized (LOCK) {
            long from = nowMono - windowMs;
            double kwMs = 0;
            long covered = 0;
            for (int k = 0; k < meanCount; k++) {
                int i = Math.floorMod(meanHead - meanCount + k, MEAN_RING);
                long t0 = meanT[i];
                long t1 = (k + 1 < meanCount) ? meanT[(i + 1) % MEAN_RING]
                    : Math.min(nowMono, t0 + MEAN_HOLD_MS);
                if (t1 - t0 > MAX_GAP_MS) t1 = t0 + MEAN_HOLD_MS;   // a gap, not a held value
                long a = Math.max(t0, from), b = Math.min(t1, nowMono);
                if (b <= a) continue;
                kwMs += meanP[i] * (b - a);
                covered += b - a;
            }
            if (covered * 2 < windowMs) return null;
            return kwMs / covered;
        }
    }

    public static Double meanPowerKw(long windowMs) { return meanPowerKw(nowMono(), windowMs); }

    public static long nowMono() {
        try {
            return SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }

    /** Drains and resets the rolling window accumulators for the telemetry tick (CarActor.TICK_INTERVAL_MS).
     * If no high-frequency OBD samples arrived during the window, falls back to the
     * provided powerKw estimate (e.g. from VHAL SOC-delta). */
    public static WindowSnapshot drainWindow(Float fallbackPowerKw) {
        return drainWindow(nowMono(), fallbackPowerKw);
    }

    /** Drains and resets the rolling window accumulators using an explicit timestamp. */
    public static WindowSnapshot drainWindow(long nowMono, Float fallbackPowerKw) {
        synchronized (LOCK) {
            long elapsedWindowMs = (windowStartMonoMs > 0) ? (nowMono - windowStartMonoMs) : 30_000;
            if (elapsedWindowMs <= 0) elapsedWindowMs = 30_000;

            Double instantKw = lastPowerKw;
            if (instantKw == null && fallbackPowerKw != null) {
                instantKw = (double) fallbackPowerKw;
            }

            Double instantSpentKw = (instantKw != null) ? Math.max(0.0, instantKw) : null;
            Double instantRegenKw = (instantKw != null) ? Math.max(0.0, -instantKw) : null;

            double outSpent = windowSpentKwh;
            double outRegen = windowRegenKwh;
            double outNet = windowNetKwh;
            int count = windowSampleCount;

            // count==0 means no full trapezoid step landed this window -- which
            // isn't only "OBD2 was unavailable." It's also what a single
            // isolated OBD reading with no predecessor to pair against looks
            // like: right after onObd2ConnectedChanged(true), right after the
            // parked+charging guard above resets lastPowerKw, or right after
            // app start. That reading is genuinely OBD2-sourced -- reaching
            // past it for the cruder VHAL SoC-delta guess just because it
            // arrived alone is exactly the "one miss taints everything"
            // behavior reported live 2026-09-23: a short trip's first window,
            // one reading in, got "estimated" though OBD2 never disconnected.
            // Prefer this single real reading (held flat across the window)
            // over the fallback, and count the window as measured -- only
            // reach for the fallback when we have NO real OBD reading at all.
            // Bounded by MAX_GAP_MS so a reading that's actually gone stale
            // (OBD2 died without a formal disconnect event, or the window
            // itself sat idle a long time) can't get held flat forever --
            // that's a real gap, not a single missed poll.
            if (count == 0 && lastPowerKw != null
                    && nowMono >= lastSampleMonoMs && (nowMono - lastSampleMonoMs) <= MAX_GAP_MS) {
                double hours = elapsedWindowMs / 3_600_000.0;
                double kw = lastPowerKw;
                if (kw >= 0) {
                    outSpent = kw * hours;
                    outRegen = 0.0;
                } else {
                    outSpent = 0.0;
                    outRegen = -kw * hours;
                }
                outNet = outSpent - outRegen;
                count = 1;
            } else if (count == 0 && fallbackPowerKw != null && elapsedWindowMs <= MAX_GAP_MS * 4) {
                // Fallback integration: OBD2 gave us nothing at all this window.
                // *4, not *3: the tick that feeds this (CarActor.TICK_INTERVAL_MS)
                // now runs at the same 30s cadence this fallback's own SoC delta
                // is sampled at, so elapsedWindowMs normally sits right at that
                // boundary -- *3 (30s) would reject a real window on nothing more
                // than ordinary scheduling jitter pushing it a few ms over. *4
                // keeps real multi-tick gaps (a suspend, a dropped tick) rejected
                // without also punishing jitter.
                double hours = elapsedWindowMs / 3_600_000.0;
                double kw = fallbackPowerKw;
                if (kw >= 0) {
                    outSpent = kw * hours;
                    outRegen = 0.0;
                } else {
                    outSpent = 0.0;
                    outRegen = -kw * hours;
                }
                outNet = outSpent - outRegen;
            }

            double hours = (elapsedWindowMs / 3_600_000.0);
            double avgKw = (hours > 0.0) ? (outNet / hours) : (instantKw != null ? instantKw : 0.0);

            WindowSnapshot snap = new WindowSnapshot(
                instantKw, instantSpentKw, instantRegenKw,
                outSpent, outRegen, outNet, count, avgKw
            );

            // Reset window accumulators for next tick
            windowSpentKwh = 0.0;
            windowRegenKwh = 0.0;
            windowNetKwh = 0.0;
            windowDurationMs = 0;
            windowSampleCount = 0;
            windowStartMonoMs = nowMono;

            return snap;
        }
    }

    /** Starts tracking energy for an active driving trip. */
    public static void startTrip() {
        synchronized (LOCK) {
            tripActive = true;
            tripSpentKwh = 0.0;
            tripRegenKwh = 0.0;
            tripNetKwh = 0.0;
            tripSampleCount = 0;
            resetTripSplit();
        }
    }

    private static void resetTripSplit() {
        tripDriveSpentKwh = 0.0;
        tripDriveRegenKwh = 0.0;
        tripClimateRawKwh = 0.0;
        tripClimateOnMs = 0.0;
        tripFanKwh = 0.0;
        tripCanNetKwh = 0.0;
        tripMeasuredMs = 0;
        tripCanMs = 0;
        tripSeededMs = 0;
    }

    private static TripSnapshot tripSnapshot() {
        return new TripSnapshot(tripSpentKwh, tripRegenKwh, tripNetKwh, tripSampleCount,
            splitWhy(tripSeededMs, tripCanMs, tripMeasuredMs), tripCanNetKwh, tripDriveSpentKwh, tripDriveRegenKwh, tripClimateRawKwh, tripClimateOnMs / 1000.0,
            tripFanKwh);
    }

    /** Null when CAN covered enough of the trip for a split, else why not:
     * the app restarted during the trip (its first part has no split), or
     * CAN covered too little of it. seededMs is Long.MAX_VALUE when a
     * restart's time is unknown. */
    static String splitWhy(long seededMs, long canMs, long measuredMs) {
        if (seededMs == Long.MAX_VALUE) return "app restarted during the trip";
        double total = (double) measuredMs + seededMs;
        if (canMs > 0 && canMs >= SPLIT_MIN_COVERAGE * total) return null;
        String cov = canMs <= 0 ? "no CAN reading"
            : String.format(java.util.Locale.US, "CAN covered %.0f %% of it (needs %.0f %%)",
                100.0 * canMs / total, 100 * SPLIT_MIN_COVERAGE);
        if (seededMs > 0) return "app restarted " + seededMs / 60_000 + " min into the trip; " + cov;
        return cov;
    }

    /** Adds already-known energy to the active trip's accumulators. For recovering
     * a trip whose in-memory totals were lost to a process restart mid-drive — the
     * caller sums the missing portion from telemetry_sample and hands it in here
     * rather than starting the trip's energy back at zero. Must be called after
     * startTrip(). */
    public static void seedTrip(double spentKwh, double regenKwh, double netKwh) {
        synchronized (LOCK) {
            tripSpentKwh += spentKwh;
            tripRegenKwh += regenKwh;
            tripNetKwh += netKwh;
            // The seeded part has no split, and its time is unknown here.
            tripSeededMs = Long.MAX_VALUE;
        }
    }

    /** As above, with how long the seeded part lasted. Its time counts as
     * time CAN did not measure, so a trip recovered early still gets a
     * split once CAN has covered SPLIT_MIN_COVERAGE of it. */
    public static void seedTrip(double spentKwh, double regenKwh, double netKwh, long seededMs) {
        seedTrip(spentKwh, regenKwh, netKwh);
        synchronized (LOCK) { tripSeededMs = Math.max(0, seededMs); }
    }

    /** The open trip's split accumulators, to save while the trip runs
     * (TripSession, every telemetry tick), so an app restart mid-trip does
     * not throw the split away. They are only in memory otherwise, and
     * telemetry_sample does not keep them: on 2026-11-22 four test installs
     * during one drive left its 18.5 kWh out of the Sankey. One line, so it
     * goes to SharedPreferences in one write. */
    public static String splitState() {
        synchronized (LOCK) {
            return encodeSplit(new double[]{tripCanNetKwh, tripDriveSpentKwh, tripDriveRegenKwh,
                tripClimateRawKwh, tripClimateOnMs, tripFanKwh, tripMeasuredMs, tripCanMs, tripSeededMs});
        }
    }

    static String encodeSplit(double[] v) {
        StringBuilder b = new StringBuilder("1");
        for (double d : v) b.append(';').append(d);
        return b.toString();
    }

    /** The values encodeSplit wrote, or null when the line is not one. */
    static double[] decodeSplit(String s) {
        if (s == null) return null;
        String[] f = s.split(";");
        if (f.length != 10 || !"1".equals(f[0])) return null;
        double[] v = new double[9];
        try {
            for (int i = 0; i < 9; i++) {
                v[i] = Double.parseDouble(f[i + 1]);
                if (!Double.isFinite(v[i])) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }

    /** After startTrip and seedTrip on recovery: puts back the split that
     * splitState saved. lostMs is the time since that save, which no sample
     * covered: it counts as time CAN did not measure, as seedTrip's does.
     * False (nothing changed) when the line is not a saved split. */
    public static boolean restoreSplit(String saved, long lostMs) {
        double[] v = decodeSplit(saved);
        if (v == null) return false;
        synchronized (LOCK) {
            tripCanNetKwh = v[0];
            tripDriveSpentKwh = v[1];
            tripDriveRegenKwh = v[2];
            tripClimateRawKwh = v[3];
            tripClimateOnMs = v[4];
            tripFanKwh = v[5];
            tripMeasuredMs = (long) v[6];
            tripCanMs = (long) v[7];
            long seeded = v[8] >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) v[8];
            tripSeededMs = seeded == Long.MAX_VALUE ? Long.MAX_VALUE : seeded + Math.max(0, lostMs);
        }
        return true;
    }

    /** Stops tracking and returns the accumulated trip energy. */
    public static TripSnapshot endTrip() {
        synchronized (LOCK) {
            tripActive = false;
            return tripSnapshot();
        }
    }

    /** Returns current trip totals while still driving. */
    public static TripSnapshot currentTrip() {
        synchronized (LOCK) {
            return tripSnapshot();
        }
    }

    /** Resets all internal state (for testing). */
    public static void resetForTesting() {
        synchronized (LOCK) {
            lastSource = 0;
            lastSampleMonoMs = 0;
            lastPowerKw = null;
            lastDriveKw = null;
            lastClimateKw = null;
            lastFanKw = 0;
            meanHead = 0;
            meanCount = 0;
            windowSpentKwh = 0.0;
            windowRegenKwh = 0.0;
            windowNetKwh = 0.0;
            windowDurationMs = 0;
            windowSampleCount = 0;
            windowStartMonoMs = 0;
            tripActive = false;
            tripSpentKwh = 0.0;
            tripRegenKwh = 0.0;
            tripNetKwh = 0.0;
            tripSampleCount = 0;
            resetTripSplit();
        }
    }

    private EnergyIntegrator() {}
}
