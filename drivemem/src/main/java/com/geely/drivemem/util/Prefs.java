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
    public static boolean getAbrpSendLocation(Context ctx) { return file(ctx).getBoolean("abrp_send_location", true); }
    public static void setAbrpSendLocation(Context ctx, boolean v) { file(ctx).edit().putBoolean("abrp_send_location", v).apply(); }

    // --- Driver assistance toggles ---
    public static boolean getParkedMonitoring(Context ctx) { return file(ctx).getBoolean("parked_monitoring", false); }
    public static void setParkedMonitoring(Context ctx, boolean v) { file(ctx).edit().putBoolean("parked_monitoring", v).apply(); }

    // --- Restore at start (HelperSync.java, modehelper RestorePolicy) ---
    // Per setting: does modehelper write the saved value back to the car in the
    // 15 s after it wakes? (issues #12, #13, #16, #88). ON until the user turns it
    // off: Drive Assist owns the car's start state; the switch exists for a
    // user whose other app (e.g. Sapo) restores the same setting.
    public static final String RESTORE_DRIVE = "restore_drive", RESTORE_REGEN = "restore_regen",
                               RESTORE_AEB = "restore_aeb", RESTORE_AVAS = "restore_avas";
    public static boolean getRestore(Context ctx, String key) { return file(ctx).getBoolean(key, true); }
    public static void setRestore(Context ctx, String key, boolean v) { file(ctx).edit().putBoolean(key, v).apply(); }

    // --- Keep Wi-Fi on (WifiKeep.java) ---
    // Whether Drive Assist and modehelper turn Wi-Fi back on when it is off.
    // ON until the user turns it off: this car does not rejoin Wi-Fi after it
    // suspends, and MQTT, OTA and Home Assistant all need the network. A user
    // who wants Wi-Fi off (issue #14) switches it off once.
    public static final String KEY_WIFI_KEEP_ON = "wifi_keep_on";
    public static boolean getWifiKeepOn(Context ctx) { return file(ctx).getBoolean(KEY_WIFI_KEEP_ON, true); }
    public static void setWifiKeepOn(Context ctx, boolean v) { file(ctx).edit().putBoolean(KEY_WIFI_KEEP_ON, v).apply(); }

    /** "Keep ADB always on" (Wi-Fi & ADB > Advanced). Off by default: while
     * on, adb is a root shell for anyone on the same network. */
    public static final String KEY_ADB_ALWAYS_ON = "adb_always_on";
    public static boolean getAdbAlwaysOn(Context ctx) { return file(ctx).getBoolean(KEY_ADB_ALWAYS_ON, false); }
    public static void setAdbAlwaysOn(Context ctx, boolean v) { file(ctx).edit().putBoolean(KEY_ADB_ALWAYS_ON, v).apply(); }

    // --- Phone access (web/PhoneAccess.java) ---
    // Mode: "off" (only while the QR code is shown), "auto" (also on the home
    // Wi-Fi) or "on". The token is what a phone proves it scanned the QR code
    // with; it is a credential, so it is never in a backup (isBackedUp).
    public static final String KEY_PHONE_MODE = "phone_access_mode", KEY_PHONE_TOKEN = "phone_access_token";
    public static String getPhoneAccessMode(Context ctx) { return file(ctx).getString(KEY_PHONE_MODE, "off"); }
    public static void setPhoneAccessMode(Context ctx, String v) { file(ctx).edit().putString(KEY_PHONE_MODE, v).apply(); }
    public static String getPhoneAccessToken(Context ctx) { return file(ctx).getString(KEY_PHONE_TOKEN, null); }
    public static void setPhoneAccessToken(Context ctx, String v) { file(ctx).edit().putString(KEY_PHONE_TOKEN, v).commit(); }

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

    // --- Comfort ruler learn mode (hvac.ComfortLearn) ---
    // On by default (owner's choice, 2026-11-17): it only acts on hand changes
    // held 10 min, twice. Off means "use the default table"; what was learned
    // is kept until reset.
    public static boolean getComfortLearn(Context ctx) { return file(ctx).getBoolean("comfort_learn_on", true); }
    public static void setComfortLearn(Context ctx, boolean v) { file(ctx).edit().putBoolean("comfort_learn_on", v).apply(); }
    /** ComfortLearn.Book as text; empty when nothing was learned. */
    public static String getComfortBook(Context ctx) { return file(ctx).getString("comfort_learn_book", ""); }
    public static void setComfortBook(Context ctx, String v) { file(ctx).edit().putString("comfort_learn_book", v).apply(); }

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
    /** The owner's Record switch. modehelper owns the recorder and keeps its
     * own copy; this one is here so a backup can give it back. Absent until
     * the owner first touches the switch. */
    public static final String KEY_DASHCAM_ON = "dashcam_on";
    public static void setDashcamOn(Context ctx, boolean v) { file(ctx).edit().putBoolean(KEY_DASHCAM_ON, v).apply(); }

    // --- Drive mode / regen / turbo ---
    public static int getDriveMode(Context ctx, int def) { return file(ctx).getInt("drive", def); }
    public static void setDriveMode(Context ctx, int v) { file(ctx).edit().putInt("drive", v).apply(); }
    public static int getRegen(Context ctx, int def) { return file(ctx).getInt("regen", def); }
    public static void setRegen(Context ctx, int v) { file(ctx).edit().putInt("regen", v).apply(); }
    public static boolean getTurboEnabled(Context ctx) { return file(ctx).getBoolean("turbo_enabled", true); }
    public static void setTurboEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("turbo_enabled", v).apply(); }
    /** Home screen Regen button: false = Strong regen on/off (the default),
     * true = each tap steps Low -> Mid -> High -> Low. Issue #15. */
    public static boolean getRegenCycle(Context ctx) { return file(ctx).getBoolean("regen_cycle", false); }
    public static void setRegenCycle(Context ctx, boolean v) { file(ctx).edit().putBoolean("regen_cycle", v).apply(); }
    // Issue #19: trip routes (GPS points) and where each charge was. Saved
    // in the car only. On by default (owner, 2026-11-15).
    public static boolean getRecordRoutes(Context ctx) { return file(ctx).getBoolean("record_routes", true); }
    public static void setRecordRoutes(Context ctx, boolean v) { file(ctx).edit().putBoolean("record_routes", v).apply(); }
    /** Which buttons the Turbo card shows (issue #15). Turbo and Regen are
     * on by default (the card as it always was); AVAS is opt-in. */
    public static final String CARD_TURBO = "turbo_card_turbo", CARD_REGEN = "turbo_card_regen",
                               CARD_AVAS = "turbo_card_avas";
    public static boolean getTurboCardShows(Context ctx, String key) {
        return file(ctx).getBoolean(key, !CARD_AVAS.equals(key));
    }
    public static void setTurboCardShows(Context ctx, String key, boolean v) { file(ctx).edit().putBoolean(key, v).apply(); }
    public static int getTurboDurationS(Context ctx, int def) { return file(ctx).getInt("turbo_duration_s", def); }
    public static void setTurboDurationS(Context ctx, int v) { file(ctx).edit().putInt("turbo_duration_s", v).apply(); }
    public static int getSwitchLockS(Context ctx, int def) { return file(ctx).getInt("switch_lock_s", def); }
    public static void setSwitchLockS(Context ctx, int v) { file(ctx).edit().putInt("switch_lock_s", v).apply(); }

    // --- Drive card ---
    public static boolean getDriveCardEnabled(Context ctx) { return file(ctx).getBoolean("drive_card_enabled", true); }
    public static void setDriveCardEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("drive_card_enabled", v).apply(); }
    /** Home cards drop their colour scales (temperature bar, consumption
     * tile) and draw them in the theme's text colours instead. Off by default. */
    public static boolean getCardGrayscale(Context ctx) { return file(ctx).getBoolean("card_grayscale", false); }
    public static void setCardGrayscale(Context ctx, boolean v) { file(ctx).edit().putBoolean("card_grayscale", v).apply(); }
    public static int getPanelDismissed(Context ctx) { return file(ctx).getInt("panel_dismissed", 0); }
    public static void setPanelDismissed(Context ctx, int v) { file(ctx).edit().putInt("panel_dismissed", v).apply(); }

    // --- Doors / windows ---
    public static final String KEY_WINDOW_ON_DOOR = "window_on_door";
    public static boolean getWindowOnDoor(Context ctx) { return file(ctx).getBoolean(KEY_WINDOW_ON_DOOR, false); }
    public static void setWindowOnDoor(Context ctx, boolean v) { file(ctx).edit().putBoolean(KEY_WINDOW_ON_DOOR, v).apply(); }

    // --- OBD2 ---
    public static boolean getObd2Enabled(Context ctx) { return file(ctx).getBoolean("obd2_enabled", false); }
    public static void setObd2Enabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("obd2_enabled", v).apply(); }

    // --- Exact energy from CAN (roadcastd, sensors/CanEnergyReader) ---
    // On by default: CAN is the car's own data, not something the owner sets
    // up. Only an explicit "off" in System keeps it off.
    public static final String KEY_CAN_ENERGY = "can_energy";
    public static boolean getCanEnergyEnabled(Context ctx) { return file(ctx).getBoolean(KEY_CAN_ENERGY, true); }
    public static void setCanEnergyEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean(KEY_CAN_ENERGY, v).apply(); }

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

    // Config > Look > Extra dark (#89, ui/ExtraDark). Off until chosen.
    public static boolean getExtraDark(Context ctx) { return file(ctx).getBoolean("extra_dark", false); }
    public static void setExtraDark(Context ctx, boolean v) { file(ctx).edit().putBoolean("extra_dark", v).apply(); }
    // Darkest mode's own hours (#130): minutes after midnight. The car's dark
    // mode starts too early in the evening for it.
    public static int getExtraDarkStartMin(Context ctx) { return file(ctx).getInt("extra_dark_start_min", 21 * 60); }
    public static void setExtraDarkStartMin(Context ctx, int v) { file(ctx).edit().putInt("extra_dark_start_min", v).apply(); }
    public static int getExtraDarkEndMin(Context ctx) { return file(ctx).getInt("extra_dark_end_min", 6 * 60); }
    public static void setExtraDarkEndMin(Context ctx, int v) { file(ctx).edit().putInt("extra_dark_end_min", v).apply(); }
    // Darkest mode's shade, percent black (ExtraDark.shadeAlpha clamps it).
    public static int getExtraDarkShadePct(Context ctx) { return file(ctx).getInt("extra_dark_shade_pct", 70); }
    public static void setExtraDarkShadePct(Context ctx, int v) { file(ctx).edit().putInt("extra_dark_shade_pct", v).apply(); }
    // Darkest mode over every app (services/DarkestOverlayService), not only the Dashboard.
    public static boolean getExtraDarkGlobal(Context ctx) { return file(ctx).getBoolean("extra_dark_global", false); }
    public static void setExtraDarkGlobal(Context ctx, boolean v) { file(ctx).edit().putBoolean("extra_dark_global", v).apply(); }
    // --- Music card (phone music) and lyrics ---
    // The "spotify_" key names are historical: the card was first fed by a
    // Spotify sign-in (removed). The names stay so each driver keeps the
    // choices made then.
    // "Large card" (Config > Elements > Home screen): the big music card.
    public static boolean getMusicLargeCard(Context ctx) { return file(ctx).getBoolean("spotify_large_card", false); }
    public static void setMusicLargeCard(Context ctx, boolean v) { file(ctx).edit().putBoolean("spotify_large_card", v).apply(); }
    // "Phone music" (Config > Elements > Home screen): the music card shows what the phone
    // plays through the car (Bluetooth, CarPlay, Android Auto), from the car apps' media
    // browsers (CarMediaSessions). Off by default. The key keeps its first name.
    public static boolean getBtMusic(Context ctx) { return file(ctx).getBoolean("bt_music", false); }
    public static void setBtMusic(Context ctx, boolean v) { file(ctx).edit().putBoolean("bt_music", v).apply(); }
    // "Lyrics search" (Config > Integrations > Lyrics): the driver's consent. Off (the default)
    // sends nothing to lrclib.net and shows no Lyrics button.
    public static boolean getLyricsSearch(Context ctx) { return file(ctx).getBoolean("spotify_lyrics", false); }
    public static void setLyricsSearch(Context ctx, boolean v) { file(ctx).edit().putBoolean("spotify_lyrics", v).apply(); }
    // The lyrics open (jumbo card) or closed: the last shrink / Lyrics tap, kept across tracks and
    // restarts. Open by default, so turning the search on shows them.
    public static boolean getLyricsOpen(Context ctx) { return file(ctx).getBoolean("spotify_lyrics_open", true); }
    public static void setLyricsOpen(Context ctx, boolean v) { file(ctx).edit().putBoolean("spotify_lyrics_open", v).apply(); }

    /** Keys of removed features: the Spotify sign-in (client id, tokens,
     * PKCE verifier, token expiry), and "Skip this version" on the update
     * window (Later does its job now). dropRemoved() deletes them at start,
     * and no backup or restore carries them (isBackedUp). */
    static final java.util.List<String> REMOVED_KEYS = java.util.Arrays.asList(
        "spotify_client_id", "spotify_access_token", "spotify_refresh_token",
        "spotify_pkce_verifier", "spotify_token_expiry",
        "skip_update_vc", "skip_update_vc_modehelper");

    /** A removed key whose value moves to another before it goes: a skipped
     * version becomes a "Later" one, so it stays quiet after the update. */
    static final String[][] MOVED_TO_LATER = {
        {"skip_update_vc", "update_later_vc"},
        {"skip_update_vc_modehelper", "update_later_vc_modehelper"},
    };

    /** Deletes the keys of removed features (REMOVED_KEYS). Called at every
     * start; it writes only when one is still there, so after the first
     * start it looks up five keys in memory and does nothing. */
    public static void dropRemoved(Context ctx) { dropRemoved(file(ctx)); }

    static void dropRemoved(SharedPreferences p) {
        SharedPreferences.Editor e = null;
        for (String[] m : MOVED_TO_LATER) {
            if (!p.contains(m[0])) continue;
            int v = p.getInt(m[0], 0);
            if (v > p.getInt(m[1], 0)) {
                if (e == null) e = p.edit();
                e.putInt(m[1], v);
            }
        }
        for (String k : REMOVED_KEYS) {
            if (!p.contains(k)) continue;
            if (e == null) e = p.edit();
            e.remove(k);
        }
        if (e != null) e.apply();
    }

    // --- Telemetry ---
    public static boolean getTeleEnabled(Context ctx) { return file(ctx).getBoolean("tele_enabled", false); }
    public static void setTeleEnabled(Context ctx, boolean v) { file(ctx).edit().putBoolean("tele_enabled", v).apply(); }
    public static int getTeleIntervalS(Context ctx) { return file(ctx).getInt("tele_interval_s", 10); }
    public static void setTeleIntervalS(Context ctx, int v) { file(ctx).edit().putInt("tele_interval_s", v).apply(); }

    // --- Theme / appearance / driving position / currency ---
    public static String getAppearance(Context ctx, String def) { return file(ctx).getString("appearance", def); }
    public static void setAppearance(Context ctx, String v) { file(ctx).edit().putString("appearance", v).apply(); }
    public static final String KEY_DRIVE_SIDE = "drive_side";
    public static String getDriveSide(Context ctx, String def) { return file(ctx).getString(KEY_DRIVE_SIDE, def); }
    public static void setDriveSide(Context ctx, String v) { file(ctx).edit().putString(KEY_DRIVE_SIDE, v).apply(); }
    public static String getTheme(Context ctx, String def) { return file(ctx).getString("theme", def); }
    public static void setTheme(Context ctx, String v) { file(ctx).edit().putString("theme", v).apply(); }

    public static final String KEY_CURRENCY = "currency";
    public static final String DEFAULT_CURRENCY = "R$";
    private static volatile String cachedCurrency = DEFAULT_CURRENCY;

    public static String normalizeCurrency(String raw) {
        if (raw == null) return DEFAULT_CURRENCY;
        String t = raw.trim();
        return t.isEmpty() ? DEFAULT_CURRENCY : t;
    }

    public static String getCurrency(Context ctx) {
        String s = normalizeCurrency(ctx != null ? file(ctx).getString(KEY_CURRENCY, DEFAULT_CURRENCY) : cachedCurrency);
        cachedCurrency = s;
        return s;
    }

    public static void setCurrency(Context ctx, String v) {
        String s = normalizeCurrency(v);
        cachedCurrency = s;
        if (ctx != null) file(ctx).edit().putString(KEY_CURRENCY, s).apply();
    }

    public static String currencySymbol() {
        return cachedCurrency;
    }

    public static String formatMoney(double amount) {
        return String.format(java.util.Locale.getDefault(), "%s %,.2f", cachedCurrency, amount);
    }

    public static String formatMoneyPerKwh(double amountPerKwh) {
        return String.format(java.util.Locale.getDefault(), "%s %.2f/kWh", cachedCurrency, amountPerKwh);
    }

    // --- Diary & Maintenance Schedule (0 = Off) ---
    // Factory defaults (Geely Brasil EX2 schedule). The getters and the settings
    // page ("back to factory default", per row) read these, so they cannot differ.
    public static final int DIARY_DEF_BALANCE_DAYS = 30;
    public static final int DIARY_DEF_TIRE_PRESSURE_DAYS = 30;
    public static final int DIARY_DEF_TIRE_ROTATION_KM = 10_000;
    public static final int DIARY_DEF_SERVICE_KM = 20_000;
    public static final int DIARY_DEF_SERVICE_MONTHS = 12;
    public static final int DIARY_DEF_CABIN_FILTER_KM = 20_000;
    public static final int DIARY_DEF_CABIN_FILTER_MONTHS = 12;
    public static final int DIARY_DEF_BRAKE_FLUID_KM = 40_000;
    public static final int DIARY_DEF_BRAKE_FLUID_MONTHS = 24;
    public static final int DIARY_DEF_COOLANT_KM = 80_000;
    public static final int DIARY_DEF_COOLANT_MONTHS = 48;
    public static final int DIARY_DEF_GEAR_OIL_KM = 80_000;
    public static final int DIARY_DEF_GEAR_OIL_MONTHS = 48;
    public static final int DIARY_DEF_WIPERS_MONTHS = 12;
    public static final int DIARY_DEF_BATTERY_12V_MONTHS = 48;
    public static int getDiaryBalanceDays(Context ctx) { return file(ctx).getInt("diary_balance_days", DIARY_DEF_BALANCE_DAYS); }
    public static void setDiaryBalanceDays(Context ctx, int days) { file(ctx).edit().putInt("diary_balance_days", days).apply(); }
    public static int getDiaryTirePressureDays(Context ctx) { return file(ctx).getInt("diary_tire_pressure_days", DIARY_DEF_TIRE_PRESSURE_DAYS); }
    public static void setDiaryTirePressureDays(Context ctx, int days) { file(ctx).edit().putInt("diary_tire_pressure_days", days).apply(); }
    public static boolean getDiaryTireColdSnap(Context ctx) { return file(ctx).getBoolean("diary_tire_cold_snap", true); }
    public static void setDiaryTireColdSnap(Context ctx, boolean on) { file(ctx).edit().putBoolean("diary_tire_cold_snap", on).apply(); }
    public static int getDiaryTireRotationKm(Context ctx) { return file(ctx).getInt("diary_tire_rotation_km", DIARY_DEF_TIRE_ROTATION_KM); }
    public static void setDiaryTireRotationKm(Context ctx, int km) { file(ctx).edit().putInt("diary_tire_rotation_km", km).apply(); }
    public static int getDiaryServiceKm(Context ctx) { return file(ctx).getInt("diary_service_km", DIARY_DEF_SERVICE_KM); }
    public static int getDiaryServiceMonths(Context ctx) { return file(ctx).getInt("diary_service_months", DIARY_DEF_SERVICE_MONTHS); }
    public static void setDiaryServiceInterval(Context ctx, int km, int months) {
        file(ctx).edit().putInt("diary_service_km", km).putInt("diary_service_months", months).apply();
    }
    public static int getDiaryCabinFilterKm(Context ctx) { return file(ctx).getInt("diary_cabin_filter_km", DIARY_DEF_CABIN_FILTER_KM); }
    public static int getDiaryCabinFilterMonths(Context ctx) { return file(ctx).getInt("diary_cabin_filter_months", DIARY_DEF_CABIN_FILTER_MONTHS); }
    public static void setDiaryCabinFilterInterval(Context ctx, int km, int months) {
        file(ctx).edit().putInt("diary_cabin_filter_km", km).putInt("diary_cabin_filter_months", months).apply();
    }
    public static int getDiaryBrakeFluidKm(Context ctx) { return file(ctx).getInt("diary_brake_fluid_km", DIARY_DEF_BRAKE_FLUID_KM); }
    public static int getDiaryBrakeFluidMonths(Context ctx) { return file(ctx).getInt("diary_brake_fluid_months", DIARY_DEF_BRAKE_FLUID_MONTHS); }
    public static void setDiaryBrakeFluidInterval(Context ctx, int km, int months) {
        file(ctx).edit().putInt("diary_brake_fluid_km", km).putInt("diary_brake_fluid_months", months).apply();
    }
    public static int getDiaryCoolantKm(Context ctx) { return file(ctx).getInt("diary_coolant_km", DIARY_DEF_COOLANT_KM); }
    public static int getDiaryCoolantMonths(Context ctx) { return file(ctx).getInt("diary_coolant_months", DIARY_DEF_COOLANT_MONTHS); }
    public static void setDiaryCoolantInterval(Context ctx, int km, int months) {
        file(ctx).edit().putInt("diary_coolant_km", km).putInt("diary_coolant_months", months).apply();
    }
    public static int getDiaryGearOilKm(Context ctx) { return file(ctx).getInt("diary_gear_oil_km", DIARY_DEF_GEAR_OIL_KM); }
    public static int getDiaryGearOilMonths(Context ctx) { return file(ctx).getInt("diary_gear_oil_months", DIARY_DEF_GEAR_OIL_MONTHS); }
    public static void setDiaryGearOilInterval(Context ctx, int km, int months) {
        file(ctx).edit().putInt("diary_gear_oil_km", km).putInt("diary_gear_oil_months", months).apply();
    }
    public static int getDiaryWipersMonths(Context ctx) { return file(ctx).getInt("diary_wipers_months", DIARY_DEF_WIPERS_MONTHS); }
    public static void setDiaryWipersMonths(Context ctx, int months) { file(ctx).edit().putInt("diary_wipers_months", months).apply(); }
    public static int getDiaryBattery12vMonths(Context ctx) { return file(ctx).getInt("diary_battery_12v_months", DIARY_DEF_BATTERY_12V_MONTHS); }
    public static void setDiaryBattery12vMonths(Context ctx, int months) { file(ctx).edit().putInt("diary_battery_12v_months", months).apply(); }
    /** Schedules the owner added, and overrides of built-in ones: see CarDiary.parseExtras. */
    public static String getDiaryExtraSchedules(Context ctx) { return file(ctx).getString("diary_extra_sched", ""); }
    public static void setDiaryExtraSchedules(Context ctx, String v) { file(ctx).edit().putString("diary_extra_sched", v).apply(); }
    /** The day the owner got the car, as an epoch day; -1 = not set. First-time
     * maintenance counts its months from it (CarDiary.computeDueStatuses). */
    public static long getDiaryPickupDay(Context ctx) { return file(ctx).getLong("diary_pickup_day", -1L); }
    public static void setDiaryPickupDay(Context ctx, long epochDay) { file(ctx).edit().putLong("diary_pickup_day", epochDay).apply(); }
    /** The odometer on that day (a used car); 0 for a new car. */
    public static int getDiaryPickupKm(Context ctx) { return file(ctx).getInt("diary_pickup_km", 0); }
    public static void setDiaryPickupKm(Context ctx, int km) { file(ctx).edit().putInt("diary_pickup_km", Math.max(0, km)).apply(); }
    public static String getDiaryJoltSensitivity(Context ctx) { return file(ctx).getString("diary_jolt_sensitivity", "medium"); }
    public static void setDiaryJoltSensitivity(Context ctx, String level) { file(ctx).edit().putString("diary_jolt_sensitivity", level).apply(); }
    // --- Auto update check throttle ---
    public static long getAutoUpdateLastCheckMs(Context ctx) { return file(ctx).getLong("auto_update_last_check_ms", 0); }
    public static void setAutoUpdateLastCheckMs(Context ctx, long v) { file(ctx).edit().putLong("auto_update_last_check_ms", v).apply(); }

    // --- Updates ---
    public static String getUpdateUrl(Context ctx, String def) { return file(ctx).getString("update_url", def); }
    public static void setUpdateUrl(Context ctx, String v) { file(ctx).edit().putString("update_url", v).apply(); }
    public static String getUpdateLastUrl(Context ctx) { return file(ctx).getString("update_last_url", ""); }
    public static void setUpdateLastUrl(Context ctx, String v) { file(ctx).edit().putString("update_last_url", v).apply(); }
    // "Later" on the update window: the versionCode it was shown for. The home
    // screen does not open the window again by itself for that version or an
    // older one (Updater.autoPrompts); a newer version, or the driver's own
    // check in Config, still shows it.
    public static int getUpdateLaterVc(Context ctx, boolean modehelper) {
        return file(ctx).getInt(modehelper ? "update_later_vc_modehelper" : "update_later_vc", 0);
    }
    public static void setUpdateLaterVc(Context ctx, boolean modehelper, int versionCode) {
        file(ctx).edit().putInt(modehelper ? "update_later_vc_modehelper" : "update_later_vc", versionCode).apply();
    }

    // --- One-time migration/rollup guards ---
    public static boolean getDbMigratedV1(Context ctx) { return file(ctx).getBoolean("db_migrated_v1", false); }
    public static void setDbMigratedV1(Context ctx) { file(ctx).edit().putBoolean("db_migrated_v1", true).apply(); }
    public static String getRollupLastDay(Context ctx) { return file(ctx).getString("rollup_last_day_v10", ""); }
    public static void setRollupLastDay(Context ctx, String day) { file(ctx).edit().putString("rollup_last_day_v10", day).apply(); }

    /** Which keys a backup (AppBackup, settings.json) leaves out.
     *
     * A backup keeps EVERY key in this file, except the ones listed here. So
     * a new setting is in the backup with no extra work. The list below is
     * the app's own state, never a choice the user made:
     *  - row ids of the open trip/charge/valet (a restore renumbers rows),
     *  - the open trip/charge/parking/valet numbers (they belong to a car
     *    state that is gone when the backup comes back),
     *  - watchdog beats, update bookkeeping, one-time migration flags,
     *  - the key that locks the backup itself (backup_*).
     *
     * Adding a key that is the app's own state, not a setting? Add it here,
     * or a restore puts an old value back. SettingsFileTest checks the list. */
    private static final java.util.Set<String> NOT_BACKED_UP = new java.util.HashSet<>(java.util.Arrays.asList(
        "update_last_url", "auto_update_last_check_ms",
        "update_later_vc", "update_later_vc_modehelper",
        "db_migrated_v1",
        "panel_dismissed",
        "drive_card_dismissed_trip",             // no longer written (the drive card's × is gone); an old install may hold it
        KEY_PHONE_TOKEN,                         // a credential: phones scan again after a restore
        "can_explore_until"));                   // CAN explorer timer (tools)
    private static final String[] NOT_BACKED_UP_PREFIXES = {
        "trip_open_", "charge_open_", "valet_", "parking_",
        "beat_", "restart_",
        "backup_",
        "rollup_last_day",
    };

    /** True when a backup keeps this key. See {@link #NOT_BACKED_UP}. */
    public static boolean isBackedUp(String key) {
        if (key == null || key.isEmpty() || NOT_BACKED_UP.contains(key) || REMOVED_KEYS.contains(key)) return false;
        for (String p : NOT_BACKED_UP_PREFIXES) if (key.startsWith(p)) return false;
        return true;
    }

    /** Settings a backup that comes from a PHONE (web/PhoneServer upload)
     * must never set. The car keeps its own value for each of them.
     *
     * The phone proves who it is with a token that travels over plain HTTP
     * on the Wi-Fi. Someone who captured the token can upload a backup they
     * made themselves. So such a backup must not open a bigger door than the
     * phone pages already are:
     *  - adb_*: ADB on this head unit is a root shell on the network
     *    (always-on switch, trusted Wi-Fi name, home gateway),
     *  - phone_access_*: how long and where the phone door itself is open,
     *  - commands_enabled: lets the MQTT broker send commands to the car.
     *
     * update_url (where the car fetches its APK) is NOT here, by the owner's
     * choice: the phone settings page edits it, so a block here closes
     * nothing. The Installer still checks the APK's package and signature.
     *
     * A new setting that gives control of the car goes here too. The Restore button on the car's own screen is not
     * limited: that needs a person in the car. PhoneRestoreKeysTest checks
     * the list. */
    private static final java.util.Set<String> NOT_FROM_PHONE = new java.util.HashSet<>(java.util.Arrays.asList(
        "commands_enabled"));
    private static final String[] NOT_FROM_PHONE_PREFIXES = {
        "adb_",
        "phone_access_",
    };

    /** True when a restore that comes from a phone may set this key: a key a
     * backup keeps ({@link #isBackedUp}) and not in {@link #NOT_FROM_PHONE}. */
    public static boolean isPhoneRestorable(String key) {
        if (!isBackedUp(key) || NOT_FROM_PHONE.contains(key)) return false;
        for (String p : NOT_FROM_PHONE_PREFIXES) if (key.startsWith(p)) return false;
        return true;
    }
}
