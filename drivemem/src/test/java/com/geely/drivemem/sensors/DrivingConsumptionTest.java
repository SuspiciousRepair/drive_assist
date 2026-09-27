package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarDb;
import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingConsumptionTest {

    private static final double NaN = Double.NaN;

    // ── existing tests ────────────────────────────────────────────────────────

    @Test public void parkedAcExcludedButTrafficStopsCount() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 0, 4, false, 2, 0, NaN);
        c.add(15000, 101, 20, 8, false, .2, 0, NaN);
        c.add(30000, 101, 0, 8, false, .1, 0, NaN);
        c.add(45000, 102, 10, 8, false, .2, .05, NaN);
        c.add(60000, 102, 0, 4, false, 3, 0, NaN);
        assertEquals(.5, c.totalSpent, 1e-9);
        assertEquals(2, c.distance[0], 1e-9);
        assertEquals(22.5, DrivingConsumption.per100km(c.spent[0], c.regen[0], c.distance[0]), 1e-9);
    }

    // Charging no longer breaks the legacy bridge when gear says driving (see
    // isDriving()'s own comment: is_charging can latch for hours, including
    // through an entire real drive, on 2026-09-14 it zeroed out two trips'
    // consumption outright). Only a real Park row still breaks it.
    @Test public void legacyBridgesThroughAStaleChargingFlagButNotPark() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 0, 8, false, NaN, NaN, 12);
        c.add(15000, 100, 0, 8, false, NaN, NaN, 12);
        assertEquals(.05, c.totalSpent, 1e-9);
        c.add(20000, 100, 0, 4, false, NaN, NaN, 12);   // Park: breaks the bridge
        c.add(30000, 100, 0, 8, false, NaN, NaN, 12);   // back to Drive: no prior ts, no integration yet
        c.add(35000, 100, 0, 8, true, NaN, NaN, 12);    // gear still says driving — charging is ignored
        c.add(40000, 100, 0, 8, false, NaN, NaN, 12);
        c.add(45000, 100, 0, 8, false, .1, 0, 12);
        c.add(50000, 100, 0, 8, false, NaN, NaN, 12);
        double expected = .05 + 12.0 * 5000 / 3_600_000.0 + 12.0 * 5000 / 3_600_000.0 + .1;
        assertEquals(expected, c.totalSpent, 1e-9);
    }

    @Test public void unknownStationaryGearIsNotAssumedDriving() {
        assertFalse(DrivingConsumption.isDriving(null, 0, false));
        assertTrue(DrivingConsumption.isDriving(null, 10, false));
        assertFalse(DrivingConsumption.isDriving(4, 10, false));
        assertTrue(DrivingConsumption.isDriving(8, 0, false));
        // Gear wins over charging whenever gear is known — see isDriving()'s
        // own comment for why trusting charging here zeroed out real trips.
        assertTrue(DrivingConsumption.isDriving(8, 0, true));
        // Charging only matters as a fallback when gear itself is unknown.
        assertFalse(DrivingConsumption.isDriving(null, 0, true));
    }

    @Test public void legacyGapsAndOdometerStartupDoNotInflateConsumption() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 20, 8, false, NaN, NaN, 12);
        c.add(120000, 101, 20, 8, false, NaN, NaN, 12);
        assertEquals(0, c.totalSpent, 0);
        c.add(135000, 0, 20, 8, false, .1, 0, 12);
        c.add(150000, 102, 20, 8, false, .1, 0, 12);
        assertEquals(1, c.distance[0], 1e-9);
    }

    // ── new regression tests ──────────────────────────────────────────────────

    /** Legacy-only trips integrate consecutive power samples. */
    @Test public void legacyOnlyTripIntegratesAllIntervals() {
        DrivingConsumption c = new DrivingConsumption();
        // 3 kW constant for three 30 s intervals → 3 × (3 kW × 30 s / 3600) = 0.075 kWh
        c.add(     0, 100, 20, 8, false, NaN, NaN, 3.0);
        c.add( 30000, 101, 20, 8, false, NaN, NaN, 3.0);
        c.add( 60000, 102, 20, 8, false, NaN, NaN, 3.0);
        c.add( 90000, 103, 20, 8, false, NaN, NaN, 3.0);
        double expected = 3 * (3.0 * 30_000.0 / 3_600_000.0);
        assertEquals(expected, c.totalSpent, 1e-9);
        assertEquals(3.0, c.distance[0], 1e-9);  // 3 km in 0-40 bucket
    }

    /** A single trip may contain legacy power rows followed by direct energy rows. */
    @Test public void mixedLegacyThenModernTrip() {
        DrivingConsumption c = new DrivingConsumption();
        // Legacy portion (powerKw only): 2 kW × 30 s
        double legacyKwh = 2.0 * 30_000.0 / 3_600_000.0;
        c.add(     0, 100, 20, 8, false, NaN, NaN, 2.0);
        c.add( 30000, 101, 20, 8, false, NaN, NaN, 2.0); // +legacyKwh
        // Modern portion (direct energy, breaks legacy chain):
        c.add( 45000, 102, 20, 8, false, 0.10, 0.0, NaN);
        c.add( 60000, 103, 20, 8, false, 0.20, 0.0, NaN);
        assertEquals(legacyKwh + 0.30, c.totalSpent, 1e-9);
        assertEquals(3.0, c.distance[0], 1e-9); // 3 km total in 0-40 bucket
    }

    /** Park drain at trip boundaries must not enter driving energy totals. */
    @Test public void parkDrainBeforeAndAfterTripExcluded() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(     0, 100,  0, 4, false, 0.50, 0.0, NaN); // parked drain — excluded
        c.add( 30000, 101, 20, 8, false, 0.20, 0.0, NaN); // driving
        c.add( 60000, 102, 20, 8, false, 0.30, 0.1, NaN); // driving with regen
        c.add( 90000, 102,  0, 4, false, 0.40, 0.0, NaN); // parked drain — excluded
        assertEquals(0.50, c.totalSpent, 1e-9);
        assertEquals(0.10, c.totalRegen, 1e-9);
    }

    /** sum(spent[bucket]) must equal totalSpent for any sequence of samples.
     *  This invariant is required for the hero kWh/100km (weighted bucket average)
     *  to always equal the per-day total efficiency — the "hero ≤ bars" guarantee. */
    @Test public void bucketSumEqualsTotal() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(     0, 100,  20, 8, false, 0.10, 0.00, NaN); // 0-40
        c.add( 15000, 101,  60, 8, false, 0.20, 0.00, NaN); // 40-80
        c.add( 30000, 102, 100, 8, false, 0.30, 0.05, NaN); // 80-120
        c.add( 45000, 103, 130, 8, false, 0.40, 0.02, NaN); // 120+
        double bucketSpent = 0, bucketRegen = 0;
        for (int i = 0; i < 4; i++) { bucketSpent += c.spent[i]; bucketRegen += c.regen[i]; }
        assertEquals(c.totalSpent, bucketSpent, 1e-9);
        assertEquals(c.totalRegen, bucketRegen, 1e-9);
    }

    /** Missing gear at zero speed is ambiguous and must not be classified as driving. */
    @Test public void missingGearAtZeroSpeedIsExcluded() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 0, null, false, 0.5, 0, NaN);
        assertEquals(0, c.totalSpent, 0);
    }

    /** Legacy regen: negative powerKw accumulates into totalRegen.
     *  The integrator uses each sample's powerKw for the interval that ENDS at that
     *  sample, so the first sample only anchors the chain (no energy from it).
     *  Both [0,15s] and [15s,30s] intervals use -2 kW → all regen, zero spent. */
    @Test public void legacyNegativePowerCountsAsRegen() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(    0, 100, 20, 8, false, NaN, NaN,  4.0); // anchor, no energy produced yet
        c.add(15000, 101, 10, 8, false, NaN, NaN, -2.0); // interval [0→15s] at -2kW → regen
        c.add(30000, 102, 10, 8, false, NaN, NaN, -2.0); // interval [15→30s] at -2kW → regen
        assertEquals(0.0, c.totalSpent, 1e-9);
        double regen = 2 * (2.0 * 15_000.0 / 3_600_000.0); // two intervals × 2 kW × 15 s
        assertEquals(regen, c.totalRegen, 1e-9);
    }

    // ── energySource() tally, via the 9-arg add() overload ──────────────────────

    @Test public void energySourceIsMeasuredWhenAllRowsAreObd2() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 20, 8, false, .1, 0, NaN, 1);
        c.add(15000, 101, 20, 8, false, .1, 0, NaN, 1);
        assertEquals(EnergySource.MEASURED, c.energySource());
    }

    @Test public void energySourceIsEstimatedWhenAllRowsAreSocDelta() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 20, 8, false, .1, 0, NaN, 0);
        c.add(15000, 101, 20, 8, false, .1, 0, NaN, 0);
        assertEquals(EnergySource.ESTIMATED, c.energySource());
    }

    @Test public void energySourceIsMixedWhenBothKindsOfRowAppear() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 20, 8, false, .1, 0, NaN, 1);
        c.add(15000, 101, 20, 8, false, .1, 0, NaN, 0);
        assertEquals(EnergySource.MIXED, c.energySource());
    }

    // A row in a driving gear but stopped (e.g. traffic light), where OBD2
    // didn't answer for that instant and the SoC-delta fallback reported
    // exactly zero, isn't an estimate of anything -- nothing moved. Caught
    // 2026-09-24: a real day with 617/619 samples cleanly OBD2-measured
    // still resolved MIXED purely from 2 rows like this one.
    @Test public void energySourceExcludesZeroEnergyEstimatedRows() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 20, 8, false, .1, 0, NaN, 1);       // measured, real energy
        c.add(15000, 100, 0, 8, false, 0, 0, NaN, 0);      // estimated, but zero -- excluded
        assertEquals(EnergySource.MEASURED, c.energySource());
    }

    // A null energy_measured is a pre-migration row: real spent/regen energy
    // exists, but nothing recorded which source it came from. Must resolve
    // NO_DATA, not get miscounted as either measured or estimated.
    @Test public void energySourceIsNoDataForLegacyRowsWithNoRecordedSource() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 20, 8, false, .1, 0, NaN, null);
        c.add(15000, 101, 20, 8, false, .1, 0, NaN, null);
        assertEquals(EnergySource.NO_DATA, c.energySource());
        assertEquals(.2, c.totalSpent, 1e-9); // energy itself is still tallied
    }

    // Matches totalSpent/totalRegen's own gating: a parked row contributes
    // nothing to the tally either, even if it claims to be measured.
    @Test public void energySourceTallyExcludesParkedRows() {
        DrivingConsumption c = new DrivingConsumption();
        c.add(0, 100, 0, 4, false, 2, 0, NaN, 1); // parked (gear 4): excluded
        assertEquals(EnergySource.NO_DATA, c.energySource());
    }

    // ── Predicate agreement tests (SQL constants vs pure Java logic) ─────────────

    /**
     * Exercises DrivingConsumption.isDriving() against a table of (gear, is_charging)
     * input combinations to verify agreement with CarDb.DRIVING_ROW_SQL:
     *   (CASE WHEN gear IS NOT NULL THEN gear <> 4
     *         ELSE (is_charging IS NULL OR is_charging = 0) END)
     *
     * In both implementations:
     * 1. Gear wins whenever known: Park (4) is false; driving gears (1, 2, 8)
     *    are true even if is_charging is true (recovering from stale charging latch).
     * 2. Charging flag is the fallback when gear is missing: charging is false.
     * 3. For moving telemetry (speed > 0), missing gear without charging is true.
     *
     * Note: CarDb.DRIVING_ROW_SQL is expected to implement this exact truth table for
     * queries over telemetry_sample rows, so any future change to one prompts checking the other.
     */
    @Test public void drivingPredicateAgreesWithSqlTruthTable() {
        assertNotNull(CarDb.DRIVING_ROW_SQL);

        // Pure (gear, is_charging) truth table matching CarDb.DRIVING_ROW_SQL:
        //   (CASE WHEN gear IS NOT NULL THEN gear <> 4
        //         ELSE (is_charging IS NULL OR is_charging = 0) END)
        Object[][] sqlTable = {
            {4, false, false, "Park (4) not charging -> false"},
            {4, true, false, "Park (4) charging -> false"},
            {8, false, true, "Drive (8) not charging -> true"},
            {8, true, true, "Drive (8) charging (stale latch) -> true"},
            {2, false, true, "Reverse (2) not charging -> true"},
            {2, true, true, "Reverse (2) charging (stale latch) -> true"},
            {1, false, true, "Neutral (1) not charging -> true"},
            {1, true, true, "Neutral (1) charging (stale latch) -> true"},
            {null, true, false, "Unknown gear, charging -> false"},
            {null, false, true, "Unknown gear, not charging -> true"},
            {null, null, true, "Unknown gear, null charging -> true"},
        };

        for (Object[] row : sqlTable) {
            Integer gear = (Integer) row[0];
            Boolean charging = (Boolean) row[1];
            boolean expected = (Boolean) row[2];
            String desc = (String) row[3];

            assertEquals("isDriving(gear, charging) failed for: " + desc,
                expected, DrivingConsumption.isDriving(gear, charging));
            if (charging != null) {
                assertEquals("isDriving(gear, boolean) failed for: " + desc,
                    expected, DrivingConsumption.isDriving(gear, charging.booleanValue()));
            }
        }

        // Telemetry-level (gear, charging, speed) table matching 3-arg isDriving()
        Object[][] speedTable = {
            // Park (4): false regardless of charging flag or speed
            {4, false, 0.0, false, "Park stationary -> not driving"},
            {4, false, 20.0, false, "Park with speed -> not driving"},
            {4, true, 0.0, false, "Park charging -> not driving"},
            {4, true, 20.0, false, "Park charging with speed -> not driving"},

            // Drive (8): true even if stationary (traffic stop) or charging latch is stuck
            {8, false, 0.0, true, "Drive stopped in traffic -> driving"},
            {8, false, 50.0, true, "Drive cruising -> driving"},
            {8, true, 0.0, true, "Drive with stuck charging flag (stopped) -> driving"},
            {8, true, 50.0, true, "Drive with stuck charging flag (moving) -> driving"},

            // Reverse (2): true
            {2, false, 5.0, true, "Reverse moving -> driving"},
            {2, true, 5.0, true, "Reverse with stuck charging flag -> driving"},

            // Neutral (1): true
            {1, false, 0.0, true, "Neutral stopped -> driving"},
            {1, false, 30.0, true, "Neutral coasting -> driving"},
            {1, true, 0.0, true, "Neutral stopped with stuck charging flag -> driving"},

            // Unknown gear (null): charging flag acts as fallback
            {null, true, 0.0, false, "Unknown gear, charging -> not driving"},
            {null, true, 30.0, false, "Unknown gear, charging with speed -> not driving"},
            {null, false, 20.0, true, "Unknown gear, not charging, moving -> driving"},
            {null, false, 0.0, false, "Unknown gear, not charging, stationary -> not driving (ambiguous)"},
        };

        for (Object[] row : speedTable) {
            Integer gear = (Integer) row[0];
            boolean charging = (Boolean) row[1];
            double speed = (Double) row[2];
            boolean expected = (Boolean) row[3];
            String desc = (String) row[4];

            assertEquals("isDriving(gear, speed, charging) failed for: " + desc,
                expected, DrivingConsumption.isDriving(gear, speed, charging));
        }
    }

    /**
     * Confirms the measured (vs estimated) row classification logic applied in
     * DailyStatsProvider.queryDrivingConsumption agrees with CarDb.MEASURED_ROW_SQL:
     *   (energy_measured = 1 OR battery_temp_c IS NOT NULL)
     * and CarDb.ESTIMATED_ROW_SQL:
     *   (energy_measured = 0 AND battery_temp_c IS NULL)
     *
     * battery_temp_c is OBD2-exclusive, so its presence overrides a missing or zero
     * energy_measured flag, repairing mislabeled rows on read.
     *
     * Note: CarDb.MEASURED_ROW_SQL and CarDb.ESTIMATED_ROW_SQL are expected to implement
     * this exact truth table, so any future change to one prompts checking the other.
     */
    @Test public void measuredPredicateResolutionAgreesWithSqlTruthTable() {
        assertNotNull(CarDb.MEASURED_ROW_SQL);
        assertNotNull(CarDb.ESTIMATED_ROW_SQL);

        // Truth table of (storedEnergyMeasured, batteryTempC, expectedSource, description)
        Object[][] table = {
            // Measured: energy_measured = 1 (with or without temp)
            {1, 28.5, EnergySource.MEASURED, "OBD2 flag=1 with valid temp -> MEASURED"},
            {1, null, EnergySource.MEASURED, "OBD2 flag=1 without temp -> MEASURED"},

            // Mislabeled as estimated (flag=0), but battery_temp_c is present: self-heals to measured
            {0, 28.5, EnergySource.MEASURED, "Mislabeled flag=0 self-healed by battery_temp_c -> MEASURED"},

            // Genuinely estimated: flag=0 and battery_temp_c is absent
            {0, null, EnergySource.ESTIMATED, "VHAL SoC-delta estimate: flag=0 and null temp -> ESTIMATED"},

            // Pre-migration legacy row: flag is null, but battery_temp_c proves OBD2 was active
            {null, 28.5, EnergySource.MEASURED, "Legacy null flag recovered to measured by battery_temp_c -> MEASURED"},

            // Pre-migration legacy row: flag is null and temp is null -> NO_DATA
            {null, null, EnergySource.NO_DATA, "Legacy row before column existed without temp -> NO_DATA"},
        };

        for (Object[] row : table) {
            Integer storedMeasured = (Integer) row[0];
            Double batteryTempC = (Double) row[1];
            EnergySource expected = (EnergySource) row[2];
            String desc = (String) row[3];

            // Verify pure helper methods in CarDb agree with the expected classification
            assertEquals("isRowMeasured() failed for: " + desc,
                expected == EnergySource.MEASURED, CarDb.isRowMeasured(storedMeasured, batteryTempC));
            assertEquals("isRowEstimated() failed for: " + desc,
                expected == EnergySource.ESTIMATED, CarDb.isRowEstimated(storedMeasured, batteryTempC));

            // Replicate the resolution logic from DailyStatsProvider.queryDrivingConsumption:
            // if (!c.isNull(9) && (measured == null || measured == 0)) measured = 1;
            Integer resolved = storedMeasured;
            if (batteryTempC != null && (resolved == null || resolved == 0)) {
                resolved = 1;
            }

            DrivingConsumption dc = new DrivingConsumption();
            dc.add(0, 100, 20, 8, false, 0.1, 0.0, NaN, resolved);

            assertEquals("energySource() failed for: " + desc,
                expected, dc.energySource());
        }
    }
}
