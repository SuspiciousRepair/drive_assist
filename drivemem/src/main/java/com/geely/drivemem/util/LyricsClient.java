package com.geely.drivemem.util;

import android.content.Context;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Synced lyrics for the Spotify card, from LRCLIB (lrclib.net).
 *
 * Spotify's Web API has no lyrics. LRCLIB is a free, open lyrics database
 * with time-stamped (LRC) lines and no API key. What it gets from the car:
 * the track name, the artist, the album and the length. Nothing else, and
 * only while Settings > Spotify > Lyrics is on.
 *
 * One request per track, made ahead for the next track in Spotify's queue
 * (prefetch) and else when the track starts. The answer is kept in the cache folder, one file
 * per Spotify track id, so a song heard again costs no network. A track
 * with no synced lyrics is kept too, as an empty file, and asked again
 * after NONE_RETRY_MS (LRCLIB grows). A network failure is not kept: the
 * next request for that track tries again, at most once per RETRY_MS.
 *
 * Plain (untimed) lyrics are ignored: the card follows the song line by
 * line, and plain text has no times to follow. */
public final class LyricsClient {
    private static final String TAG = CarAccess.TAG;
    private static final String BASE = "https://lrclib.net/api";
    private static final long NONE_RETRY_MS = 7L * 24 * 3600_000L;
    private static final long RETRY_MS = 60_000L;
    private static final int MAX_FILES = 300;
    /** A search hit counts only when its length is this close to Spotify's. */
    static final int DURATION_TOLERANCE_S = 3;

