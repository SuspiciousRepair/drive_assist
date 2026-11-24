package com.geely.drivemem.state;

/** Now-playing card state: what the phone plays through the car (Bluetooth
 * music, CarPlay, Android Auto), read from the car apps' media sessions by
 * CarMediaSessions -- any music app on the phone.
 *
 * Pure Java (no Android), so the unit tests run it directly. Play/Skip go
 * to the session shown. */
public final class MusicState {

    public interface Listener { void onMusic(boolean playing, String title, String artist, String artUrl); }

    /** Play/Skip for the car's media session, given by CarMediaSessions. */
    public interface Controls { void playPause(); void next(); }

    /** The session's view of the track. */
    public static final class Track {
        public boolean playing;
        public String title, artist, album, artUrl, id;
        /** A picture the session handed over itself (a Bitmap), when it has
         * no URL. Object, so this class stays Android-free. */
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

    private static volatile Track shown = new Track();
    private static volatile Listener listener;
    private static volatile Controls controls;

    /** The car's media session's track; null (or no title) when there is none. */
    public static void setSession(Track t, Controls c) {
        Track was = shown;
        Track now = t != null && t.hasTrack() ? t.copy() : new Track();
        shown = now;
        controls = now.hasTrack() ? c : null;
        boolean visibleChange = was.playing != now.playing
            || !java.util.Objects.equals(was.title, now.title)
            || !java.util.Objects.equals(was.artist, now.artist)
            // The album names the cover (CoverCache) and shows on the card.
            // A player that sends it after the title left the card on the
            // last song's album and cover until something else changed.
            || !java.util.Objects.equals(was.album, now.album)
            || !java.util.Objects.equals(was.artUrl, now.artUrl)
            || was.artImage != now.artImage;
        if (visibleChange) {
            Listener l = listener;
            if (l != null) l.onMusic(now.playing, now.title, now.artist, now.artUrl);
        }
    }

    public static boolean playing() { return shown.playing; }
    public static String title() { return shown.title; }
    public static String artist() { return shown.artist; }
    public static String artUrl() { return shown.artUrl; }
    public static Object artImage() { return shown.artImage; }
    public static String trackId() { return shown.id; }
    public static String album() { return shown.album; }
    public static long durationMs() { return shown.durationMs; }
    public static void setListener(Listener l) { listener = l; }

    /** The position now: the session's last report, moved on by the time
     * since, while playing. Never past the end of the track. */
    public static long positionNowMs(long nowMs) {
        Track t = shown;
        long p = t.progressMs;
        if (t.playing) p += Math.max(0, nowMs - t.progressAtMs);
        return t.durationMs > 0 ? Math.min(p, t.durationMs) : p;
    }

    /** Next track, on the session shown. Nothing when there is none. */
    public static void next() {
        Controls c = controls;
        if (c != null) c.next();
    }

    /** Play/pause, on the session shown. Nothing when there is none. */
    public static void playPause() {
        Controls c = controls;
        if (c != null) c.playPause();
    }

    /** Tests only: back to nothing. */
    static void resetForTest() {
        shown = new Track(); controls = null;
    }

    private MusicState() {}
}
