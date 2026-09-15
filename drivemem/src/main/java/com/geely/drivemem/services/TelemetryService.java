package com.geely.drivemem.services;

import com.geely.drivemem.R;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.sensors.GpsReader;
import com.geely.drivemem.util.Beat;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

/** Publishes telemetry to Home Assistant over MQTT-WS on a fixed schedule.
 * Pulls cached data from CarActor (never triggers fresh reads). GPS updates are
 * received on this service's own thread. Configuration stored in SharedPreferences. */
public class TelemetryService extends Service {
    static final String TAG = "DriveMem";
    private HandlerThread thread;
    private Handler h;
    private MqttReporter mqtt;
    private volatile boolean running = false;
    private int publishIntervalMs = 10000;
    // signature of the broker config the current mqtt client was built from; if
    // the screen changes address/credentials we rebuild it without demanding Off->On
    private volatile String mqttCfg;

    public static final String ACTION_FORCE_DISCOVERY = "com.geely.drivemem.FORCE_DISCOVERY";

    private static volatile TelemetryService instance;
    public static TelemetryService getInstance() { return instance; }
    public boolean isRunning() { return running; }
    public boolean isConnected() { return mqtt != null && mqtt.isConnected(); }
    public String getConnectedBroker() { return mqtt != null ? mqtt.getConnectedBroker() : null; }

    public void triggerDiscovery(MqttReporter.Result cb) {
        if (!running || h == null) {
            if (cb != null) cb.onResult(false, getString(R.string.cfg_discovery_svc_inactive));
            return;
        }
        h.post(() -> {
            if (mqtt != null) {
                mqtt.forceDiscovery(cb);
            } else if (cb != null) {
                cb.onResult(false, getString(R.string.cfg_discovery_no_client));
            }
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        instance = this;
        if (i != null && ACTION_FORCE_DISCOVERY.equals(i.getAction())) {
            triggerDiscovery(null);
            return START_STICKY;
        }
        startAsForeground(); // Android 9: lets us start from boot without being blocked
        // Core car-state tracking (CarActor, TripSession, ParkSession,
        // ChargeSession, EnergyIntegrator, ComfortHub, etc.) used to be
        // bootstrapped here, on the theory that this service is the thing
        // guaranteed to start early. It moved to DriveMemApplication.onCreate()
        // — see that class's own comment for why: this service is neither
        // guaranteed to run (BootReceiver skips it entirely when MQTT
        // telemetry is off) nor reliably alive (it crashed and was
        // force-restarted four times in one evening, 2026-09-14). Nothing
        // below this point is anything but the MQTT-to-HA publishing this
        // service is actually named for.
        SharedPreferences p = getSharedPreferences("drivemem", MODE_PRIVATE);
        if (!p.getBoolean("tele_enabled", false)) { stopSelf(); return START_NOT_STICKY; }
        String uri = p.getString("mqtt_uri", "");
        if (uri.isEmpty()) { Log.w(TAG, "tele: no mqtt_uri"); stopSelf(); return START_NOT_STICKY; }
        publishIntervalMs = Math.max(5, p.getInt("tele_interval_s", 10)) * 1000;

        if (running) {
            // service already running. If the broker changed (address/credentials),
            // rebuild the client ON THE LOOP'S OWN THREAD (the same one that uses
            // `mqtt`, so no race); otherwise just re-apply the commands lock.
            final String newCfg = cfgSig(p);
            final boolean cmds = p.getBoolean("commands_enabled", true);
            h.post(() -> {
                if (!newCfg.equals(mqttCfg)) {
                    Log.i(TAG, "tele: broker config changed — reconnecting");
                    if (mqtt != null) mqtt.close();
                    mqtt = buildReporter(p);
                } else if (mqtt != null) {
                    mqtt.setCommandsEnabled(cmds);
                }
            });
            return START_STICKY;
        }
        running = true;
        thread = new HandlerThread("tele"); thread.start();   // GPS listener delivery only
        h = new Handler(thread.getLooper());
        mqtt = buildReporter(p);
        registerGps();
        h.post(this::publishTick);
        Log.i(TAG, "TelemetryService started, publish " + publishIntervalMs + "ms");
        return START_STICKY;
    }

    // This service's own clock — publishes on a fixed cadence regardless of
    // whether anything changed, which is what a periodic MQTT/HA update is
    // supposed to be (HA wants "still here, still parked at X" too, not
    // only edges). Reads CarActor's cache, never triggers a fresh car read.
    private void publishTick() {
        if (!running) return;
        try {
            CarActor.Reading r = CarActor.get(this).get("telemetry.tick");
            if (r.status == CarActor.Reading.Status.OK) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> data = (java.util.Map<String, Object>) r.value;
                mqtt.publish(data);
                Log.i(TAG, "tele published: " + data);
                double[] loc = GpsReader.read(getApplicationContext());
                if (loc != null) mqtt.publishLocation(loc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "tele publish error: " + t);
        }
        // Heartbeat: marks that this loop turned — NOT that the publish
        // succeeded. A broker outage must not read as "the thread is dead"
        // to the watchdog; a sleeping car with no reading yet is likewise
        // healthy, not stuck.
        Beat.mark(this, Beat.TELE);
        if (running) h.postDelayed(this::publishTick, publishIntervalMs);
    }

    private android.location.LocationListener gpsListener;

    // Keeps the GPS producing FRESH fixes. Without this getLastKnownLocation
    // keeps returning the same stale cache and the position freezes while moving.
    private void registerGps() {
        try {
            android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            gpsListener = new android.location.LocationListener() {
                @Override public void onLocationChanged(android.location.Location loc) { GpsReader.live = loc; }
                @Override public void onStatusChanged(String prov, int st, android.os.Bundle ex) {}
                @Override public void onProviderEnabled(String prov) {}
                @Override public void onProviderDisabled(String prov) {}
            };
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER,
                2000L, 0f, gpsListener, thread.getLooper());
            try {
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER,
                    5000L, 0f, gpsListener, thread.getLooper());
            } catch (Throwable ignored) {}
            Log.i(TAG, "GPS: updates registered");
        } catch (SecurityException se) { Log.w(TAG, "GPS permission denied: " + se);
        } catch (Throwable t) { Log.w(TAG, "GPS register error: " + t); }
    }