    private static final java.util.concurrent.ExecutorService io =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "lyrics"));

    private static volatile String loadedId;   // track the lines below belong to
    private static volatile Lrc loaded;        // null: none (yet) for loadedId
    private static volatile String inFlightId;
    private static volatile String failedId;
    private static volatile long failedAtMs;
    private static String userAgent;

    private LyricsClient() {}

    /** The lines for trackId, or null when they are not here (yet). */
    public static Lrc linesFor(String trackId) {
        return trackId != null && trackId.equals(loadedId) ? loaded : null;
    }

    /** True while the lyrics for trackId are being fetched. */
    public static boolean pending(String trackId) {
        return trackId != null && trackId.equals(inFlightId);
    }

    /** Gets the lyrics for this track in the background, from the cache or
     * from LRCLIB, then runs onDone (on the lyrics thread) -- with lines or
     * without, unless a newer track took over meanwhile. Cheap to call again
     * for the same track: it does nothing then. */
    public static void request(Context ctx, String trackId, String title, String artist,
                               String album, long durationMs, Runnable onDone) {
        if (trackId == null || title == null || title.isEmpty() || artist == null) return;
        if (trackId.equals(loadedId) || trackId.equals(inFlightId)) return;
        if (trackId.equals(failedId) && System.currentTimeMillis() - failedAtMs < RETRY_MS) return;
        final Context app = ctx.getApplicationContext();
        inFlightId = trackId;
        io.execute(() -> {
            try {
                String lrc = load(app, trackId, title, artist, album, durationMs);
                if (!trackId.equals(inFlightId)) return;   // a newer track took over
                if (lrc == null) { failedId = trackId; failedAtMs = System.currentTimeMillis(); }
                else { loaded = Lrc.parse(lrc); loadedId = trackId; }
            } catch (Throwable t) {
                Log.w(TAG, "lyrics: " + t);
            }
            if (!trackId.equals(inFlightId)) return;
            inFlightId = null;
            if (onDone != null) onDone.run();
        });
    }

    /** Puts the lyrics of a track that has not started yet (the next in the
     * queue) in the cache, so they are there at once when it starts. Does
     * not touch what the card shows. */
    public static void prefetch(Context ctx, String trackId, String title, String artist,
                                String album, long durationMs) {
        if (trackId == null || title == null || title.isEmpty() || artist == null) return;
        final Context app = ctx.getApplicationContext();
        io.execute(() -> {
            try { load(app, trackId, title, artist, album, durationMs); }
            catch (Throwable t) { Log.w(TAG, "lyrics prefetch: " + t); }
        });
    }

    /** The LRC text, "" when LRCLIB has no synced lines, null on a failure. */
    private static String load(Context app, String trackId, String title, String artist,
                               String album, long durationMs) {
        File dir = new File(app.getCacheDir(), "lyrics");
        File f = new File(dir, safeName(trackId) + ".lrc");
        if (f.isFile()) {
            boolean none = f.length() == 0;
            if (!none || System.currentTimeMillis() - f.lastModified() < NONE_RETRY_MS) {
                String cached = read(f);
                if (cached != null) return cached;
            }
        }
        String lrc = fetch(app, title, artist, album, durationMs);
        if (lrc == null) return null;
        try {
            if (dir.isDirectory() || dir.mkdirs()) {
                java.nio.file.Files.write(f.toPath(), lrc.getBytes(StandardCharsets.UTF_8));
                trim(dir);
            }
        } catch (Throwable t) { Log.w(TAG, "lyrics cache: " + t); }
        return lrc;
    }

    private static String fetch(Context app, String title, String artist, String album, long durationMs) {
        int durS = (int) Math.round(durationMs / 1000.0);
        // 1. The exact match: LRCLIB answers 404 when it has no such track.
        StringBuilder q = new StringBuilder("/get?track_name=").append(enc(title))
            .append("&artist_name=").append(enc(artist));
        if (album != null && !album.isEmpty()) q.append("&album_name=").append(enc(album));
        if (durS > 0) q.append("&duration=").append(durS);
        Http got = get(app, q.toString());
        if (got == null) return null;
        if (got.code == 200) {
            try {
                String s = syncedOf(new org.json.JSONObject(got.body));
                if (s != null) return s;
            } catch (Throwable t) { Log.w(TAG, "lyrics get: " + t); }
        } else if (got.code != 404) {
            return null;
        }
        // 2. A search, for an album name or length that differs a little.
        Http found = get(app, "/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
        if (found == null || found.code != 200) return found == null ? null : "";
        try {
            String s = pickSynced(new org.json.JSONArray(found.body), durS);
            return s == null ? "" : s;
        } catch (Throwable t) {
            Log.w(TAG, "lyrics search: " + t);
            return "";
        }
    }

    /** The synced text of one LRCLIB record, or null (instrumental, plain only). */
    static String syncedOf(org.json.JSONObject o) {
        if (o == null || o.optBoolean("instrumental", false)) return null;
        if (o.isNull("syncedLyrics")) return null;
        String s = o.optString("syncedLyrics", "");
        return s.trim().isEmpty() ? null : s;
    }

    /** The first search hit with synced lines whose length matches the
     * track's (when the track's length is known). */
    static String pickSynced(org.json.JSONArray hits, int durationS) {
        if (hits == null) return null;
        for (int i = 0; i < hits.length(); i++) {
            org.json.JSONObject o = hits.optJSONObject(i);
            String s = syncedOf(o);
            if (s == null) continue;
            if (durationS > 0) {
                double d = o.optDouble("duration", -1);
                if (d < 0 || Math.abs(d - durationS) > DURATION_TOLERANCE_S) continue;
            }
            return s;
        }
        return null;
    }

    private static final class Http {
        final int code; final String body;
        Http(int code, String body) { this.code = code; this.body = body; }
    }

    private static Http get(Context app, String path) {
        try {
            java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(BASE + path).openConnection();
            c.setRequestProperty("User-Agent", userAgent(app));
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code != 200) return new Http(code, null);
            try (java.io.InputStream in = c.getInputStream()) {
                return new Http(code, new String(readAllBytes(in), StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "lyrics fetch: " + t);
            return null;
        }
    }

    private static synchronized String userAgent(Context app) {
        if (userAgent == null) {
            String ver = "?";
            try { ver = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; }
            catch (Exception ignored) { }
            userAgent = "DriveAssist/" + ver + " (+https://github.com/SuspiciousRepair/drive_assist)";
        }
        return userAgent;
    }

    /** Spotify ids are base62; anything else is made file-safe all the same. */
    static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String read(File f) {
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        catch (Throwable t) { return null; }
    }

    /** Keeps the newest MAX_FILES files. */
    private static void trim(File dir) {
        File[] all = dir.listFiles();
        if (all == null || all.length <= MAX_FILES) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_FILES; i < all.length; i++) //noinspection ResultOfMethodCallIgnored
            all[i].delete();
    }

    private static byte[] readAllBytes(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
