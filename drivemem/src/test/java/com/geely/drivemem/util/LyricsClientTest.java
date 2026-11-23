package com.geely.drivemem.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class LyricsClientTest {

    private static JSONObject hit(Object synced, double duration, boolean instrumental) throws Exception {
        JSONObject o = new JSONObject();
        o.put("syncedLyrics", synced == null ? JSONObject.NULL : synced);
        o.put("plainLyrics", "plain words");
        o.put("duration", duration);
        o.put("instrumental", instrumental);
        return o;
    }

    @Test public void syncedOfTakesOnlyTimedLines() throws Exception {
        assertEquals("[00:01.00]a", LyricsClient.syncedOf(hit("[00:01.00]a", 200, false)));
        assertNull("plain only", LyricsClient.syncedOf(hit(null, 200, false)));
        assertNull("blank", LyricsClient.syncedOf(hit("  ", 200, false)));
        assertNull("instrumental", LyricsClient.syncedOf(hit("[00:01.00]a", 200, true)));
    }

    @Test public void pickSyncedSkipsPlainAndWrongLength() throws Exception {
        JSONArray hits = new JSONArray()
            .put(hit(null, 200, false))            // plain only
            .put(hit("[00:01.00]live", 260, false)) // a live take, far longer
            .put(hit("[00:01.00]right", 201.6, false));
        assertEquals("[00:01.00]right", LyricsClient.pickSynced(hits, 200));
    }

    @Test public void pickSyncedWithUnknownLengthTakesTheFirstTimed() throws Exception {
        JSONArray hits = new JSONArray()
            .put(hit(null, 200, false))
            .put(hit("[00:01.00]any", 260, false));
        assertEquals("[00:01.00]any", LyricsClient.pickSynced(hits, 0));
    }

    @Test public void pickSyncedFindsNothing() throws Exception {
        assertNull(LyricsClient.pickSynced(new JSONArray(), 200));
        assertNull(LyricsClient.pickSynced(new JSONArray().put(hit("[00:01.00]x", 300, false)), 200));
        assertNull(LyricsClient.pickSynced(null, 200));
    }

    @Test public void safeNameKeepsSafeIdsAndCleansTheRest() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", LyricsClient.safeName("4uLU6hMCjMI75M1A2tKUQC"));
        assertEquals("a_b_c", LyricsClient.safeName("a/b.c"));
    }
}
