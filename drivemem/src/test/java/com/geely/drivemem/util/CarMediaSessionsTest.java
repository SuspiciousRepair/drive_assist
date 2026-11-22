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
}
