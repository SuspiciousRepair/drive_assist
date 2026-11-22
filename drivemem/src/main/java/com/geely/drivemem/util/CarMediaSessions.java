package com.geely.drivemem.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.services.MediaAccessListener;
import com.geely.drivemem.state.MusicState;

import java.util.List;

/** The car's own media sessions -- what the phone plays over Bluetooth, from
 * any app -- for the music card (MusicState's SESSION source).
 *
 * Reading other apps' sessions needs MediaAccessListener to be an enabled
 * notification listener. Only the driver grants that, in the head unit's own
 * Android screen (accessScreen()), which Config opens when "Bluetooth music"
 * is turned on -- Drive Assist adds no permission screen of its own and
 * grants nothing silently. Without it getActiveSessions throws
 * SecurityException and the card simply stays on Spotify.
 *
 * Runs only while the home screen is up (start/stop from ComfortActivity,
 * like SpotifyClient's poll). Follows one session: the one playing, else
 * the first with a title; switches when the list changes. The position
 * comes straight from the session's PlaybackState (position + the
 * elapsedRealtime it was taken at), so the lyrics follow it exactly. */
public final class CarMediaSessions {
    private static final String TAG = CarAccess.TAG;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static MediaSessionManager msm;
    private static ComponentName key;
    private static MediaController current;
    private static boolean running;

    private CarMediaSessions() {}

    private static final MediaSessionManager.OnActiveSessionsChangedListener changed = CarMediaSessions::follow;

    private static final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { publish(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { publish(); }
        @Override public void onSessionDestroyed() { follow(sessions()); }
    };

    public static void start(Context ctx) {
        if (running || !Prefs.getBtMusic(ctx)) return;
        Context app = ctx.getApplicationContext();
        msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        key = new ComponentName(app, MediaAccessListener.class);
        if (msm == null) return;
        try {
            msm.addOnActiveSessionsChangedListener(changed, key, main);
        } catch (SecurityException e) {
            Log.i(TAG, "bt music: notification access not allowed (Config opens Android's screen)");
            return;   // next start (screen resumed) tries again
        } catch (Throwable t) { Log.w(TAG, "bt music: " + t); return; }
        running = true;
        follow(sessions());
    }

    public static void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(staleCheck);
        try { msm.removeOnActiveSessionsChangedListener(changed); } catch (Throwable ignored) { }
        attach(null);
        MusicState.setSession(null, null);
    }

    /** Whether the driver has allowed Drive Assist in Android's notification
     * access screen. */
    public static boolean hasAccess(Context ctx) {
        try {
            android.app.NotificationManager nm =
                (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationListenerAccessGranted(
                new ComponentName(ctx, MediaAccessListener.class));
        } catch (Throwable t) { return false; }
    }

    /** The head unit's own screen where the driver allows it: Android's page
     * for Drive Assist's listener, else Android's notification access list.
     * Null when this head unit has neither -- then Bluetooth music is not
     * offered. */
    public static Intent accessScreen(Context ctx) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        Intent detail = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                new ComponentName(ctx, MediaAccessListener.class).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent list = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (Intent i : new Intent[]{detail, list}) {
            try { if (pm.resolveActivity(i, 0) != null) return i; } catch (Throwable ignored) { }
        }
        return null;
    }

    private static List<MediaController> sessions() {
        try { return msm == null ? null : msm.getActiveSessions(key); }
        catch (Throwable t) { Log.w(TAG, "bt music sessions: " + t); return null; }
    }

    // Picks the session to follow: one that plays, else the first with a title.
    private static void follow(List<MediaController> list) {
        if (!running) return;
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                if (c == null || "com.geely.drivemem".equals(c.getPackageName())) continue;
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
                if (pick == null && title(c.getMetadata()) != null) pick = c;
            }
        }
        attach(pick);
        publish();
    }

    private static void attach(MediaController c) {
        if (current != null && c != null && current.getSessionToken().equals(c.getSessionToken())) return;
        if (current != null) { try { current.unregisterCallback(cb); } catch (Throwable ignored) { } }
        current = c;
        if (c != null) {
            try { c.registerCallback(cb, main); } catch (Throwable t) { Log.w(TAG, "bt music cb: " + t); }
            Log.i(TAG, "bt music: following " + c.getPackageName());
        }
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
        long wake = hideAt > 0 ? hideAt : ends;
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
