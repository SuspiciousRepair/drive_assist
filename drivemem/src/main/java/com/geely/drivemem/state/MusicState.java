package com.geely.drivemem.state;

import com.geely.drivemem.util.SpotifyClient;

/** Now-playing card state, from two sources:
 *
 * - SPOTIFY: SpotifyClient's poll of the Spotify Web API (set() and
 *   setPosition()), whatever device is playing;
 * - SESSION: the car's own media session (CarMediaSessions), which is what
 *   the phone sends over Bluetooth -- any app, not only Spotify.
 *
 * The card shows one of them: the one playing, Spotify first when both are
 * (same song, and Spotify has the artwork URL and a real track id); when
 * neither plays, the one that has a track, Spotify first. Pure Java (no
 * Android), so the unit tests run it directly. Play/Skip go to the source
 * shown. */
public final class MusicState {

    public interface Listener { void onMusic(boolean playing, String title, String artist, String artUrl); }

    /** Play/Skip for the car's media session, given by CarMediaSessions. */
    public interface Controls { void playPause(); void next(); }

    public enum Source { NONE, SPOTIFY, SESSION }

    /** One source's view of the track. */
    public static final class Track {
        public boolean playing;
        public String title, artist, album, artUrl, id;
        /** A picture the source handed over itself (a Bitmap from the car's
         * session), when it has no URL. Object, so this class stays Android-free. */
        public Object artImage;
        public long durationMs, progressMs, progressAtMs;

        boolean hasTrack() { return title != null && !title.isEmpty(); }

        Track copy() {
            Track t = new Track();
            t.playing = playing; t.title = title; t.artist = artist; t.album = album;
            t.artUrl = artUrl; t.id = id; t.artImage = artImage;
            t.durationMs = durationMs; t.progressMs = progressMs; t.progressAtMs = progressAtMs;
            return t;
        }
    }

    private static final Object lock = new Object();
    private static Track spotify = new Track();
    private static Track session;              // null: no car media session
    private static volatile Track shown = new Track();
    private static volatile Source source = Source.NONE;
    private static volatile Listener listener;
    private static volatile Controls sessionControls;

    // ---- Spotify (SpotifyClient) ----

    /** Spotify's now-playing (called by SpotifyClient after each poll). */
    public static void set(boolean p, String t, String a, String u) {
        synchronized (lock) {
            spotify.playing = p; spotify.title = t; spotify.artist = a; spotify.artUrl = u;
            if (t == null) { spotify.id = null; spotify.album = null; spotify.durationMs = 0; }
        }
        publish();
    }

    /** From each Spotify poll: the track, and its position when the answer
     * came (atMs is SystemClock.elapsedRealtime(), or any clock that only goes up). */
    public static void setPosition(String id, String albumName, long duration, long progress, long atMs) {
        synchronized (lock) {
            spotify.id = id; spotify.album = albumName; spotify.durationMs = duration;
            spotify.progressMs = progress; spotify.progressAtMs = atMs;
        }
        publish();
    }

    /** True when the last good Spotify poll is too old to trust: the track it
     * saw would have ended by now (when it was playing), plus graceMs. Used
     * when Spotify cannot be reached -- the car offline -- so the card does
     * not show a song that may have stopped long ago. Paused: graceMs after
     * that poll, since nothing tells how long a pause lasts. */
    public static boolean staleAt(long nowMs, long graceMs) {
        synchronized (lock) {
            if (!spotify.hasTrack()) return false;   // nothing shown, nothing to hide
            long remaining = 0;
            if (spotify.playing && spotify.durationMs > 0)
                remaining = Math.max(0, spotify.durationMs - spotify.progressMs);
            return nowMs - spotify.progressAtMs > remaining + graceMs;
        }
    }

    // ---- the car's media session (CarMediaSessions) ----

    /** The car's media session's track; null when there is none. */
    public static void setSession(Track t, Controls controls) {
        synchronized (lock) { session = t == null ? null : t.copy(); }
        sessionControls = controls;
        publish();
    }

    // ---- what the card shows ----

    static Source choose(Track sp, Track se) {
        boolean spHas = sp != null && sp.hasTrack(), seHas = se != null && se.hasTrack();
        if (spHas && sp.playing) return Source.SPOTIFY;
        if (seHas && se.playing) return Source.SESSION;
        if (spHas) return Source.SPOTIFY;
        if (seHas) return Source.SESSION;
        return Source.NONE;
    }

    private static void publish() {
        Track was = shown, now;
        Source src;
        synchronized (lock) {
            src = choose(spotify, session);
            now = src == Source.SPOTIFY ? spotify.copy() : src == Source.SESSION ? session.copy() : new Track();
        }
        shown = now;
        source = src;
        boolean visibleChange = was.playing != now.playing
            || !java.util.Objects.equals(was.title, now.title)
            || !java.util.Objects.equals(was.artist, now.artist)
            || !java.util.Objects.equals(was.artUrl, now.artUrl)
            || was.artImage != now.artImage;
        if (visibleChange) {
            Listener l = listener;
            if (l != null) l.onMusic(now.playing, now.title, now.artist, now.artUrl);
        }
    }

    public static Source source() { return source; }
    public static boolean playing() { return shown.playing; }
    public static String title() { return shown.title; }
    public static String artist() { return shown.artist; }
    public static String artUrl() { return shown.artUrl; }
    public static Object artImage() { return shown.artImage; }
    public static String trackId() { return shown.id; }
    public static String album() { return shown.album; }
    public static long durationMs() { return shown.durationMs; }
    public static void setListener(Listener l) { listener = l; }

    /** The position now: the source's last report, moved on by the time
     * since, while playing. Never past the end of the track. */
    public static long positionNowMs(long nowMs) {
        Track t = shown;
        long p = t.progressMs;
        if (t.playing) p += Math.max(0, nowMs - t.progressAtMs);
        return t.durationMs > 0 ? Math.min(p, t.durationMs) : p;
    }

    /** Next track, on the source the card shows. */
    public static void next() {
        Controls c = sessionControls;
        if (source == Source.SESSION && c != null) c.next(); else SpotifyClient.next();
    }

    /** Play/pause, on the source the card shows. */
    public static void playPause() {
        Controls c = sessionControls;
        if (source == Source.SESSION && c != null) c.playPause(); else SpotifyClient.playPause();
    }

    /** Tests only: back to nothing. */
    static void resetForTest() {
        synchronized (lock) { spotify = new Track(); session = null; }
        shown = new Track(); source = Source.NONE; sessionControls = null;
    }

    private MusicState() {}
}
