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

        Map<String, Object> back = SettingsFile.parseSettings(
            SettingsFile.toJson(prefs, "v1", 25, 1L));

        assertEquals(prefs, back);
        assertTrue(back.get("skyline_seed") instanceof Long);
        assertTrue(back.get("tele_interval_s") instanceof Integer);
    }

    @Test public void runtimeStateIsNeverWritten() throws Exception {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("trip_open_start_sample_id", 55L);
        prefs.put("charge_open_row_id", 3L);
        prefs.put("beat_tele", 1L);
        prefs.put("update_last_url", "https://x");
        prefs.put("theme", "vapor");

        JSONObject s = new JSONObject(SettingsFile.toJson(prefs, "v1", 25, 1L)).getJSONObject("settings");
        assertEquals(1, s.length());
        assertEquals("vapor", s.getString("theme"));
    }

    @Test public void unknownOrMistypedKeysAreSkipped() throws Exception {
        String json = "{\"format\":1,\"db_version\":25,\"settings\":{"
            + "\"future_setting\":1,\"tele_enabled\":\"yes\",\"mqtt_user\":\"bob\",\"tele_interval_s\":30}}";
        Map<String, Object> back = SettingsFile.parseSettings(json);
        assertEquals(2, back.size());
        assertEquals("bob", back.get("mqtt_user"));
        assertEquals(30, back.get("tele_interval_s"));
        assertFalse(back.containsKey("tele_enabled"));
        assertEquals(25, SettingsFile.dbVersion(json));
    }

    @Test public void backupListHoldsNoRowIdPrefs() {
        for (String k : new String[]{"trip_open_start_sample_id", "charge_open_start_sample_id",
                "charge_open_row_id", "valet_row_id", "drive_card_dismissed_trip"}) {
            assertFalse(k, Prefs.BACKUP_SETTINGS.containsKey(k));
        }
    }
}
