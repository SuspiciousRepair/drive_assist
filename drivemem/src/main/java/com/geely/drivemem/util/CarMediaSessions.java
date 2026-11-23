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
import android.view.KeyEvent;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.state.MusicState;

import java.util.ArrayList;
import java.util.List;

/** What the phone plays through the car -- Bluetooth music, Apple CarPlay,
 * Android Auto -- for the music card (MusicState).
 *
 * Each of those car apps offers a media browser service (the standard
 * Android way for a music app to share what it plays), exported and with no
 * permission. Drive Assist links to each one and reads its session from
 * there: song, artist, album, cover, position, Play and Skip. No permission
 * is asked or granted for this. A notification listener was used first; it
 * needs an Android screen this head unit does not have.
 *
 * Starts with the home screen and then stays up while the app runs (it
 * only stops when Phone music is turned off): a fresh link would not know a
 * player that already went quiet. The callbacks are cheap (CarPlay: one
 * update every ~5 s).
 * Follows one session: the one playing, else
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
        // The state as the session sent it (the callback gets it raw).
        // getPlaybackState() is not the same: while playing, Android moves the
        // position on to "now" and caps it at the song's length -- and CarPlay
        // gives every song a length of 0, so the position read that way was
        // always 0 and the lyrics stayed at the start. Paused, Android leaves
        // it alone, which is why pausing showed the right line.
        PlaybackState state;
        // While it plays, CarPlay sends its position every ~5 s. After the
        // cable is pulled it keeps saying "playing" and just stops sending.
        long lastPlayingPush;   // elapsedRealtime of the last "playing" update
        int ticks;              // updates in a row, each within TICK_GAP_MS
        final MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata m) { follow(); }
            @Override public void onPlaybackStateChanged(PlaybackState s) {
                state = s;
                long now = android.os.SystemClock.elapsedRealtime();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    ticks = lastPlayingPush > 0 && now - lastPlayingPush <= TICK_GAP_MS ? ticks + 1 : 1;
                    lastPlayingPush = now;
                } else {
                    ticks = 0;
                }
                follow();
            }
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
        main.removeCallbacks(hide);
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
        l.state = null;
        l.ticks = 0;
        l.lastPlayingPush = 0;
    }

    private static Link linkOf(MediaController c) {
        for (Link l : links) if (l.controller == c) return l;
        return null;
    }

    /** Two "playing" updates this close count as the player ticking. */
    static final long TICK_GAP_MS = 10_000;
    /** A ticking player silent this long while "playing" is gone. */
    static final long SILENT_MS = 20_000;

    /** When a playing session that was ticking (3 updates in a row) counts as
     * gone: SILENT_MS after its last update. 0 when the rule does not apply:
     * paused, or a player that does not tick (Bluetooth sends on change only). */
    static long silentAt(boolean playing, int ticks, long lastPlayingPush) {
        if (!playing || ticks < 3 || lastPlayingPush <= 0) return 0;
        return lastPlayingPush + SILENT_MS;
    }

    /** The session's own last state (see Link.state), or Android's view of
     * it before the first update arrives. */
    private static PlaybackState stateOf(MediaController c) {
        if (c == null) return null;
        for (Link l : links) if (l.controller == c && l.state != null) return l.state;
        return c.getPlaybackState();
    }

    // Picks the session to follow: one that plays, else the first with a title.
    private static void follow() {
        if (!running) return;
        MediaController pick = null;
        for (Link l : links) {
            MediaController c = l.controller;
            if (c == null) continue;
            PlaybackState st = stateOf(c);
            if (st != null && ended(st.getState())) continue;
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

    // A skip on CarPlay drops the song for a moment before the next one
    // comes, which hid the card and showed it again on every skip. The card
    // now waits HIDE_WAIT_MS; a song that comes back in that time cancels it.
    static final long HIDE_WAIT_MS = 3000;
    private static final Runnable hide = () -> MusicState.setSession(null, null);

    private static void hideSoon() {
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_WAIT_MS);
    }

    private static void publish() {
        main.removeCallbacks(staleCheck);
        final MediaController c = current;
        MediaMetadata m = c == null ? null : c.getMetadata();
        String title = title(m);
        if (!running) { main.removeCallbacks(hide); MusicState.setSession(null, null); return; }
        PlaybackState st = stateOf(c);
        if (c == null || title == null || (st != null && ended(st.getState()))) { hideSoon(); return; }
        main.removeCallbacks(hide);
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
        // A player that kept sending updates while playing and went quiet:
        // the phone is gone (CarPlay after the cable is pulled).
        Link link = linkOf(c);
        long silentAt = link == null ? 0 : silentAt(t.playing, link.ticks, link.lastPlayingPush);
        if (silentAt > 0 && now >= silentAt) {
            Log.i(TAG, "music: " + c.getPackageName() + " stopped sending while playing, hiding the card");
            MusicState.setSession(null, null);
            return;
        }
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
        if (silentAt > 0 && (wake <= 0 || silentAt < wake)) wake = silentAt;
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
                PlaybackState s = stateOf(c);
                boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
                if (playing || !startBluetooth(c.getPackageName())) press(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
            @Override public void next() { press(c, KeyEvent.KEYCODE_MEDIA_NEXT); }
        });
    }

    /** When a playing track reaches its end (elapsedRealtime); 0 when paused
     * or no length is given. */
    static long endsAt(boolean playing, long progressMs, long progressAtMs, long durationMs) {
        if (!playing || durationMs <= 0) return 0;
        return progressAtMs + Math.max(0, durationMs - progressMs);
    }

    /** A session with nothing to resume: none, or in error. When the phone
     * disconnects, the car's Bluetooth session goes to error ("Bluetooth audio
     * disconnected") but keeps the last song; the card must not show it.
     * Stopped is kept: a phone that just connected can report its song as
     * stopped, and the card's Play is how it starts (startBluetooth). */
    static boolean ended(int state) {
        return state == PlaybackState.STATE_ERROR || state == PlaybackState.STATE_NONE;
    }

    /** How long a paused session stays on the card. */
    static final long PAUSED_SHOW_MS = 10 * 60_000L;

    /** When a paused session leaves the card (elapsedRealtime): PAUSED_SHOW_MS
     * after it was paused. 0 while it plays, or when the pause time is unknown. */
    static long pausedHideAt(boolean playing, long pausedAtMs) {
        return playing || pausedAtMs <= 0 ? 0 : pausedAtMs + PAUSED_SHOW_MS;
    }

    /** How long a session may say "playing" past its song's end before the
     * card stops believing it. Short drop-outs do not blink the card off. */
    static final long STALE_GRACE_MS = 30_000;

    private static String endId;      // the track first seen past its end ...
    private static long endSeenMs;    // ... at this time

    /** When a session still "playing" past its song's end stops being
     * believed: STALE_GRACE_MS after it was first seen there. 0 while the
     * song has not ended (or it is paused, or has no length). */
    static long staleAt(String id, long endsAt, long nowMs) {
        if (endsAt <= 0 || nowMs < endsAt) { endId = null; return 0; }
        if (endId == null || !endId.equals(id)) { endId = id; endSeenMs = nowMs; }
        return endSeenMs + STALE_GRACE_MS;
    }

    // Play/pause and Skip go as media keys, like the steering-wheel buttons.
    // CarPlay ignores pause() and skipToNext() sent through its controller
    // (its session lists only the play/pause toggle), but it takes the keys:
    // checked on the car, play/pause and next both worked. Bluetooth and
    // Android Auto take them too (MediaSession's own key handling).
    private static void press(MediaController c, int keyCode) {
        try {
            long t = android.os.SystemClock.uptimeMillis();
            c.dispatchMediaButtonEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
            c.dispatchMediaButtonEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable e) { Log.w(TAG, "music key " + keyCode + ": " + e); }
    }

    // The car's own Bluetooth music app owns the audio source. A play sent
    // straight to the Bluetooth session reaches the phone, but the car does
    // not switch its sound to Bluetooth: the first play had to come from that
    // app. The app takes this broadcast from other apps (exported, no
    // permission): it switches the car to Bluetooth and, with needPlay,
    // plays. Found in GeelyXCBtMusic's MediaCenterReceiver.
    static final String BT_PACKAGE = "com.android.bluetooth";
    static final String BT_APP = "com.flyme.auto.localmusic.bluetooth";
    static final String BT_RECEIVER = "com.flyme.auto.local.carcontroller.mediacenter.MediaCenterReceiver";
    static final String BT_SOURCE = "com.flyme.auto.localmusic.bluetooth.CHANGE_BT_SOURCE";

    static Intent bluetoothSourceIntent(String from) {
        return new Intent(BT_SOURCE).setComponent(new ComponentName(BT_APP, BT_RECEIVER))
            .putExtra("from", from).putExtra("needPlay", true);
    }

    /** Only the Bluetooth session needs the car's app to start; CarPlay and
     * Android Auto start from their own session. */
    static boolean viaCarBluetoothApp(String sessionPackage) { return BT_PACKAGE.equals(sessionPackage); }

    /** Play on Bluetooth through the car's own Bluetooth music app. False
     * when the session is not Bluetooth or this car has no such app. */
    private static boolean startBluetooth(String sessionPackage) {
        if (!viaCarBluetoothApp(sessionPackage) || app == null) return false;
        try {
            Intent i = bluetoothSourceIntent(app.getPackageName());
            if (app.getPackageManager().queryBroadcastReceivers(i, 0).isEmpty()) return false;
            app.sendBroadcast(i);
            Log.i(TAG, "music: asked the car's Bluetooth app to play");
            return true;
        } catch (Throwable t) { Log.w(TAG, "music bt play: " + t); return false; }
    }

    /** A stable id for a session track (sessions give no track id): the
     * lyrics cache key. Same artist and title, same key. */
    static String trackKey(String artist, String title) {
        String s = (artist == null ? "" : artist.trim().toLowerCase(java.util.Locale.ROOT)) + "\n"
            + title.trim().toLowerCase(java.util.Locale.ROOT);
        return "s-" + java.util.UUID.nameUUIDFromBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
