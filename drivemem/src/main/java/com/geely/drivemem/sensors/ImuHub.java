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
import com.geely.drivemem.state.CarState;

/** Low-cost head-unit accelerometer hub (IHU629G).
 *
 * Rides on the 16 Hz (80 ms) ACCELEROMETER stream that com.njda.adapter already
 * holds open since boot, on a dedicated background HandlerThread, gated to
 * driving only (0% CPU when parked).
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
            if (handler != null) handler.post(() -> syncRegistration(!parked));
        });
        handler.post(() -> syncRegistration(!CarState.isParked()));
    }

    /** Registers the live UI consumer (e.g. ComfortActivity -> VaporArtView). */
    public static void setListener(Listener l) {
        liveListener = l;
        Handler h = handler;
        if (h != null) h.post(() -> syncRegistration(!CarState.isParked()));
    }

    /** Clears the live UI consumer only if it is still {@code l}. */
    public static synchronized void clearListener(Listener l) {
        if (liveListener == l) {
            liveListener = null;
            Handler h = handler;
            if (h != null) h.post(() -> syncRegistration(!CarState.isParked()));
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
                Log.i(TAG, "imu: paused (parked)");
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
            Float speedKmh = readCachedSpeedKmh();
            float speedVal = speedKmh != null ? speedKmh : -1f;
            if (speedKmh != null) FILTER.onSpeedKmh(speedKmh, e.timestamp);
            FILTER.onAccel(e.values[0], e.values[1], e.values[2], e.timestamp, speedVal);

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
        Float speedKmh = readCachedSpeedKmh();
        Float driveKw = CanEnergyReader.liveDriveKw();
        Integer brake = readCachedBrake();
        LocationHub.Fix fix = LocationHub.get(c);
        LOG.write(c, nowMs, s, speedKmh, driveKw, brake, fix);
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

    private static Float readCachedSpeedKmh() {
        Float can = CanEnergyReader.liveSpeedKmh();
        if (can != null) return can;
        CarActor a = CarActor.get();
        if (a == null) return null;
        CarActor.Reading r = a.get("telemetry.speed");
        if (r != null && r.status == CarActor.Reading.Status.OK && r.value instanceof Number) {
            return ((Number) r.value).floatValue();
        }
        return null;
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
