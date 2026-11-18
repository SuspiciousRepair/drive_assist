package com.geely.drivemem;

import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.SettingsFile;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsFileTest {

    @Test public void roundTripKeepsEachSettingAndItsType() throws Exception {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("mqtt_uri", "tcp://broker:1883");
        prefs.put("mqtt_pass", "secret");
        prefs.put("tele_enabled", true);
        prefs.put("tele_interval_s", 10);
        prefs.put("skyline_seed", 12L);                    // small long: must come back a Long
        prefs.put("spotify_token_expiry", 1794000000000L);
        prefs.put("abrp_user_token", "tok");
        prefs.put("outtemp_on", true);
        prefs.put("some_float", 1.5f);

        Map<String, Object> back = SettingsFile.parseSettings(
            SettingsFile.toJson(prefs, "v1", 25, 1L));

        assertEquals(prefs, back);
        assertTrue(back.get("skyline_seed") instanceof Long);
        assertTrue(back.get("tele_interval_s") instanceof Integer);
        assertTrue(back.get("some_float") instanceof Float);
    }

    /** The whole point: a setting nobody listed anywhere is still kept. These
     * were all missing from the old fixed list. */
    @Test public void settingsNobodyListedAreKept() throws Exception {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("card_grayscale", true);
        prefs.put("lock_sound_base", "custom");
        prefs.put("purge_open_256", 40);
        prefs.put("adb_always_on", true);
        prefs.put(Prefs.KEY_DASHCAM_ON, false);
        prefs.put("a_setting_added_next_year", 7L);

        Map<String, Object> back = SettingsFile.parseSettings(
            SettingsFile.toJson(prefs, "v1", 25, 1L));

        assertEquals(prefs, back);
        assertTrue(back.get("purge_open_256") instanceof Integer);
        assertTrue(back.get("a_setting_added_next_year") instanceof Long);
    }

    @Test public void runtimeStateIsNeverWritten() throws Exception {
        Map<String, Object> prefs = new HashMap<>();
        for (String k : STATE) prefs.put(k, 55L);
        prefs.put("theme", "vapor");

        JSONObject s = new JSONObject(SettingsFile.toJson(prefs, "v1", 25, 1L)).getJSONObject("settings");
        assertEquals(1, s.length());
        assertEquals("vapor", s.getString("theme"));
    }

    /** Every key the app keeps as its own state, as found in the code and in
     * a real car's prefs file (2026-11-15). */
    private static final String[] STATE = {
        "trip_open_start_ms", "trip_open_start_sample_id", "trip_open_start_odo_km", "trip_open_start_soc",
        "charge_open_start_ms", "charge_open_start_sample_id", "charge_open_row_id", "charge_open_cost",
        "valet_active", "valet_row_id", "valet_start_ms",
        "parking_start_ms", "parking_start_soc", "parking_start_temp", "parking_temp_trail",
        "beat_tele", "beat_mqtt", "restart_mqtt",
        "backup_key", "backup_salt", "backup_last_auto_ms", "backup_last_try_ms",
        "backup_auto_fails", "backup_offer_done", "backup_restore_note",
        "update_last_url", "auto_update_last_check_ms", "skip_update_vc", "skip_update_vc_modehelper",
        "db_migrated_v1", "rollup_last_day_v10",
        "spotify_pkce_verifier", "panel_dismissed", "drive_card_dismissed_trip", "can_explore_until",
        "phone_access_token",
    };

    @Test public void stateKeysAreNotBackedUp() {
        for (String k : STATE) assertFalse(k, Prefs.isBackedUp(k));
    }

    /** A hand edit, or a backup from a build that kept state, must not put
     * state back. */
    @Test public void stateInAFileIsNotRestored() throws Exception {
        String json = "{\"format\":2,\"db_version\":25,"
            + "\"settings\":{\"trip_open_start_sample_id\":9,\"backup_key\":\"x\",\"theme\":\"vapor\"},"
            + "\"types\":{\"trip_open_start_sample_id\":\"long\",\"backup_key\":\"string\",\"theme\":\"string\"}}";
        Map<String, Object> back = SettingsFile.parseSettings(json);
        assertEquals(1, back.size());
        assertEquals("vapor", back.get("theme"));
    }

    @Test public void untypedOrMistypedKeysAreSkipped() throws Exception {
        String json = "{\"format\":2,\"db_version\":25,"
            + "\"settings\":{\"no_type\":1,\"tele_enabled\":\"yes\",\"mqtt_user\":\"bob\",\"tele_interval_s\":30},"
            + "\"types\":{\"tele_enabled\":\"bool\",\"mqtt_user\":\"string\",\"tele_interval_s\":\"int\"}}";
        Map<String, Object> back = SettingsFile.parseSettings(json);
        assertEquals(2, back.size());
        assertEquals("bob", back.get("mqtt_user"));
        assertEquals(30, back.get("tele_interval_s"));
        assertFalse(back.containsKey("tele_enabled"));
        assertEquals(25, SettingsFile.dbVersion(json));
    }

    /** Backups made before this change have no "types": the old fixed list
     * types them, and a key it does not know is skipped. */
    @Test public void format1BackupsStillRestore() throws Exception {
        String json = "{\"format\":1,\"db_version\":25,\"settings\":{"
            + "\"future_setting\":1,\"tele_enabled\":true,\"mqtt_user\":\"bob\","
            + "\"tele_interval_s\":30,\"skyline_seed\":12}}";
        Map<String, Object> back = SettingsFile.parseSettings(json);
        assertEquals(4, back.size());
        assertEquals(true, back.get("tele_enabled"));
        assertEquals(30, back.get("tele_interval_s"));
        assertEquals(12L, back.get("skyline_seed"));
        assertFalse(back.containsKey("future_setting"));
    }

    /** An older app reads "settings" and ignores the rest: the field names
     * it relies on must stay. */
    @Test public void format2KeepsTheFieldsOlderAppsRead() throws Exception {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("theme", "vapor");
        JSONObject root = new JSONObject(SettingsFile.toJson(prefs, "v1", 25, 7L));
        assertEquals("vapor", root.getJSONObject("settings").getString("theme"));
        assertEquals(25, root.getInt("db_version"));
        assertEquals(7L, root.getLong("created_ms"));
    }

    @Test public void nonFiniteFloatIsSkippedNotFatal() throws Exception {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("bad", Float.NaN);
        prefs.put("theme", "vapor");
        Map<String, Object> back = SettingsFile.parseSettings(SettingsFile.toJson(prefs, "v1", 25, 1L));
        assertEquals(1, back.size());
    }

    @Test public void aValueOfAnotherTypeThanTheStoredOneIsDropped() throws Exception {
        // A crafted file says theme is an int; the app stores and reads it as a String.
        String json = "{\"format\":2,\"settings\":{\"theme\":5,\"tele_enabled\":true},"
            + "\"types\":{\"theme\":\"int\",\"tele_enabled\":\"bool\"}}";
        Map<String, Object> parsed = SettingsFile.parseSettings(json);
        assertEquals(5, parsed.get("theme"));               // the file alone cannot tell

        Map<String, Object> stored = new HashMap<>();
        stored.put("theme", "vapor");
        stored.put("tele_enabled", false);
        Map<String, Object> kept = SettingsFile.matchStoredTypes(parsed, stored);
        assertFalse(kept.containsKey("theme"));
        assertEquals(true, kept.get("tele_enabled"));       // same type: restored
    }

    @Test public void aKeyTheAppDoesNotHoldYetKeepsTheFileType() throws Exception {
        Map<String, Object> parsed = new HashMap<>();
        parsed.put("tele_interval_s", 10);
        assertEquals(10, SettingsFile.matchStoredTypes(parsed, new HashMap<>()).get("tele_interval_s"));
    }
}
