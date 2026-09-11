package com.geely.drivemem;

import com.geely.drivemem.state.MusicState;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MusicStateTest {

    private static final class Call {
        final boolean playing; final String title, artist, artUrl;
        Call(boolean p, String t, String a, String u) { playing = p; title = t; artist = a; artUrl = u; }
    }

    private static final class Recorder implements MusicState.Listener {
        final List<Call> calls = new ArrayList<>();
        @Override public void onMusic(boolean playing, String title, String artist, String artUrl) {
            calls.add(new Call(playing, title, artist, artUrl));
        }
    }

    @Test public void setUpdatesAllFourFieldsAndFiresListenerWithSameValues() {
        Recorder r = new Recorder();
        MusicState.setListener(r);

        MusicState.set(true, "Song", "Artist", "http://art");

        assertTrue(MusicState.playing());
        assertEquals("Song", MusicState.title());
        assertEquals("Artist", MusicState.artist());
        assertEquals("http://art", MusicState.artUrl());

        assertEquals(1, r.calls.size());
        Call c = r.calls.get(0);
        assertTrue(c.playing);
        assertEquals("Song", c.title);
        assertEquals("Artist", c.artist);
        assertEquals("http://art", c.artUrl);

        MusicState.setListener(null);
    }

    @Test public void setWithNoListenerRegisteredDoesNotThrow() {
        MusicState.setListener(null);
        MusicState.set(false, "X", "Y", "Z"); // must not throw
        assertFalse(MusicState.playing());
    }

    @Test public void clearingToNothingPlayingReflectsInAllGetters() {
        MusicState.set(true, "Song", "Artist", "http://art");
        MusicState.set(false, null, null, null);

        assertFalse(MusicState.playing());
        assertNull(MusicState.title());
        assertNull(MusicState.artist());
        assertNull(MusicState.artUrl());
    }

    @Test public void replacingListenerOnlyNewOneFiresOnNextSet() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();

        MusicState.setListener(first);
        MusicState.set(true, "A", "B", "C");
        assertEquals(1, first.calls.size());

        MusicState.setListener(second);
        MusicState.set(true, "D", "E", "F");

        assertEquals(1, first.calls.size()); // first did not receive the second update
        assertEquals(1, second.calls.size());
        assertEquals("D", second.calls.get(0).title);

        MusicState.setListener(null);
    }

    @Test public void consecutiveSetsEachFireWithLatestValues() {
        Recorder r = new Recorder();
        MusicState.setListener(r);

        MusicState.set(true, "One", "A1", "U1");
        MusicState.set(true, "Two", "A2", "U2");
        MusicState.set(false, "Two", "A2", "U2");

        assertEquals(3, r.calls.size());
        assertEquals("One", r.calls.get(0).title);
        assertEquals("Two", r.calls.get(1).title);
        assertFalse(r.calls.get(2).playing);

        MusicState.setListener(null);
    }
}
