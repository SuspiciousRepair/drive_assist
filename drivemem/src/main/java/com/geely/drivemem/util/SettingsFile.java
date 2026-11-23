package com.geely.drivemem.util;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** settings.json in a backup: one flat, readable list of the user's settings.
 *
 *   {
 *     "format": 2,
 *     "app_version": "20261114-0130-abc1234",
 *     "db_version": 25,
 *     "created_ms": 1794000000000,
 *     "settings": { "mqtt_uri": "tcp://...", "tele_enabled": true, ... },
 *     "types":    { "mqtt_uri": "string",   "tele_enabled": "bool", ... }
 *   }
 *
 * Every key in the prefs file goes in, except the app's own state
 * ({@link Prefs#isBackedUp}). So a new setting needs no change here.
 *
 * "types" says how each value goes back (10 can be an int or a long, and
 * SharedPreferences throws when it reads the wrong one). A key with no
 * known type is skipped, never guessed.
 *
 * Format 1 (backups made before this list was automatic) had no "types":
 * its keys are typed by {@link #V1_TYPES}, the fixed list those backups used.
 * An older app reads a format 2 file too: it reads "settings" with its own
 * list and skips the rest.
 */
public final class SettingsFile {
    private SettingsFile() {}

    public static final int FORMAT = 2;

    static final String T_STRING = "string", T_BOOL = "bool", T_INT = "int",
                        T_LONG = "long", T_FLOAT = "float";

    public static String toJson(Map<String, ?> prefs, String appVersion, int dbVersion, long nowMs)
            throws Exception {
        JSONObject s = new JSONObject();
        JSONObject t = new JSONObject();
        // Sorted, so two backups of the same settings read the same.
        for (Map.Entry<String, ?> en : new TreeMap<String, Object>(prefs).entrySet()) {
            String k = en.getKey();
            Object v = en.getValue();
            if (!Prefs.isBackedUp(k)) continue;
            String type = typeOf(v);
            if (type == null) continue;                // a Set<String>: none in this app
            if (v instanceof Float && !Float.isFinite((Float) v)) continue;   // JSON has no NaN
            s.put(k, v);
            t.put(k, type);
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("app_version", appVersion);
        root.put("db_version", dbVersion);
        root.put("created_ms", nowMs);
        root.put("settings", s);
        root.put("types", t);
        return root.toString(2);
    }

    /** The settings in a settings.json, each as the type the app expects. */
    public static Map<String, Object> parseSettings(String json) throws Exception {
        return parseSettings(json, false);
    }

    /** Same. fromPhone: the file came in over the phone pages, so the keys
     * a phone must not set are left out ({@link Prefs#isPhoneRestorable}). */
    public static Map<String, Object> parseSettings(String json, boolean fromPhone) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject s = root.optJSONObject("settings");
        JSONObject types = root.optJSONObject("types");
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!Prefs.isBackedUp(k)) continue;        // a hand edit, or state an old build kept
            if (fromPhone && !Prefs.isPhoneRestorable(k)) continue;
            String type = types != null ? types.optString(k, null) : V1_TYPES.get(k);
            Object v = s.opt(k);
            if (type == null || v == null || v == JSONObject.NULL) continue;
            Object typed = coerce(v, type);
            if (typed != null) out.put(k, typed);
        }
        return out;
    }

    public static int dbVersion(String json) throws Exception {
        return new JSONObject(json).optInt("db_version", 0);
    }

    public static long createdMs(String json) throws Exception {
        return new JSONObject(json).optLong("created_ms", 0);
    }

    static String typeOf(Object v) {
        if (v instanceof String) return T_STRING;
        if (v instanceof Boolean) return T_BOOL;
        if (v instanceof Integer) return T_INT;
        if (v instanceof Long) return T_LONG;
        if (v instanceof Float) return T_FLOAT;
        return null;
    }

    static Object coerce(Object v, String type) {
        if (T_STRING.equals(type)) return v instanceof String ? v : null;
        if (T_BOOL.equals(type)) return v instanceof Boolean ? v : null;
        if (!(v instanceof Number)) return null;
        Number n = (Number) v;
        if (T_INT.equals(type)) return n.intValue();
        if (T_LONG.equals(type)) return n.longValue();
        if (T_FLOAT.equals(type)) return n.floatValue();
        return null;
    }

    /** The settings whose type agrees with the value the app has stored now.
     * The "types" list comes from the file, so a hand-made or damaged backup
     * could put an int where the app reads a String; SharedPreferences then
     * throws on every read (Prefs.getTheme in every onCreate: a crash loop).
     * Where the app already holds the key, its own type wins and a value of
     * another type is dropped. */
    public static Map<String, Object> matchStoredTypes(Map<String, Object> settings,
                                                       Map<String, ?> stored) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> en : settings.entrySet()) {
            Object now = stored.get(en.getKey());
            if (now != null && !java.util.Objects.equals(typeOf(now), typeOf(en.getValue()))) continue;
            out.put(en.getKey(), en.getValue());
        }
        return out;
    }

    /** Writes typed values into a SharedPreferences editor. */
    public static void apply(android.content.SharedPreferences.Editor e, Map<String, Object> settings) {
        for (Map.Entry<String, Object> en : settings.entrySet()) {
            Object v = en.getValue();
            String k = en.getKey();
            if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
        }
    }

    /** The fixed list format 1 backups were written from, with each type.
     * Only for reading those old files: do not add new keys here. The
     * Spotify sign-in keys in it are never restored (Prefs.REMOVED_KEYS). */
    static final Map<String, String> V1_TYPES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : new String[]{"mqtt_uri", "mqtt_uri_alt", "mqtt_user", "mqtt_pass",
                "mqtt_tls_test_target", "abrp_user_token", "spotify_client_id",
                "spotify_access_token", "spotify_refresh_token", "appearance", "theme",
                "adb_trusted_ssid", "adb_home_gw", "update_url"}) m.put(k, T_STRING);
        for (String k : new String[]{"tele_enabled", "commands_enabled", "abrp_enabled",
                "abrp_send_location", "spotify_large_card", "outtemp_on", "soc_on", "wifiicon_on",
                "overlay_on", "skyline_enabled", "skyline_random_per_drive", "drive_card_enabled",
                "aeb_on", "avas_on", "restore_drive", "restore_regen", "restore_aeb", "restore_avas",
                "turbo_enabled", "regen_cycle", "turbo_card_turbo", "turbo_card_regen",
                "turbo_card_avas", "window_on_door", "parked_monitoring", "obd2_enabled",
                "can_energy", "wifi_keep_on"}) m.put(k, T_BOOL);
        for (String k : new String[]{"tele_interval_s", "overlay_x", "overlay_y", "drive", "regen",
                "turbo_duration_s", "switch_lock_s", "dashcam_limit_gb"}) m.put(k, T_INT);
        for (String k : new String[]{"spotify_token_expiry", "skyline_seed"}) m.put(k, T_LONG);
        V1_TYPES = Collections.unmodifiableMap(m);
    }
}
