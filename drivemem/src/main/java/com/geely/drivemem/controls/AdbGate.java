package com.geely.drivemem.controls;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.net.Updater;

/** Controls ADB state through the privileged modehelper.
 *
 * Writing ADB_ENABLED requires WRITE_SECURE_SETTINGS, granted only to the
 * platform-signed modehelper; this class owns the UI and MQTT command surface,
 * plus the home-network guard. Reading state is unprivileged and always reflects
 * actual ADB state, not cached requests.
 */
public final class AdbGate {
    static final String HELPER_PKG = Updater.HELPER_PKG;
    static final String HELPER_SET = "com.geely.modehelper.SET_ADB";
    static final String HELPER_SET_TRUSTED = "com.geely.modehelper.SET_TRUSTED_WIFI";

    public static final int DEFAULT_MINUTES = 15;

    // Privileged Wi-Fi network (SSID) where modehelper keeps ADB open indefinitely
    // and holds a partial WakeLock to prevent head unit suspend during development.
    public static final String KEY_TRUSTED_SSID = "adb_trusted_ssid";
    public static final String DEFAULT_TRUSTED_SSID = "car";

    // Home network gateway (configurable via prefs, not hardcoded) to allow
    // adaptation when the network changes without requiring an app rebuild.
    // TODO: consider adding a UI to set this, in order to make this app public. home Wifi/network should be a user choice, not a hardcoded value.
    static final String KEY_HOME_GW = "adb_home_gw";
    static final String DEFAULT_HOME_GW = "192.168.0.1";

    private AdbGate() {}

    /** Sets the privileged Wi-Fi SSID in drivemem preferences and broadcasts it to modehelper. */
    public static void setTrustedWifi(Context ctx, String ssid) {
        String clean = (ssid != null) ? ssid.trim() : "";
        ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
           .edit().putString(KEY_TRUSTED_SSID, clean).apply();
        if (helperPresent(ctx)) {
            Intent i = new Intent(HELPER_SET_TRUSTED).setPackage(HELPER_PKG);
            i.putExtra("ssid", clean);
            ctx.sendBroadcast(i);
            Log.i(CarAccess.TAG, "adb: broadcasted trusted SSID \"" + clean + "\" to modehelper");
        }
    }

    /** Gets the configured privileged Wi-Fi SSID. */
    public static String getTrustedWifi(Context ctx) {
        return ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                  .getString(KEY_TRUSTED_SSID, DEFAULT_TRUSTED_SSID);
    }

    /** Gets the currently connected Wi-Fi SSID without quotes, or empty if disconnected. */
    public static String getCurrentSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                                              .getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "";
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String s = info.getSSID();
            if (s == null || "<unknown ssid>".equals(s)) return "";
            return s.replace("\"", "").trim();
        } catch (Throwable t) { return ""; }
    }

    /** Returns true if ADB is currently enabled in Settings. */
    public static boolean isEnabled(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                                          Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable t) { return false; }
    }

    static boolean helperPresent(Context ctx) {
        try { ctx.getPackageManager().getPackageInfo(HELPER_PKG, 0); return true; }
        catch (Throwable t) { return false; }
    }

    /** Requests modehelper to change ADB state. Returns false if modehelper is not
     * installed; actual result comes from re-reading the setting. */
    public static boolean request(Context ctx, boolean on, int minutes) {
        if (!helperPresent(ctx)) {
            Log.w(CarAccess.TAG, "adb: modehelper is not installed — nothing can flip adb "
                               + "from inside the app");
            return false;
        }
        Intent i = new Intent(HELPER_SET).setPackage(HELPER_PKG);
        i.putExtra("enable", on);
        i.putExtra("minutes", minutes);
        ctx.sendBroadcast(i);
        Log.i(CarAccess.TAG, "adb: asked the helper to turn it " + (on ? "ON" : "OFF"));
        return true;
    }

    /** Returns true if the car is on the home network (by gateway check).
     * This is the primary security guard for the MQTT path. */
    public static boolean isHome(Context ctx) {
        String want = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                         .getString(KEY_HOME_GW, DEFAULT_HOME_GW);
        String gw = gateway(ctx);
        boolean home = want.equals(gw);
        if (!home) Log.i(CarAccess.TAG, "adb: not home — gateway is " + gw + ", expected " + want);
        return home;
    }

    // TODO: Is DHCP really a load-bearing information that we can rely on? Any network with same gateway address would grant access. Consider using SSID instead.
    /** Reads the DHCP gateway address. Prefers gateway over SSID (which requires
     * location permission and is often unreliable). Returns null if unavailable. */
    static String gateway(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                                              .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            android.net.DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return null;
            int g = d.gateway;   // DhcpInfo stores as little-endian
            return (g & 0xff) + "." + ((g >> 8) & 0xff) + "."
                 + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (Throwable t) { return null; }
    }
}
