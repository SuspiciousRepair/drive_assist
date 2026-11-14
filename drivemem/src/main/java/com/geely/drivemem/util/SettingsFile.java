package com.geely.drivemem.util;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** settings.json in a backup: one flat, readable list of the user's settings.
 *
 *   {
 *     "format": 1,
 *     "app_version": "20261114-0130-abc1234",
 *     "db_version": 25,
 *     "created_ms": 1794000000000,
 *     "settings": { "mqtt_uri": "tcp://...", "tele_enabled": true, ... }
 *   }
 *
 * Only keys in {@link Prefs#BACKUP_SETTINGS} are written or read back. The
 * type comes from that list, not from the JSON, so 10 can go back as an int
 * or a long as the setting needs. A key the list does not know (a newer
 * app's backup, a typo in a hand edit) is skipped, never guessed.
 */
public final class SettingsFile {
    private SettingsFile() {}

    public static final int FORMAT = 1;

    public static String toJson(Map<String, ?> prefs, String appVersion, int dbVersion, long nowMs)
            throws Exception {
        JSONObject s = new JSONObject();
        for (String k : Prefs.BACKUP_SETTINGS.keySet()) {
            Object v = prefs.get(k);
            if (v != null) s.put(k, v);
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("app_version", appVersion);
        root.put("db_version", dbVersion);
        root.put("created_ms", nowMs);
        root.put("settings", s);
        return root.toString(2);
    }

    /** The settings in a settings.json, each as the type the app expects. */
    public static Map<String, Object> parseSettings(String json) throws Exception {
        JSONObject s = new JSONObject(json).optJSONObject("settings");
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String k = it.next();
            Class<?> type = Prefs.BACKUP_SETTINGS.get(k);
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

    static Object coerce(Object v, Class<?> type) {
        if (type == String.class) return v instanceof String ? v : null;
        if (type == Boolean.class) return v instanceof Boolean ? v : null;
        if (!(v instanceof Number)) return null;
        Number n = (Number) v;
        if (type == Integer.class) return n.intValue();
        if (type == Long.class) return n.longValue();
        if (type == Float.class) return n.floatValue();
        return null;
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
}
