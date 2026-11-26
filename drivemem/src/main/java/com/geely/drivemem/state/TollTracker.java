package com.geely.drivemem.state;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.LocationHub;
import com.geely.drivemem.sensors.TollMatcher;
import com.geely.drivemem.util.Prefs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Privacy-first, offline-capable automatic toll tracker and historical trip
 * backfill engine.
 *
 * Privacy contract:
 * - Strictly opt-in ({@link Prefs#getTollsEnabled(Context)} defaults to false).
 * - Never sends exact routes, start/end points, or home location to OpenStreetMap;
 *   Overpass queries ONLY use snapped coarse 0.5 deg (~55 km) tiles.
 *
 * Offline resilience:
 * - While driving (even with zero cellular signal), matches GPS fixes live
 *   against the local SQLite {@code toll_booth} cache.
 * - When driving through a brand-new area with no cellular signal, GPS points
 *   are saved locally in {@code trip_point}; the next time the car is parked
 *   with Wi-Fi/cellular, the missing coarse tile(s) are fetched automatically
 *   and un-scanned trips are backfilled. */
public final class TollTracker {
    private static final String TAG = "DriveMem";
    private static final long TILE_TTL_MS = 30L * 24 * 60 * 60_000L; // 30 days
    private static final long OVERPASS_GAP_MS = 1_000L;
    private static final String[] OVERPASS_URLS = {
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    };
    private static volatile int lastGoodEndpoint = 0;

    private static volatile boolean subscribed;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "toll-sync");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean SCANNING = new AtomicBoolean(false);
    private static volatile int scanDone = 0;
    private static volatile int scanTotal = 0;
    private static volatile String lastError = "";
    private static final List<ProgressCallback> activeProgress = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<ScanCallback> activeCallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile TollMatcher.SpatialIndex liveIndex = new TollMatcher.SpatialIndex(Collections.emptyList());
    private static volatile TollMatcher.Runner liveRunner;
    // Live Free Flow hits buffered until trip end so consecutive corridor gantries
    // collapse cleanly into one drop-off passage (fallback when trip_point is empty).
    private static final List<TollMatcher.Hit> liveFreeFlowHits = new java.util.concurrent.CopyOnWriteArrayList<>();

    private TollTracker() {}

    /** One recorded toll passage row for UI display & editing. */
    public static final class Passage {
        public final long id;
        public final Long tripId;
        public final Long boothId;
        public final long tsMs;
        public final double lat;
        public final double lon;
        public final Double speedKmh;
        public final String boothName;
        public final String boothKind;
        public final Double cost;
        public final String source;

        public Passage(long id, Long tripId, Long boothId, long tsMs, double lat, double lon,
                       Double speedKmh, String boothName, String boothKind, Double cost, String source) {
            this.id = id; this.tripId = tripId; this.boothId = boothId;
            this.tsMs = tsMs; this.lat = lat; this.lon = lon; this.speedKmh = speedKmh;
            this.boothName = boothName; this.boothKind = boothKind; this.cost = cost; this.source = source;
        }
    }

    /** Per-trip aggregate for DailyStatsView's compact drive-card pill. */
    public static final class TripSummary {
        public int count;
        public double totalCost;
        public int unpricedCount;
    }

    /** Result of an install/historical backfill or offline catch-up scan. */
    public static final class ScanResult {
        public final boolean ran;
        public final boolean offline;
        public final int tilesFetched;
        public final int tilesFailed;
        public final int boothsCached;
        public final int tripsScanned;
        public final int passagesAdded;

        public ScanResult(boolean ran, boolean offline, int tilesFetched, int tilesFailed,
                          int boothsCached, int tripsScanned, int passagesAdded) {
            this.ran = ran; this.offline = offline;
            this.tilesFetched = tilesFetched; this.tilesFailed = tilesFailed;
            this.boothsCached = boothsCached; this.tripsScanned = tripsScanned;
            this.passagesAdded = passagesAdded;
        }
    }

    public interface ScanCallback { void onDone(ScanResult r); }
    public interface ProgressCallback { void onProgress(int done, int total); }

    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed || ctx == null) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();

        WORKER.execute(() -> reloadIndex(app));

        // Auto-retry offline/pending scans whenever any internet network comes up while parked.
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.registerNetworkCallback(
                    new android.net.NetworkRequest.Builder()
                        .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(android.net.Network network) {
                            scheduleBackfillIfDue(app);
                        }
                    });
            }
        } catch (Throwable ignored) {}

        // Offline live matching on GPS fixes while driving (opt-in only).
        EntityBus.subscribe(LocationHub.FIX, (key, reading) -> {
            if (reading.status != CarActor.Reading.Status.OK || !(reading.value instanceof LocationHub.Fix)) return;
            if (!Prefs.getTollsEnabled(app) || CarState.isParked()) return;
            TollMatcher.Runner runner = liveRunner;
            if (runner == null || liveIndex.isEmpty()) return;
            LocationHub.Fix fix = (LocationHub.Fix) reading.value;
            if (fix.accuracyM > 35f) return;
            List<TollMatcher.Hit> hits = runner.onPoint(System.currentTimeMillis(), fix.lat, fix.lon, fix.speedKmh);
            if (hits.isEmpty()) return;
            boolean insertedLive = false;
            for (TollMatcher.Hit h : hits) {
                if (TollMatcher.KIND_FREE_FLOW.equals(h.booth.kind)) {
                    // Defer Free Flow until trip end so consecutive corridor sensors
                    // collapse cleanly into a single drop-off passage.
                    liveFreeFlowHits.add(h);
                } else {
                    insertedLive = true;
                    CarDb.get(app).write(() -> insertPassageIfNew(CarDb.get(app).db(), null, h, "auto_live"));
                }
            }
            if (insertedLive) {
                EntityBus.publish("toll.changed", CarActor.Reading.ok(System.currentTimeMillis()));
            }
        });

        // Entering Park: reset live runner and catch up any offline trips once network is up.
        CarState.addListener(parked -> {
            TollMatcher.Runner r = liveRunner;
            if (parked) {
                if (r != null) r.resetTrack();
                scheduleBackfillIfDue(app);
            }
        });

        if (CarState.isParked()) scheduleBackfillIfDue(app);
    }

    /** Called on CarDb's writer thread inside TripSession.finalizeTrip after claiming trip_points. */
    public static void onTripFinalized(Context ctx, long tripId, long startMs, long endMs) {
        if (ctx == null || tripId <= 0) return;
        Context app = ctx.getApplicationContext();
        List<TollMatcher.Hit> bufferedFf = new ArrayList<>(liveFreeFlowHits);
        liveFreeFlowHits.clear();
        try {
            SQLiteDatabase db = CarDb.get(app).db();
            db.execSQL(CarDb.CLAIM_TOLL_PASSAGES_SQL, new Object[]{tripId, startMs, endMs});
            if (Prefs.getTollsEnabled(app)) {
                int added = 0;
                // Authoritative offline pass using already-cached booths (collapses Free Flow corridors).
                TollMatcher.SpatialIndex idx = liveIndex;
                if (idx != null && !idx.isEmpty()) {
                    added += matchTripInDb(db, idx, tripId, "auto_live");
                }
                // Fallback for users with Trip routes disabled: collapse buffered live Free Flow hits.
                if (!bufferedFf.isEmpty()) {
                    List<TollMatcher.Hit> collapsed = TollMatcher.collapseFreeFlowCorridors(bufferedFf);
                    for (TollMatcher.Hit h : collapsed) {
                        if (h.tsMs >= startMs && h.tsMs <= endMs && insertPassageIfNew(db, tripId, h, "auto_live")) {
                            added++;
                        }
                    }
                }
                if (added > 0) EntityBus.publish("toll.changed", CarActor.Reading.ok(tripId));
            }
        } catch (Throwable t) {
            Log.w(TAG, "tolls: onTripFinalized: " + t);
        }
        // If this trip visited any un-fetched 0.5 deg tile (e.g. offline mountain highway),
        // catch up automatically once parked with Wi-Fi / cellular.
        scheduleBackfillIfDue(app);
    }

    /** Schedules an automatic background backfill if opted in, parked, and un-scanned trips exist. */
    public static void scheduleBackfillIfDue(Context ctx) {
        if (ctx == null || !Prefs.getTollsEnabled(ctx) || !CarState.isParked()) return;
        Context app = ctx.getApplicationContext();
        scanPastTripsAsync(app, false, null);
    }

    public static boolean isScanning() { return SCANNING.get(); }
    public static int currentScanDone() { return scanDone; }
    public static int currentScanTotal() { return scanTotal; }
    public static String lastError() { return lastError; }

    /** Attaches UI progress/completion listeners to an already-running scan, if any. */
    public static void attachIfScanning(ProgressCallback progress, ScanCallback cb) {
        if (!SCANNING.get()) return;
        if (progress != null) activeProgress.add(progress);
        if (cb != null) activeCallbacks.add(cb);
    }

    public static void scanPastTripsAsync(Context ctx, boolean forceAllTrips, ScanCallback cb) {
        scanPastTripsAsync(ctx, forceAllTrips, null, cb);
    }

    /** Runs the install / historical trip scan (or incremental catch-up for trips
     * completed offline). Only runs when opted in and network is available. */
    public static void scanPastTripsAsync(Context ctx, boolean forceAllTrips,
                                          ProgressCallback progress, ScanCallback cb) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        if (!Prefs.getTollsEnabled(app)) {
            if (cb != null) cb.onDone(new ScanResult(false, false, 0, 0, 0, 0, 0));
            return;
        }
        if (progress != null) activeProgress.add(progress);
        if (cb != null) activeCallbacks.add(cb);
        if (!SCANNING.compareAndSet(false, true)) {
            if (progress != null && scanTotal > 0) progress.onProgress(scanDone, scanTotal);
            return;
        }
        scanDone = 0;
        scanTotal = 0;
        lastError = "";
        WORKER.execute(() -> {
            ScanResult res;
            try {
                res = runScanBlocking(app, forceAllTrips);
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName();
                Log.w(TAG, "tolls: scan failed: " + t);
                res = new ScanResult(true, false, 0, 1, countRows(CarDb.get(app).db(), "toll_booth"), 0, 0);
            } finally {
                SCANNING.set(false);
            }
            if (res.passagesAdded > 0 || res.tilesFetched > 0) {
                EntityBus.publish("toll.changed", CarActor.Reading.ok(System.currentTimeMillis()));
            }
            List<ScanCallback> cbs = new ArrayList<>(activeCallbacks);
            activeCallbacks.clear();
            activeProgress.clear();
            for (ScanCallback c : cbs) {
                try { c.onDone(res); } catch (Throwable ignored) {}
            }
        });
    }

    private static ScanResult runScanBlocking(Context app, boolean forceAllTrips) {
        SQLiteDatabase db = CarDb.get(app).db();
        long afterTripId = forceAllTrips ? 0L : Prefs.getTollLastScannedTrip(app);

        List<Long> tripIds = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT id FROM trip WHERE end_ms IS NOT NULL AND id > ? ORDER BY id ASC",
                new String[]{String.valueOf(afterTripId)})) {
            while (c.moveToNext()) tripIds.add(c.getLong(0));
        }
        if (tripIds.isEmpty()) {
            int booths = countRows(db, "toll_booth");
            return new ScanResult(true, false, 0, 0, booths, 0, 0);
        }

        // Collect coarse 0.5 deg (~55 km) privacy tiles touched by these trips.
        Set<String> neededTiles = new LinkedHashSet<>();
        for (long tid : tripIds) {
            try (Cursor c = db.rawQuery(
                    "SELECT lat, lon FROM trip_point WHERE trip_id = ?",
                    new String[]{String.valueOf(tid)})) {
                while (c.moveToNext()) {
                    neededTiles.add(TollMatcher.tileKey(c.getDouble(0), c.getDouble(1)));
                }
            }
        }

        long now = System.currentTimeMillis();
        Set<String> freshTiles = new HashSet<>();
        try (Cursor c = db.rawQuery(
                "SELECT tile_key FROM toll_region WHERE fetched_ms >= ?",
                new String[]{String.valueOf(now - TILE_TTL_MS)})) {
            while (c.moveToNext()) freshTiles.add(c.getString(0));
        }

        List<String> missingTiles = new ArrayList<>();
        for (String tk : neededTiles) {
            if (!freshTiles.contains(tk)) missingTiles.add(tk);
        }

        int tilesFetched = 0, tilesFailed = 0;
        if (!missingTiles.isEmpty()) {
            if (!hasNetwork(app)) {
                // Offline: still run local match against whatever booths are cached,
                // but do NOT advance toll_last_scanned_trip so we retry once Wi-Fi returns!
                reloadIndex(app);
                int localAdded = 0;
                if (!liveIndex.isEmpty()) {
                    for (long tid : tripIds) localAdded += matchTripInDb(db, liveIndex, tid, "auto_backfill");
                }
                return new ScanResult(false, true, 0, 0, countRows(db, "toll_booth"), tripIds.size(), localAdded);
            }
            scanTotal = missingTiles.size();
            for (int i = 0; i < missingTiles.size(); i++) {
                scanDone = i + 1;
                for (ProgressCallback p : activeProgress) {
                    try { p.onProgress(scanDone, scanTotal); } catch (Throwable ignored) {}
                }
                if (i > 0) {
                    try { Thread.sleep(OVERPASS_GAP_MS); } catch (InterruptedException ignored) { break; }
                }
                String tk = missingTiles.get(i);
                String json = fetchOverpassTile(tk);
                if (json == null) {
                    tilesFailed++;
                    continue;
                }
                List<TollMatcher.Booth> booths = TollMatcher.parseOverpassJson(json);
                upsertBoothsAndRegion(app, tk, booths, System.currentTimeMillis());
                tilesFetched++;
            }
        }

        reloadIndex(app);
        int passagesAdded = 0;
        for (long tid : tripIds) {
            passagesAdded += matchTripInDb(db, liveIndex, tid, "auto_backfill");
        }

        // Advance watermark only if every needed tile succeeded (otherwise offline/retry catches missed tiles).
        if (tilesFailed == 0 && !tripIds.isEmpty()) {
            Prefs.setTollLastScannedTrip(app, tripIds.get(tripIds.size() - 1));
        }
        int totalBooths = countRows(db, "toll_booth");
        Log.i(TAG, "tolls: scan done tilesFetched=" + tilesFetched + " failed=" + tilesFailed
            + " booths=" + totalBooths + " trips=" + tripIds.size() + " added=" + passagesAdded);
        return new ScanResult(true, false, tilesFetched, tilesFailed, totalBooths, tripIds.size(), passagesAdded);
    }

    private static String fetchOverpassTile(String tileKey) {
        String ql = TollMatcher.overpassQueryForTile(tileKey);
        int n = OVERPASS_URLS.length;
        int start = lastGoodEndpoint % n;
        for (int k = 0; k < n; k++) {
            int idx = (start + k) % n;
            String endpoint = OVERPASS_URLS[idx];
            HttpURLConnection conn = null;
            try {
                byte[] body = ("data=" + URLEncoder.encode(ql, "UTF-8")).getBytes(StandardCharsets.UTF_8);
                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(20_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", "DriveAssist/1.0 (+https://github.com/SuspiciousRepair/drive_assist)");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
                int code = conn.getResponseCode();
                if (code != 200) {
                    lastError = "HTTP " + code;
                    Log.w(TAG, "tolls: overpass " + endpoint + " HTTP " + code + " tile=" + tileKey);
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                lastGoodEndpoint = idx;
                lastError = "";
                return sb.toString();
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName();
                Log.w(TAG, "tolls: overpass " + endpoint + " tile=" + tileKey + ": " + t);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private static void upsertBoothsAndRegion(Context app, String tileKey,
                                              List<TollMatcher.Booth> booths, long nowMs) {
        SQLiteDatabase db = CarDb.get(app).db();
        db.beginTransaction();
        try {
            for (TollMatcher.Booth b : booths) {
                Long existingId = null;
                try (Cursor c = db.rawQuery(
                        "SELECT id FROM toll_booth WHERE source = 'osm' AND external_id = ?",
                        new String[]{b.externalId})) {
                    if (c.moveToFirst()) existingId = c.getLong(0);
                }
                ContentValues v = new ContentValues();
                v.put("name", b.name);
                if (b.operator != null) v.put("operator", b.operator); else v.putNull("operator");
                v.put("kind", b.kind);
                v.put("lat", b.lat);
                v.put("lon", b.lon);
                v.put("radius_m", b.radiusM);
                if (b.bearingDeg != null) v.put("bearing_deg", b.bearingDeg); else v.putNull("bearing_deg");
                v.put("bearing_tol_deg", b.bearingTolDeg);
                if (b.chargeRaw != null) v.put("charge_raw", b.chargeRaw); else v.putNull("charge_raw");
                if (b.catalogPrice != null) v.put("catalog_price", b.catalogPrice); else v.putNull("catalog_price");
                if (b.checkDate != null) v.put("check_date", b.checkDate); else v.putNull("check_date");
                v.put("updated_ms", nowMs);

                if (existingId != null) {
                    // Never overwrite user_price or enabled flag!
                    db.update("toll_booth", v, "id = ?", new String[]{String.valueOf(existingId)});
                } else {
                    v.put("uuid", UUID.randomUUID().toString());
                    v.put("source", "osm");
                    v.put("external_id", b.externalId);
                    v.put("enabled", 1);
                    db.insertWithOnConflict("toll_booth", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            ContentValues rv = new ContentValues();
            rv.put("tile_key", tileKey);
            rv.put("fetched_ms", nowMs);
            rv.put("booth_count", booths.size());
            db.insertWithOnConflict("toll_region", null, rv, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static int matchTripInDb(SQLiteDatabase db, TollMatcher.SpatialIndex index,
                                     long tripId, String source) {
        if (index == null || index.isEmpty()) return 0;
        long[] ts;
        double[] lat, lon, spd;
        try (Cursor c = db.rawQuery(
                "SELECT ts_ms, lat, lon, COALESCE(speed_kmh, 0) FROM trip_point WHERE trip_id = ? ORDER BY ts_ms",
                new String[]{String.valueOf(tripId)})) {
            int n = c.getCount();
            if (n < 2) return 0;
            ts = new long[n]; lat = new double[n]; lon = new double[n]; spd = new double[n];
            for (int i = 0; c.moveToNext(); i++) {
                ts[i] = c.getLong(0); lat[i] = c.getDouble(1);
                lon[i] = c.getDouble(2); spd[i] = c.getDouble(3);
            }
        }
        List<TollMatcher.Hit> hits = TollMatcher.matchTrack(index, ts, lat, lon, spd, ZoneId.systemDefault());
        int added = 0;
        for (TollMatcher.Hit h : hits) {
            if (insertPassageIfNew(db, tripId, h, source)) added++;
        }
        return added;
    }

    private static boolean insertPassageIfNew(SQLiteDatabase db, Long tripId,
                                              TollMatcher.Hit h, String source) {
        // Dedupe against any existing (including dismissed!) passage: same spot
        // and time, or a Free Flow corridor that already holds part of this one.
        List<TollMatcher.Saved> saved = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT booth_id, ts_ms, lat, lon, booth_kind FROM toll_passage WHERE ts_ms BETWEEN ? AND ?",
                new String[]{String.valueOf(TollMatcher.dedupeFromMs(h)), String.valueOf(TollMatcher.dedupeToMs(h))})) {
            while (c.moveToNext()) {
                saved.add(new TollMatcher.Saved(c.isNull(0) ? -1L : c.getLong(0), c.getLong(1),
                    c.getDouble(2), c.getDouble(3), c.getString(4)));
            }
        }
        if (TollMatcher.isDuplicate(h, saved)) return false;
        ContentValues v = new ContentValues();
        v.put("uuid", UUID.randomUUID().toString());
        if (tripId != null) v.put("trip_id", tripId);
        if (h.booth.id > 0) v.put("booth_id", h.booth.id);
        v.put("ts_ms", h.tsMs);
        v.put("lat", h.lat);
        v.put("lon", h.lon);
        v.put("speed_kmh", h.speedKmh);
        v.put("booth_name", h.booth.name);
        v.put("booth_kind", h.booth.kind);
        if (h.cost != null) v.put("cost", h.cost);
        v.put("dismissed", 0);
        v.put("source", source);
        return db.insert("toll_passage", null, v) > 0;
    }

    public static void reloadIndex(Context ctx) {
        List<TollMatcher.Booth> all = new ArrayList<>();
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT id, external_id, name, operator, kind, lat, lon, radius_m, bearing_deg, "
              + "bearing_tol_deg, charge_raw, catalog_price, user_price, check_date "
              + "FROM toll_booth WHERE enabled = 1", null)) {
            while (c.moveToNext()) {
                all.add(new TollMatcher.Booth(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getDouble(5), c.getDouble(6), c.getDouble(7),
                    c.isNull(8) ? null : c.getDouble(8), c.getDouble(9),
                    c.getString(10),
                    c.isNull(11) ? null : c.getDouble(11),
                    c.isNull(12) ? null : c.getDouble(12),
                    c.getString(13)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "tolls: reloadIndex: " + t);
        }
        // Cross-tile dedupe in memory so overlapping tile border fetches never double-match.
        List<TollMatcher.Booth> deduped = TollMatcher.dedupeBooths(all);
        liveIndex = new TollMatcher.SpatialIndex(deduped);
        liveRunner = new TollMatcher.Runner(liveIndex, ZoneId.systemDefault());
    }

    // ---- Queries & user edits ----

    public static List<Passage> forTrip(Context ctx, long tripId) {
        List<Passage> out = new ArrayList<>();
        if (ctx == null || tripId <= 0) return out;
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT id, trip_id, booth_id, ts_ms, lat, lon, speed_kmh, booth_name, booth_kind, cost, source "
              + "FROM toll_passage WHERE trip_id = ? AND dismissed = 0 ORDER BY ts_ms ASC",
                new String[]{String.valueOf(tripId)})) {
            while (c.moveToNext()) {
                out.add(new Passage(
                    c.getLong(0), c.isNull(1) ? null : c.getLong(1), c.isNull(2) ? null : c.getLong(2),
                    c.getLong(3), c.getDouble(4), c.getDouble(5),
                    c.isNull(6) ? null : c.getDouble(6), c.getString(7), c.getString(8),
                    c.isNull(9) ? null : c.getDouble(9), c.getString(10)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "tolls: forTrip: " + t);
        }
        return out;
    }

    /** Batch-reads toll counts & totals for a set of trip IDs (used by DailyStatsProvider). */
    public static Map<Long, TripSummary> summariesForTrips(SQLiteDatabase db, Collection<Long> tripIds) {
        Map<Long, TripSummary> out = new HashMap<>();
        if (db == null || tripIds == null || tripIds.isEmpty()) return out;
        StringBuilder in = new StringBuilder();
        for (Long id : tripIds) {
            if (id == null || id <= 0) continue;
            if (in.length() > 0) in.append(',');
            in.append(id);
        }
        if (in.length() == 0) return out;
        try (Cursor c = db.rawQuery(
                "SELECT trip_id, COUNT(*), COALESCE(SUM(CASE WHEN cost > 0 THEN cost ELSE 0 END), 0), "
              + "SUM(CASE WHEN cost IS NULL THEN 1 ELSE 0 END) "
              + "FROM toll_passage WHERE dismissed = 0 AND trip_id IN (" + in + ") GROUP BY trip_id", null)) {
            while (c.moveToNext()) {
                TripSummary s = new TripSummary();
                s.count = c.getInt(1);
                s.totalCost = c.getDouble(2);
                s.unpricedCount = c.getInt(3);
                out.put(c.getLong(0), s);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Updates one passage's price, optionally learning it on the booth for future passages. */
    public static void updatePassageCost(Context ctx, long passageId, Long boothId,
                                         double cost, boolean rememberForBooth) {
        updatePassageCost(ctx, passageId, boothId, cost, rememberForBooth, null);
    }

    public static void updatePassageCost(Context ctx, long passageId, Long boothId,
                                         double cost, boolean rememberForBooth, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            SQLiteDatabase db = CarDb.get(app).db();
            ContentValues v = new ContentValues();
            v.put("cost", cost);
            db.update("toll_passage", v, "id = ?", new String[]{String.valueOf(passageId)});
            if (rememberForBooth && boothId != null && boothId > 0) {
                ContentValues bv = new ContentValues();
                bv.put("user_price", cost);
                db.update("toll_booth", bv, "id = ?", new String[]{String.valueOf(boothId)});
                // Also fill any earlier unpriced passages at the same booth.
                db.execSQL("UPDATE toll_passage SET cost = ? WHERE booth_id = ? AND cost IS NULL AND dismissed = 0",
                    new Object[]{cost, boothId});
                reloadIndex(app);
            }
            EntityBus.publish("toll.changed", CarActor.Reading.ok(passageId));
            if (onDone != null) onDone.run();
        });
    }

    /** Soft-deletes a false positive so future backfills never recreate it. */
    public static void dismissPassage(Context ctx, long passageId) {
        dismissPassage(ctx, passageId, null);
    }

    public static void dismissPassage(Context ctx, long passageId, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            ContentValues v = new ContentValues();
            v.put("dismissed", 1);
            CarDb.get(app).db().update("toll_passage", v, "id = ?", new String[]{String.valueOf(passageId)});
            EntityBus.publish("toll.changed", CarActor.Reading.ok(passageId));
            if (onDone != null) onDone.run();
        });
    }

    /** Returns {boothCount, activePassageCount, tileCount}. */
    public static int[] cacheStats(Context ctx) {
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            int booths = countRows(db, "toll_booth");
            int tiles = countRows(db, "toll_region");
            int passages = 0;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM toll_passage WHERE dismissed = 0", null)) {
                if (c.moveToFirst()) passages = c.getInt(0);
            }
            return new int[]{booths, passages, tiles};
        } catch (Throwable t) {
            return new int[]{0, 0, 0};
        }
    }

    /** Clears fetched tile timestamps so the next scan refreshes OSM booths (keeps user_price). */
    public static void clearTileCache(Context ctx, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            CarDb.get(app).db().delete("toll_region", null, null);
            Prefs.setTollLastScannedTrip(app, 0L);
            if (onDone != null) onDone.run();
        });
    }

    private static int countRows(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) { return 0; }
    }

    private static boolean hasNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) { return false; }
    }
}
