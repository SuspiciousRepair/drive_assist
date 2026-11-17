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
        EnergySplit s = EnergySplit.of(3.0, 3.0, 2.8, 0.4, 0.1, 600);
        assertEquals(3.0, s.driveKwh + s.climateKwh + s.otherKwh, EPS);
        assertEquals(2.4, s.driveKwh, EPS);
        assertEquals(ClimatePower.correctedKwh(0.1, 600), s.climateKwh, EPS);
    }

    /** CAN dropped out for a moment and the dongle (5 % higher) filled in:
     * the shares stay CAN's, the total is the trip's. */
    @Test public void scaledToTheTripNetWhenTheDongleFilledIn() {
        EnergySplit s = EnergySplit.of(3.15, 3.0, 2.8, 0.4, 0.1, 600);
        assertEquals(3.15, s.driveKwh + s.climateKwh + s.otherKwh, EPS);
        assertEquals(2.4 * 1.05, s.driveKwh, 1e-9);
    }

    @Test public void tinyCanNetIsNotScaled() {
        EnergySplit s = EnergySplit.of(0.5, 0.01, 0.3, 0.3, 0, 0);
        assertEquals(0, s.driveKwh, EPS);
        assertEquals(0.01, s.otherKwh, EPS);
    }

    // --- EnergyIntegrator: the split is taken from CAN samples only ---

    private static void can(long t, double kw, double drive, double climate) {
        EnergyIntegrator.onPowerReading(t, kw, EnergyIntegrator.SOURCE_CAN, drive, climate);
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
        EnergyIntegrator.onPowerReading(10_500, 10.5, EnergyIntegrator.SOURCE_OBD2);
        EnergyIntegrator.onPowerReading(11_000, 10.5, EnergyIntegrator.SOURCE_OBD2);   // 0.5 s dongle
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.endTrip();
        assertTrue(t.splitValid);
        assertTrue(t.netKwh > t.canNetKwh);
    }

    /** Mostly the dongle: no split, rather than a split of a sliver. */
    @Test public void mostlyDongleHasNoSplit() {
        EnergyIntegrator.startTrip();
        can(0, 10, 9, 0);
        can(1000, 10, 9, 0);
        for (int i = 0; i <= 10; i++) EnergyIntegrator.onPowerReading(2000 + i * 2000L, 10, EnergyIntegrator.SOURCE_OBD2);
        assertFalse(EnergyIntegrator.endTrip().splitValid);
    }

    @Test public void dongleOnlyHasNoSplit() {
        EnergyIntegrator.startTrip();
        EnergyIntegrator.onPowerReading(0, 10, EnergyIntegrator.SOURCE_OBD2);
        EnergyIntegrator.onPowerReading(2000, 10, EnergyIntegrator.SOURCE_OBD2);
        EnergyIntegrator.TripSnapshot t = EnergyIntegrator.endTrip();
        assertFalse(t.splitValid);
        assertTrue(t.netKwh > 0);
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
        CarActor.Reading r = EnergyHub.other(live, CarActor.Reading.ok(10f), CarActor.Reading.ok(8f), CarActor.Reading.ok(1f));
        assertEquals(10 - 8 - ClimatePower.correctedKw(1), ((Number) r.value).doubleValue(), 0.006);
        // Drive and climate not seen yet: 0 kW.
        r = EnergyHub.other(live, CarActor.Reading.ok(0.3f), CarActor.Reading.NOT_AVAILABLE, CarActor.Reading.NOT_AVAILABLE);
        assertEquals(0.3, ((Number) r.value).doubleValue(), 0.006);
        // CAN not live: nothing, never a dongle-based value.
        assertEquals(CarActor.Reading.Status.NOT_AVAILABLE,
            EnergyHub.other(CarActor.Reading.ok(0), CarActor.Reading.ok(10f), CarActor.Reading.ok(8f), CarActor.Reading.ok(1f)).status);
    }

    // --- Mean power for ABRP ---

    @Test public void meanPowerOverTheWindow() {
        for (int i = 0; i <= 60; i++) can(i * 100L, i < 30 ? 10 : 20, 0, 0);   // 3 s at 10, 3 s at 20
        assertEquals(15.0, EnergyIntegrator.meanPowerKw(6_000, 6_000), 0.2);
    }

    @Test public void meanPowerNeedsHalfTheWindow() {
        can(0, 10, 0, 0);
        assertNull(EnergyIntegrator.meanPowerKw(10_000, 6_000));   // last sample long gone
    }

    @Test public void meanPowerStartsOverWhenTheSourceChanges() {
        for (int i = 0; i <= 60; i++) can(i * 100L, 50, 0, 0);
        EnergyIntegrator.onPowerReading(6_000, 10, EnergyIntegrator.SOURCE_OBD2);
        EnergyIntegrator.onPowerReading(8_000, 10, EnergyIntegrator.SOURCE_OBD2);
        EnergyIntegrator.onPowerReading(10_000, 10, EnergyIntegrator.SOURCE_OBD2);
        assertEquals(10.0, EnergyIntegrator.meanPowerKw(11_000, 6_000), EPS);
    }
}
