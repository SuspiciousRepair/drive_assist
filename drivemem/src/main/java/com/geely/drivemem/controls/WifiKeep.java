package com.geely.drivemem.controls;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.util.Prefs;

/** The one place that may turn Wi-Fi back on, and only when the user's
 * "Keep Wi-Fi on" switch allows it (issue #14).
 *
 * This car does not rejoin Wi-Fi by itself after it suspends, and MQTT, OTA
 * and Home Assistant all need the network — that is why the app used to force
 * Wi-Fi on from several places. A user who does not use those must be able to
 * switch Wi-Fi off and have it stay off. modehelper keeps its own copy of the
 * switch (it forces Wi-Fi too), pushed by {@link #syncHelper}. */
public final class WifiKeep {
    static final String HELPER_SET = "com.geely.modehelper.SET_WIFI_KEEPON";

    private WifiKeep() {}

    /** Turns Wi-Fi on if it is off AND the switch allows it. {@code who} is for the log. */
    public static void ensure(Context ctx, String who) {
        if (!Prefs.getWifiKeepOn(ctx)) return;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                                              .getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                Log.i(CarAccess.TAG, who + ": turning Wi-Fi back on -> " + wm.setWifiEnabled(true));
            }
        } catch (Throwable t) { Log.w(CarAccess.TAG, who + " wifi: " + t); }
    }

    /** Saves the switch and tells modehelper. */
    public static void set(Context ctx, boolean on) {
        Prefs.setWifiKeepOn(ctx, on);
        syncHelper(ctx);
        if (on) ensure(ctx, "wifi-keep");
    }

    /** Pushes the current switch to modehelper. Idempotent and cheap, so it is
     * safe to call on every watchdog tick: a modehelper that was reinstalled or
     * missed a broadcast catches up on the next one. */
    public static void syncHelper(Context ctx) {
        try {
            Intent i = new Intent(HELPER_SET).setPackage(Updater.HELPER_PKG);
            i.putExtra("on", Prefs.getWifiKeepOn(ctx));
            ctx.sendBroadcast(i);
        } catch (Throwable t) { Log.w(CarAccess.TAG, "wifi-keep sync: " + t); }
    }
}
