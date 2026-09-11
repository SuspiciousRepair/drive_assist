package com.geely.drivemem.net;

import com.geely.drivemem.BuildConfig;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.sensors.GpsReader;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.state.ParkSession;
import com.geely.drivemem.state.TripSession;
import com.geely.drivemem.util.Modes;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

// Streams live telemetry to ABRP (A Better Route Planner, api.iternio.com)
// so trip planning sees this car's real state instead of a generic model.
//
// SAMPLING AND SENDING ARE DELIBERATELY DECOUPLED, but not purely batched
// either -- ABRP's own docs (pulled from their API collection,
// see field-catalog.md) say two things that pull in different directions:
// speed/power/is_charging at least once per 10s for consumption calibration
// (a data-density requirement), AND "the system delays processing by 60
// seconds to wait for all sources... if you only send data within a 60
// second window, it will not get processed until you send data outside of
// that window." That second one means pure bulk-every-30s sending -- the
// original design here -- leaves the car looking "recent" but never
// actually "online" in ABRP's own UI, confirmed live: real data was
// visibly landing, but the connection never read as live.
//
// So every LIVE_EVERY_N'th sample (currently every 10th, i.e. once a
// minute at SAMPLE_MS=6s) is sent immediately via the single /send
// endpoint instead of being queued -- that's the one that keeps ABRP's own
// "online" indicator honest. The other 9 still batch into one /bulk call
// right before it. Same 10-points-a-minute data density either way
// (comfortably past the 10s minimum), just one point per minute escapes
// the 60s batching delay on purpose.
//
// OFFLINE QUEUEING: a reading that fails to send (tunnel, dead zone, wifi
// blip) is kept, not dropped, and flushed through ABRP's bulk endpoint the
// moment a send succeeds again -- explicitly asked for, so a short gap in
// connectivity doesn't leave a hole in the trip's data. The queue is
// in-memory only (capped, oldest dropped first if a gap runs long): durable
// across a dropped CONNECTION mid-drive, not across the app PROCESS itself
// dying -- ABRP data is a live-tracking nicety, not the car's own permanent
// history (that's CarDb, which has its own durable writer).
//
// SENDING IS A SEPARATE CONCERN FROM GATHERING. A drive with no signal the
// whole way (the car's cell modem is walled off from the general internet,
// see field-catalog.md) can queue points that never flush if the retry only
// lives inside "just took a new sample," which stops firing the moment the
// car parks (see sampleOnce()'s driving/charging gate below) — a backlog
// would then sit in memory on a car parked on working WiFi, unsent, until the
// next drive happened to tick past a multiple of LIVE_EVERY_N again.
// samplerLoop() instead retries the queue on its own, gated only on
// "is there a backlog and has it been a while,"
// not on whether a fresh sample exists this tick.
public final class AbrpUploader {
    static final String TAG = "DriveMem";
    private static final String SEND_URL = "https://api.iternio.com/1/tlm/send";
    private static final String BULK_URL = "https://api.iternio.com/1/tlm/bulk";
    // This app's own ABRP developer API key: identifies this integration to
    // ABRP, shared across all cars. Not a per-install or per-car secret like
    // the user token below, which genuinely does vary — injected at build
    // time (ABRP_API_KEY env var, or local.properties' abrp.apiKey) rather
    // than user-editable or checked into source. See build.gradle.
    private static final String API_KEY = BuildConfig.ABRP_API_KEY;
    // 10 points/minute -- well past ABRP's stated 10s-minimum for
    // calibration, and fine-grained enough that the one "live" point per
    // minute (see LIVE_EVERY_N) is never more than 6s stale.
    private static final long SAMPLE_MS = 6_000;
    // Every Nth sample bypasses the batch queue and goes out immediately,
    // alone, via /send -- see this file's own header for why.
    private static final int LIVE_EVERY_N = 10;
    // ~100 min of readings at one per SAMPLE_MS -- generous for a tunnel or
    // a dead zone, not meant to survive a whole day with no signal.
    private static final int QUEUE_CAP = 200;
    private static int sampleCount = 0;   // only touched from the sampler thread

    public static final String PREF_SEND_LOCATION = "abrp_send_location";

    public static boolean isLocationEnabled(Context ctx) {
        if (ctx == null) return false;
        return ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
                  .getBoolean(PREF_SEND_LOCATION, true);
    }

    private static volatile boolean subscribed = false;
    // Only ever touched from the "abrp-sampler" thread below -- no lock
    // needed, same discipline CarActor's own actor thread already relies on.
    private static final ArrayDeque<JSONObject> queue = new ArrayDeque<>();

