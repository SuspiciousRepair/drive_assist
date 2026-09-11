package com.geely.drivemem.sensors;

/** Driving energy and speed buckets from chronological telemetry, including traffic stops. */
public final class DrivingConsumption {
    final double[] spent = new double[4];
    final double[] regen = new double[4];
    final double[] distance = new double[4];
    double totalSpent;
    double totalRegen;
    private double previousOdo = Double.NaN;
    private long previousLegacyTs = -1;

    /** Missing gear only qualifies when speed proves movement; zero speed alone is ambiguous. */
    static boolean isDriving(Integer gear, double speed, boolean charging) {
        if (charging || (gear != null && gear == 4)) return false;
        return (gear != null && (gear == 1 || gear == 2 || gear == 8)) || speed > 0;
    }

    void add(long ts, double odo, double speed, Integer gear, boolean charging,
             double spentKwh, double regenKwh, double powerKw) {
        boolean driving = isDriving(gear, speed, charging);
        boolean direct = Double.isFinite(spentKwh) && Double.isFinite(regenKwh);
        boolean legacy = !direct && Double.isFinite(powerKw);
        boolean hasEnergy = direct;
        if (driving && legacy && previousLegacyTs >= 0) {
            long elapsed = ts - previousLegacyTs;
            if (elapsed > 0 && elapsed <= 60_000) {
                double energy = powerKw * elapsed / 3_600_000.0;
                spentKwh = Math.max(0, energy);
                regenKwh = Math.max(0, -energy);
                hasEnergy = true;
            }
        }
        // Never integrate across a parked/charging row or a direct-energy interval.
        previousLegacyTs = driving && legacy ? ts : -1;
        if (driving && hasEnergy) {
            totalSpent += spentKwh;
            totalRegen += regenKwh;
            if (Double.isFinite(speed) && speed >= 0) {
                int bucket = speed < 40 ? 0 : speed < 80 ? 1 : speed < 120 ? 2 : 3;
                double delta = odo - previousOdo;
                if (Double.isFinite(odo) && odo > 0 && delta >= 0 && delta < 50) {
                    distance[bucket] += delta;
                }
                spent[bucket] += spentKwh;
                regen[bucket] += regenKwh;
            }
        }
        // Keep parked baselines so distance is not bridged over excluded samples.
        if (Double.isFinite(odo) && odo > 0) previousOdo = odo;
    }

    static double per100km(double spent, double regen, double km) {
        return km > 0.2 ? Math.max(0, spent - regen) / km * 100 : 0;
    }
}
