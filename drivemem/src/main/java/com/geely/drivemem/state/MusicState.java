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
        if (p == playing && java.util.Objects.equals(t, title)
                && java.util.Objects.equals(a, artist) && java.util.Objects.equals(u, artUrl)) return;
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

    // ---- where in the track, for the lyrics card ----
    // Not part of set() and fires no listener: the position changes on every
    // poll, and the card reads it on its own clock (positionNowMs()).
    private static volatile String trackId, album;
    private static volatile long durationMs, progressMs, progressAtMs;

    /** From each poll: the track, and its position when the answer came
     * (atMs is SystemClock.elapsedRealtime(), or any clock that only goes up). */
    public static void setPosition(String id, String albumName, long duration, long progress, long atMs) {
        trackId = id; album = albumName; durationMs = duration;
        progressMs = progress; progressAtMs = atMs;
    }

    public static String trackId() { return trackId; }
    public static String album() { return album; }
    public static long durationMs() { return durationMs; }

    /** The position now: the last poll's, moved on by the time since, while
     * playing. Never past the end of the track. */
    public static long positionNowMs(long nowMs) {
        long p = progressMs;
        if (playing) p += Math.max(0, nowMs - progressAtMs);
        long d = durationMs;
        return d > 0 ? Math.min(p, d) : p;
    }

    // Delegates to SpotifyClient; actual state updates come from next poll.
    /** Requests next track. */
    public static void next() { SpotifyClient.next(); }
    /** Toggles play/pause. */
    public static void playPause() { SpotifyClient.playPause(); }

    private MusicState() {}
}
