package com.geely.drivemem.sensors;

/** Accumulates 1 Hz IMU summaries over a trip or Valet interval and computes
 * two separate 0-100 scores:
 *
 * 1. Road Score (vertical axis only): speed-normalized vertical RMS plus
 *    distinct pothole/bump strikes per km. Driving style does not pollute it.
 * 2. Driver Score (horizontal axes + jerk + optional EV regen): smoothness
 *    (excluding bump-strike seconds so bad pavement does not penalize the
 *    driver), harsh brakes/launches, harsh corners, and regen-first slowing.
 *
 * Pure Java, zero Android dependencies, unit-tested directly against real
 * Geely EX2 drive distributions. */
public final class DriveScorer {
    public static final int TRIP_MIN_MOVING_S = 20;
    public static final int VALET_MIN_MOVING_S = 10;

    static final float MIN_SPEED_KMH = 5.0f;
    public static final float BUMP_PEAK_Z = 3.8f; // ~0.39 g vertical strike
    static final float HARD_BRAKE_MEAN_X = -2.0f;
    static final float HARD_BRAKE_PEAK_X = -3.5f;
    static final float HARD_ACCEL_MEAN_X = 2.0f;
    static final float HARD_ACCEL_PEAK_X = 3.5f;
    static final float HARD_CORNER_MEAN_Y = 2.2f;
    static final float REGEN_KW_THRESHOLD = -3.0f;

    private static final int BUMP_DEBOUNCE_S = 2;
    private static final int EVENT_DEBOUNCE_S = 3;
    private static final int MIN_REGEN_SLOW_S = 10;

    /** Immutable score snapshot written to trip or valet_session rows. */
    public static final class Snapshot {
        public final int roadScore;
        public final int driverScore;
        public final int roadBumps;
        public final int hardBrakes;
        public final int hardAccels;
        public final int hardCorners;
        public final int movingSeconds;

        public Snapshot(int roadScore, int driverScore, int roadBumps,
                        int hardBrakes, int hardAccels, int hardCorners,
                        int movingSeconds) {
            this.roadScore = roadScore;
            this.driverScore = driverScore;
            this.roadBumps = roadBumps;
            this.hardBrakes = hardBrakes;
            this.hardAccels = hardAccels;
            this.hardCorners = hardCorners;
            this.movingSeconds = movingSeconds;
        }
    }

    private int tick;
    private int movingSeconds;
    private double estDistanceKm;

    // Road metrics
    private double sumNormRmsZ;
    private int roadBumps;
    private int lastBumpTick = -100;

    // Driver metrics
    private double sumJerk;
    private int jerkSeconds;
    private int hardBrakes;
    private int lastBrakeTick = -100;
    private int hardAccels;
    private int lastAccelTick = -100;
    private int hardCorners;
    private int lastCornerTick = -100;
    private int slowingSeconds;
    private int regenSlowSeconds;

    public synchronized void reset() {
        tick = 0;
        movingSeconds = 0;
        estDistanceKm = 0;
        sumNormRmsZ = 0;
        roadBumps = 0;
        lastBumpTick = -100;
        sumJerk = 0;
        jerkSeconds = 0;
        hardBrakes = 0;
        lastBrakeTick = -100;
        hardAccels = 0;
        lastAccelTick = -100;
        hardCorners = 0;
        lastCornerTick = -100;
        slowingSeconds = 0;
        regenSlowSeconds = 0;
    }

