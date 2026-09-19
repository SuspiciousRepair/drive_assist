package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class ClipViewSettingsTest {
    private static final float[] FRONT = {0, 0, 180, 21, 100, 70, 95.4f, 1.561f};
    private static final float[] SIDE = {0, 1, 180, 20, 115, 55, 80.3f, 1.713f};

    @Test public void defaultsRestoreExactCalibrationForDifferentLenses() {
        assertArrayEquals(FRONT, ClipViewSettings.defaults(FRONT).camera(), 0);
        assertArrayEquals(SIDE, ClipViewSettings.defaults(SIDE).camera(), 0);
    }

    @Test public void zoomPreservesImageShapeAtBothLimits() {
        for (float[] calibration : new float[][] {FRONT, SIDE}) {
            double originalAspect = aspect(calibration);
            for (int fov : new int[] {70, 100, 130}) {
                float[] adjusted = new ClipViewSettings(calibration, fov, 0).camera();
                assertEquals(originalAspect, aspect(adjusted), .00001);
                assertTrue(adjusted[5] > 0 && adjusted[5] < 180);
            }
        }
    }

    @Test public void framingDoesNotChangeLensOrOtherCameraCalibration() {
        float[] original = FRONT.clone();
        float[] adjusted = new ClipViewSettings(FRONT, 125, -12).camera();
        assertEquals(9, adjusted[3], 0);
        assertEquals(125, adjusted[4], 0);
        for (int index : new int[] {0, 1, 2, 6, 7}) {
            assertEquals(original[index], adjusted[index], 0);
        }
        assertArrayEquals(original, FRONT, 0);
        assertArrayEquals(SIDE, ClipViewSettings.defaults(SIDE).camera(), 0);
    }

    @Test public void invalidSavedNumbersUseCalibratedDefaults() {
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertArrayEquals(SIDE, new ClipViewSettings(SIDE, invalid, invalid).camera(), 0);
        }
    }

    @Test public void outOfRangePrefsCannotSendUnboundedAnglesToShader() {
        ClipViewSettings tooHigh = new ClipViewSettings(FRONT, Float.MAX_VALUE, Float.MAX_VALUE);
        assertEquals(130, tooHigh.horizontalFov);
        assertEquals(20, tooHigh.pitchOffset);
        ClipViewSettings tooLow = new ClipViewSettings(FRONT, -Float.MAX_VALUE, -Float.MAX_VALUE);
        assertEquals(70, tooLow.horizontalFov);
        assertEquals(-20, tooLow.pitchOffset);
    }

    @Test public void snapshotsCannotMutateEachOtherOrSavedCalibration() {
        float[] calibration = FRONT.clone();
        ClipViewSettings settings = ClipViewSettings.defaults(calibration);
        calibration[3] = 500;
        float[] first = settings.camera();
        first[4] = 1;
        assertArrayEquals(FRONT, settings.camera(), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void orbitDoesNotAccidentallyBecomeASingleCamera() {
        new ClipViewSettings(new float[] {-2}, 100, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rawCompositeDoesNotAccidentallyBecomeASingleCamera() {
        new ClipViewSettings(null, 100, 0);
    }

    private static double aspect(float[] camera) {
        return Math.tan(Math.toRadians(camera[4]) / 2)
            / Math.tan(Math.toRadians(camera[5]) / 2);
    }
}
