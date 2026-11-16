package com.geely.drivemem.sensors;

import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Prefs;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Owns the app's one location listener. Without a listener the GPS stays
 * idle and getLastKnownLocation returns the same old fix for ever.
 *
 * The listener used to live in TelemetryService, which stops itself when
 * MQTT is off, so a driver without MQTT got no real fixes: no trip routes,
 * no height profile, no ascent / descent. This owner does not depend on MQTT.
 *
 * It listens while a consumer needs fixes ({@link #want}) and removes the
 * listener when the car has been in Park for a while, so the GPS is not held
 * on in a parked car.
 *
 * {@link #ensure} is idempotent and may be called from any thread: the work
 * is posted to the main thread, and fixes arrive there too. The callback
 * only stores the fix (GpsReader.offerLive); LocationHub polls it from the hub. */
public final class GpsOwner {
    static final String TAG = "DriveMem";
    /** How long after shifting to Park the GPS stays on (a charge that
     * starts right after parking still gets a live fix). */
    static final long PARK_GRACE_MS = 2 * 60 * 1000L;
    static final long GPS_INTERVAL_MS = 2000L;
    static final long NETWORK_INTERVAL_MS = 5000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context app;
    private static volatile boolean hooked;
    /** elapsedRealtime of the shift to Park, or -1 while out of Park. */
    private static volatile long parkedSinceMs = -1;
    // Main thread only.
    private static LocationListener listener;
    private static final Runnable APPLY = GpsOwner::apply;

    private GpsOwner() {}

    /** True when the listener should be registered. Pure.
     * @param consumer    a consumer of live fixes is switched on
     * @param parked      the car is in Park
     * @param parkedForMs how long it has been in Park */
    static boolean want(boolean consumer, boolean parked, long parkedForMs) {
        if (!consumer) return false;
        return !parked || parkedForMs < PARK_GRACE_MS;
    }

    /** True when something that needs live fixes is switched on: trip
     * routes (also the charge place), MQTT telemetry with a broker set, or
     * ABRP with "send location". Pure. */
    static boolean consumer(boolean routesOn, boolean teleOn, boolean mqttUriSet, boolean abrpLocationOn) {
        return routesOn || (teleOn && mqttUriSet) || abrpLocationOn;
    }

    /** Starts or stops listening as the switches and the Park state say.
     * Called at app start, by the watchdog, on Park edges and when a switch
     * changes. */
    public static void ensure(Context ctx) {
        if (ctx == null) return;
        app = ctx.getApplicationContext();
        if (!hooked) {
            synchronized (GpsOwner.class) {
                if (!hooked) {
                    hooked = true;
                    CarState.addListener(GpsOwner::onParked);
                    // CarState starts as "parked" until the first gear read.
                    if (!CarState.isParked()) parkedSinceMs = -1;
                    else if (parkedSinceMs < 0) parkedSinceMs = SystemClock.elapsedRealtime();
                }
            }
        }
        MAIN.removeCallbacks(APPLY);
        MAIN.post(APPLY);
    }

    // Hub thread: two cheap posts, no location call here.
    private static void onParked(boolean parked) {
        parkedSinceMs = parked ? SystemClock.elapsedRealtime() : -1;
        MAIN.removeCallbacks(APPLY);
        MAIN.post(APPLY);
    }

    // Main thread.
    private static void apply() {
        final Context c = app;
        if (c == null) return;
        try {
            boolean consumer = consumer(Prefs.getRecordRoutes(c), Prefs.getTeleEnabled(c),
                !Prefs.getMqttUri(c, "").isEmpty(), AbrpUploader.isLocationEnabled(c));
            long since = parkedSinceMs;
            boolean parked = since >= 0;
            long parkedFor = parked ? SystemClock.elapsedRealtime() - since : 0;
            boolean want = want(consumer, parked, parkedFor);
            if (want && listener == null) start(c);
            else if (!want && listener != null) stop(c);
            // Still in the grace time: look again when it ends.
            MAIN.removeCallbacks(APPLY);
            if (want && parked) MAIN.postDelayed(APPLY, PARK_GRACE_MS - parkedFor + 1000L);
        } catch (Throwable t) { Log.w(TAG, "GPS owner: " + t); }
    }

    private static void start(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            LocationListener l = new LocationListener() {
                @Override public void onLocationChanged(Location loc) { GpsReader.offerLive(loc); }
                @Override public void onStatusChanged(String prov, int st, Bundle ex) {}
                @Override public void onProviderEnabled(String prov) {}
                @Override public void onProviderDisabled(String prov) {}
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, l, Looper.getMainLooper());
            listener = l;
            // Keeps the network provider's last-known fix fresh for
            // GpsReader's final fallback. It never becomes the live fix.
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NETWORK_INTERVAL_MS, 0f, l, Looper.getMainLooper());
            } catch (Throwable ignored) {}
            Log.i(TAG, "GPS: updates registered");
        } catch (SecurityException se) { Log.w(TAG, "GPS permission denied: " + se);
        } catch (Throwable t) { Log.w(TAG, "GPS register error: " + t); }
    }

    private static void stop(Context c) {
        try { ((LocationManager) c.getSystemService(Context.LOCATION_SERVICE)).removeUpdates(listener); }
        catch (Throwable ignored) {}
        listener = null;
        GpsReader.clearLive();
        Log.i(TAG, "GPS: updates removed");
    }
}
