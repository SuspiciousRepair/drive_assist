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

/** The Spotify sign-in was removed: its stored keys go at the first start,
 * and no backup or restore brings them back. The music card's and the
 * lyrics' switches keep their old ("spotify_") names. */
public class RemovedPrefsTest {

    /** A SharedPreferences over a map; counts the edits it is asked for. */
    private static SharedPreferences prefs(Map<String, Object> m, AtomicInteger edits) {
        SharedPreferences.Editor[] ed = {null};
        ed[0] = (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.Editor.class}, (proxy, meth, args) -> {
                if (meth.getName().equals("remove")) m.remove((String) args[0]);
                return meth.getReturnType() == boolean.class ? (Object) Boolean.TRUE
                    : meth.getReturnType() == void.class ? null : ed[0];
            });
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, meth, args) -> {
                switch (meth.getName()) {
                    case "contains": return m.containsKey((String) args[0]);
                    case "edit": edits.incrementAndGet(); return ed[0];
                    default: return null;
                }
            });
    }

    @Test public void theSignInKeysAreDeletedAndTheSwitchesKept() {
        Map<String, Object> m = new HashMap<>();
        for (String k : Prefs.REMOVED_KEYS) m.put(k, "x");
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
        assertEquals(5, Prefs.REMOVED_KEYS.size());
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
