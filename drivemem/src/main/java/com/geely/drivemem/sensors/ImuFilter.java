package com.geely.drivemem.sensors;

/** Pure-Java accelerometer processor for the head-unit IMU.
 *
 * Separates gravity in software (MTK's TYPE_LINEAR_ACCELERATION registers as
 * active on the IHU629G but delivers zero events), auto-calibrates the head
 * unit's mounting orientation into car axes (up / forward / right), and
 * accumulates 1-second window statistics without per-sample heap allocations.
 *
 * Sign conventions (car frame):
 * - az (+): upward jolt (bump compression)
 * - ax (+): forward acceleration; (-) braking
 * - ay (+): rightward acceleration (centripetal during a right turn)
 */
public final class ImuFilter {
    public static final float G = 9.80665f;
    private static final float GRAV_TAU_REST_S = 1.0f;
    // The head unit is rigidly bolted to the dash, so gravity only shifts slowly
    // with road grade/banking. An 8 s time constant tracks hills without washing
    // out 2-4 s launches, stops, or sustained corners.
    private static final float GRAV_TAU_MOVING_S = 8.0f;
    private static final float REST_SPEED_KMH = 0.5f;
    private static final float REST_MIN_G = 8.4f;
    private static final float REST_MAX_G = 11.2f;
    private static final float REST_MAX_STEP = 0.25f;
    private static final int CAL_Z_SAMPLES = 15;
    private static final float CAL_FWD_WEIGHT_TARGET = 4.0f; // ~14 km/h of confirmed dv
    private static final float MIN_CAL_DVDT = 1.1f;      // m/s^2 straight accel/brake
    private static final float MIN_CAL_HORIZ = 0.55f;    // m/s^2 horizontal linear accel
    private static final float MAX_CAL_HORIZ = 12.0f;    // reject impact spikes

    // Orthonormal basis in sensor coordinates: up (+Z_car), fwd (+X_car), right (+Y_car).
    // Default matches a portrait/landscape dash panel until rest gravity arrives.
    private float upX = 0f, upY = -0.971f, upZ = -0.238f;
    private float fwdX = 0f, fwdY = -0.238f, fwdZ = 0.971f;
    private float rightX = 1f, rightY = 0f, rightZ = 0f;

    // Rest-gravity calibration accumulator.
    private double restX, restY, restZ;
    private int restCount;
    private boolean calZ;

    // Forward-axis calibration accumulator (weighted sum of horizontal vectors aligned with +dv/dt).
    private double fwdAccX, fwdAccY, fwdAccZ;
    private float fwdWeight;
    private boolean calXy;

    // IIR gravity estimate in sensor coordinates.
    private boolean gravInit;
    private float gravX, gravY, gravZ;

    // Previous raw sample & previous horizontal linear accel for rest check and jerk.
    private float prevRawX, prevRawY, prevRawZ;
    private boolean hasPrevRaw;
    private float prevAx, prevAy;
    private boolean hasPrevHoriz;
    private long prevSampleNs;

    // Speed derivative tracking for forward-axis calibration.
    private float lastSpeedMps = Float.NaN;
    private long lastSpeedNs;
    private float lastDvDt;
    private long lastDvDtNs;

    // Latest instantaneous calibrated linear acceleration (m/s^2).
    private float curAx, curAy, curAz;

    // 1-second summary bucket accumulators (primitive-only, zero allocations).
    private int winCount;
    private int winJerkCount;
    private double sumRx, sumRy, sumRz;
    private double sumAx, sumAy, sumAz;
    private double sumSqZ;
    private float peakZ;          // signed value with largest |az|
    private float peakX;          // signed value with largest |ax|
    private float peakY;          // signed value with largest |ay|
    private double sumSqJerk;

    public ImuFilter() {
        rebuildHorizontalFromUp();
    }

