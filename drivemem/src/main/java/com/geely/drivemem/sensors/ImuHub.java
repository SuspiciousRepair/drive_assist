package com.geely.drivemem.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.state.CarState;

/** Low-cost head-unit accelerometer hub (IHU629G).
 *
 * Rides on the 16 Hz (80 ms) ACCELEROMETER stream that com.njda.adapter already
 * holds open since boot, on a dedicated background HandlerThread, gated to
 * driving only (0% CPU when parked) and to a live CAN reader.
 *
 * It needs the CAN speed: the car's own speed in the hub can be 30 s old, and
 * with an old speed a door slam at a stop light looks like a bump on the road
 * and the "which way is up" calibration goes wrong. Without CAN it stays off.
 *
 * The 16 Hz stream is a direct path (the hub cannot take that rate). The hub
 * gets a 1 Hz copy: {@link #VERT_G}, {@link #LONG_G}, {@link #LAT_G}.
 *
 * Consumers:
 * - Live UI listener (Noturno VaporArtView bounce & cornering roll)
 * - Compact 1 Hz driving summary log (files/imu-summary.tsv) for Road Score /
 *   Driver Score tuning before committing to CarDb schema changes. */
public final class ImuHub {
    private static final String TAG = "DriveMem";
    private static final String PREFS = "drivemem";
    private static final String PREF_BASIS = "imu_basis_v2";

    // Matches SX11SensorManager's existing 80 ms (16 Hz) rate — zero extra HAL wakeups.
    private static final int SAMPLING_PERIOD_US = 80_000;
    // Batching latency when no live UI listener needs frame-by-frame motion.
    private static final int BG_BATCH_US = 500_000;
    private static final long SUMMARY_INTERVAL_MS = 1_000L;

    /** Jolt detection thresholds for Car Diary post-park incident review.
     * Calibrated above EX2 normal bumps (~0.39 g) and hard brakes (~0.36 g) on
     * the 16 Hz head-unit accelerometer, gated to moving speed >= 10 km/h so
     * door slams while parked never trigger false positives. */
    public static final float JOLT_MIN_SPEED_KMH = 10.0f;
    public static final float JOLT_PEAK_Z_G = 0.85f;
    public static final float JOLT_PEAK_HORIZ_G = 0.75f;

    /** Jolt sensitivity presets stored in Prefs ("off", "low", "medium", "high"). */
    public static final String JOLT_OFF = "off";
    public static final String JOLT_LOW = "low";
    public static final String JOLT_MEDIUM = "medium";
    public static final String JOLT_HIGH = "high";

    /** Returns {zThresholdG, horizThresholdG} for a sensitivity level, or null if off. Pure. */
    public static float[] joltThresholds(String sensitivity) {
        if (JOLT_OFF.equals(sensitivity)) return null;
        if (JOLT_LOW.equals(sensitivity)) return new float[]{1.10f, 0.95f};
        if (JOLT_HIGH.equals(sensitivity)) return new float[]{0.65f, 0.55f};
        return new float[]{JOLT_PEAK_Z_G, JOLT_PEAK_HORIZ_G};
    }

    /** Hub keys: the largest acceleration of the last second on each car axis,
     * in g, 0.1 g steps (Float). Only while driving with CAN live. */
    public static final String VERT_G = "imu.vert_g";
    public static final String LONG_G = "imu.long_g";
    public static final String LAT_G = "imu.lat_g";
    public static final String[] KEYS = {VERT_G, LONG_G, LAT_G};

    /** Instantaneous calibrated car-frame acceleration in g (+fwd, +right, +up). */
    public interface Listener { void onImu(float axG, float ayG, float azG); }

    private static final ImuFilter FILTER = new ImuFilter();
    private static final ImuSummaryLog LOG = new ImuSummaryLog();

    private static volatile Context app;
    private static volatile Listener liveListener;
    private static boolean started;
    private static HandlerThread thread;
    private static Handler handler;
    private static SensorManager sensorManager;
    private static Sensor accel;
    private static boolean registered;
    private static int registeredBatchUs = -1;
    private static long lastSummaryMs;
    private static boolean savedCalZ, savedCalXy;

    private ImuHub() {}