    // The latest telemetry.tick snapshot, cheap to cache -- the sampler
    // thread reads this every SAMPLE_MS rather than waiting on the tick
    // itself, which is what lets sampling run faster than that ~15s tick.
    private static volatile Map<String, Object> lastData = null;
    // This class's own cached copy of the one thing Obd2Reader pushes --
    // see buildTlm()'s own comment for why this replaced five separate
    // freshXxx() calls.
    private static volatile Obd2Reader.Reading lastObdReading = null;

    // Debug/diagnostic state for the settings screen — what was actually
    // last sent (or attempted) and how it went. Only the sampler thread
    // writes this; pushed to listeners rather than polled.
    private static volatile JSONObject lastTlm = null;
    private static volatile long lastAttemptAtMs = 0;
    private static volatile boolean lastOk = false;
    // Set directly by post() the moment a request fails -- centralizing it
    // there means every caller (flushQueue, testConnect) gets it for free.
    private static volatile String lastError = null;
    private static final int LOG_CAP = 30;
    private static final java.util.Deque<String> debugLog = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public interface Listener { void onAbrpAttempt(); }
    private static final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static void subscribe(Listener l) { listeners.add(l); }
    public static void unsubscribe(Listener l) { listeners.remove(l); }

    public static JSONObject lastTlmSent() { return lastTlm; }
    public static long lastAttemptAtMs() { return lastAttemptAtMs; }
    public static boolean lastAttemptOk() { return lastOk; }
    public static String lastErrorDetail() { return lastError; }
    public static java.util.List<String> recentDebugLog() { return new java.util.ArrayList<>(debugLog); }

