package com.geely.drivemem.state;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.Obd2Reader;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;

/** Detects CarPlay connection state and re-enables Bluetooth if needed.
 *
 * The OEM CarPlay app disables the entire Bluetooth radio when connecting,
 * breaking OBD2 and other Bluetooth devices. Safe to re-enable after CarPlay
 * reports CONNECTED (safe from both CarPlay setup and OBD2 reconnection).
 * Tracks state via broadcast receiver; uses same static field pattern as
 * ChargeSession. */
public final class CarplayState {
    private static final String TAG = CarAccess.TAG;
    private static final String ACTION = "com.njda.carplay.broadcast";
    // Time for CarPlay's A2DP/HFP teardown before re-enabling Bluetooth.
    private static final long REENABLE_DELAY_MS = 5000;
    // Time for Bluetooth radio to complete startup sequence after enable().
    // Prevents OBD2 reconnect attempts while radio is still powering up.
    private static final long RECONNECT_AFTER_ENABLE_MS = 2500;

    private static volatile boolean subscribed = false;
    private static volatile boolean connected = false;

    private CarplayState() {}

    public static boolean connected() { return connected; }

    /** Subscribes to CarPlay events; idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        Handler h = new Handler(app.getMainLooper());
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    String n = intent.getStringExtra("notification");
                    if ("connected".equals(n)) {
                        connected = true;
                        Log.i(TAG, "carplay: connected");
                        h.postDelayed(() -> reenableBluetoothIfOff(h), REENABLE_DELAY_MS);
                    } else if ("disconnected".equals(n)) {
                        connected = false;
                        Log.i(TAG, "carplay: disconnected");
                    }
                }
            }, new IntentFilter(ACTION));
        } catch (Throwable t) { Log.w(TAG, "carplay: register: " + t); }
    }

    private static void reenableBluetoothIfOff(Handler h) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null && !a.isEnabled()) {
                Log.i(TAG, "carplay: still connected, bluetooth still off -- turning it back on");
                a.enable();
                // Don't wait for Obd2Reader's own RETRY_MS (45s default).
                h.postDelayed(Obd2Reader::forceReconnectSoon, RECONNECT_AFTER_ENABLE_MS);
            }
        } catch (Throwable t) { Log.w(TAG, "carplay: re-enable bluetooth: " + t); }
    }
}