    /** Feeds one 1-second IMU summary along with cached vehicle speed / CAN signals. */
    public synchronized void addSecond(ImuFilter.Summary s, float speedKmh,
                                       Float driveKw, Integer brake) {
        if (s == null || !Float.isFinite(speedKmh)) return;
        tick++;
        if (speedKmh < MIN_SPEED_KMH) return;

        movingSeconds++;
        estDistanceKm += speedKmh / 3600.0;

        // Road roughness: speed-normalized vertical RMS + debounced bump strikes.
        double vMps = Math.max(speedKmh / 3.6, 4.0);
        sumNormRmsZ += s.rmsZ / Math.sqrt(vMps);
        boolean isBump = Math.abs(s.peakZ) > BUMP_PEAK_Z;
        if (isBump && (tick - lastBumpTick) >= BUMP_DEBOUNCE_S) {
            roadBumps++;
            lastBumpTick = tick;
        }

        // Driver smoothness: ignore bump-strike seconds so potholes don't look like bad driving.
        if (!isBump && Float.isFinite(s.jerkRms)) {
            sumJerk += Math.min(s.jerkRms, 40f);
            jerkSeconds++;
        }
        if ((s.meanX < HARD_BRAKE_MEAN_X || s.peakX < HARD_BRAKE_PEAK_X)
                && (tick - lastBrakeTick) >= EVENT_DEBOUNCE_S) {
            hardBrakes++;
            lastBrakeTick = tick;
        }
        if ((s.meanX > HARD_ACCEL_MEAN_X || s.peakX > HARD_ACCEL_PEAK_X)
                && (tick - lastAccelTick) >= EVENT_DEBOUNCE_S) {
            hardAccels++;
            lastAccelTick = tick;
        }
        if (Math.abs(s.meanY) > HARD_CORNER_MEAN_Y
                && (tick - lastCornerTick) >= EVENT_DEBOUNCE_S) {
            hardCorners++;
            lastCornerTick = tick;
        }

        // EV regen discipline when CAN brake + drive power are present.
        if (brake != null && driveKw != null && Float.isFinite(driveKw)) {
            if (brake == 1) {
                slowingSeconds++;
            } else if (driveKw < REGEN_KW_THRESHOLD) {
                slowingSeconds++;
                regenSlowSeconds++;
            }
        }
    }

    /** Computes current scores, or returns null when there is too little moving data. */
    public synchronized Snapshot snapshot(double odoDistanceKm, int minMovingSeconds) {
        if (movingSeconds < minMovingSeconds) return null;
        double km = odoDistanceKm > 0.05 ? odoDistanceKm : estDistanceKm;

        double avgNormRms = sumNormRmsZ / movingSeconds;
        double bumpsPerKm = roadBumps / Math.max(km, 0.5);
        int roadScore = clampScore(100.0
            - Math.max(0.0, avgNormRms - 0.06) * 145.0
            - bumpsPerKm * 3.0);

        double avgJerk = jerkSeconds > 0 ? (sumJerk / jerkSeconds) : 0.0;
        double smoothSub = clampScore(100.0 - Math.max(0.0, avgJerk - 5.0) * 2.2);
        double brakeSub = clampScore(100.0 - (hardBrakes + hardAccels) / Math.max(km, 1.0) * 12.0);
        double cornerSub = clampScore(100.0 - hardCorners / Math.max(km, 1.0) * 16.0);

        int driverScore;
        if (slowingSeconds >= MIN_REGEN_SLOW_S) {
            double regenSub = clampScore(50.0 + 50.0 * regenSlowSeconds / slowingSeconds);
            driverScore = clampScore(0.35 * smoothSub + 0.30 * brakeSub
                + 0.20 * cornerSub + 0.15 * regenSub);
        } else {
            driverScore = clampScore(0.40 * smoothSub + 0.35 * brakeSub + 0.25 * cornerSub);
        }
        return new Snapshot(roadScore, driverScore, roadBumps,
            hardBrakes, hardAccels, hardCorners, movingSeconds);
    }

    /** Computes and clears the session accumulator. */
    public synchronized Snapshot finish(double odoDistanceKm, int minMovingSeconds) {
        Snapshot s = snapshot(odoDistanceKm, minMovingSeconds);
        reset();
        return s;
    }

    private static int clampScore(double v) {
        return (int) Math.round(Math.max(0.0, Math.min(100.0, v)));
    }
}