    private static void logDebug(String line) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        debugLog.addFirst("[" + ts + "] " + line);
        while (debugLog.size() > LOG_CAP) debugLog.removeLast();
    }

    // `kind` says what actually happened at the wire — "live" (single
    // point, /send) vs "batch xN" (N points in one /bulk call) — since a
    // representative JSONObject alone looks identical in the log either way.
    private static volatile Context appCtx;
    private static final java.text.SimpleDateFormat ATTEMPT_LOG_FMT =
        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);

    // Persistent append-only record of every upload attempt. Unlike debugLog
    // (in-memory, capped, cleared on process death), this stays on disk and
    // allows retroactive verification that ABRP uploads worked throughout a
    // drive. Capped at LOG_CAP_BYTES to prevent unbounded growth.
    private static final long LOG_CAP_BYTES = 5L * 1024 * 1024;

    private static void logAttemptToFile(boolean ok, String kind, JSONObject representative) {
        Context app = appCtx;
        if (app == null) return;
        String line = ATTEMPT_LOG_FMT.format(new java.util.Date()) + '\t'
            + (ok ? "OK" : "FAILED(" + lastError + ")") + '\t' + kind + '\t'
            + (representative != null ? representative.toString() : "(no data)");
        try {
            java.io.File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            java.io.File f = new java.io.File(dir, "abrp-attempt.log");
            if (f.exists() && f.length() > LOG_CAP_BYTES) f.delete(); // rotate: start over, don't grow forever
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            try { w.write(line + "\n"); } finally { w.close(); }
        } catch (Throwable t) { Log.w(TAG, "abrp: attempt log write: " + t); }
    }

    private static void recordAttempt(JSONObject representative, boolean ok, String kind) {
        lastTlm = representative;
        lastAttemptAtMs = System.currentTimeMillis();
        lastOk = ok;
        for (Listener l : listeners) l.onAbrpAttempt();
        logDebug((ok ? "OK " : "FAILED (" + lastError + ") ") + "[" + kind + "] "
            + (representative != null ? representative.toString() : "(no data)"));
        logAttemptToFile(ok, kind, representative);
    }

    private AbrpUploader() {}

    // Fired from the Save button on the settings screen. ABRP's "Generic"
    // vehicle token shows as pending until it receives a real post. Waiting
    // for normal driving/charging conditions (sampleOnce's gate) could leave
    // the user waiting a long time, so Save sends one ping immediately.
    public interface TestCallback { void onResult(boolean ok, String detail); }

    public static void testConnect(Context ctx, TestCallback cb) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            SharedPreferences p = app.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
            String userToken = p.getString("abrp_user_token", "").trim();
            if (userToken.isEmpty()) {
                cb.onResult(false, "missing user token");
                return;
            }
            JSONObject tlm = null;
            Map<String, Object> data = lastData;
            if (data != null) {
                Integer isCharging = asInt(data.get("is_charging"));
                boolean charging = isCharging != null && isCharging == 1;
                tlm = buildTlm(app, data, charging);
            }
            if (tlm == null) {
                // No telemetry seen yet (e.g. testing right after a fresh
                // install) -- still worth one real post with just a
                // timestamp, so ABRP has SOMETHING to confirm the token
                // against instead of nothing at all.
                try { tlm = new JSONObject().put("utc", System.currentTimeMillis() / 1000); }
                catch (JSONException e) { cb.onResult(false, "build tlm: " + e); return; }
            }
            boolean ok = postSingle(API_KEY, userToken, tlm);
            recordAttempt(tlm, ok, "test");
            cb.onResult(ok, ok ? "sent" : "send failed -- check logcat (tag DriveMem, \"abrp:\")");
        }, "abrp-test-connect").start();
    }

    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        appCtx = app;
        EntityBus.subscribe("telemetry.tick", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) reading.value;
                lastData = data;
            }
        });
        Obd2Reader.subscribe(new Obd2Reader.Listener() {
            @Override public void onObd2ConnectedChanged(boolean connected) {}
            @Override public void onObd2Reading(Obd2Reader.Reading r) { lastObdReading = r; }
        });
        Thread t = new Thread(() -> samplerLoop(app), "abrp-sampler");
        t.setDaemon(true);
        t.start();
    }

    // Same cadence as the driving-live-tick retry (LIVE_EVERY_N * SAMPLE_MS
    // = 60s) -- not a new rhythm, just applied while parked too.
    private static final long PARKED_RETRY_MS = LIVE_EVERY_N * SAMPLE_MS;

    // sampleOnce's gate stops gathering when parked. The last ABRP reading
    // for a trip is still is_parked=0 from driving. This sends exactly one
    // is_parked=1 point at the transition to signal trip end.
    private static volatile boolean parkedMarkerSent = false;

    private static void samplerLoop(Context ctx) {
        while (true) {
            try {
                JSONObject tlm = sampleOnce(ctx);
                if (tlm != null) {
                    parkedMarkerSent = false;
                    sampleCount++;
                    if (sampleCount % LIVE_EVERY_N == 0) {
                        // Flush whatever's batched BEFORE the live one, so
                        // older data leaves before newer data.
                        flushQueue(ctx);
                        sendLive(ctx, tlm);
                    } else {
                        queue.addLast(tlm);
                        while (queue.size() > QUEUE_CAP) queue.pollFirst();
                    }
                } else if (!parkedMarkerSent) {
                    JSONObject marker = buildParkedMarker(ctx);
                    if (marker != null) {
                        sendLive(ctx, marker);
                        parkedMarkerSent = true;
                    }
                } else if (!queue.isEmpty()
                        && System.currentTimeMillis() - lastAttemptAtMs >= PARKED_RETRY_MS) {
                    // Gathering has stopped but a backlog from an earlier failed
                    // drive is still queued. Sending is independent of gathering
                    // and must not wait for the next drive to retry.
                    flushQueue(ctx);
                }
            } catch (Throwable t) {
                Log.w(TAG, "abrp: sampler: " + t);
            }
            try { Thread.sleep(SAMPLE_MS); } catch (InterruptedException ignored) {}
        }
    }

    // Builds one reading, or null if there's nothing worth building yet
    // (not configured, not driving/charging, or no telemetry seen at all).
    // Doesn't touch the queue itself -- samplerLoop() decides whether this
    // one gets batched or sent live.
    private static JSONObject sampleOnce(Context ctx) {
        Map<String, Object> data = lastData;
        if (data == null) return null;   // no telemetry seen yet

        SharedPreferences p = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        if (!p.getBoolean("abrp_enabled", false)) return null;
        String userToken = p.getString("abrp_user_token", "");
        if (userToken.isEmpty()) return null;

        Integer isCharging = asInt(data.get("is_charging"));
        Float speed = asFloat(data.get("speed"));
        boolean charging = isCharging != null && isCharging == 1;
        boolean driving = speed != null && speed > 1f;
        // Only while it's actually informative -- ABRP itself asks for
        // this, and there's no route to plan around a car parked and idle.
        if (!driving && !charging) return null;

        return buildTlm(ctx, data, charging);
    }

    // One-shot park marker -- only fires when gear has actually settled to
    // P, so a stop at a red light (speed 0, gear still D) doesn't trip it.
    private static JSONObject buildParkedMarker(Context ctx) {
        Map<String, Object> data = lastData;
        if (data == null) return null;
        SharedPreferences p = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        if (!p.getBoolean("abrp_enabled", false)) return null;
        if (p.getString("abrp_user_token", "").isEmpty()) return null;

        Object gear = data.get("gear");
        boolean parked = gear instanceof Integer && (Integer) gear == Modes.GEAR_PARK_ADAPTED;
        if (!parked) return null;

        return buildTlm(ctx, data, false);
    }

    private static void flushQueue(Context ctx) {
        if (queue.isEmpty()) return;
        SharedPreferences p = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        String userToken = p.getString("abrp_user_token", "");
        if (userToken.isEmpty()) return;

        int n = queue.size();   // captured before any clear() -- that's what's actually going out
        JSONObject representative = queue.peekLast();   // the freshest sample in this batch
        boolean ok = (n == 1)
            ? postSingle(API_KEY, userToken, queue.peekFirst())
            : postBulk(API_KEY, userToken, queue);
        recordAttempt(representative, ok, n == 1 ? "single x1" : "batch x" + n);
        if (ok) queue.clear();
        // else: stays queued, retried on the next flush
    }

    // The one point per LIVE_EVERY_N that skips the batch queue entirely --
    // see this file's own header for why that matters to ABRP's "online"
    // status specifically, not just data freshness.
    private static void sendLive(Context ctx, JSONObject tlm) {
        SharedPreferences p = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        String userToken = p.getString("abrp_user_token", "");
        if (userToken.isEmpty()) return;

        boolean ok = postSingle(API_KEY, userToken, tlm);
        recordAttempt(tlm, ok, "live");
        if (!ok) {
            // Don't lose it -- the next batch flush picks it up instead.
            queue.addLast(tlm);
            while (queue.size() > QUEUE_CAP) queue.pollFirst();
        }
    }

    static JSONObject buildTlm(Context ctx, Map<String, Object> data, boolean charging) {
        double[] loc = (ctx != null && isLocationEnabled(ctx)) ? GpsReader.read(ctx) : null;
        return buildTlm(ctx, data, charging, loc);
    }

    public static JSONObject buildTlm(Context ctx, Map<String, Object> data, boolean charging, double[] loc) {
        try {
            JSONObject tlm = new JSONObject();
            tlm.put("utc", System.currentTimeMillis() / 1000);

            // Read from the cached Reading Obd2Reader last PUSHED (see
            // ensureSubscribed()), not by calling back into its freshXxx()
            // getters -- this class caches its own local copy of the one
            // upstream source instead of polling it repeatedly.
            Obd2Reader.Reading obd = lastObdReading;
            boolean obdFresh = obd != null && System.currentTimeMillis() - obd.atMs <= 20_000;

            // OBD2 SOC is finer-grained (one decimal) than the VHAL's
            // whole-percent reading when it's actually connected and fresh
            // -- prefer it, same "replace when connected" rule Telemetry
            // .java's own power estimate uses.
            Float obdSoc = (obdFresh && obd.soc != null) ? obd.soc.floatValue() : null;
            Integer battery = asInt(data.get("battery"));
            if (obdSoc != null) tlm.put("soc", obdSoc);
            else if (battery != null) tlm.put("soc", battery);

            Float power = (obdFresh && obd.powerKw != null) ? obd.powerKw.floatValue() : null;
            if (power == null) power = asFloat(data.get("instant_power_kw_est"));
            if (power != null) tlm.put("power", power);

            Float speed = asFloat(data.get("speed"));
            if (speed != null) tlm.put("speed", speed);

            // [lat, lon, alt, bearing, speed, accuracy] -- bearing (index 3)
            // went unused until now even though it was already being read.
            if (loc != null) {
                tlm.put("lat", loc[0]);
                tlm.put("lon", loc[1]);
                tlm.put("elevation", loc[2]);
                tlm.put("heading", loc[3]);
            }

            tlm.put("is_charging", charging ? 1 : 0);
            // Parked, not just "not driving" -- ABRP's own definition is the
            // gear being in P, same signal TripSession/ParkSession/CarState
            // already use for exactly this.
            Object gear = data.get("gear");
            if (gear instanceof Integer) tlm.put("is_parked", (Integer) gear == Modes.GEAR_PARK_ADAPTED ? 1 : 0);

            // is_dcfc: use voltage, not current or power, to distinguish modes.
            // The voltage divider reads AC mains side during AC charging (~240V)
            // but the DC pack side during DC fast charging (~400V). Power is
            // unreliable because DCFC tapers near full charge but remains on the
            // DC pack at high voltage. 250V threshold safely separates the two.
            if (charging) {
                Float voltsForDcfc = (obdFresh && obd.voltage != null) ? obd.voltage.floatValue() : null;
                if (voltsForDcfc == null) voltsForDcfc = asFloat(data.get("charge_v"));
                if (voltsForDcfc != null) tlm.put("is_dcfc", voltsForDcfc > 250f ? 1 : 0);
            }

            Float odo = asFloat(data.get("odometer"));
            if (odo != null) tlm.put("odometer", odo);

            // Already read for the Stats screen (functionId 289407752,
            // "Autonomia") -- just never wired into this payload before.
            Float range = asFloat(data.get("range"));
            if (range != null) tlm.put("est_battery_range", range);

            // A separate CarActor cache key (its own 15s poll, not part of
            // the telemetry.tick map) -- same fetch ComfortRuler's own
            // cachedOutsideTempC() uses.
            if (ctx != null) {
                try {
                    CarActor.Reading tempReading = CarActor.get(ctx).get("telemetry.outside_temp");
                    if (tempReading != null && tempReading.status == CarActor.Reading.Status.OK && tempReading.value instanceof Float) {
                        tlm.put("ext_temp", (Float) tempReading.value);
                    }
                } catch (Throwable ignored) {}
            }

            Float v = (obdFresh && obd.voltage != null) ? obd.voltage.floatValue() : null;
            if (v != null) tlm.put("voltage", v);
            Float a = (obdFresh && obd.current != null) ? obd.current.floatValue() : null;
            if (a != null) tlm.put("current", a);
            Float bt = (obdFresh && obd.battTempC != null) ? obd.battTempC.floatValue() : null;
            if (bt != null) tlm.put("batt_temp", bt);

            return tlm;
        } catch (JSONException e) {
            try { Log.w(TAG, "abrp: build tlm: " + e); } catch (Throwable ignored) {}
            return null;
        }
    }

    // /send takes NO JSON body at all -- token and tlm (itself a
    // URL-encoded JSON string) are query parameters on the URL, even though
    // the method is POST. Confirmed from ABRP's own real Postman collection
    // (pulled directly, not the rendered doc page, which is JS-only and
    // doesn't expose this) and cross-checked against a working open-source
    // integration (Riffer/abrp_telemetry) -- both agree, and both disagree
    // with what this method used to send (a JSON body), which is why every
    // real send attempt got HTTP 401 the first time this was tested live.
    private static boolean postSingle(String apiKey, String userToken, JSONObject tlm) {
        try {
            String url = SEND_URL
                + "?token=" + java.net.URLEncoder.encode(userToken, "UTF-8")
                + "&tlm=" + java.net.URLEncoder.encode(tlm.toString(), "UTF-8");
            return post(url, apiKey, null);
        } catch (Exception e) { Log.w(TAG, "abrp: " + e); return false; }
    }

    // /bulk DOES take a real JSON body, but shaped as {"data":[{"token":...,
    // "tlm_list":[...]}]} -- a list of per-token entries, each with its own
    // reading list -- not the flat {"token":..., "tlm":[...]} this used to
    // send. Only ever one token here (this app has one car), so "data" is
    // always a single-element array.
    private static boolean postBulk(String apiKey, String userToken, ArrayDeque<JSONObject> q) {
        try {
            JSONArray tlmList = new JSONArray();
            for (JSONObject o : q) tlmList.put(o);
            JSONObject entry = new JSONObject();
            entry.put("token", userToken);
            entry.put("tlm_list", tlmList);
            JSONObject body = new JSONObject();
            body.put("data", new JSONArray().put(entry));
            return post(BULK_URL, apiKey, body.toString());
        } catch (JSONException e) { return false; }
    }

    // jsonBody null means send with no body at all (still a POST, just an
    // empty one) -- see postSingle()'s own header for why /send needs this.
    private static boolean post(String urlStr, String apiKey, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            // "APIKEY <key>", not "Iternio-Token <key>" -- the latter was
            // never right, confirmed against the real API collection and a
            // working open-source integration (see postSingle()'s header).
            conn.setRequestProperty("Authorization", "APIKEY " + apiKey);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String msg = "HTTP " + code;
                Log.w(TAG, "abrp: " + msg + " from " + urlStr);
                lastError = msg;
                return false;
            }
            lastError = null;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "abrp: " + t);
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Integer asInt(Object o) {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Float) return Math.round((Float) o);
        return null;
    }
    private static Float asFloat(Object o) {
        if (o instanceof Float) return (Float) o;
        if (o instanceof Integer) return ((Integer) o).floatValue();
        return null;
    }
}
