package com.geely.drivemem.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.service.media.MediaBrowserService;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.state.MusicState;

import java.util.ArrayList;
import java.util.List;

/** What the phone plays through the car -- Bluetooth music, Apple CarPlay,
 * Android Auto -- for the music card (MusicState's SESSION source).
 *
 * Each of those car apps offers a media browser service (the standard
 * Android way for a music app to share what it plays), exported and with no
 * permission. Drive Assist links to each one and reads its session from
 * there: song, artist, album, cover, position, Play and Skip. No permission
 * is asked or granted for this. A notification listener was used first; it
 * needs an Android screen this head unit does not have.
 *
 * Runs only while the home screen is up (start/stop from ComfortActivity,
 * like SpotifyClient's poll). Follows one session: the one playing, else
 * the first with a title. The position comes straight from the session's
 * PlaybackState (position + the elapsedRealtime it was taken at), so the
 * lyrics follow it exactly. */
public final class CarMediaSessions {
    private static final String TAG = CarAccess.TAG;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static final List<Link> links = new ArrayList<>();
    private static MediaController current;
    private static boolean running;

    private CarMediaSessions() {}

    /** One car app's media browser, and its session once linked. */
    private static final class Link {
        final ComponentName name;
        MediaBrowser browser;
        MediaController controller;
        final MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata m) { follow(); }
            @Override public void onPlaybackStateChanged(PlaybackState s) { follow(); }
            @Override public void onSessionDestroyed() { drop(Link.this); follow(); }
        };
        Link(ComponentName name) { this.name = name; }
    }

    public static void start(Context ctx) {
        if (running || !Prefs.getBtMusic(ctx)) return;
        app = ctx.getApplicationContext();
        running = true;
        for (ComponentName c : browserServices(app)) link(c);
    }

    public static void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(staleCheck);
        for (Link l : links) {
            drop(l);
            try { l.browser.disconnect(); } catch (Throwable ignored) { }
        }
        links.clear();
        current = null;
        MusicState.setSession(null, null);
    }

    /** The media browser services other apps may link to: exported, no
     * permission, not ours. */
    static List<ComponentName> browserServices(Context ctx) {
        List<ComponentName> out = new ArrayList<>();
        try {
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentServices(
                    new Intent(MediaBrowserService.SERVICE_INTERFACE), 0)) {
                ServiceInfo si = ri.serviceInfo;
                if (si != null && usable(si.packageName, si.exported, si.permission, ctx.getPackageName()))
                    out.add(new ComponentName(si.packageName, si.name));
            }
        } catch (Throwable t) { Log.w(TAG, "music browsers: " + t); }
        return out;
    }

    static boolean usable(String pkg, boolean exported, String permission, String own) {
        return pkg != null && !pkg.equals(own) && exported && permission == null;
    }

    private static void link(ComponentName c) {
        final Link l = new Link(c);
        l.browser = new MediaBrowser(app, c, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (!running) return;
                try {
                    l.controller = new MediaController(app, l.browser.getSessionToken());
                    l.controller.registerCallback(l.cb, main);
                    Log.i(TAG, "music: linked to " + c.getPackageName());
                } catch (Throwable t) { Log.w(TAG, "music " + c.getPackageName() + ": " + t); }
                follow();
            }
            @Override public void onConnectionFailed() {
                Log.i(TAG, "music: " + c.getPackageName() + " did not link");
            }
            @Override public void onConnectionSuspended() { drop(l); follow(); }
        }, null);
        links.add(l);
        try { l.browser.connect(); } catch (Throwable t) { Log.w(TAG, "music " + c.getPackageName() + ": " + t); }
    }

    private static void drop(Link l) {
        if (l.controller == null) return;
        try { l.controller.unregisterCallback(l.cb); } catch (Throwable ignored) { }
        if (l.controller == current) current = null;
        l.controller = null;
    }

    // Picks the session to follow: one that plays, else the first with a title.
    private static void follow() {
        if (!running) return;
        MediaController pick = null;
        for (Link l : links) {
            MediaController c = l.controller;
            if (c == null) continue;
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            if (pick == null && title(c.getMetadata()) != null) pick = c;
        }
        if (pick != current && pick != null) Log.i(TAG, "music: following " + pick.getPackageName());
        current = pick;
        publish();
    }

    private static String title(MediaMetadata m) {
        if (m == null) return null;
        String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        return t == null || t.trim().isEmpty() ? null : t;
    }

    // Re-checks the followed session when its song should have ended: a
    // session that says "playing" but stopped sending (the phone left, the
    // CarPlay link dropped) sends no callback, so a timer has to look.
    private static final Runnable staleCheck = CarMediaSessions::publish;
    private static String loggedState;

    private static void publish() {
        main.removeCallbacks(staleCheck);
        final MediaController c = current;
        MediaMetadata m = c == null ? null : c.getMetadata();
        String title = title(m);
        if (c == null || title == null || !running) { MusicState.setSession(null, null); return; }
        PlaybackState st = c.getPlaybackState();
        MusicState.Track t = new MusicState.Track();
        t.title = title;
        t.artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (t.artist == null) t.artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        t.album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        t.durationMs = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
        t.playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (st != null) {
            t.progressMs = Math.max(0, st.getPosition());
            t.progressAtMs = st.getLastPositionUpdateTime();
            if (t.progressAtMs <= 0) t.progressAtMs = android.os.SystemClock.elapsedRealtime();
        }
        t.id = trackKey(t.artist, t.title);
        long now = android.os.SystemClock.elapsedRealtime();
        String state = c.getPackageName() + " '" + t.title + "' playing=" + t.playing + " dur=" + t.durationMs;
        if (!state.equals(loggedState)) { loggedState = state; Log.i(TAG, "bt music: " + state); }
        // Android moves a playing session's position on by itself when it is
        // read, and stops it at the song's end. So "playing" at the end for
        // longer than the grace means nothing plays any more.
        long ends = endsAt(t.playing, t.progressMs, t.progressAtMs, t.durationMs);
        long hideAt = staleAt(t.id, ends, now);
        if (hideAt > 0 && now >= hideAt) {
            Log.i(TAG, "bt music: " + c.getPackageName() + " says playing past the song's end, hiding the card");
            MusicState.setSession(null, null);
            return;
        }
        // Paused for long: the phone left, or nobody will press Play again.
        // A paused session keeps the time it was paused at (Android re-stamps
        // only a playing one), so this counts from the pause.
        long pausedHideAt = pausedHideAt(t.playing, st == null ? 0 : st.getLastPositionUpdateTime());
        if (pausedHideAt > 0 && now >= pausedHideAt) {
            Log.i(TAG, "music: " + c.getPackageName() + " paused for long, hiding the card");
            MusicState.setSession(null, null);
            return;
        }
        long wake = hideAt > 0 ? hideAt : ends > 0 ? ends : pausedHideAt;
        if (wake > 0) main.postDelayed(staleCheck, Math.max(0, wake - now) + 1000);
        android.graphics.Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        t.artImage = art;
        if (art == null) {
            String u = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (u == null) u = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
            t.artUrl = u;
        }
        MusicState.setSession(t, new MusicState.Controls() {
            @Override public void playPause() {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
                else c.getTransportControls().play();
            }
            @Override public void next() { c.getTransportControls().skipToNext(); }
        });
    }

    /** When a playing track reaches its end (elapsedRealtime); 0 when paused
     * or no length is given. */
    static long endsAt(boolean playing, long progressMs, long progressAtMs, long durationMs) {
        if (!playing || durationMs <= 0) return 0;
        return progressAtMs + Math.max(0, durationMs - progressMs);
    }

    /** How long a paused session stays on the card. */
    static final long PAUSED_SHOW_MS = 10 * 60_000L;

    /** When a paused session leaves the card (elapsedRealtime): PAUSED_SHOW_MS
     * after it was paused. 0 while it plays, or when the pause time is unknown. */
    static long pausedHideAt(boolean playing, long pausedAtMs) {
        return playing || pausedAtMs <= 0 ? 0 : pausedAtMs + PAUSED_SHOW_MS;
    }

    private static String endId;      // the track first seen past its end ...
    private static long endSeenMs;    // ... at this time

    /** When a session still "playing" past its song's end stops being
     * believed: SpotifyClient.STALE_GRACE_MS after it was first seen there,
     * the same rule as Spotify's when it cannot be reached. 0 while the song
     * has not ended (or it is paused, or has no length). */
    static long staleAt(String id, long endsAt, long nowMs) {
        if (endsAt <= 0 || nowMs < endsAt) { endId = null; return 0; }
        if (endId == null || !endId.equals(id)) { endId = id; endSeenMs = nowMs; }
        return endSeenMs + SpotifyClient.STALE_GRACE_MS;
    }

    /** A stable id for a session track (sessions give no Spotify id): the
     * lyrics cache key. Same artist and title, same key. */
    static String trackKey(String artist, String title) {
        String s = (artist == null ? "" : artist.trim().toLowerCase(java.util.Locale.ROOT)) + "\n"
            + title.trim().toLowerCase(java.util.Locale.ROOT);
        return "s-" + java.util.UUID.nameUUIDFromBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
