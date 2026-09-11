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
 * rather than point-sampling or averaging across the 15-second telemetry tick.
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

    // Most recent sample state
    private static long lastSampleMonoMs = 0;
    private static Double lastPowerKw = null;

    // Window accumulators (drained every ~15s by Telemetry / TelemetrySampler)
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

    /** Snapshot of energy accumulated over a sampling window (e.g. 15s). */
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

        public TripSnapshot(double spentKwh, double regenKwh, double netKwh, int sampleCount) {
            this.spentKwh = spentKwh;
            this.regenKwh = regenKwh;
            this.netKwh = netKwh;
            this.sampleCount = sampleCount;
        }
    }

    /** Subscribes to Obd2Reader updates (idempotent). */
    public static void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        synchronized (LOCK) {
            if (subscribed) return;
            subscribed = true;
            windowStartMonoMs = nowMono();
            Obd2Reader.subscribe(new Obd2Reader.Listener() {
                @Override public void onObd2ConnectedChanged(boolean connected) {
                    if (!connected) {
                        synchronized (LOCK) {
                            lastSampleMonoMs = 0;
                            lastPowerKw = null;
                        }
                    }
                }

                @Override public void onObd2Reading(Obd2Reader.Reading r) {
                    if (r != null && r.powerKw != null) {
                        onPowerReading(nowMono(), r.powerKw);
                    }
                }
            });
        }
    }

    /** Ingests a power reading (kW) and performs trapezoidal integration.
     * Accessible package-private / for testing. */
    public static void onPowerReading(long nowMonoMs, double kw) {
        synchronized (LOCK) {
            // If the vehicle is plugged in and actively charging, do not integrate
            // charging current as driving regenerative braking.
            if (com.geely.drivemem.state.ChargeSession.isCharging()) {
                lastSampleMonoMs = 0;
                lastPowerKw = null;
                return;
            }

            if (lastSampleMonoMs > 0 && lastPowerKw != null) {
                long deltaMs = nowMonoMs - lastSampleMonoMs;
                if (deltaMs > 0 && deltaMs <= MAX_GAP_MS) {
                    double hours = deltaMs / 3_600_000.0;
                    double p0 = lastPowerKw;
                    double p1 = kw;
                    double stepSpentKwh = 0.0;
                    double stepRegenKwh = 0.0;

                    if (p0 >= 0.0 && p1 >= 0.0) {
                        // Entire interval is positive (discharge / regular consumption)
                        stepSpentKwh = ((p0 + p1) / 2.0) * hours;
                    } else if (p0 <= 0.0 && p1 <= 0.0) {
                        // Entire interval is negative (charging / regenerative braking)
                        stepRegenKwh = ((-p0 - p1) / 2.0) * hours;
                    } else {
                        // Zero-crossing within this interval: split trapezoid into two triangles
                        double range = Math.abs(p1 - p0);
                        if (range > 0.0) {
                            double frac = Math.abs(p0) / range;
                            double h0 = hours * frac;
                            double h1 = hours * (1.0 - frac);
                            if (p0 > 0.0) {
                                stepSpentKwh = (p0 / 2.0) * h0;
                                stepRegenKwh = (-p1 / 2.0) * h1;
                            } else {
                                stepRegenKwh = (-p0 / 2.0) * h0;
                                stepSpentKwh = (p1 / 2.0) * h1;
                            }
                        }
                    }

                    double stepNetKwh = stepSpentKwh - stepRegenKwh;

                    windowSpentKwh += stepSpentKwh;
                    windowRegenKwh += stepRegenKwh;
                    windowNetKwh += stepNetKwh;
                    windowDurationMs += deltaMs;
                    windowSampleCount++;

                    if (tripActive) {
                        tripSpentKwh += stepSpentKwh;
                        tripRegenKwh += stepRegenKwh;
                        tripNetKwh += stepNetKwh;
                        tripSampleCount++;
                    }
                }
            }

            lastSampleMonoMs = nowMonoMs;
            lastPowerKw = kw;
        }
    }

    public static long nowMono() {
        try {
            return SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }

    /** Drains and resets the rolling window accumulators for the 15-second telemetry tick.
     * If no high-frequency OBD samples arrived during the window, falls back to the
     * provided powerKw estimate (e.g. from VHAL SOC-delta). */
    public static WindowSnapshot drainWindow(Float fallbackPowerKw) {
        return drainWindow(nowMono(), fallbackPowerKw);
    }

    /** Drains and resets the rolling window accumulators using an explicit timestamp. */
    public static WindowSnapshot drainWindow(long nowMono, Float fallbackPowerKw) {
        synchronized (LOCK) {
            long elapsedWindowMs = (windowStartMonoMs > 0) ? (nowMono - windowStartMonoMs) : 15_000;
            if (elapsedWindowMs <= 0) elapsedWindowMs = 15_000;

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

            // Fallback integration if OBD2 was unavailable during this entire window
            if (count == 0 && fallbackPowerKw != null && elapsedWindowMs <= MAX_GAP_MS * 3) {
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
        }
    }

    /** Stops tracking and returns the accumulated trip energy. */
    public static TripSnapshot endTrip() {
        synchronized (LOCK) {
            tripActive = false;
            return new TripSnapshot(tripSpentKwh, tripRegenKwh, tripNetKwh, tripSampleCount);
        }
    }

    /** Returns current trip totals while still driving. */
    public static TripSnapshot currentTrip() {
        synchronized (LOCK) {
            return new TripSnapshot(tripSpentKwh, tripRegenKwh, tripNetKwh, tripSampleCount);
        }
    }

    /** Resets all internal state (for testing). */
    public static void resetForTesting() {
        synchronized (LOCK) {
            lastSampleMonoMs = 0;
            lastPowerKw = null;
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
        }
    }

    private EnergyIntegrator() {}
}