    /** Restores a previously saved calibration string from {@link #serializeBasis()}. */
    public boolean restoreBasis(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] p = s.split(",");
        if (p.length != 8) return false;
        try {
            float ux = Float.parseFloat(p[0]), uy = Float.parseFloat(p[1]), uz = Float.parseFloat(p[2]);
            float fx = Float.parseFloat(p[3]), fy = Float.parseFloat(p[4]), fz = Float.parseFloat(p[5]);
            boolean cz = "1".equals(p[6]);
            boolean cxy = "1".equals(p[7]);
            float un = len(ux, uy, uz);
            if (un < 0.5f) return false;
            upX = ux / un; upY = uy / un; upZ = uz / un;
            calZ = cz;
            if (cxy) {
                if (!applyForwardVector(fx, fy, fz)) return false;
                calXy = true;
                fwdWeight = CAL_FWD_WEIGHT_TARGET;
                fwdAccX = fwdX * fwdWeight; fwdAccY = fwdY * fwdWeight; fwdAccZ = fwdZ * fwdWeight;
            } else {
                rebuildHorizontalFromUp();
                calXy = false;
            }
            if (cz) {
                restCount = CAL_Z_SAMPLES;
                restX = upX * G * restCount;
                restY = upY * G * restCount;
                restZ = upZ * G * restCount;
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** Compact CSV of the current basis for SharedPreferences persistence. */
    public String serializeBasis() {
        return String.format(java.util.Locale.US,
            "%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%d,%d",
            upX, upY, upZ, fwdX, fwdY, fwdZ, calZ ? 1 : 0, calXy ? 1 : 0);
    }

    public boolean isCalibratedZ() { return calZ; }
    public boolean isCalibratedXy() { return calXy; }

    public float ax() { return curAx; }
    public float ay() { return curAy; }
    public float az() { return curAz; }

    public float axG() { return curAx / G; }
    public float ayG() { return curAy / G; }
    public float azG() { return curAz / G; }

    /** Feeds vehicle speed (km/h) to estimate longitudinal dv/dt for forward-axis calibration. */
    public void onSpeedKmh(float speedKmh, long nowNs) {
        if (!Float.isFinite(speedKmh) || speedKmh < 0f) return;
        float v = speedKmh / 3.6f;
        if (!Float.isNaN(lastSpeedMps) && nowNs > lastSpeedNs) {
            float dt = (nowNs - lastSpeedNs) * 1e-9f;
            if (dt >= 0.35f && dt <= 2.5f) {
                lastDvDt = (v - lastSpeedMps) / dt;
                lastDvDtNs = nowNs;
                lastSpeedMps = v;
                lastSpeedNs = nowNs;
            } else if (dt > 2.5f) {
                lastSpeedMps = v;
                lastSpeedNs = nowNs;
                lastDvDt = 0f;
            }
        } else {
            lastSpeedMps = v;
            lastSpeedNs = nowNs;
        }
    }

    /** Processes one raw TYPE_ACCELEROMETER sample (m/s^2). Zero heap allocations. */
    public void onAccel(float rx, float ry, float rz, long timestampNs, float speedKmh) {
        float dt = 0f;
        if (prevSampleNs > 0L && timestampNs > prevSampleNs) {
            dt = (timestampNs - prevSampleNs) * 1e-9f;
            if (dt > 0.5f) dt = 0.5f;
        }
        prevSampleNs = timestampNs;

        // 1. Update gravity IIR (seeded on first sample to avoid startup spike).
        if (!gravInit) {
            gravX = rx; gravY = ry; gravZ = rz;
            gravInit = true;
        } else if (dt > 0f) {
            float tau = (speedKmh >= REST_SPEED_KMH) ? GRAV_TAU_MOVING_S : GRAV_TAU_REST_S;
            float alpha = dt / (tau + dt);
            gravX += alpha * (rx - gravX);
            gravY += alpha * (ry - gravY);
            gravZ += alpha * (rz - gravZ);
        }

        // 2. Stationary gravity calibration for Z_up.
        boolean quiet = false;
        if (hasPrevRaw) {
            float dx = rx - prevRawX, dy = ry - prevRawY, dz = rz - prevRawZ;
            quiet = (dx * dx + dy * dy + dz * dz) < (REST_MAX_STEP * REST_MAX_STEP);
        }
        prevRawX = rx; prevRawY = ry; prevRawZ = rz;
        hasPrevRaw = true;

        float rawMagSq = rx * rx + ry * ry + rz * rz;
        if (speedKmh >= 0f && speedKmh < REST_SPEED_KMH && quiet
                && rawMagSq >= REST_MIN_G * REST_MIN_G && rawMagSq <= REST_MAX_G * REST_MAX_G) {
            if (restCount < 200) {
                restX += rx; restY += ry; restZ += rz;
                restCount++;
            } else {
                // Slow EMA once settled so minor thermal drift tracks cleanly.
                restX = restX * 0.995 + rx;
                restY = restY * 0.995 + ry;
                restZ = restZ * 0.995 + rz;
            }
            if (restCount >= CAL_Z_SAMPLES) {
                float mx = (float) (restX / restCount);
                float my = (float) (restY / restCount);
                float mz = (float) (restZ / restCount);
                float n = len(mx, my, mz);
                if (n > 5f) {
                    upX = mx / n; upY = my / n; upZ = mz / n;
                    calZ = true;
                    if (calXy) {
                        applyForwardVector((float) fwdAccX, (float) fwdAccY, (float) fwdAccZ);
                    } else {
                        rebuildHorizontalFromUp();
                    }
                }
            }
        }

        // 3. Linear acceleration in sensor frame.
        float lx = rx - gravX;
        float ly = ry - gravY;
        float lz = rz - gravZ;

        // 4. Straight-line forward-axis calibration when CAN/VHAL speed shows clear accel/brake.
        if (calZ && speedKmh > 7.2f && (timestampNs - lastDvDtNs) < 1_000_000_000L
                && Math.abs(lastDvDt) >= MIN_CAL_DVDT) {
            float lDotUp = lx * upX + ly * upY + lz * upZ;
            float hx = lx - lDotUp * upX;
            float hy = ly - lDotUp * upY;
            float hz = lz - lDotUp * upZ;
            float hMagSq = hx * hx + hy * hy + hz * hz;
            if (hMagSq >= MIN_CAL_HORIZ * MIN_CAL_HORIZ && hMagSq <= MAX_CAL_HORIZ * MAX_CAL_HORIZ) {
                float sgn = lastDvDt > 0f ? 1f : -1f;
                float w = Math.min(3f, Math.abs(lastDvDt)) * (dt > 0f ? dt : 0.0625f);
                if (fwdWeight < 60f) {
                    fwdAccX += sgn * hx * w;
                    fwdAccY += sgn * hy * w;
                    fwdAccZ += sgn * hz * w;
                    fwdWeight += w;
                } else {
                    float decay = 0.995f;
                    fwdAccX = fwdAccX * decay + sgn * hx * w;
                    fwdAccY = fwdAccY * decay + sgn * hy * w;
                    fwdAccZ = fwdAccZ * decay + sgn * hz * w;
                }
                if (fwdWeight >= CAL_FWD_WEIGHT_TARGET
                        && applyForwardVector((float) fwdAccX, (float) fwdAccY, (float) fwdAccZ)) {
                    calXy = true;
                }
            }
        }

        // 5. Project into car frame: ax (fwd), ay (right), az (up).
        float ax = lx * fwdX + ly * fwdY + lz * fwdZ;
        float ay = lx * rightX + ly * rightY + lz * rightZ;
        float az = lx * upX + ly * upY + lz * upZ;
        curAx = ax; curAy = ay; curAz = az;

        // 6. Accumulate 1-second window stats.
        winCount++;
        sumRx += rx; sumRy += ry; sumRz += rz;
        sumAx += ax; sumAy += ay; sumAz += az;
        sumSqZ += (double) az * az;
        if (Math.abs(az) > Math.abs(peakZ)) peakZ = az;
        if (Math.abs(ax) > Math.abs(peakX)) peakX = ax;
        if (Math.abs(ay) > Math.abs(peakY)) peakY = ay;

        if (hasPrevHoriz && dt > 0.005f) {
            float jx = (ax - prevAx) / dt;
            float jy = (ay - prevAy) / dt;
            sumSqJerk += (double) jx * jx + (double) jy * jy;
            winJerkCount++;
        }
        prevAx = ax; prevAy = ay;
        hasPrevHoriz = true;
    }

    /** Immutable 1-second summary snapshot produced once per second (not per sample). */
    public static final class Summary {
        public final int count;
        public final boolean calZ, calXy;
        public final float rmsZ, peakZ;
        public final float meanX, peakX;
        public final float meanY, peakY;
        public final float jerkRms;
        public final float rawX, rawY, rawZ;

        Summary(int count, boolean calZ, boolean calXy,
                float rmsZ, float peakZ, float meanX, float peakX,
                float meanY, float peakY, float jerkRms,
                float rawX, float rawY, float rawZ) {
            this.count = count; this.calZ = calZ; this.calXy = calXy;
            this.rmsZ = rmsZ; this.peakZ = peakZ;
            this.meanX = meanX; this.peakX = peakX;
            this.meanY = meanY; this.peakY = peakY;
            this.jerkRms = jerkRms;
            this.rawX = rawX; this.rawY = rawY; this.rawZ = rawZ;
        }
    }

    /** Snapshots and resets the current summary window. Returns null if no samples arrived. */
    public Summary drainWindow() {
        if (winCount == 0) return null;
        int n = winCount;
        Summary s = new Summary(
            n, calZ, calXy,
            (float) Math.sqrt(sumSqZ / n), peakZ,
            (float) (sumAx / n), peakX,
            (float) (sumAy / n), peakY,
            winJerkCount > 0 ? (float) Math.sqrt(sumSqJerk / winJerkCount) : 0f,
            (float) (sumRx / n), (float) (sumRy / n), (float) (sumRz / n)
        );
        resetWindow();
        return s;
    }

    public void resetWindow() {
        winCount = 0; winJerkCount = 0;
        sumRx = sumRy = sumRz = 0;
        sumAx = sumAy = sumAz = 0;
        sumSqZ = sumSqJerk = 0;
        peakZ = peakX = peakY = 0f;
    }

    /** Resets transient IIR/window state on Park->Drive without discarding learned calibration. */
    public void onResumeStream() {
        gravInit = false;
        hasPrevRaw = false;
        hasPrevHoriz = false;
        prevSampleNs = 0L;
        lastSpeedMps = Float.NaN;
        lastDvDt = 0f;
        curAx = curAy = curAz = 0f;
        resetWindow();
    }

    /** Builds a deterministic default horizontal basis orthogonal to {@code up}. */
    private void rebuildHorizontalFromUp() {
        // Project sensor +X onto the plane perpendicular to up (on the IHU629G,
        // sensor +X is nearly horizontal left-right). Fallback to +Y if up is near X.
        float refX = 1f, refY = 0f, refZ = 0f;
        if (Math.abs(upX) > 0.9f) { refX = 0f; refY = 1f; }
        float dot = refX * upX + refY * upY + refZ * upZ;
        float rx = refX - dot * upX;
        float ry = refY - dot * upY;
        float rz = refZ - dot * upZ;
        float rn = len(rx, ry, rz);
        rightX = rx / rn; rightY = ry / rn; rightZ = rz / rn;
        // fwd = up x right so that fwd x up == right (right-handed).
        fwdX = upY * rightZ - upZ * rightY;
        fwdY = upZ * rightX - upX * rightZ;
        fwdZ = upX * rightY - upY * rightX;
    }

    /** Orthogonalizes {@code (fx, fy, fz)} against {@code up} and updates fwd & right. */
    private boolean applyForwardVector(float fx, float fy, float fz) {
        float dot = fx * upX + fy * upY + fz * upZ;
        float px = fx - dot * upX;
        float py = fy - dot * upY;
        float pz = fz - dot * upZ;
        float n = len(px, py, pz);
        if (n < 1e-3f) return false;
        fwdX = px / n; fwdY = py / n; fwdZ = pz / n;
        // right = fwd x up
        rightX = fwdY * upZ - fwdZ * upY;
        rightY = fwdZ * upX - fwdX * upZ;
        rightZ = fwdX * upY - fwdY * upX;
        return true;
    }

    private static float len(float x, float y, float z) {
        return (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
    }
}
