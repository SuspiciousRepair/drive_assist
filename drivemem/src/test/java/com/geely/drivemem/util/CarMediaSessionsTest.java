package com.geely.drivemem.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class CarMediaSessionsTest {
    @Test public void trackKeyIsStableAndIgnoresCaseAndSpaces() {
        String a = CarMediaSessions.trackKey("Queen", "Under Pressure");
        assertEquals(a, CarMediaSessions.trackKey(" queen ", "under pressure "));
        assertTrue(a.startsWith("s-"));
        assertNotEquals(a, CarMediaSessions.trackKey("Queen", "Bohemian Rhapsody"));
        assertEquals(LyricsClient.safeName(a), a);   // usable as a cache file name as is
    }

    @Test public void trackKeyWithNoArtist() {
        assertNotNull(CarMediaSessions.trackKey(null, "Song"));
    }

    @Test public void endsAtIsWhenThePlayingSongEnds() {
        // 4 min song, at 1 min when reported at t=10 s: ends at 190 s.
        assertEquals(190_000, CarMediaSessions.endsAt(true, 60_000, 10_000, 240_000));
        assertEquals(10_000, CarMediaSessions.endsAt(true, 300_000, 10_000, 240_000));
        assertEquals(0, CarMediaSessions.endsAt(false, 60_000, 10_000, 240_000));
        assertEquals(0, CarMediaSessions.endsAt(true, 60_000, 10_000, 0));
    }

    @Test public void stuckAtTheEndIsStaleAfterTheGrace() {
        long g = SpotifyClient.STALE_GRACE_MS;
        assertEquals(0, CarMediaSessions.staleAt("a", 190_000, 100_000));       // still playing
        assertEquals(200_000 + g, CarMediaSessions.staleAt("a", 190_000, 200_000)); // first seen at the end
        // Android re-stamps the position on each read: the end moves to "now",
        // but the first sighting holds.
        assertEquals(200_000 + g, CarMediaSessions.staleAt("a", 215_000, 215_000));
        assertEquals(200_000 + g, CarMediaSessions.staleAt("a", 240_000, 240_000));
        // Another track starts the count again.
        assertEquals(250_000 + g, CarMediaSessions.staleAt("b", 250_000, 250_000));
        // Playing again (not at the end) clears it.
        assertEquals(0, CarMediaSessions.staleAt("b", 400_000, 260_000));
        assertEquals(300_000 + g, CarMediaSessions.staleAt("b", 300_000, 300_000));
    }
}
