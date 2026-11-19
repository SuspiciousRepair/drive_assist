package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.state.CarState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** Where a trip's energy went: drive, climate, other. The parts come from
 * the same CAN samples and are scaled to the trip's own net, so they add up
 * with or without the OBD2 dongle. */
public class EnergySplitTest {
    private static final double EPS = 1e-9;

    @Before public void setUp() {
        EnergyIntegrator.resetForTesting();
        CarState.setParkedForTesting(false);
    }

    @After public void tearDown() {
        EnergyIntegrator.resetForTesting();
        CarState.setParkedForTesting(true);
    }

    // --- ClimatePower: the fit from the 2026-11-17 parked step test ---

    @Test public void climateOffIsZero() {
        assertEquals(0, ClimatePower.correctedKw(0), EPS);
        assertEquals(0, ClimatePower.correctedKwh(0, 0), EPS);
    }

    @Test public void climateFitMatchesTheTestPoints() {
        assertEquals(3.34, ClimatePower.correctedKw(2.64), 0.05);   // heat step
        assertEquals(0.58, ClimatePower.correctedKw(0.32), 0.05);   // air-con on
        assertEquals(1.42, ClimatePower.correctedKw(1.00), 0.05);   // while charging
    }

    @Test public void climateKwhAddsTheOnPowerForTheOnTime() {
        // 1 kWh raw over one hour on = 1.19 + 0.2
        assertEquals(1.39, ClimatePower.correctedKwh(1.0, 3600), EPS);
    }

    // --- EnergySplit: the parts add up to the trip's net ---

    @Test public void partsAddUpToTheTripNet() {
        EnergySplit s = EnergySplit.of(3.0, 3.0, 2.8, 0.4, 0.1, 600, 0);
        assertEquals(3.0, s.driveKwh + s.climateKwh + s.otherKwh, EPS);
        assertEquals(2.4, s.driveKwh, EPS);
        assertEquals(ClimatePower.correctedKwh(0.1, 600), s.climateKwh, EPS);
    }

    /** CAN dropped out for a moment and the dongle (5 % higher) filled in:
     * the shares stay CAN's, the total is the trip's. */
    @Test public void scaledToTheTripNetWhenTheDongleFilledIn() {
        EnergySplit s = EnergySplit.of(3.15, 3.0, 2.8, 0.4, 0.1, 600, 0);
        assertEquals(3.15, s.driveKwh + s.climateKwh + s.otherKwh, EPS);
        assertEquals(2.4 * 1.05, s.driveKwh, 1e-9);
    }

    /** A real trip (2026-11-19, 41 min): battery 2.18 out, 0.61 back; the
     * motor 1.86 out, 0.77 back. The Sankey's two sides add up with the
     * motor's regen, and Drive is the motor's real energy out. */
    @Test public void motorOutAndRegenBalanceTheSankey() {
        EnergySplit s = EnergySplit.of(1.57, 1.57, 1.86, 0.77, 0.11, 2317, 0.022);
        assertEquals(1.86, s.motorOutKwh, EPS);
        assertEquals(0.77, s.motorRegenKwh, EPS);
        assertEquals(s.motorOutKwh - s.motorRegenKwh, s.driveKwh, EPS);
        assertEquals(1.57, s.batteryKwh(), EPS);
        assertEquals(s.batteryKwh() + s.motorRegenKwh,
            s.motorOutKwh + s.climateKwh + s.otherKwh, EPS);
    }

    @Test public void motorNumbersScaleWithTheTripNet() {
        EnergySplit s = EnergySplit.of(3.15, 3.0, 2.8, 0.4, 0.1, 600, 0);
        assertEquals(2.8 * 1.05, s.motorOutKwh, 1e-9);
        assertEquals(0.4 * 1.05, s.motorRegenKwh, 1e-9);
    }

