package com.geely.drivemem.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to the app's single "drivemem" SharedPreferences file — the
 * one place the raw file name and every key string live, so a typo in a key
 * can no longer silently read or write the wrong preference with no compile
 * error. Every key string and default value here is unchanged from its
 * previous call site — this is a pure refactor, not a behavior change, so
 * existing users' saved settings still read back exactly as before. */
public final class Prefs {
    private Prefs() {}

    public static SharedPreferences file(Context ctx) {
        return ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
    }

    /** Wipes every saved preference (MQTT credentials, saved modes, etc). */
    public static void clearAll(Context ctx) {
        file(ctx).edit().clear().commit();
    }

    // --- ABRP ---
    public static boolean getAbrpEnabled(Context ctx) { return file(ctx).getBoolean("abrp_enabled", false); }
    public static void setAbrpEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("abrp_enabled", v).apply(); }
    public static String getAbrpUserToken(Context ctx) { return file(ctx).getString("abrp_user_token", ""); }
    public static void setAbrpUserToken(Context ctx, String v) { file(ctx).edit().putString("abrp_user_token", v).apply(); }

    // --- Driver assistance toggles ---
    public static boolean getAebOn(Context ctx) { return file(ctx).getBoolean("aeb_on", true); }
    public static void setAebOn(Context ctx, boolean v) { file(ctx).edit().putBoolean("aeb_on", v).apply(); }
    public static boolean getAvasOn(Context ctx) { return file(ctx).getBoolean("avas_on", true); }
    public static void setAvasOn(Context ctx, boolean v) { file(ctx).edit().putBoolean("avas_on", v).apply(); }
    public static boolean getParkedMonitoring(Context ctx) { return file(ctx).getBoolean("parked_monitoring", false); }
    public static void setParkedMonitoring(Context ctx, boolean v) { file(ctx).edit().putBoolean("parked_monitoring", v).apply(); }

    // --- ADB gate (AdbGate.java) ---
    public static final String KEY_TRUSTED_SSID = "adb_trusted_ssid";
    public static final String KEY_HOME_GW = "adb_home_gw";
    public static String getTrustedSsid(Context ctx, String def) { return file(ctx).getString(KEY_TRUSTED_SSID, def); }
    public static void setTrustedSsid(Context ctx, String v) { file(ctx).edit().putString(KEY_TRUSTED_SSID, v).apply(); }
    public static String getHomeGateway(Context ctx, String def) { return file(ctx).getString(KEY_HOME_GW, def); }
    public static void setHomeGateway(Context ctx, String v) { file(ctx).edit().putString(KEY_HOME_GW, v).apply(); }

    // --- Watchdog heartbeats (Beat.java) — dynamic per-watchdog-id keys ---
    public static long getBeat(Context ctx, String who, long def) { return file(ctx).getLong("beat_" + who, def); }
    public static void setBeat(Context ctx, String who, long v) { file(ctx).edit().putLong("beat_" + who, v).apply(); }
    public static void removeBeat(Context ctx, String who) { file(ctx).edit().remove("beat_" + who).apply(); }
    public static long getRestart(Context ctx, String who, long def) { return file(ctx).getLong("restart_" + who, def); }
    public static void setRestart(Context ctx, String who, long v) { file(ctx).edit().putLong("restart_" + who, v).apply(); }

    // --- Commands / MQTT ---
    public static boolean getCommandsEnabled(Context ctx) { return file(ctx).getBoolean("commands_enabled", true); }
    public static void setCommandsEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("commands_enabled", v).apply(); }
    public static String getMqttUri(Context ctx, String def) { return file(ctx).getString("mqtt_uri", def); }
    public static void setMqttUri(Context ctx, String v) { file(ctx).edit().putString("mqtt_uri", v).apply(); }
    public static String getMqttUriAlt(Context ctx) { return file(ctx).getString("mqtt_uri_alt", ""); }
    public static void setMqttUriAlt(Context ctx, String v) { file(ctx).edit().putString("mqtt_uri_alt", v).apply(); }
    public static String getMqttUser(Context ctx, String def) { return file(ctx).getString("mqtt_user", def); }
    public static void setMqttUser(Context ctx, String v) { file(ctx).edit().putString("mqtt_user", v).apply(); }
    public static String getMqttPass(Context ctx) { return file(ctx).getString("mqtt_pass", ""); }
    public static void setMqttPass(Context ctx, String v) { file(ctx).edit().putString("mqtt_pass", v).apply(); }
    public static String getMqttTlsTestTarget(Context ctx) { return file(ctx).getString("mqtt_tls_test_target", ""); }
    public static void setMqttTlsTestTarget(Context ctx, String v) { file(ctx).edit().putString("mqtt_tls_test_target", v).apply(); }

    // --- Dashcam ---
    public static int getDashcamLimitGb(Context ctx) { return file(ctx).getInt("dashcam_limit_gb", 10); }
    public static void setDashcamLimitGb(Context ctx, int v) { file(ctx).edit().putInt("dashcam_limit_gb", v).apply(); }

    // --- Drive mode / regen / turbo ---
    public static int getDriveMode(Context ctx, int def) { return file(ctx).getInt("drive", def); }
    public static void setDriveMode(Context ctx, int v) { file(ctx).edit().putInt("drive", v).apply(); }
    public static int getRegen(Context ctx, int def) { return file(ctx).getInt("regen", def); }
    public static void setRegen(Context ctx, int v) { file(ctx).edit().putInt("regen", v).apply(); }
    public static boolean getTurboEnabled(Context ctx) { return file(ctx).getBoolean("turbo_enabled", true); }
    public static void setTurboEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("turbo_enabled", v).apply(); }
    public static int getTurboDurationS(Context ctx, int def) { return file(ctx).getInt("turbo_duration_s", def); }
    public static void setTurboDurationS(Context ctx, int v) { file(ctx).edit().putInt("turbo_duration_s", v).apply(); }
    public static int getSwitchLockS(Context ctx, int def) { return file(ctx).getInt("switch_lock_s", def); }
    public static void setSwitchLockS(Context ctx, int v) { file(ctx).edit().putInt("switch_lock_s", v).apply(); }

    // --- Drive card ---
    public static boolean getDriveCardEnabled(Context ctx) { return file(ctx).getBoolean("drive_card_enabled", true); }
    public static void setDriveCardEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("drive_card_enabled", v).apply(); }
    public static long getDriveCardDismissedTrip(Context ctx) { return file(ctx).getLong("drive_card_dismissed_trip", -1); }
    public static void setDriveCardDismissedTrip(Context ctx, long v) { file(ctx).edit().putLong("drive_card_dismissed_trip", v).apply(); }
    public static int getPanelDismissed(Context ctx) { return file(ctx).getInt("panel_dismissed", 0); }
    public static void setPanelDismissed(Context ctx, int v) { file(ctx).edit().putInt("panel_dismissed", v).apply(); }

    // --- Doors / windows ---
    public static final String KEY_WINDOW_ON_DOOR = "window_on_door";
    public static boolean getWindowOnDoor(Context ctx) { return file(ctx).getBoolean(KEY_WINDOW_ON_DOOR, false); }
    public static void setWindowOnDoor(Context ctx, boolean v) { file(ctx).edit().putBoolean(KEY_WINDOW_ON_DOOR, v).apply(); }

    // --- OBD2 ---
    public static boolean getObd2Enabled(Context ctx) { return file(ctx).getBoolean("obd2_enabled", false); }
    public static void setObd2Enabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("obd2_enabled", v).apply(); }

    // --- Status bar icons ---
    public static boolean getOutTempOn(Context ctx) { return file(ctx).getBoolean("outtemp_on", false); }
    public static void setOutTempOn(Context ctx, boolean v) { file(ctx).edit().putBoolean("outtemp_on", v).apply(); }
    public static boolean getOverlayOn(Context ctx) { return file(ctx).getBoolean("overlay_on", false); }
    public static void setOverlayOn(Context ctx, boolean v) { file(ctx).edit().putBoolean("overlay_on", v).apply(); }
    public static boolean getSocOn(Context ctx) { return file(ctx).getBoolean("soc_on", false); }
    public static void setSocOn(Context ctx, boolean v) { file(ctx).edit().putBoolean("soc_on", v).apply(); }
    public static boolean getWifiIconOn(Context ctx) { return file(ctx).getBoolean("wifiicon_on", false); }
    public static void setWifiIconOn(Context ctx, boolean v) { file(ctx).edit().putBoolean("wifiicon_on", v).apply(); }

    // --- Skyline art ---
    public static boolean getSkylineEnabled(Context ctx) { return file(ctx).getBoolean("skyline_enabled", true); }
    public static void setSkylineEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("skyline_enabled", v).apply(); }
    public static boolean getSkylineRandomPerDrive(Context ctx) { return file(ctx).getBoolean("skyline_random_per_drive", false); }
    public static void setSkylineRandomPerDrive(Context ctx, boolean v) { file(ctx).edit().putBoolean("skyline_random_per_drive", v).apply(); }
    public static long getSkylineSeed(Context ctx, long def) { return file(ctx).getLong("skyline_seed", def); }
    public static void setSkylineSeed(Context ctx, long v) { file(ctx).edit().putLong("skyline_seed", v).apply(); }

    // --- Spotify ---
    public static boolean getSpotifyLargeCard(Context ctx) { return file(ctx).getBoolean("spotify_large_card", false); }
    public static void setSpotifyLargeCard(Context ctx, boolean v) { file(ctx).edit().putBoolean("spotify_large_card", v).apply(); }
    public static String getSpotifyClientId(Context ctx) { return file(ctx).getString("spotify_client_id", ""); }
    public static void setSpotifyClientId(Context ctx, String v) { file(ctx).edit().putString("spotify_client_id", v).apply(); }
    public static String getSpotifyAccessToken(Context ctx) { return file(ctx).getString("spotify_access_token", null); }
    public static void setSpotifyAccessToken(Context ctx, String v) { file(ctx).edit().putString("spotify_access_token", v).apply(); }
    public static String getSpotifyRefreshToken(Context ctx) { return file(ctx).getString("spotify_refresh_token", null); }
    public static void setSpotifyRefreshToken(Context ctx, String v) { file(ctx).edit().putString("spotify_refresh_token", v).apply(); }
    public static String getSpotifyPkceVerifier(Context ctx) { return file(ctx).getString("spotify_pkce_verifier", null); }
    public static void setSpotifyPkceVerifier(Context ctx, String v) { file(ctx).edit().putString("spotify_pkce_verifier", v).apply(); }
    public static long getSpotifyTokenExpiry(Context ctx) { return file(ctx).getLong("spotify_token_expiry", 0); }
    public static void setSpotifyTokenExpiry(Context ctx, long v) { file(ctx).edit().putLong("spotify_token_expiry", v).apply(); }

    // --- Telemetry ---
    public static boolean getTeleEnabled(Context ctx) { return file(ctx).getBoolean("tele_enabled", false); }
    public static void setTeleEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("tele_enabled", v).apply(); }
    public static int getTeleIntervalS(Context ctx) { return file(ctx).getInt("tele_interval_s", 10); }
    public static void setTeleIntervalS(Context ctx, int v) { file(ctx).edit().putInt("tele_interval_s", v).apply(); }

    // --- Theme / appearance ---
    public static String getAppearance(Context ctx, String def) { return file(ctx).getString("appearance", def); }
    public static void setAppearance(Context ctx, String v) { file(ctx).edit().putString("appearance", v).apply(); }
    public static String getTheme(Context ctx, String def) { return file(ctx).getString("theme", def); }
    public static void setTheme(Context ctx, String v) { file(ctx).edit().putString("theme", v).apply(); }

    // --- Updates ---
    public static String getUpdateUrl(Context ctx, String def) { return file(ctx).getString("update_url", def); }
    public static void setUpdateUrl(Context ctx, String v) { file(ctx).edit().putString("update_url", v).apply(); }
    public static String getUpdateLastUrl(Context ctx) { return file(ctx).getString("update_last_url", ""); }
    public static void setUpdateLastUrl(Context ctx, String v) { file(ctx).edit().putString("update_last_url", v).apply(); }
    public static int getSkipUpdateVc(Context ctx, boolean modehelper) {
        return file(ctx).getInt(modehelper ? "skip_update_vc_modehelper" : "skip_update_vc", 0);
    }
    public static void setSkipUpdateVc(Context ctx, boolean modehelper, int versionCode) {
        file(ctx).edit().putInt(modehelper ? "skip_update_vc_modehelper" : "skip_update_vc", versionCode).apply();
    }

    // --- One-time migration/rollup guards ---
    public static boolean getDbMigratedV1(Context ctx) { return file(ctx).getBoolean("db_migrated_v1", false); }
    public static void setDbMigratedV1(Context ctx) { file(ctx).edit().putBoolean("db_migrated_v1", true).apply(); }
    public static String getRollupLastDay(Context ctx) { return file(ctx).getString("rollup_last_day_v10", ""); }
    public static void setRollupLastDay(Context ctx, String day) { file(ctx).edit().putString("rollup_last_day_v10", day).apply(); }
}
