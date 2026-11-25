package com.geely.drivemem.util;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Spotify sign-in and "Skip this version" were removed: their stored
 * keys go at the first start, and no backup or restore brings them back. The
 * music card's and the lyrics' switches keep their old ("spotify_") names. */
public class RemovedPrefsTest {

    /** A SharedPreferences over a map; counts the edits it is asked for. */
    private static SharedPreferences prefs(Map<String, Object> m, AtomicInteger edits) {
        SharedPreferences.Editor[] ed = {null};
        ed[0] = (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.Editor.class}, (proxy, meth, args) -> {
                if (meth.getName().equals("remove")) m.remove((String) args[0]);
                if (meth.getName().equals("putInt")) m.put((String) args[0], args[1]);
                return meth.getReturnType() == boolean.class ? (Object) Boolean.TRUE
                    : meth.getReturnType() == void.class ? null : ed[0];
            });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, meth, args) -> {
                switch (meth.getName()) {
                    case "contains": return m.containsKey((String) args[0]);
                    case "getInt": return m.containsKey((String) args[0]) ? m.get((String) args[0]) : args[1];
                    case "edit": edits.incrementAndGet(); return ed[0];
                    default: return null;
                }
            });
    }

    @Test public void theSignInKeysAreDeletedAndTheSwitchesKept() {
        Map<String, Object> m = new HashMap<>();
        for (String k : Prefs.REMOVED_KEYS) m.put(k, k.startsWith("skip_") ? (Object) 0 : "x");
        m.put("bt_music", true);
        m.put("spotify_lyrics", true);
        m.put("spotify_lyrics_open", false);
        m.put("spotify_large_card", true);
        AtomicInteger edits = new AtomicInteger();
        Prefs.dropRemoved(prefs(m, edits));
        assertEquals(4, m.size());
        assertTrue(m.containsKey("bt_music"));
        assertTrue(m.containsKey("spotify_lyrics"));
        assertTrue(m.containsKey("spotify_lyrics_open"));
        assertTrue(m.containsKey("spotify_large_card"));
        assertEquals(1, edits.get());
    }

    @Test public void nothingToDeleteWritesNothing() {
        Map<String, Object> m = new HashMap<>();
        m.put("bt_music", true);
        AtomicInteger edits = new AtomicInteger();
        Prefs.dropRemoved(prefs(m, edits));
        assertEquals(0, edits.get());
        assertEquals(1, m.size());
    }

    @Test public void theRemovedKeysAreTheSignInOnes() {
        assertTrue(Prefs.REMOVED_KEYS.contains("spotify_client_id"));
        assertTrue(Prefs.REMOVED_KEYS.contains("spotify_access_token"));
        assertTrue(Prefs.REMOVED_KEYS.contains("spotify_refresh_token"));
        assertTrue(Prefs.REMOVED_KEYS.contains("spotify_pkce_verifier"));
        assertTrue(Prefs.REMOVED_KEYS.contains("spotify_token_expiry"));
        assertTrue(Prefs.REMOVED_KEYS.contains("skip_update_vc"));
        assertTrue(Prefs.REMOVED_KEYS.contains("skip_update_vc_modehelper"));
        assertTrue(Prefs.REMOVED_KEYS.contains("restore_drive"));
        assertTrue(Prefs.REMOVED_KEYS.contains("restore_regen"));
        assertTrue(Prefs.REMOVED_KEYS.contains("restore_aeb"));
        assertTrue(Prefs.REMOVED_KEYS.contains("restore_avas"));
        assertEquals(11, Prefs.REMOVED_KEYS.size());
    }

    /** "Skip this version" went; a version skipped before the update stays
     * quiet, as a "Later" one. A newer Later is not lowered. */
    @Test public void aSkippedVersionBecomesALaterOne() {
        Map<String, Object> m = new HashMap<>();
        m.put("skip_update_vc", 29923000);
        m.put("skip_update_vc_modehelper", 29922000);
        m.put("update_later_vc_modehelper", 29923500);
        Prefs.dropRemoved(prefs(m, new AtomicInteger()));
        assertEquals(29923000, m.get("update_later_vc"));
        assertEquals(29923500, m.get("update_later_vc_modehelper"));
        assertFalse(m.containsKey("skip_update_vc"));
        assertFalse(m.containsKey("skip_update_vc_modehelper"));
    }

    @Test public void noBackupOrRestoreCarriesThem() {
        for (String k : Prefs.REMOVED_KEYS) assertFalse(k, Prefs.isBackedUp(k));
        for (String k : new String[]{"bt_music", "spotify_lyrics", "spotify_lyrics_open", "spotify_large_card"})
            assertTrue(k, Prefs.isBackedUp(k));
    }

    @Test public void anOldBackupDoesNotBringTheSignInBack() throws Exception {
        // A format 1 file (no "types"): the old fixed list still names them.
        String json = "{\"format\":1,\"db_version\":25,\"settings\":{"
            + "\"spotify_client_id\":\"id\",\"spotify_refresh_token\":\"rt\","
            + "\"spotify_token_expiry\":5,\"spotify_large_card\":true}}";
        Map<String, Object> back = SettingsFile.parseSettings(json);
        assertEquals(1, back.size());
        assertEquals(Boolean.TRUE, back.get("spotify_large_card"));
    }
}