    @Test public void tinyCanNetIsNotScaled() {
        EnergySplit s = EnergySplit.of(0.5, 0.01, 0.3, 0.3, 0, 0, 0);
        assertEquals(0, s.driveKwh, EPS);
        assertEquals(0.01, s.otherKwh, EPS);
    }

    // --- EnergyIntegrator: the split is taken from CAN samples only ---

    /** Test clocks start here: the integrator reads time 0 as "no previous
     * sample", like a fresh boot. */
    private static final long T0 = 1_000_000;

    private static void can(long t, double kw, double drive, double climate) {
        EnergyIntegrator.onPowerReading(T0 + t, kw, EnergyIntegrator.SOURCE_CAN, drive, climate, 0);
    }

    private static void obd(long t, double kw) {
        EnergyIntegrator.onPowerReading(T0 + t, kw, EnergyIntegrator.SOURCE_OBD2);
    }

    @Test public void allCanTripHasAValidSplit() {
        EnergyIntegrator.startTrip();
        for (int i = 0; i <= 100; i++) can(i * 100L, 10.0, 9.0, 0.5);   // 10 s
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.endTrip();
        assertTrue(t.splitValid);
        double h = 10.0 / 3600;
        assertEquals(10 * h, t.canNetKwh, 1e-9);
        assertEquals(t.netKwh, t.canNetKwh, 1e-12);
        assertEquals(9 * h, t.driveSpentKwh, 1e-9);
        assertEquals(0, t.driveRegenKwh, EPS);
        assertEquals(0.5 * h, t.climateRawKwh, 1e-9);
        assertEquals(10.0, t.climateOnSeconds, 1e-9);
    }

    @Test public void regenGoesToDriveRegen() {
        EnergyIntegrator.startTrip();
        can(0, -5.0, -6.0, 0);
        can(3600, -5.0, -6.0, 0);   // 3.6 s
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.endTrip();
        assertEquals(6.0 * 3.6 / 3600, t.driveRegenKwh, 1e-9);
        assertEquals(0, t.driveSpentKwh, EPS);
    }

    /** A short dongle stretch (CAN dropped out) keeps the split: CAN still
     * covers over 90 % of the trip. */
    @Test public void shortDongleStretchKeepsTheSplit() {
        EnergyIntegrator.startTrip();
        for (int i = 0; i <= 100; i++) can(i * 100L, 10.0, 9.0, 0);           // 10 s CAN
        obd(10_500, 10.5);
        obd(11_000, 10.5);   // 0.5 s dongle
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.endTrip();
        assertTrue(t.splitValid);
        assertTrue(t.netKwh > t.canNetKwh);
    }

    /** Mostly the dongle: no split, rather than a split of a sliver. */
    @Test public void mostlyDongleHasNoSplit() {
        EnergyIntegrator.startTrip();
        can(0, 10, 9, 0);
        can(1000, 10, 9, 0);
        for (int i = 0; i <= 10; i++) obd(2000 + i * 2000L, 10);
        assertFalse(EnergyIntegrator.endTrip().splitValid);
    }

    @Test public void dongleOnlyHasNoSplit() {
        EnergyIntegrator.startTrip();
        obd(0, 10);
        obd(2000, 10);
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.endTrip();
        assertFalse(t.splitValid);
        assertTrue(t.netKwh > 0);
    }

    /** Recovered after a restart one second in, then 20 s of CAN: the
     * split counts (CAN covered over 90 % of the trip's time). */
    @Test public void tripRecoveredEarlyKeepsItsSplit() {
        EnergyIntegrator.startTrip();
        EnergyIntegrator.seedTrip(0.01, 0, 0.01, 1_000);
        for (int i = 0; i <= 200; i++) can(i * 100L, 10, 9, 0);
        assertTrue(EnergyIntegrator.endTrip().splitValid);
    }