    // signature of the config that matters for the connection (excludes interval/toggles)
    private static String cfgSig(SharedPreferences p) {
        return p.getString("mqtt_uri", "") + "\n" + p.getString("mqtt_uri_alt", "")
             + "\n" + p.getString("mqtt_user", "") + "\n" + p.getString("mqtt_pass", "");
    }

    // creates the reporter, applies the commands lock and records the current signature
    private MqttReporter buildReporter(SharedPreferences p) {
        MqttReporter r = new MqttReporter(p.getString("mqtt_uri", ""), p.getString("mqtt_uri_alt", ""),
                p.getString("mqtt_user", ""), p.getString("mqtt_pass", ""), this);
        r.setCommandsEnabled(p.getBoolean("commands_enabled", true));
        mqttCfg = cfgSig(p);
        return r;
    }

    // minimal notification so we can run as a foreground service (allows starting at boot)
    private void startAsForeground() {
        try {
            String ch = "drivemem_tele";
            android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel c = new android.app.NotificationChannel(
                    ch, getString(R.string.notif_tele_channel), android.app.NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(c);
            }
            android.app.Notification n = new android.app.Notification.Builder(this, ch)
                .setContentTitle(getString(R.string.notif_tele_title))
                .setContentText(getString(R.string.notif_tele_text))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build();
            startForeground(42, n);
        } catch (Throwable t) {
            // Failing here is NOT cosmetic: anything entered via startForegroundService
            // that does not call startForeground is killed by the system shortly
            // after, with an ANR/crash. Log as an error so the reason shows up in logcat.
            Log.e(TAG, "startForeground FAILED — the system will kill the service: " + t, t);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // Cleanup runs off the main thread to avoid blocking. Blocking close() on the
    // main thread could prevent onStartCommand delivery and break watchdog resurrection.
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        running = false;
        Beat.clear(this, Beat.TELE);       // a deliberate stop must not cause a restart
        if (gpsListener != null) {
            try { ((android.location.LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(gpsListener); }
            catch (Throwable ignored) {}
            gpsListener = null;
        }
        // mqtt.close() is already non-blocking (it posts CLOSE to the actor's
        // mailbox and lets that thread finish on its own). CarActor's own
        // connection is process-lifetime now, same as ComfortHub's — this
        // service stopping does not tear it down.
        final MqttReporter m = mqtt;
        mqtt = null;
        try { if (m != null) m.close(); } catch (Throwable ignored) {}
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
