package com.geely.drivemem.state;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** The music card's state: one source, the car's media session (phone music). */
public class MusicStateTest {

    @Before public void clean() { MusicState.resetForTest(); MusicState.setListener(null); }
    @After public void after() { MusicState.resetForTest(); MusicState.setListener(null); }

    private static MusicState.Track track(String title, boolean playing) {
        MusicState.Track t = new MusicState.Track();
        t.title = title; t.artist = "A"; t.playing = playing; t.id = "id-" + title;
        return t;
    }

    @Test public void aSessionTrackIsWhatTheCardShows() {
        List<String> seen = new ArrayList<>();
        MusicState.setListener((p, t, a, u) -> seen.add(p + " " + t + " " + a));
        MusicState.Track t = track("Song", true);
        t.album = "Album";
        MusicState.setSession(t, null);
        assertTrue(MusicState.playing());
        assertEquals("Song", MusicState.title());
        assertEquals("A", MusicState.artist());
        assertEquals("Album", MusicState.album());
        assertEquals("id-Song", MusicState.trackId());
        assertEquals(1, seen.size());
        assertEquals("true Song A", seen.get(0));
    }

    @Test public void noSessionClearsTheCard() {
        MusicState.setSession(track("Song", true), null);
        AtomicInteger n = new AtomicInteger();
        MusicState.setListener((p, t, a, u) -> n.incrementAndGet());
        MusicState.setSession(null, null);
        assertFalse(MusicState.playing());
        assertNull(MusicState.title());
        assertNull(MusicState.trackId());
        assertEquals(1, n.get());
    }

    @Test public void aTrackWithNoTitleIsNoTrack() {
        MusicState.setSession(new MusicState.Track(), null);
        assertNull(MusicState.title());
    }

    @Test public void listenerFiresOnlyOnVisibleChanges() {
        AtomicInteger n = new AtomicInteger();
        MusicState.setListener((p, t, a, u) -> n.incrementAndGet());
        MusicState.Track t = track("Same", true);
        MusicState.setSession(t, null);
        t.progressMs = 5000;   // only the position moved
        MusicState.setSession(t, null);
        assertEquals(1, n.get());
        t.playing = false;     // paused: the card changes
        MusicState.setSession(t, null);
        assertEquals(2, n.get());
    }

    @Test public void theSessionTrackIsCopied() {
        MusicState.Track t = track("Before", true);
        MusicState.setSession(t, null);
        t.title = "After";
        assertEquals("Before", MusicState.title());
    }

    @Test public void sessionPositionIsUsedForTheLyrics() {
        MusicState.Track t = track("Pos", true);
        t.durationMs = 200_000; t.progressMs = 30_000; t.progressAtMs = 1_000;
        MusicState.setSession(t, null);
        assertEquals(32_000, MusicState.positionNowMs(3_000));
        assertEquals(200_000, MusicState.positionNowMs(10_000_000));   // never past the end
        assertEquals("a clock that went back adds nothing", 30_000, MusicState.positionNowMs(0));
    }

    @Test public void aPausedPositionStaysPut() {
        MusicState.Track t = track("Paused", false);
        t.durationMs = 200_000; t.progressMs = 42_000; t.progressAtMs = 1_000;
        MusicState.setSession(t, null);
        assertEquals(42_000, MusicState.positionNowMs(60_000));
    }

    @Test public void controlsGoToTheSessionShown() {
        AtomicInteger next = new AtomicInteger(), play = new AtomicInteger();
        MusicState.setSession(track("Bt", true), new MusicState.Controls() {
            @Override public void playPause() { play.incrementAndGet(); }
            @Override public void next() { next.incrementAndGet(); }
        });
        MusicState.next();
        MusicState.playPause();
        assertEquals(1, next.get());
        assertEquals(1, play.get());
    }

    @Test public void noSessionMeansTheButtonsDoNothing() {
        AtomicInteger n = new AtomicInteger();
        MusicState.setSession(track("Bt", true), new MusicState.Controls() {
            @Override public void playPause() { n.incrementAndGet(); }
            @Override public void next() { n.incrementAndGet(); }
        });
        MusicState.setSession(null, null);
        MusicState.next();      // must not throw, must not reach the old session
        MusicState.playPause();
        assertEquals(0, n.get());
    }
}