    /** Recovered after half the trip: not enough CAN for a split. */
    @Test public void tripRecoveredLateHasNoSplit() {
        EnergyIntegrator.startTrip();
        EnergyIntegrator.seedTrip(0.1, 0, 0.1, 20_000);
        for (int i = 0; i <= 200; i++) can(i * 100L, 10, 9, 0);
        assertFalse(EnergyIntegrator.endTrip().splitValid);
    }

    @Test public void liveTripShowsItsSplitSoFar() {
        EnergyIntegrator.startTrip();
        for (int i = 0; i <= 100; i++) can(i * 100L, 10, 9, 0.5);
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.currentTrip();
        assertTrue(t.splitValid);
        assertTrue(t.driveSpentKwh > 0);
    }

    @Test public void recoveredTripHasNoSplit() {
        EnergyIntegrator.startTrip();
        EnergyIntegrator.seedTrip(1.0, 0, 1.0);
        can(0, 10, 9, 0);
        can(1000, 10, 9, 0);
        assertFalse(EnergyIntegrator.endTrip().splitValid);
    }

    // --- Live hub keys: CAN only, and they add up to can.power_kw ---

    @Test public void liveOtherIsPowerMinusDriveMinusClimate() {
        CarActor.Reading live = CarActor.Reading.ok(1);
        CarActor.Reading r = EnergyHub.other(live, CarActor.Reading.ok(10f), CarActor.Reading.ok(8f), CarActor.Reading.ok(1f), CarActor.Reading.ok(0));
        assertEquals(10 - 8 - ClimatePower.correctedKw(1), ((Number) r.value).doubleValue(), 0.006);
        // Drive and climate not seen yet: 0 kW.
        r = EnergyHub.other(live, CarActor.Reading.ok(0.3f), CarActor.Reading.NOT_AVAILABLE, CarActor.Reading.NOT_AVAILABLE, CarActor.Reading.NOT_AVAILABLE);
        assertEquals(0.3, ((Number) r.value).doubleValue(), 0.006);
        // CAN not live: nothing, never a dongle-based value.
        assertEquals(CarActor.Reading.Status.NOT_AVAILABLE,
            EnergyHub.other(CarActor.Reading.ok(0), CarActor.Reading.ok(10f), CarActor.Reading.ok(8f), CarActor.Reading.ok(1f), CarActor.Reading.ok(0)).status);
    }

    // --- The fan: a 12 V load, counted as climate ---

    @Test public void fanTable() {
        assertEquals(0, FanPower.kw(0), EPS);
        assertEquals(0.12, FanPower.kw(8), EPS);
        assertEquals(0, FanPower.kw(null), EPS);
        assertEquals(0, FanPower.kw(9), EPS);
        for (int i = 1; i < FanPower.KW.length; i++) assertTrue(FanPower.KW[i] >= FanPower.KW[i - 1]);
    }

    /** Fan only (compressor off): it moves from other to climate. */
    @Test public void liveFanMovesFromOtherToClimate() {
        CarActor.Reading live = CarActor.Reading.ok(1), fan8 = CarActor.Reading.ok(8);
        CarActor.Reading noComp = CarActor.Reading.NOT_AVAILABLE;
        assertEquals(0.12, ((Number) EnergyHub.climate(live, noComp, fan8).value).doubleValue(), 0.006);
        assertEquals(0.16 - 0.12, ((Number) EnergyHub.other(live, CarActor.Reading.ok(0.16f),
            CarActor.Reading.ok(0f), noComp, fan8).value).doubleValue(), 0.006);
    }

    @Test public void tripFanIsPartOfClimate() {
        EnergyIntegrator.startTrip();
        for (int i = 0; i <= 36; i++)   // 3.6 s at fan 8
            EnergyIntegrator.onPowerReading(T0 + i * 100L, 1.0, EnergyIntegrator.SOURCE_CAN, 0.8, 0.0, 0.12);
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.endTrip();
        assertEquals(0.12 * 3.6 / 3600, t.fanKwh, 1e-12);
        assertEquals(t.fanKwh, t.climateKwh(), 1e-12);
        EnergySplit s = EnergySplit.of(t.netKwh, t.canNetKwh, t.driveSpentKwh, t.driveRegenKwh,
            t.climateRawKwh, t.climateOnSeconds, t.fanKwh);
        assertEquals(t.netKwh, s.driveKwh + s.climateKwh + s.otherKwh, 1e-12);
        assertEquals(t.fanKwh, s.climateKwh, 1e-12);
    }

