package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.*;

/** Unit tests for DriveScorer (Road Score & Driver Score). */
public class DriveScorerTest {

    private static ImuFilter.Summary sample(float rmsZ, float peakZ,
                                            float meanX, float peakX,
                                            float meanY, float peakY,
                                            float jerkRms) {
        return new ImuFilter.Summary(16, true, true,
            rmsZ, peakZ, meanX, peakX, meanY, peakY, jerkRms, 0f, -9.6f, -1.6f);
    }

    @Test public void tooFewMovingSecondsReturnsNull() {
        DriveScorer s = new DriveScorer();
        for (int i = 0; i < 15; i++) {
            s.addSecond(sample(0.2f, 0.4f, 0.2f, 0.4f, 0.1f, 0.2f, 4f), 30f, 5f, 0);
        }
        assertNull("Trip score requires >= 20 moving s", s.snapshot(0.5, DriveScorer.TRIP_MIN_MOVING_S));
        assertNotNull("Valet score accepts >= 10 moving s", s.snapshot(0.5, DriveScorer.VALET_MIN_MOVING_S));
    }

    @Test public void smoothHighwayScoresNearPerfectOnBoth() {
        DriveScorer s = new DriveScorer();
        for (int i = 0; i < 60; i++) {
            s.addSecond(sample(0.25f, 0.6f, 0.1f, 0.3f, 0.05f, 0.2f, 4.5f), 80f, 10f, 0);
        }
        DriveScorer.Snapshot snap = s.snapshot(1.33, DriveScorer.TRIP_MIN_MOVING_S);
        assertNotNull(snap);
        assertTrue("Smooth highway roadScore should be >= 95, got " + snap.roadScore, snap.roadScore >= 95);
        assertTrue("Calm highway driverScore should be >= 95, got " + snap.driverScore, snap.driverScore >= 95);
        assertEquals(0, snap.roadBumps);
        assertEquals(0, snap.hardBrakes);
        assertEquals(0, snap.hardAccels);
        assertEquals(0, snap.hardCorners);
    }

    @Test public void roughRoadDoesNotPenalizeCalmDriver() {
        DriveScorer s = new DriveScorer();
        for (int i = 0; i < 60; i++) {
            boolean bump = (i % 8 == 0);
            // Bump seconds have high vertical RMS/peak AND wheel-shudder jerk,
            // but calm horizontal meanX/meanY.
            s.addSecond(sample(
                bump ? 2.2f : 1.3f,
                bump ? 4.8f : 2.1f,
                0.2f, 0.5f, 0.1f, 0.3f,
                bump ? 35f : 5.0f), 30f, 5f, 0);
        }
        DriveScorer.Snapshot snap = s.snapshot(0.5, DriveScorer.TRIP_MIN_MOVING_S);
        assertNotNull(snap);
        assertTrue("Rough street roadScore should be low (< 50), got " + snap.roadScore, snap.roadScore < 50);
        assertTrue("Calm driver on rough street should still score >= 90, got " + snap.driverScore, snap.driverScore >= 90);
        assertEquals(8, snap.roadBumps);
    }

    @Test public void aggressiveDrivingLowersDriverScoreWithoutPollutingRoadScore() {
        DriveScorer s = new DriveScorer();
        for (int i = 0; i < 60; i++) {
            boolean brake = (i % 10 == 2);
            boolean launch = (i % 10 == 5);
            boolean corner = (i % 10 == 8);
            s.addSecond(sample(
                0.25f, 0.5f,
                brake ? -2.6f : (launch ? 2.6f : 0.1f),
                brake ? -4.0f : (launch ? 3.8f : 0.3f),
                corner ? 2.8f : 0.1f,
                corner ? 3.5f : 0.2f,
                22f), 40f, brake ? -10f : 15f, brake ? 1 : 0);
        }
        DriveScorer.Snapshot snap = s.snapshot(0.67, DriveScorer.TRIP_MIN_MOVING_S);
        assertNotNull(snap);
        assertTrue("Smooth pavement roadScore stays high (>= 90), got " + snap.roadScore, snap.roadScore >= 90);
        assertTrue("Aggressive driving lowers driverScore (< 55), got " + snap.driverScore, snap.driverScore < 55);
        assertEquals(6, snap.hardBrakes);
        assertEquals(6, snap.hardAccels);
        assertEquals(6, snap.hardCorners);
    }

    @Test public void regenDisciplineRewardsRegenOverFrictionBraking() {
        DriveScorer regenDriver = new DriveScorer();
        DriveScorer frictionDriver = new DriveScorer();
        for (int i = 0; i < 40; i++) {
            ImuFilter.Summary calm = sample(0.25f, 0.5f, -0.5f, -0.8f, 0.1f, 0.2f, 5f);
            regenDriver.addSecond(calm, 40f, -12f, 0);     // pure regen slowing
            frictionDriver.addSecond(calm, 40f, -2f, 1);   // friction brake slowing
        }
        DriveScorer.Snapshot r = regenDriver.snapshot(0.5, DriveScorer.TRIP_MIN_MOVING_S);
        DriveScorer.Snapshot f = frictionDriver.snapshot(0.5, DriveScorer.TRIP_MIN_MOVING_S);
        assertNotNull(r);
        assertNotNull(f);
        assertTrue("Regen driver (" + r.driverScore + ") should outscore friction driver (" + f.driverScore + ")",
            r.driverScore > f.driverScore);
    }

    @Test public void debouncesConsecutiveBumpAndHarshSeconds() {
        DriveScorer s = new DriveScorer();
        // 3 consecutive bump seconds -> counts as 2 (2s debounce: tick 1 and tick 3)
        for (int i = 0; i < 20; i++) {
            boolean bump = (i == 5 || i == 6);
            boolean brake = (i == 10 || i == 11 || i == 12);
            s.addSecond(sample(0.4f, bump ? 4.5f : 0.8f,
                brake ? -2.5f : 0.1f, brake ? -3.8f : 0.2f,
                0.1f, 0.2f, 5f), 30f, 0f, 0);
        }
        DriveScorer.Snapshot snap = s.finish(0.3, DriveScorer.TRIP_MIN_MOVING_S);
        assertNotNull(snap);
        assertEquals("Adjacent bump seconds count once", 1, snap.roadBumps);
        assertEquals("3-second brake maneuver counts once", 1, snap.hardBrakes);
        assertNull("finish() resets the accumulator", s.snapshot(0.3, DriveScorer.TRIP_MIN_MOVING_S));
    }
}
