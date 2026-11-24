package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.*;

/** Pure-Java unit tests for ImuFilter calibration, projection, and 1 Hz summaries. */
public class ImuFilterTest {

    private static final long STEP_NS = 62_500_000L; // 16 Hz (matches SX11SensorManager)

    @Test public void restCalibrationMatchesIhu629gAndIsolatesVerticalBumps() {
        ImuFilter f = new ImuFilter();
        long t = 1_000_000_000L;
        // Real stationary readings from the IHU629G head unit (~13.7 deg tilt).
        float gx = 0.10f, gy = -9.45f, gz = -2.31f;
        float gNorm = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        float ux = gx / gNorm, uy = gy / gNorm, uz = gz / gNorm;

        for (int i = 0; i < 30; i++) {
            f.onAccel(gx, gy, gz, t, 0f);
            t += STEP_NS;
        }
        assertTrue("Z axis should calibrate after quiet rest samples", f.isCalibratedZ());
        assertFalse("XY axis should not calibrate at rest", f.isCalibratedXy());
        assertEquals(0f, f.az(), 0.05f);

        // Apply a pure +3.0 m/s^2 upward bump along the calibrated up vector.
        f.drainWindow();
        f.onAccel(gx + 3.0f * ux, gy + 3.0f * uy, gz + 3.0f * uz, t, 40f);
        assertEquals(2.85f, f.az(), 0.25f);
        assertEquals(0f, f.ax(), 0.15f);
        assertEquals(0f, f.ay(), 0.15f);
    }

    @Test public void forwardAxisAutoCalibratesOnArbitraryMountedRotation() {
        ImuFilter f = new ImuFilter();
        long t = 1_000_000_000L;

        // Invent a rotated head unit in the MTK HAL (g - a) frame:
        // rest grav (up)  = (0, 0, -1)
        // launch (fwd)    = (cos 35, sin 35, 0)
        // right turn (+y) = up x fwd = (sin 35, -cos 35, 0)
        float cos = (float) Math.cos(Math.toRadians(35.0));
        float sin = (float) Math.sin(Math.toRadians(35.0));
        float gx = 0f, gy = 0f, gz = -ImuFilter.G;

        // 1. Stationary calibration for Z_up.
        for (int i = 0; i < 25; i++) {
            f.onAccel(gx, gy, gz, t, 0f);
            t += STEP_NS;
        }
        assertTrue(f.isCalibratedZ());

        // 2. A turning launch (strong lateral G mixed in) must NOT skew calibration.
        float speedKmh = 15f;
        float aFwd = 1.5f;
        float aTurn = 3.0f;
        for (int i = 0; i < 32; i++) {
            speedKmh += aFwd * (STEP_NS * 1e-9f) * 3.6f;
            f.onSpeedKmh(speedKmh, t);
            f.onAccel(gx + aFwd * cos + aTurn * sin, gy + aFwd * sin - aTurn * cos, gz, t, speedKmh);
            t += STEP_NS;
        }
        assertFalse("Turning launch should be rejected by straight-line gate", f.isCalibratedXy());

        // Let the moving gravity IIR recover after the sustained turn before testing straight launch.
        for (int i = 0; i < 320; i++) {
            f.onAccel(gx, gy, gz, t, speedKmh);
            t += STEP_NS;
        }

        // 3. Straight-line acceleration at +2.5 m/s^2 for 6 seconds locks fwd cleanly.
        aFwd = 2.5f;
        for (int i = 0; i < 96; i++) {
            speedKmh += aFwd * (STEP_NS * 1e-9f) * 3.6f;
            f.onSpeedKmh(speedKmh, t);
            f.onAccel(gx + aFwd * cos, gy + aFwd * sin, gz, t, speedKmh);
            t += STEP_NS;
        }
        assertTrue("Forward/lateral axes should calibrate from straight-line accel", f.isCalibratedXy());

        // 4. Let gravity IIR settle at constant speed (~25 s), then test pure right turn.
        for (int i = 0; i < 400; i++) {
            f.onAccel(gx, gy, gz, t, 50f);
            t += STEP_NS;
        }
        float aRight = 2.0f;
        f.onAccel(gx + aRight * sin, gy - aRight * cos, gz, t, 50f);
        assertEquals("Right turn should project onto +ay", aRight, f.ay(), 0.25f);
        assertEquals("Right turn should not bleed into ax", 0f, f.ax(), 0.25f);
        assertEquals("Right turn should not bleed into az", 0f, f.az(), 0.15f);
    }

    @Test public void ihu629gDefaultPriorMatchesRealDriveTurnSigns() {
        ImuFilter f = new ImuFilter();
        long t = 1_000_000_000L;
        float gx = -0.08f, gy = -9.60f, gz = -1.60f;
        for (int i = 0; i < 25; i++) {
            f.onAccel(gx, gy, gz, t, 0f);
            t += STEP_NS;
        }
        // Observed on real drive: left turn pushes rawX positive (+2.2), right turn negative (-2.2).
        f.onAccel(gx - 2.2f, gy, gz, t, 25f);
        assertTrue("Right turn (-rawX) must produce positive ay", f.ay() > 1.8f);
        t += STEP_NS;
        f.onAccel(gx + 2.2f, gy, gz, t, 25f);
        assertTrue("Left turn (+rawX) must produce negative ay", f.ay() < -1.8f);
    }