    // --- Why a trip has no split (can-events.log) ---

    @Test public void splitWhyIsNullWhenCanCoveredTheTrip() {
        assertNull(EnergyIntegrator.splitWhy(0, 950, 1000));
    }

    // 2026-11-19: the app was installed during two trips; both lost the split.
    @Test public void splitWhyNamesAnAppRestart() {
        assertEquals("app restarted during the trip",
            EnergyIntegrator.splitWhy(Long.MAX_VALUE, 1000, 1000));
        assertEquals("app restarted 18 min into the trip; CAN covered 33 % of it (needs 90 %)",
            EnergyIntegrator.splitWhy(18 * 60_000L, 9 * 60_000L, 9 * 60_000L));
    }

    @Test public void splitWhyNamesMissingCan() {
        assertEquals("no CAN reading", EnergyIntegrator.splitWhy(0, 0, 1000));
        assertEquals("CAN covered 50 % of it (needs 90 %)", EnergyIntegrator.splitWhy(0, 500, 1000));
    }

    // --- Stats page: the split summed over a period ---

    @Test public void splitTotalsSkipTripsWithoutASplit() {
        java.util.List<DailyStatsProvider.DaySession> list = new java.util.ArrayList<>();
        DailyStatsProvider.DriveSession a = new DailyStatsProvider.DriveSession(1, 0, 1, 10, 80, 78, 0, 0, 0, 1.5, 1.5, 15, EnergySource.MEASURED);
        a.split = EnergySplit.of(1.5, 1.5, 1.6, 0.4, 0.1, 600, 0.02);
        DailyStatsProvider.DriveSession b = new DailyStatsProvider.DriveSession(2, 2, 3, 20, 78, 74, 0, 0, 0, 3.0, 3.0, 15, EnergySource.MEASURED);
        list.add(a);
        list.add(b);   // older trip, no split: its km must not dilute the Wh/km
        double[] t = DailyStatsProvider.splitTotals(list);
        assertNotNull(t);
        // {motor out, climate, other, km, battery, motor regen}
        assertEquals(1.6, t[0], 1e-9);
        assertEquals(10, t[3], 1e-9);
        assertEquals(1.5, t[4], 1e-9);
        assertEquals(0.4, t[5], 1e-9);
        // Battery + regen = the three parts (the Sankey adds up).
        assertEquals(t[4] + t[5], t[0] + t[1] + t[2], 1e-9);
        assertNull(DailyStatsProvider.splitTotals(java.util.Collections.singletonList(b)));
    }

    // --- Mean power for ABRP ---

    @Test public void meanPowerOverTheWindow() {
        for (int i = 0; i <= 60; i++) can(i * 100L, i < 30 ? 10 : 20, 0, 0);   // 3 s at 10, 3 s at 20
        assertEquals(15.0, EnergyIntegrator.meanPowerKw(T0 + 6_000, 6_000), 0.2);
    }

    @Test public void meanPowerNeedsHalfTheWindow() {
        can(0, 10, 0, 0);
        assertNull(EnergyIntegrator.meanPowerKw(T0 + 10_000, 6_000));   // last sample long gone
    }

    @Test public void meanPowerStartsOverWhenTheSourceChanges() {
        for (int i = 0; i <= 60; i++) can(i * 100L, 50, 0, 0);
        obd(6_000, 10);
        obd(8_000, 10);
        obd(10_000, 10);
        assertEquals(10.0, EnergyIntegrator.meanPowerKw(T0 + 11_000, 6_000), EPS);
    }
}
