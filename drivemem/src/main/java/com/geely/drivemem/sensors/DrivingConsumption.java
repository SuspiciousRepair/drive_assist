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
    long measuredSamples;
    long estimatedSamples;

    /** Whether the energy tallied so far came from OBD2, the VHAL SoC-delta
     * estimate, a mix, or no samples with a known source at all (e.g. rows
     * from before the energy_measured column existed). */
    public EnergySource energySource() { return EnergySource.resolve(measuredSamples, estimatedSamples); }

    /** Missing gear only qualifies when speed proves movement; zero speed alone is ambiguous.
     * Gear wins over the charging flag whenever gear is actually known: is_charging is
     * derived from a car property (charge_a) that has been observed to latch at its last
     * reading for hours, including straight through an entire drive (2026-09-14) — trusting
     * it over gear=8 zeroed out real trips' consumption entirely. charging is only the
     * decider when gear itself is missing, the one case a stuck charging read is still the
     * best available signal for "probably parked." */
    static boolean isDriving(Integer gear, double speed, boolean charging) {
        if (gear != null) {
            if (gear == 4) return false;
            return gear == 1 || gear == 2 || gear == 8 || speed > 0;
        }
        if (charging) return false;
        return speed > 0;
    }

    void add(long ts, double odo, double speed, Integer gear, boolean charging,
             double spentKwh, double regenKwh, double powerKw) {
        add(ts, odo, speed, gear, charging, spentKwh, regenKwh, powerKw, null);
    }

    /** Same as the 8-arg add(), plus a per-row measured/estimated tally.
     * energyMeasured mirrors telemetry_sample.energy_measured: 1 = OBD2,
     * 0 = VHAL SoC-delta estimate, null = unknown (e.g. a pre-migration row).
     * Public (not package-private) so CarDb's v22 migration can reuse the
     * exact same driving/hasEnergy accumulation TelemetryRollup uses,
     * instead of a second, drifting copy of that logic in raw SQL, when
     * repairing an already-frozen daily_stat.energy_source after fixing
     * mislabeled telemetry_sample rows. */
    public void add(long ts, double odo, double speed, Integer gear, boolean charging,
             double spentKwh, double regenKwh, double powerKw, Integer energyMeasured) {
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
            if (energyMeasured != null) {
                if (energyMeasured != 0) measuredSamples++;
                else estimatedSamples++;
            }
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

    /** Headline efficiency (kWh/100km) for a drive: net energy (spent minus
     * regen) when it's a positive load, falling back to gross energy spent
     * when net isn't (a regen-heavy trip shouldn't read as "0" consumption).
     * 0 below the 200m qualifying-distance floor.
     *
     * The one formula behind the day overview, every completed trip row, the
     * still-driving trip on the daily stats page, and the live journey card
     * on the home screen -- consolidated 2026-09-13 after it had drifted
     * into four separate copies, one of them (the journey card) missing the
     * regen netting entirely and overstating consumption on any trip with
     * real regen braking. Deliberately NOT the same formula as per100km()
     * above: that one backs the speed-bucket chart, which clamps a
     * net-negative bucket to a plain 0 rather than falling back to gross --
     * a different, equally deliberate choice for a different display.
     */
    public static double efficiencyKwh100km(double distanceKm, double spentKwh, double regenKwh) {
        if (distanceKm <= 0.2) return 0;
        double netKwh = spentKwh - regenKwh;
        if (netKwh > 0) return netKwh / distanceKm * 100.0;
        return spentKwh > 0 ? spentKwh / distanceKm * 100.0 : 0;
    }
}
