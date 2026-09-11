package com.geely.drivemem.state;

import com.geely.drivemem.ui.ComfortActivity;
import com.geely.drivemem.util.SpotifyClient;

/** Now-playing card state. Fed by SpotifyClient via Spotify Web API.
 * Exposes now-playing info (playing, title, artist, artwork URL) and
 * provides play/pause and next-track controls. */
public final class MusicState {

    public interface Listener { void onMusic(boolean playing, String title, String artist, String artUrl); }

    private static volatile boolean playing;
    private static volatile String title, artist, artUrl;
    private static volatile Listener listener;

    /** Updates now-playing state (called by SpotifyClient after each poll). */
    public static void set(boolean p, String t, String a, String u) {
        playing = p; title = t; artist = a; artUrl = u;
        fire();
    }

    private static void fire() {
        Listener l = listener;
        if (l != null) l.onMusic(playing, title, artist, artUrl);
    }

    public static boolean playing() { return playing; }
    public static String title() { return title; }
    public static String artist() { return artist; }
    public static String artUrl() { return artUrl; }
    public static void setListener(Listener l) { listener = l; }

    // Delegates to SpotifyClient; actual state updates come from next poll.
    /** Requests next track. */
    public static void next() { SpotifyClient.next(); }
    /** Toggles play/pause. */
    public static void playPause() { SpotifyClient.playPause(); }

    private MusicState() {}
}