    /** Idempotent; called once from DriveMemApplication.onCreate(). */
    public static synchronized void ensureStarted(Context ctx) {
        if (started || ctx == null) return;
        started = true;
        app = ctx.getApplicationContext();
        sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        accel = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (accel == null) {
            Log.i(TAG, "imu: no TYPE_ACCELEROMETER on this device");
            return;
        }
        String saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_BASIS, null);
        if (FILTER.restoreBasis(saved)) {
            savedCalZ = FILTER.isCalibratedZ();
            savedCalXy = FILTER.isCalibratedXy();
            Log.i(TAG, "imu: restored basis calZ=" + savedCalZ + " calXy=" + savedCalXy);
        }
        thread = new HandlerThread("imu");
        thread.start();
        handler = new Handler(thread.getLooper());
        CarState.addListener(parked -> {
            if (handler != null) handler.post(ImuHub::syncRegistration);
        });
        com.geely.drivemem.car.EntityBus.subscribe(CanHub.LIVE, (key, r) -> {
            if (handler != null) handler.post(ImuHub::syncRegistration);
        });
        handler.post(ImuHub::syncRegistration);
    }

    /** Out of Park and the CAN reader is live (hub key can.live). */
    private static boolean wantActive() {
        if (CarState.isParked()) return false;
        CarActor a = CarActor.get();
        if (a == null) return false;
        CarActor.Reading r = a.get(CanHub.LIVE);
        return r != null && r.status == CarActor.Reading.Status.OK
            && r.value instanceof Number && ((Number) r.value).intValue() != 0;
    }

    private static void syncRegistration() { syncRegistration(wantActive()); }

    /** Registers the live UI consumer (e.g. ComfortActivity -> VaporArtView). */
    public static void setListener(Listener l) {
        liveListener = l;
        Handler h = handler;
        if (h != null) h.post(ImuHub::syncRegistration);
    }

    /** Clears the live UI consumer only if it is still {@code l}. */
    public static synchronized void clearListener(Listener l) {
        if (liveListener == l) {
            liveListener = null;
            Handler h = handler;
            if (h != null) h.post(ImuHub::syncRegistration);
        }
    }

    private static void syncRegistration(boolean wantActive) {
        if (sensorManager == null || accel == null || handler == null) return;
        int wantBatchUs = (liveListener != null) ? 0 : BG_BATCH_US;
        if (!wantActive) {
            if (registered) {
                sensorManager.unregisterListener(SENSOR_LISTENER);
                registered = false;
                registeredBatchUs = -1;
                flushSummary(System.currentTimeMillis());
                LOG.close();
                Listener l = liveListener;
                if (l != null) l.onImu(0f, 0f, 0f);
                publish(0f, 0f, 0f);
                Log.i(TAG, "imu: paused (parked or no CAN)");
            }
            return;
        }
        if (registered && registeredBatchUs == wantBatchUs) return;
        if (registered) {
            sensorManager.unregisterListener(SENSOR_LISTENER);
            registered = false;
        } else {
            FILTER.onResumeStream();
            lastSummaryMs = System.currentTimeMillis();
        }
        boolean ok = sensorManager.registerListener(
            SENSOR_LISTENER, accel, SAMPLING_PERIOD_US, wantBatchUs, handler);
        registered = ok;
        registeredBatchUs = ok ? wantBatchUs : -1;
        Log.i(TAG, "imu: registered 16Hz batch=" + wantBatchUs + "us ok=" + ok);
    }

    private static final SensorEventListener SENSOR_LISTENER = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
            // No fresh speed, no sample: an old speed would spoil the calibration.
            Float speedKmh = CanEnergyReader.liveSpeedKmh();
            if (speedKmh == null) return;
            FILTER.onSpeedKmh(speedKmh, e.timestamp);
            FILTER.onAccel(e.values[0], e.values[1], e.values[2], e.timestamp, speedKmh);

            Listener l = liveListener;
            if (l != null) {
                l.onImu(FILTER.axG(), FILTER.ayG(), FILTER.azG());
            }

            long nowMs = System.currentTimeMillis();
            if (lastSummaryMs == 0L) {
                lastSummaryMs = nowMs;
            } else if (nowMs - lastSummaryMs >= SUMMARY_INTERVAL_MS) {
                flushSummary(nowMs);
                lastSummaryMs = nowMs;
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private static void flushSummary(long nowMs) {
        Context c = app;
        if (c == null) return;
        ImuFilter.Summary s = FILTER.drainWindow();
        if (s == null) return;
        maybePersistBasis(c, s.calZ, s.calXy);
        Float speedKmh = CanEnergyReader.liveSpeedKmh();
        Float driveKw = CanEnergyReader.liveDriveKw();
        publish(s.peakZ / ImuFilter.G, s.peakX / ImuFilter.G, s.peakY / ImuFilter.G);
        Integer brake = readCachedBrake();
        LocationHub.Fix fix = LocationHub.get(c);
        LOG.write(c, nowMs, s, speedKmh, driveKw, brake, fix);
        if (shouldTriggerJolt(s, speedKmh, com.geely.drivemem.util.Prefs.getDiaryJoltSensitivity(c))) {
            float zG = s.peakZ / ImuFilter.G;
            float xG = s.peakX / ImuFilter.G;
            float yG = s.peakY / ImuFilter.G;
            float rmsZG = s.rmsZ / ImuFilter.G;
            float jerkZGs = s.peakJerkZ / ImuFilter.G;
            Log.i(TAG, String.format(java.util.Locale.US,
                "imu: jolt detected z=%.2fg jerkZ=%.1fg/s rmsZ=%.2fg x=%.2fg y=%.2fg speed=%.0fkm/h",
                zG, jerkZGs, rmsZG, xG, yG, speedKmh));
            CarDiary.recordPendingJolt(
                nowMs, zG, xG, yG, rmsZG, jerkZGs, speedKmh,
                CarDiary.latestOdometerKm(c),
                fix != null ? fix.lat : null,
                fix != null ? fix.lon : null);
        }
    }

    /** Pure jolt detector for Car Diary pending incident queue (default medium sensitivity). */
    public static boolean shouldTriggerJolt(ImuFilter.Summary s, Float speedKmh) {
        return shouldTriggerJolt(s, speedKmh, JOLT_MEDIUM);
    }

    /** Pure jolt detector with configurable sensitivity preset ("off", "low", "medium", "high"). */
    public static boolean shouldTriggerJolt(ImuFilter.Summary s, Float speedKmh, String sensitivity) {
        float[] th = joltThresholds(sensitivity);
        if (th == null) return false;
        if (s == null || !s.calZ || speedKmh == null || !Float.isFinite(speedKmh)) return false;
        if (speedKmh < JOLT_MIN_SPEED_KMH) return false;
        float absZG = Math.abs(s.peakZ) / ImuFilter.G;
        float absXG = Math.abs(s.peakX) / ImuFilter.G;
        float absYG = Math.abs(s.peakY) / ImuFilter.G;
        return absZG >= th[0] || absXG >= th[1] || absYG >= th[1];
    }

    private static void maybePersistBasis(Context c, boolean calZ, boolean calXy) {
        if ((calZ && !savedCalZ) || (calXy && !savedCalXy)) {
            savedCalZ = calZ;
            savedCalXy = calXy;
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putString(PREF_BASIS, FILTER.serializeBasis()).apply();
            Log.i(TAG, "imu: saved calibrated basis calZ=" + calZ + " calXy=" + calXy);
        }
    }

    /** The hub's 1 Hz copy, in 0.1 g steps so road buzz does not republish. */
    private static void publish(float vertG, float longG, float latG) {
        CarActor a = CarActor.get();
        if (a == null) return;
        a.inject(VERT_G, quantize(vertG));
        a.inject(LONG_G, quantize(longG));
        a.inject(LAT_G, quantize(latG));
    }

    static float quantize(float g) {
        return Float.isFinite(g) ? Math.round(g * 10f) / 10f : 0f;
    }

    private static Integer readCachedBrake() {
        CarActor a = CarActor.get();
        if (a == null) return null;
        CarActor.Reading r = a.get(CanHub.BRAKE);
        if (r != null && r.status == CarActor.Reading.Status.OK && r.value instanceof Number) {
            return ((Number) r.value).intValue();
        }
        return null;
    }
}
