package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.state.MusicState;
import com.geely.drivemem.ui.ComfortActivity;
import com.geely.drivemem.ui.SpotifyAuthActivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/** Spotify Web API client for music control from the car.
 *
 * Uses Spotify's Web API directly (not App Remote SDK or MQTT) to reflect the account's
 * current playback status regardless of device. Authentication uses Authorization Code + PKCE
 * via the system browser (in-app WebView was tried but this unit's browser was too old).
 */
// correctly (garbled icon fonts) and kept losing the email step. Everything
// after the one-time login (refresh, polling, commands) is silent background
// HTTPS, unrelated to whatever hosted the login page.
public final class SpotifyClient {
    static final String TAG = CarAccess.TAG;
    private static final String PREFS = "drivemem";
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API_BASE = "https://api.spotify.com/v1";
    static final String REDIRECT_URI = "drivemem://spotify-callback";
    static final String SCOPES = "user-read-playback-state user-modify-playback-state";

    private static volatile Context appCtx;
    private static HandlerThread thread;
    private static Handler h;
    private static volatile boolean polling = false;

    // Safe to call repeatedly from any screen (ComfortActivity, Config, the
    // auth activity) — same idempotent-init idiom as ComfortHub.get().
    public static synchronized void init(Context ctx) {
        if (appCtx != null) return;
        appCtx = ctx.getApplicationContext();
        thread = new HandlerThread("spotify");
        thread.start();
        h = new Handler(thread.getLooper());
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Config screen reads these directly with their own Context, no
    // dependency on init() having run yet ----
    public static boolean connected(Context ctx) {
        return !prefs(ctx).getString("spotify_refresh_token", "").isEmpty();
    }
    public static String clientId(Context ctx) { return prefs(ctx).getString("spotify_client_id", ""); }
    public static void setClientId(Context ctx, String id) {
        prefs(ctx).edit().putString("spotify_client_id", id.trim()).apply();
    }

    // ---- PKCE + the authorize URL (built by SpotifyAuthActivity) ----
    public static String[] newPkce() {
        byte[] bytes = new byte[64];
        new java.security.SecureRandom().nextBytes(bytes);
        String verifier = b64url(bytes);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String challenge = b64url(md.digest(verifier.getBytes("US-ASCII")));
            return new String[]{verifier, challenge};
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String b64url(byte[] b) {
        return android.util.Base64.encodeToString(b,
            android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    // The verifier has to survive the trip out to the browser and back — a
    // separate app/process, not just a paused Activity — so it rides in
    // prefs instead of an instance field. It is single-use and overwritten
    // on every new login attempt, so there is nothing to clean up.
    public static void savePkceVerifier(Context ctx, String verifier) {
        prefs(ctx).edit().putString("spotify_pkce_verifier", verifier).apply();
    }
    public static String readPkceVerifier(Context ctx) {
        return prefs(ctx).getString("spotify_pkce_verifier", "");
    }

    public static String authorizeUrl(Context ctx, String codeChallenge) {
        return AUTH_URL + "?response_type=code"
            + "&client_id=" + enc(clientId(ctx))
            + "&scope=" + enc(SCOPES)
            + "&redirect_uri=" + enc(REDIRECT_URI)
            + "&code_challenge_method=S256"
            + "&code_challenge=" + enc(codeChallenge);
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // BLOCKING — call from a background thread (SpotifyAuthActivity has its
    // own for exactly this). Saves the tokens on success.
    public static boolean exchangeCode(Context ctx, String code, String verifier) {
        String body = "grant_type=authorization_code"
            + "&code=" + enc(code)
            + "&redirect_uri=" + enc(REDIRECT_URI)
            + "&client_id=" + enc(clientId(ctx))
            + "&code_verifier=" + enc(verifier);
        org.json.JSONObject resp = post(TOKEN_URL, body);
        if (resp == null) return false;
        saveTokens(ctx, resp);
        return true;
    }

    private static void saveTokens(Context ctx, org.json.JSONObject resp) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.putString("spotify_access_token", resp.optString("access_token", ""));
        long expiresIn = resp.optLong("expires_in", 3600);
        // 30s margin so a poll starting right at the edge doesn't race the
        // token's real expiry.
        e.putLong("spotify_token_expiry", System.currentTimeMillis() + expiresIn * 1000 - 30_000);
        // refresh_token is only returned on the FIRST exchange (and only
        // sometimes on a refresh, if Spotify chose to rotate it) — never
        // overwrite a good one with an absent one.
        String rt = resp.optString("refresh_token", "");
        if (!rt.isEmpty()) e.putString("spotify_refresh_token", rt);
        e.apply();
    }

    private static boolean refresh(Context ctx) {
        String rt = prefs(ctx).getString("spotify_refresh_token", "");
        if (rt.isEmpty()) return false;
        String body = "grant_type=refresh_token&refresh_token=" + enc(rt)
            + "&client_id=" + enc(clientId(ctx));
        org.json.JSONObject resp = post(TOKEN_URL, body);
        if (resp == null) return false;
        saveTokens(ctx, resp);
        return true;
    }

    // Valid access token, refreshing first if expired or about to be. Null
    // means there is nothing usable — never connected, or the refresh itself
    // failed (revoked access, bad client id, offline).
    private static String accessToken(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (System.currentTimeMillis() < p.getLong("spotify_token_expiry", 0)) {
            String at = p.getString("spotify_access_token", "");
            if (!at.isEmpty()) return at;
        }
        if (!refresh(ctx)) return null;
        return p.getString("spotify_access_token", "");
    }

    // ---- polling (started/stopped from ComfortActivity's onResume/onPause,
    // same pattern as ambientPoll/speedPoll — no need for this to outlive the
    // screen the way Turbo/the gate do) ----
    private static final Runnable pollTick = new Runnable() {
        @Override public void run() {
            doPoll();
            if (polling) h.postDelayed(this, 6000);
        }
    };

    public static void startPolling(Context ctx) {
        init(ctx);
        if (polling) return;
        polling = true;
        h.post(pollTick);
    }

    public static void stopPolling() {
        polling = false;
        if (h != null) h.removeCallbacks(pollTick);
    }

    private static void doPoll() {
        String token = accessToken(appCtx);
        if (token == null) { MusicState.set(false, null, null, null); return; }
        try {
            java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(API_BASE + "/me/player").openConnection();
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setConnectTimeout(6000); c.setReadTimeout(6000);
            int code = c.getResponseCode();
            if (code == 204) { MusicState.set(false, null, null, null); return; }   // nothing playing anywhere
            if (code == 401) {
                // token rejected outright (not just expired-by-our-clock) —
                // force a real refresh on the next poll instead of trusting
                // the cached expiry
                prefs(appCtx).edit().putLong("spotify_token_expiry", 0).apply();
                MusicState.set(false, null, null, null);
                return;
            }
            if (code != 200) { Log.w(TAG, "spotify poll: HTTP " + code); return; }   // leave last known state
            org.json.JSONObject o = new org.json.JSONObject(readAll(c.getInputStream()));
            boolean isPlaying = o.optBoolean("is_playing", false);
            org.json.JSONObject item = o.optJSONObject("item");
            if (item == null) { MusicState.set(false, null, null, null); return; }
            String title = item.optString("name", null);
            String artist = null;
            org.json.JSONArray artists = item.optJSONArray("artists");
            if (artists != null && artists.length() > 0) {
                org.json.JSONObject a0 = artists.optJSONObject(0);
                if (a0 != null) artist = a0.optString("name", null);
            }
            String art = null;
            org.json.JSONObject album = item.optJSONObject("album");
            if (album != null) {
                org.json.JSONArray images = album.optJSONArray("images");
                // Spotify orders images large -> small; the LAST one is the
                // smallest — plenty for an 80dp card thumbnail, lighter to
                // pull every 6s than the largest.
                if (images != null && images.length() > 0) {
                    org.json.JSONObject last = images.optJSONObject(images.length() - 1);
                    if (last != null) art = last.optString("url", null);
                }
            }
            MusicState.set(isPlaying, title, artist, art);
        } catch (Throwable t) { Log.w(TAG, "spotify poll: " + t); }
    }

    // ---- UI -> Spotify: skip / play-pause ----
    public static void next() {
        if (h == null) return;
        h.post(() -> call("POST", "/me/player/next"));
    }

    public static void playPause() {
        if (h == null) return;
        // The Web API has no toggle endpoint (unlike HA's media_play_pause) —
        // separate play/pause calls, so the direction has to come from the
        // last poll. Worst case on a stale read is calling pause-while-
        // already-paused, which Spotify just no-ops; nothing like the gate's
        // physical toggle relay where the wrong call reverses something real.
        boolean wasPlaying = MusicState.playing();
        h.post(() -> call(wasPlaying ? "PUT" : "PUT", wasPlaying ? "/me/player/pause" : "/me/player/play"));
    }

    private static void call(String method, String path) {
        if (appCtx == null) return;
        String token = accessToken(appCtx);
        if (token == null) return;
        try {
            java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(API_BASE + path).openConnection();
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setFixedLengthStreamingMode(0);   // these calls have no body, but need Content-Length: 0
            c.setDoOutput(true);
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code >= 300) Log.w(TAG, "spotify " + method + " " + path + ": HTTP " + code);
        } catch (Throwable t) { Log.w(TAG, "spotify " + method + " " + path + ": " + t); }
        // Re-poll shortly after, so the card reflects the new state quickly
        // instead of waiting up to 6s for the next tick.
        if (h != null) h.postDelayed(SpotifyClient::doPoll, 700);
    }

    // ---- small HTTP helpers ----
    private static org.json.JSONObject post(String url, String body) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.getOutputStream().write(body.getBytes("UTF-8"));
            int code = c.getResponseCode();
            String resp = readAll(code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code >= 300) { Log.w(TAG, "spotify token http " + code + ": " + resp); return null; }
            return new org.json.JSONObject(resp);
        } catch (Throwable t) { Log.w(TAG, "spotify post " + url + ": " + t); return null; }
    }

    private static String readAll(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private SpotifyClient() {}
}
