package com.geely.drivemem.state;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MusicSourcesTest {

    @Before public void clean() { MusicState.resetForTest(); MusicState.setListener(null); }
    @After public void after() { MusicState.resetForTest(); MusicState.setListener(null); }

    private static MusicState.Track track(String title, boolean playing) {
        MusicState.Track t = new MusicState.Track();
        t.title = title; t.artist = "A"; t.playing = playing; t.id = "id-" + title;
        return t;
    }

    @Test public void choosesThePlayingSourceSpotifyFirst() {
        assertEquals(MusicState.Source.SPOTIFY, MusicState.choose(track("s", true), track("b", true)));
        assertEquals(MusicState.Source.SESSION, MusicState.choose(track("s", false), track("b", true)));
        assertEquals(MusicState.Source.SPOTIFY, MusicState.choose(track("s", false), track("b", false)));
        assertEquals(MusicState.Source.SESSION, MusicState.choose(new MusicState.Track(), track("b", false)));
        assertEquals(MusicState.Source.NONE, MusicState.choose(new MusicState.Track(), null));
    }

    @Test public void bluetoothShowsWhenSpotifyHasNothing() {
        MusicState.set(false, null, null, null);   // Spotify not connected / nothing playing
        MusicState.setSession(track("Song", true), null);
        assertEquals(MusicState.Source.SESSION, MusicState.source());
        assertEquals("Song", MusicState.title());
        assertEquals("id-Song", MusicState.trackId());
    }

    @Test public void spotifyPlayingWinsAndLosingItFallsBack() {
        MusicState.setSession(track("Bt", true), null);
        MusicState.set(true, "Sp", "X", "http://art");
        assertEquals("Sp", MusicState.title());
        MusicState.set(false, null, null, null);
        assertEquals("Bt", MusicState.title());
    }

    @Test public void listenerFiresOnlyOnVisibleChanges() {
        AtomicInteger n = new AtomicInteger();
        MusicState.setListener((p, t, a, u) -> n.incrementAndGet());
        MusicState.Track t = track("Same", true);
        MusicState.setSession(t, null);
        t.progressMs = 5000;   // only the position moved
        MusicState.setSession(t, null);
        assertEquals(1, n.get());
    }

    @Test public void sessionPositionIsUsedForTheLyrics() {
        MusicState.Track t = track("Pos", true);
        t.durationMs = 200_000; t.progressMs = 30_000; t.progressAtMs = 1_000;
        MusicState.setSession(t, null);
        assertEquals(32_000, MusicState.positionNowMs(3_000));
    }

    @Test public void controlsGoToTheSourceShown() {
        AtomicInteger next = new AtomicInteger();
        MusicState.setSession(track("Bt", true), new MusicState.Controls() {
            @Override public void playPause() { }
            @Override public void next() { next.incrementAndGet(); }
        });
        MusicState.next();
        assertEquals(1, next.get());
    }
}
