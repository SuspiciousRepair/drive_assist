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
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.util.Log;

import com.geely.drivemem.util.CarMediaSessions;

import java.util.Map;

/** Detects CarPlay connection state and re-enables Bluetooth if needed.
 *
 * The OEM CarPlay app disables the entire Bluetooth radio when connecting,
 * breaking OBD2 and other Bluetooth devices. Safe to re-enable after CarPlay
 * reports CONNECTED (safe from both CarPlay setup and OBD2 reconnection).
 *
 * Tracks state via:
 *  1. OEM CarPlay broadcast ({@code com.njda.carplay.broadcast}),
 *  2. Live USB inspection ({@link UsbManager} for Apple vendor ID 0x05AC / USB
 *     accessories) so wired cable CarPlay is detected immediately even after an
 *     app restart or OTA update when no fresh broadcast was fired, and
 *  3. Linked CarPlay media session ({@link CarMediaSessions#hasCarplaySession()}). */
public final class CarplayState {
    private static final String TAG = CarAccess.TAG;
    private static final String ACTION = "com.njda.carplay.broadcast";
    /** Apple Inc. USB Vendor ID (iPhone / iPad over cable CarPlay). */
    public static final int USB_VENDOR_APPLE = 0x05AC;
    // Time for CarPlay's A2DP/HFP teardown before re-enabling Bluetooth.
    private static final long REENABLE_DELAY_MS = 5000;
    // Time for Bluetooth radio to complete startup sequence after enable().
    // Prevents OBD2 reconnect attempts while radio is still powering up.
    private static final long RECONNECT_AFTER_ENABLE_MS = 2500;

    private static volatile boolean subscribed = false;
    private static volatile boolean connected = false;
    private static volatile Context appCtx = null;

    private CarplayState() {}

    public static boolean connected() { return isConnected(appCtx); }

    /** True when CarPlay is connected (broadcast, wired Apple USB device, or active session). */
    public static boolean isConnected(Context ctx) {
        if (connected) return true;
        Context c = ctx != null ? ctx.getApplicationContext() : appCtx;
        if (hasWiredAppleUsb(c)) return true;
        return CarMediaSessions.hasCarplaySession();
    }

    /** True when an Apple USB device (iPhone/iPad, VID 0x05AC) or CarPlay USB accessory is attached. */
    public static boolean hasWiredAppleUsb(Context ctx) {
        if (ctx == null) return false;
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um == null) return false;
            Map<String, UsbDevice> devs = um.getDeviceList();
            if (devs != null) {
                for (UsbDevice d : devs.values()) {
                    if (d != null && d.getVendorId() == USB_VENDOR_APPLE) return true;
                }
            }
            UsbAccessory[] accs = um.getAccessoryList();
            if (accs != null) {
                for (UsbAccessory a : accs) {
                    if (a == null) continue;
                    String m = a.getManufacturer();
                    String model = a.getModel();
                    if ((m != null && m.toLowerCase(java.util.Locale.ROOT).contains("apple"))
                            || (model != null && model.toLowerCase(java.util.Locale.ROOT).contains("carplay"))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** USB serial numbers of the attached Apple devices (an iPhone reports
     * its own unique id). The Security lock trusts only the owner's one. */
    public static java.util.Set<String> appleUsbSerials(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (ctx == null) return out;
        try {
            UsbManager um = (UsbManager) ctx.getApplicationContext().getSystemService(Context.USB_SERVICE);
            Map<String, UsbDevice> devs = um == null ? null : um.getDeviceList();
            if (devs == null) return out;
            for (UsbDevice d : devs.values()) {
                if (d == null || d.getVendorId() != USB_VENDOR_APPLE) continue;
                String sn = d.getSerialNumber();
                if (sn != null && !sn.trim().isEmpty()) out.add(sn.trim());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Subscribes to CarPlay events; idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        if (subscribed || appCtx == null) return;
        subscribed = true;
        Context app = appCtx;
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
