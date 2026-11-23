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
        long g = CarMediaSessions.STALE_GRACE_MS;
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

    @Test public void linksOnlyToOpenBrowsersOfOtherApps() {
        assertTrue(CarMediaSessions.usable("com.android.bluetooth", true, null, "com.geely.drivemem"));
        assertFalse(CarMediaSessions.usable("com.geely.drivemem", true, null, "com.geely.drivemem"));
        assertFalse(CarMediaSessions.usable("com.x", false, null, "com.geely.drivemem"));
        assertFalse(CarMediaSessions.usable("com.x", true, "com.x.PRIVATE", "com.geely.drivemem"));
    }

    @Test public void aPausedSongLeavesTheCardAfterTenMinutes() {
        assertEquals(5_000 + CarMediaSessions.PAUSED_SHOW_MS, CarMediaSessions.pausedHideAt(false, 5_000));
        assertEquals(0, CarMediaSessions.pausedHideAt(true, 5_000));    // playing: never by this rule
        assertEquals(0, CarMediaSessions.pausedHideAt(false, 0));       // pause time unknown
    }

    @Test public void onlyBluetoothStartsThroughTheCarsBluetoothApp() {
        assertTrue(CarMediaSessions.viaCarBluetoothApp("com.android.bluetooth"));
        assertFalse(CarMediaSessions.viaCarBluetoothApp("com.njda.carplay"));
        assertFalse(CarMediaSessions.viaCarBluetoothApp(null));
    }

    @Test public void aDisconnectedPhoneLeavesNoSongOnTheCard() {
        // PlaybackState values: 7 error (Bluetooth after the phone leaves), 0 none.
        assertTrue(CarMediaSessions.ended(7));
        assertTrue(CarMediaSessions.ended(0));
        assertFalse(CarMediaSessions.ended(1));   // stopped: Play can still start it
        assertFalse(CarMediaSessions.ended(2));   // paused: still on the card for a while
        assertFalse(CarMediaSessions.ended(3));   // playing
    }
}
