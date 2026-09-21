package com.geely.drivemem.sensors;

import java.util.List;

/** Whether a stretch of energy data came from the OBD2 dongle (measured),
 * the VHAL SoC-delta fallback (estimated), a blend of both, or no telemetry
 * at all. Lives here, not nested in DailyStatsProvider, so state.TripSession
 * can use the same classification without state gaining a dependency back
 * onto sensors.DailyStatsProvider -- it already depends one-way on sensors
 * via EnergyIntegrator/GpsReader. */
public enum EnergySource {
    MEASURED, MIXED, ESTIMATED, NO_DATA;

    /** Classifies a stretch of samples from how many were measured vs estimated. */
    public static EnergySource resolve(long measuredSamples, long estimatedSamples) {
        if (measuredSamples <= 0 && estimatedSamples <= 0) return NO_DATA;
        if (estimatedSamples <= 0) return MEASURED;
        if (measuredSamples <= 0) return ESTIMATED;
        return MIXED;
    }

    /** Folds several already-resolved states (e.g. one per day, for a
     * Week/Month) into one. NO_DATA entries don't drag a real day into
     * MIXED -- a day with zero telemetry says nothing about data quality. */
    public static EnergySource combine(List<EnergySource> states) {
        boolean sawMeasured = false, sawEstimated = false, sawMixed = false;
        for (EnergySource s : states) {
            if (s == MIXED) sawMixed = true;
            else if (s == MEASURED) sawMeasured = true;
            else if (s == ESTIMATED) sawEstimated = true;
        }
        if (sawMixed || (sawMeasured && sawEstimated)) return MIXED;
        if (sawMeasured) return MEASURED;
        if (sawEstimated) return ESTIMATED;
        return NO_DATA;
    }
}
