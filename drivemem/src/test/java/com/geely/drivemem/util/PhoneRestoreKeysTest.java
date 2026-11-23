package com.geely.drivemem.util;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A backup sent by a phone may be made by someone who only captured the
 * phone token (plain HTTP). It must not set the settings that give control
 * of the car or of what runs on it. The car's own Restore is not limited. */
public class PhoneRestoreKeysTest {

    /** Every key a phone restore must leave alone. */
    private static final String[] BLOCKED = {
        Prefs.KEY_ADB_ALWAYS_ON, Prefs.KEY_TRUSTED_SSID, Prefs.KEY_HOME_GW,
        "adb_a_switch_added_next_year",
        Prefs.KEY_PHONE_MODE, Prefs.KEY_PHONE_TOKEN,
        "commands_enabled",
        // never in any restore (Prefs.isBackedUp): the key that locks the backups
        AppBackup.PREF_KEY, AppBackup.PREF_SALT,
    };

    /** Settings a phone restore still brings back. */
    private static final String[] ALLOWED = {
        "mqtt_uri", "mqtt_uri_alt", "mqtt_user", "mqtt_pass", "tele_enabled", "tele_interval_s",
        "abrp_user_token", "abrp_enabled",
        // the music card and lyrics switches keep their historical names
        "bt_music", "spotify_lyrics", "spotify_lyrics_open", "spotify_large_card",
        // the owner's choice: the phone settings page edits it too
        "update_url",
        "theme", "appearance", "drive", "regen", Prefs.KEY_DASHCAM_ON, Prefs.KEY_WIFI_KEEP_ON,
        "a_setting_added_next_year",
    };

    @Test public void theKeysThatGiveControlAreBlocked() {
        for (String k : BLOCKED) assertFalse(k, Prefs.isPhoneRestorable(k));
    }

    @Test public void ordinarySettingsStillComeBack() {
        for (String k : ALLOWED) assertTrue(k, Prefs.isPhoneRestorable(k));
    }

    @Test public void theNamesAreTheOnesTheAppUses() {
        assertEquals("adb_always_on", Prefs.KEY_ADB_ALWAYS_ON);
        assertEquals("adb_trusted_ssid", Prefs.KEY_TRUSTED_SSID);
        assertEquals("adb_home_gw", Prefs.KEY_HOME_GW);
        assertEquals("phone_access_mode", Prefs.KEY_PHONE_MODE);
        assertEquals("phone_access_token", Prefs.KEY_PHONE_TOKEN);
    }

    @Test public void aKeyNoBackupKeepsIsNeverPhoneRestorable() {
        for (String k : new String[]{"trip_open_start_sample_id", "charge_open_row_id", "valet_row_id",
                "backup_last_auto_ms", "update_last_url", "skip_update_vc", "beat_tele", null, ""}) {
            assertFalse(String.valueOf(k), Prefs.isPhoneRestorable(k));
        }
    }

    /** What an attacker would upload: every dangerous key, with types. */
    private static final String CRAFTED = "{\"format\":2,\"db_version\":25,\"settings\":{"
        + "\"adb_always_on\":true,\"adb_trusted_ssid\":\"evil\",\"adb_home_gw\":\"evil-gw\","
        + "\"update_url\":\"http://evil.example/drive_assist.apk\","
        + "\"phone_access_mode\":\"on\",\"phone_access_token\":\"known\","
        + "\"commands_enabled\":true,"
        + "\"backup_key\":\"AAAA\",\"backup_salt\":\"BBBB\","
        + "\"mqtt_uri\":\"tcp://broker:1883\",\"theme\":\"vapor\",\"tele_interval_s\":30},"
        + "\"types\":{"
        + "\"adb_always_on\":\"bool\",\"adb_trusted_ssid\":\"string\",\"adb_home_gw\":\"string\","
        + "\"update_url\":\"string\",\"phone_access_mode\":\"string\",\"phone_access_token\":\"string\","
        + "\"commands_enabled\":\"bool\",\"backup_key\":\"string\",\"backup_salt\":\"string\","
        + "\"mqtt_uri\":\"string\",\"theme\":\"string\",\"tele_interval_s\":\"int\"}}";

    @Test public void aCraftedBackupFromAPhoneSetsOnlyOrdinarySettings() throws Exception {
        Map<String, Object> back = SettingsFile.parseSettings(CRAFTED, true);
        assertEquals(4, back.size());
        assertEquals("http://evil.example/drive_assist.apk", back.get("update_url"));
        assertEquals("tcp://broker:1883", back.get("mqtt_uri"));
        assertEquals("vapor", back.get("theme"));
        assertEquals(30, back.get("tele_interval_s"));
        for (String k : back.keySet()) assertTrue(k, Prefs.isPhoneRestorable(k));
    }

    @Test public void theSameBackupOnTheCarsOwnRestoreKeepsTodaysBehaviour() throws Exception {
        Map<String, Object> back = SettingsFile.parseSettings(CRAFTED, false);
        assertEquals(back, SettingsFile.parseSettings(CRAFTED));
        assertEquals(true, back.get("adb_always_on"));
        assertEquals("evil", back.get("adb_trusted_ssid"));
        assertEquals("http://evil.example/drive_assist.apk", back.get("update_url"));
        assertEquals("on", back.get("phone_access_mode"));
        assertEquals(true, back.get("commands_enabled"));
        // state and the backup key never come back, from anywhere
        assertFalse(back.containsKey("phone_access_token"));
        assertFalse(back.containsKey("backup_key"));
        assertFalse(back.containsKey("backup_salt"));
        assertEquals(9, back.size());
    }

    /** Format 1 has no "types": its fixed list knows adb_trusted_ssid,
     * adb_home_gw and commands_enabled, so they need the same filter. */
    @Test public void anOldFormatFileFromAPhoneIsFilteredToo() throws Exception {
        String json = "{\"format\":1,\"db_version\":25,\"settings\":{"
            + "\"adb_trusted_ssid\":\"evil\",\"adb_home_gw\":\"evil-gw\","
            + "\"update_url\":\"http://evil.example/a.apk\",\"commands_enabled\":true,"
            + "\"mqtt_user\":\"bob\"}}";
        Map<String, Object> back = SettingsFile.parseSettings(json, true);
        assertEquals(2, back.size());
        assertEquals("bob", back.get("mqtt_user"));
        assertEquals("http://evil.example/a.apk", back.get("update_url"));
        assertEquals(5, SettingsFile.parseSettings(json, false).size());
    }

    @Test public void onlyTheCarsOwnRestoreStoresTheBackupKey() {
        assertTrue(AppBackup.storesKey(AppBackup.Origin.CAR));
        assertFalse(AppBackup.storesKey(AppBackup.Origin.PHONE));
    }
}