    @Test public void windowSummaryTracksRmsPeaksAndJerk() {
        ImuFilter f = new ImuFilter();
        long t = 1_000_000_000L;
        float gx = 0f, gy = 0f, gz = ImuFilter.G;
        for (int i = 0; i < 20; i++) {
            f.onAccel(gx, gy, gz, t, 0f);
            t += STEP_NS;
        }
        f.drainWindow();

        // Feed 16 samples (~1 s): one upward bump and one lateral sway.
        for (int i = 0; i < 16; i++) {
            float dz = (i == 5) ? 4.0f : 0f;
            float dx = (i == 10) ? 2.5f : 0f;
            f.onAccel(gx + dx, gy, gz + dz, t, 45f);
            t += STEP_NS;
        }
        ImuFilter.Summary s = f.drainWindow();
        assertNotNull(s);
        assertEquals(16, s.count);
        assertTrue(s.calZ);
        assertTrue("Peak Z should capture the 4 m/s^2 bump", s.peakZ > 3.5f);
        assertTrue("RMS Z should be non-zero", s.rmsZ > 0.8f);
        assertTrue("Jerk RMS should reflect the lateral impulse", s.jerkRms > 5f);
        assertNull("Second drain without new samples should return null", f.drainWindow());
    }

    @Test public void basisRoundTripsThroughSerialization() {
        ImuFilter f1 = new ImuFilter();
        long t = 1_000_000_000L;
        for (int i = 0; i < 25; i++) {
            f1.onAccel(0.10f, -9.45f, -2.31f, t, 0f);
            t += STEP_NS;
        }
        String saved = f1.serializeBasis();

        ImuFilter f2 = new ImuFilter();
        assertTrue(f2.restoreBasis(saved));
        assertTrue(f2.isCalibratedZ());
        assertFalse(f2.isCalibratedXy());
        assertEquals(saved, f2.serializeBasis());
    }

    @Test public void joltDetectorGatesBySpeedCalibrationAndThresholds() {
        // Normal 0.4 g bump -> does not trigger jolt
        ImuFilter.Summary mild = new ImuFilter.Summary(16, true, true, 1.0f, 4.0f, 0f, 2.0f, 0f, 1.5f, 3f, 0f, -9.8f, 0f);
        assertFalse(ImuHub.shouldTriggerJolt(mild, 40f));

        // Sharp 0.95 g vertical pothole at 40 km/h -> triggers jolt
        ImuFilter.Summary pothole = new ImuFilter.Summary(16, true, true, 2.5f, 9.3f, 0f, 1.0f, 0f, 0.5f, 8f, 0f, -9.8f, 0f);
        assertTrue(ImuHub.shouldTriggerJolt(pothole, 40f));
        // Same spike while parked / crawling (< 10 km/h) -> ignored (door slam protection)
        assertFalse(ImuHub.shouldTriggerJolt(pothole, 0f));
        assertFalse(ImuHub.shouldTriggerJolt(pothole, 5f));

        // Uncalibrated Z -> ignored
        ImuFilter.Summary uncal = new ImuFilter.Summary(16, false, false, 2.5f, 12.0f, 0f, 0f, 0f, 0f, 8f, 0f, -9.8f, 0f);
        assertFalse(ImuHub.shouldTriggerJolt(uncal, 40f));

        // Sharp 0.85 g horizontal shock (hard curb / panic brake) -> triggers jolt
        ImuFilter.Summary curb = new ImuFilter.Summary(16, true, true, 1.0f, 2.0f, -2f, -8.3f, 0f, 1.0f, 9f, 0f, -9.8f, 0f);
        assertTrue(ImuHub.shouldTriggerJolt(curb, 30f));

        // Configurable sensitivity presets: off, low (1.1 g), medium (0.85 g), high (0.65 g)
        assertFalse(ImuHub.shouldTriggerJolt(pothole, 40f, ImuHub.JOLT_OFF));
        assertFalse(ImuHub.shouldTriggerJolt(pothole, 40f, ImuHub.JOLT_LOW));   // 0.95g < 1.1g
        assertTrue(ImuHub.shouldTriggerJolt(pothole, 40f, ImuHub.JOLT_MEDIUM)); // 0.95g >= 0.85g
        ImuFilter.Summary moderate = new ImuFilter.Summary(16, true, true, 1.5f, 7.0f, 0f, 1.0f, 0f, 0.5f, 5f, 0f, -9.8f, 0f);
        assertFalse(ImuHub.shouldTriggerJolt(moderate, 40f, ImuHub.JOLT_MEDIUM)); // 0.71g < 0.85g
        assertTrue(ImuHub.shouldTriggerJolt(moderate, 40f, ImuHub.JOLT_HIGH));    // 0.71g >= 0.65g
    }
}
