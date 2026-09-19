package com.geely.drivemem.ui;

/** User framing of a recorded camera view. Lens calibration and the source file stay intact. */
final class ClipViewSettings {
    static final int MIN_FOV = 70;
    static final int MAX_FOV = 130;
    static final int MIN_PITCH = -20;
    static final int MAX_PITCH = 20;

    final int horizontalFov;
    final int pitchOffset;
    private final float[] calibrated;

    ClipViewSettings(float[] calibrated, float horizontalFov, float pitchOffset) {
        if (calibrated == null || calibrated.length != 8) {
            throw new IllegalArgumentException("A single calibrated camera is required");
        }
        this.calibrated = calibrated.clone();
        this.horizontalFov = bounded(horizontalFov, MIN_FOV, MAX_FOV, Math.round(calibrated[4]));
        this.pitchOffset = bounded(pitchOffset, MIN_PITCH, MAX_PITCH, 0);
    }

    static ClipViewSettings defaults(float[] calibrated) {
        return new ClipViewSettings(calibrated, calibrated[4], 0);
    }

    /** Return an independent snapshot, safe to pass from the UI to the GL thread. */
    float[] camera() {
        float[] result = calibrated.clone();
        result[3] += pitchOffset;
        if (horizontalFov != calibrated[4]) {
            // Zoom both axes together: preserve the calibrated rectilinear aspect
            // instead of stretching the image when the horizontal field changes.
            double scale = Math.tan(Math.toRadians(horizontalFov) / 2)
                / Math.tan(Math.toRadians(calibrated[4]) / 2);
            result[4] = horizontalFov;
            result[5] = (float) Math.toDegrees(2 * Math.atan(
                Math.tan(Math.toRadians(calibrated[5]) / 2) * scale));
        }
        return result;
    }

    private static int bounded(float value, int minimum, int maximum, int fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, Math.round(value)));
    }
}
