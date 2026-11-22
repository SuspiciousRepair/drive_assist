package com.geely.drivemem.util;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SpotifyQueueTest {

    @Test public void readsTheFirstQueuedTrack() throws Exception {
        JSONObject o = new JSONObject("{\"currently_playing\":{\"id\":\"cur\"},\"queue\":["
            + "{\"type\":\"track\",\"id\":\"n1\",\"name\":\"Next\",\"duration_ms\":201000,"
            + "\"artists\":[{\"name\":\"Band\"},{\"name\":\"Guest\"}],\"album\":{\"name\":\"LP\"}},"
            + "{\"type\":\"track\",\"id\":\"n2\",\"name\":\"After\",\"artists\":[{\"name\":\"X\"}]}]}");
        SpotifyClient.QueuedTrack t = SpotifyClient.firstQueued(o);
        assertNotNull(t);
        assertEquals("n1", t.id);
        assertEquals("Next", t.title);
        assertEquals("Band", t.artist);
        assertEquals("LP", t.album);
        assertEquals(201000, t.durationMs);
    }

    @Test public void emptyQueueOrEpisodeGivesNothing() throws Exception {
        assertNull(SpotifyClient.firstQueued(new JSONObject("{\"queue\":[]}")));
        assertNull(SpotifyClient.firstQueued(new JSONObject("{}")));
        assertNull(SpotifyClient.firstQueued(null));
        assertNull(SpotifyClient.firstQueued(new JSONObject(
            "{\"queue\":[{\"type\":\"episode\",\"id\":\"e\",\"name\":\"Pod\"}]}")));
    }

    @Test public void trackWithNoArtistGivesNothing() throws Exception {
        assertNull(SpotifyClient.firstQueued(new JSONObject(
            "{\"queue\":[{\"type\":\"track\",\"id\":\"n\",\"name\":\"N\",\"artists\":[]}]}")));
    }
}
