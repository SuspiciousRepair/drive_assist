package com.geely.drivemem.state;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.car.TireWarning;
import com.geely.drivemem.sensors.Aux12vMonitor;
import com.geely.drivemem.sensors.LocationHub;
import com.geely.drivemem.util.DiaryClipTrimmer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Car Diary state, two-level category hierarchy, care due-status engine,
 * open-noise recurrence tracking, and pending accelerometer jolt queue.
 *
 * Pure calculation methods ({@link #computeDueStatuses}, {@link #parseBundled})
 * have no Android runtime dependency so unit tests exercise them directly. */
public final class CarDiary {
    private CarDiary() {}

    /** EntityBus event emitted whenever diary entries or pending jolts change. */
    public static final String EVENT_CHANGED = "diary.changed";

    /** Kind values persisted in diary_event.kind. */
    public static final String KIND_CARE = "CARE";
    public static final String KIND_NOISE = "NOISE";
    public static final String KIND_INCIDENT = "INCIDENT";
    public static final String KIND_AUTO = "AUTO";

    /** Status values persisted in diary_event.status. */
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** Filter modes for the Diary list view. */
    public enum Filter { ALL, CARE, NOTES }

    /** Top-level category groups shown in Row 1 of the Add Entry dialog. */
    public enum Group {
        TIRES(R.string.diary_grp_tires),
        SERVICE(R.string.diary_grp_service),
        ELECTRICAL(R.string.diary_grp_electrical),
        COSMETIC(R.string.diary_grp_cosmetic),
        NOTES(R.string.diary_grp_notes);

        public final int labelRes;
        Group(int labelRes) { this.labelRes = labelRes; }
    }

    /** Second-level subcategories shown in Row 2 (plus auto-logged types). */
    public enum Category {
        // Tires
        TIRE_PRESSURE(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_pressure, 1_500.0, 1, true),
        TIRE_ROTATION(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_rotation, 10_000.0, null, true),
        TIRE_BALANCING(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_balancing, 10_000.0, null, true),
        TIRE_ALIGNMENT(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_alignment, 10_000.0, 12, true),
        TIRES(Group.TIRES, KIND_CARE, R.string.diary_cat_tires, 40_000.0, 60, true),

        // Service & Fluids
        SERVICE(Group.SERVICE, KIND_CARE, R.string.diary_cat_service, 10_000.0, 12, true),
        CABIN_FILTER(Group.SERVICE, KIND_CARE, R.string.diary_cat_cabin_filter, 20_000.0, 12, true),
        BRAKE_FLUID(Group.SERVICE, KIND_CARE, R.string.diary_cat_brake_fluid, null, 24, true),
        BRAKE_PADS(Group.SERVICE, KIND_CARE, R.string.diary_cat_brake_pads, 30_000.0, null, true),
        COOLANT(Group.SERVICE, KIND_CARE, R.string.diary_cat_coolant, null, 60, true),
        GEAR_OIL(Group.SERVICE, KIND_CARE, R.string.diary_cat_gear_oil, 50_000.0, 48, true),

        // Electrical & Body
        BATTERY_12V(Group.ELECTRICAL, KIND_CARE, R.string.diary_cat_battery_12v, null, 48, true),
        WIPERS(Group.ELECTRICAL, KIND_CARE, R.string.diary_cat_wipers, null, 12, true),
        LIGHTS_FUSES(Group.ELECTRICAL, KIND_CARE, R.string.diary_cat_lights_fuses, null, null, true),

        // Cosmetic / Aesthetics
        WASH_DETAIL(Group.COSMETIC, KIND_CARE, R.string.diary_cat_wash_detail, null, null, true),
        WASHER_FLUID(Group.COSMETIC, KIND_CARE, R.string.diary_cat_washer_fluid, null, null, true),
        POLISHING(Group.COSMETIC, KIND_CARE, R.string.diary_cat_polishing, null, null, true),
        WAX_CERAMIC(Group.COSMETIC, KIND_CARE, R.string.diary_cat_wax_ceramic, null, null, true),
        PAINT_DENT(Group.COSMETIC, KIND_CARE, R.string.diary_cat_paint_dent, null, null, true),

        // Notes & Issues
        NOISE(Group.NOTES, KIND_NOISE, R.string.diary_cat_noise, null, null, true),
        INCIDENT(Group.NOTES, KIND_INCIDENT, R.string.diary_cat_incident, null, null, true),
        OTHER(Group.NOTES, KIND_CARE, R.string.diary_cat_other, null, null, true),

        // Auto-logged by Drive Assist (not in manual picker)
        TPMS_AUTO(Group.NOTES, KIND_AUTO, R.string.diary_cat_tpms_auto, null, null, false),
        AUX12V_AUTO(Group.NOTES, KIND_AUTO, R.string.diary_cat_aux12v_auto, null, null, false);

        public final Group group;
        public final String kind;
        public final int labelRes;
        public final Double kmInterval;
        public final Integer monthInterval;
        public final boolean userPickable;

        Category(Group group, String kind, int labelRes, Double kmInterval,
                 Integer monthInterval, boolean userPickable) {
            this.group = group;
            this.kind = kind;
            this.labelRes = labelRes;
            this.kmInterval = kmInterval;
            this.monthInterval = monthInterval;
            this.userPickable = userPickable;
        }

        public boolean hasInterval() { return kmInterval != null || monthInterval != null; }

        public static List<Category> forGroup(Group g) {
            List<Category> out = new ArrayList<>();
            for (Category c : values()) {
                if (c.userPickable && c.group == g) out.add(c);
            }
            return out;
        }

        public static Category fromName(String s) {
            if (s == null) return OTHER;
            try { return valueOf(s); }
            catch (IllegalArgumentException e) { return OTHER; }
        }
    }

    /** Core items displayed in the right-hand Care Schedule card (plus any overdue item). */
    public static final Category[] SCHEDULE_CORE = {
        Category.TIRE_PRESSURE, Category.TIRE_ROTATION, Category.WIPERS, Category.COOLANT
    };

    /** Categories that can be bundled inside a scheduled Service Visit. */
    public static final Category[] SERVICE_BUNDLABLE = {
        Category.CABIN_FILTER, Category.TIRE_ROTATION, Category.BRAKE_PADS, Category.COOLANT
    };

    public static final double COLD_SNAP_DROP_C = 10.0;
    private static final double MS_PER_DAY = 86_400_000.0;
    private static final double DAYS_PER_MONTH = 30.4375;

    /** One diary entry row. */
    public static final class Entry {
        public final long id;
        public final String uuid;
        public final long tsMs;
        public final String kind;
        public final Category category;
        public final String status;
        public final Double odoKm;
        public final Double outsideTempC;
        public final Double lat, lon;
        public final Double cost;
        public final String title;
        public final String note;
        public final String tags;
        public final String clipName;
        public final Long parentId;
        public final String metaJson;
        /** Total occurrences (1 + child recurrence rows) for parent NOISE entries. */
        public final int occurrences;

        public Entry(long id, String uuid, long tsMs, String kind, Category category,
                     String status, Double odoKm, Double outsideTempC, Double lat, Double lon,
                     Double cost, String title, String note, String tags, String clipName,
                     Long parentId, String metaJson, int occurrences) {
            this.id = id;
            this.uuid = uuid;
            this.tsMs = tsMs;
            this.kind = kind;
            this.category = category;
            this.status = status;
            this.odoKm = odoKm;
            this.outsideTempC = outsideTempC;
            this.lat = lat;
            this.lon = lon;
            this.cost = cost;
            this.title = title;
            this.note = note;
            this.tags = tags;
            this.clipName = clipName;
            this.parentId = parentId;
            this.metaJson = metaJson;
            this.occurrences = Math.max(1, occurrences);
        }

        public boolean isOpen() { return STATUS_OPEN.equals(status); }
        public boolean isNoteKind() {
            return KIND_NOISE.equals(kind) || KIND_INCIDENT.equals(kind) || KIND_AUTO.equals(kind);
        }
    }

    /** Computed due status for one tracked care category. */
    public static final class DueStatus {
        public final Category category;
        public final Entry lastEntry; // nullable when untracked
        public final Double kmSinceLast;
        public final Double kmRemaining;
        public final Integer daysSinceLast;
        public final Integer daysRemaining;
        public final boolean overdue;
        public final boolean dueSoon;
        public final boolean coldSnapNudge;

        public DueStatus(Category category, Entry lastEntry, Double kmSinceLast, Double kmRemaining,
                         Integer daysSinceLast, Integer daysRemaining,
                         boolean overdue, boolean dueSoon, boolean coldSnapNudge) {
            this.category = category;
            this.lastEntry = lastEntry;
            this.kmSinceLast = kmSinceLast;
            this.kmRemaining = kmRemaining;
            this.daysSinceLast = daysSinceLast;
            this.daysRemaining = daysRemaining;
            this.overdue = overdue;
            this.dueSoon = dueSoon;
            this.coldSnapNudge = coldSnapNudge;
        }
    }

    /** Last 100% charge info (quiet reference from charge_session / daily_stat). */
    public static final class LastFullCharge {
        public final long tsMs;
        public final int daysAgo;
        public final Double kmSince;

        public LastFullCharge(long tsMs, int daysAgo, Double kmSince) {
            this.tsMs = tsMs;
            this.daysAgo = daysAgo;
            this.kmSince = kmSince;
        }
    }

    /** Accelerometer-detected jolt waiting for driver review while parked. */
    public static final class PendingJolt {
        public final String id;
        public final long tsMs;
        public final float peakZG, peakXG, peakYG;
        public final Float speedKmh;
        public final Double odoKm;
        public final Double lat, lon;

        public PendingJolt(String id, long tsMs, float peakZG, float peakXG, float peakYG,
                           Float speedKmh, Double odoKm, Double lat, Double lon) {
            this.id = id;
            this.tsMs = tsMs;
            this.peakZG = peakZG;
            this.peakXG = peakXG;
            this.peakYG = peakYG;
            this.speedKmh = speedKmh;
            this.odoKm = odoKm;
            this.lat = lat;
            this.lon = lon;
        }

        public float maxG() {
            return Math.max(Math.abs(peakZG), Math.max(Math.abs(peakXG), Math.abs(peakYG)));
        }
    }

    // ---- Pending IMU jolt ring buffer (max 3, 24 h expiry) ----

    private static final int MAX_PENDING_JOLTS = 3;
    private static final long JOLT_TTL_MS = 24L * 3600_000L;
    private static final long JOLT_COOLDOWN_MS = 30_000L;
    private static final List<PendingJolt> pendingJolts = new ArrayList<>();

    /** Hook for ImuHub (or tests) to queue a sharp driving jolt for post-park review.
     * Lights the iOS-style notification dot on the Diary dock icon without interrupting driving. */
    public static synchronized PendingJolt recordPendingJolt(
            long tsMs, float peakZG, float peakXG, float peakYG,
            Float speedKmh, Double odoKm, Double lat, Double lon) {
        pruneExpiredJoltsLocked(tsMs);
        if (!pendingJolts.isEmpty()) {
            PendingJolt last = pendingJolts.get(0);
            if (Math.abs(tsMs - last.tsMs) < JOLT_COOLDOWN_MS) {
                // Keep the stronger shock within the same cooldown window.
                float g = Math.max(Math.abs(peakZG), Math.max(Math.abs(peakXG), Math.abs(peakYG)));
                if (g <= last.maxG()) return last;
                pendingJolts.remove(0);
            }
        }
        PendingJolt j = new PendingJolt(UUID.randomUUID().toString(), tsMs,
            peakZG, peakXG, peakYG, speedKmh, odoKm, lat, lon);
        pendingJolts.add(0, j);
        while (pendingJolts.size() > MAX_PENDING_JOLTS) {
            pendingJolts.remove(pendingJolts.size() - 1);
        }
        publishChanged();
        return j;
    }

    public static synchronized List<PendingJolt> pendingJolts(long nowMs) {
        pruneExpiredJoltsLocked(nowMs);
        return new ArrayList<>(pendingJolts);
    }

    public static List<PendingJolt> pendingJolts() {
        return pendingJolts(System.currentTimeMillis());
    }

    public static synchronized void dismissPendingJolt(String id) {
        if (id == null) return;
        boolean removed = pendingJolts.removeIf(j -> id.equals(j.id));
        if (removed) publishChanged();
    }

    static synchronized void clearPendingJoltsForTest() {
        pendingJolts.clear();
    }

    private static void pruneExpiredJoltsLocked(long nowMs) {
        pendingJolts.removeIf(j -> nowMs - j.tsMs > JOLT_TTL_MS);
    }

    // ---- Auto-subscriptions (TireWarning -> TPMS_AUTO) ----

    private static volatile boolean subscribed;
    private static volatile int lastTireWarn = 0;

    /** Idempotent; called once from DriveMemApplication.onCreate(). */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed || ctx == null) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        EntityBus.subscribe(TireWarning.ANY, (key, r) -> {
            if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) return;
            int now = ((Number) r.value).intValue() != 0 ? 1 : 0;
            int prev = lastTireWarn;
            lastTireWarn = now;
            if (prev == 0 && now == 1) {
                logAutoTireWarning(app);
            }
        });
    }

    private static void logAutoTireWarning(Context ctx) {
        CarActor a = CarActor.get();
        StringBuilder wheels = new StringBuilder();
        String[] labels = {"FL", "FR", "RL", "RR"};
        if (a != null) {
            for (int i = 0; i < TireWarning.KEYS.length; i++) {
                if (TireWarning.warns(a.get(TireWarning.KEYS[i]))) {
                    if (wheels.length() > 0) wheels.append(", ");
                    wheels.append(labels[i]);
                }
            }
        }
        String note = wheels.length() > 0 ? "iTPMS: " + wheels : "iTPMS";
        insert(ctx, Category.TPMS_AUTO, STATUS_OPEN, System.currentTimeMillis(),
            latestOdometerKm(ctx), latestOutsideTempC(), null,
            null, note, null, null, null, null, null);
    }

    // ---- Database CRUD ----

    /** Inserts a new diary entry asynchronously on CarDb's writer thread. */
    public static void insert(Context ctx, Category cat, String status, long tsMs,
                              Double odoKm, Double outsideTempC, Double cost,
                              String title, String note, String tags, String clipName,
                              Long parentId, String metaJson, Runnable onDone) {
        LocationHub.Fix fix = latestLocation();
        insert(ctx, cat, status, tsMs, odoKm, outsideTempC,
            fix != null ? fix.lat : null, fix != null ? fix.lon : null,
            cost, title, note, tags, clipName, parentId, metaJson, onDone);
    }

    public static void insert(Context ctx, Category cat, String status, long tsMs,
                              Double odoKm, Double outsideTempC, Double lat, Double lon,
                              Double cost, String title, String note, String tags,
                              String clipName, Long parentId, String metaJson, Runnable onDone) {
        if (ctx == null || cat == null) return;
        final Context app = ctx.getApplicationContext();
        final String st = (status != null) ? status
            : ((cat == Category.NOISE || cat == Category.INCIDENT) ? STATUS_OPEN : STATUS_DONE);
        final String uuid = UUID.randomUUID().toString();
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                ContentValues v = new ContentValues();
                v.put("uuid", uuid);
                v.put("ts_ms", tsMs);
                v.put("kind", cat.kind);
                v.put("category", cat.name());
                v.put("status", st);
                if (odoKm != null && odoKm > 0) v.put("odo_km", odoKm);
                if (outsideTempC != null) v.put("outside_temp_c", outsideTempC);
                if (lat != null) v.put("lat", lat);
                if (lon != null) v.put("lon", lon);
                if (cost != null && cost > 0) v.put("cost", cost);
                if (title != null && !title.trim().isEmpty()) v.put("title", title.trim());
                if (note != null && !note.trim().isEmpty()) v.put("note", note.trim());
                if (tags != null && !tags.trim().isEmpty()) v.put("tags", tags.trim());
                if (clipName != null && !clipName.trim().isEmpty()) v.put("clip_name", clipName.trim());
                if (parentId != null) v.put("parent_id", parentId);
                if (metaJson != null && !metaJson.trim().isEmpty()) v.put("meta_json", metaJson);
                db.insert("diary_event", null, v);
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary insert failed: " + t);
            }
        });
    }

    /** Inserts an incident and optionally trims a lossless 5-second dashcam clip off-thread first. */
    public static void insertIncidentWithOptionalClip(
            Context ctx, long tsMs, boolean captureClip, boolean flagForDealer,
            Double odoKm, Double outsideTempC, Double lat, Double lon,
            Double cost, String title, String note, String tags,
            String metaJson, String pendingJoltId, Runnable onDone) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        final String uuid = UUID.randomUUID().toString();
        final String st = flagForDealer ? STATUS_OPEN : STATUS_DONE;
        CarDb.get(app).write(() -> {
            String clip = null;
            if (captureClip) {
                clip = DiaryClipTrimmer.captureFiveSecondClip(app, tsMs, uuid);
            }
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                ContentValues v = new ContentValues();
                v.put("uuid", uuid);
                v.put("ts_ms", tsMs);
                v.put("kind", KIND_INCIDENT);
                v.put("category", Category.INCIDENT.name());
                v.put("status", st);
                if (odoKm != null && odoKm > 0) v.put("odo_km", odoKm);
                if (outsideTempC != null) v.put("outside_temp_c", outsideTempC);
                if (lat != null) v.put("lat", lat);
                if (lon != null) v.put("lon", lon);
                if (cost != null && cost > 0) v.put("cost", cost);
                if (title != null && !title.trim().isEmpty()) v.put("title", title.trim());
                if (note != null && !note.trim().isEmpty()) v.put("note", note.trim());
                if (tags != null && !tags.trim().isEmpty()) v.put("tags", tags.trim());
                if (clip != null) v.put("clip_name", clip);
                if (metaJson != null && !metaJson.trim().isEmpty()) v.put("meta_json", metaJson);
                db.insert("diary_event", null, v);
                if (pendingJoltId != null) dismissPendingJolt(pendingJoltId);
                else publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary incident insert failed: " + t);
            }
        });
    }

    /** Appends a '+1 happened again' recurrence child row linked to parentId, and bumps parent ts_ms. */
    public static void addRecurrence(Context ctx, long parentId, Runnable onDone) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        final long now = System.currentTimeMillis();
        final Double odo = latestOdometerKm(app);
        final Double outC = latestOutsideTempC();
        final LocationHub.Fix fix = latestLocation();
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                ContentValues v = new ContentValues();
                v.put("uuid", UUID.randomUUID().toString());
                v.put("ts_ms", now);
                v.put("kind", KIND_NOISE);
                v.put("category", Category.NOISE.name());
                v.put("status", STATUS_DONE);
                if (odo != null && odo > 0) v.put("odo_km", odo);
                if (outC != null) v.put("outside_temp_c", outC);
                if (fix != null) { v.put("lat", fix.lat); v.put("lon", fix.lon); }
                v.put("parent_id", parentId);
                db.insert("diary_event", null, v);
                ContentValues upd = new ContentValues();
                upd.put("ts_ms", now);
                if (odo != null && odo > 0) upd.put("odo_km", odo);
                db.update("diary_event", upd, "id = ?", new String[]{String.valueOf(parentId)});
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary recurrence failed: " + t);
            }
        });
    }

    /** Marks an open noise/incident/auto entry as RESOLVED. */
    public static void resolve(Context ctx, long id, Runnable onDone) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put("status", STATUS_RESOLVED);
                CarDb.get(app).db().update("diary_event", v, "id = ?", new String[]{String.valueOf(id)});
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary resolve failed: " + t);
            }
        });
    }

    /** Deletes an entry (plus any recurrence children and attached 5-second clip). */
    public static void delete(Context ctx, long id, String clipName, Runnable onDone) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                db.delete("diary_event", "parent_id = ?", new String[]{String.valueOf(id)});
                db.delete("diary_event", "id = ?", new String[]{String.valueOf(id)});
                DiaryClipTrimmer.deleteClip(app, clipName);
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary delete failed: " + t);
            }
        });
    }

    /** Reads top-level diary entries (parent_id IS NULL), newest first, with recurrence counts. */
    public static List<Entry> readEntries(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            Map<Long, Integer> childCounts = new HashMap<>();
            try (Cursor cc = db.rawQuery(
                    "SELECT parent_id, COUNT(*) FROM diary_event WHERE parent_id IS NOT NULL GROUP BY parent_id",
                    null)) {
                while (cc.moveToNext()) childCounts.put(cc.getLong(0), cc.getInt(1));
            }
            try (Cursor c = db.rawQuery(
                    "SELECT id, uuid, ts_ms, kind, category, status, odo_km, outside_temp_c, "
                    + "lat, lon, cost, title, note, tags, clip_name, parent_id, meta_json "
                    + "FROM diary_event WHERE parent_id IS NULL ORDER BY ts_ms DESC, id DESC", null)) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    int occ = 1 + (childCounts.containsKey(id) ? childCounts.get(id) : 0);
                    out.add(new Entry(
                        id, c.getString(1), c.getLong(2), c.getString(3),
                        Category.fromName(c.getString(4)), c.getString(5),
                        c.isNull(6) ? null : c.getDouble(6),
                        c.isNull(7) ? null : c.getDouble(7),
                        c.isNull(8) ? null : c.getDouble(8),
                        c.isNull(9) ? null : c.getDouble(9),
                        c.isNull(10) ? null : c.getDouble(10),
                        c.getString(11), c.getString(12), c.getString(13),
                        c.getString(14), c.isNull(15) ? null : c.getLong(15),
                        c.getString(16), occ));
                }
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary read failed: " + t);
        }
        return out;
    }

    /** Filters entries by top-level class/group (null = All). Pure. */
    public static List<Entry> filterByGroup(List<Entry> all, Group group) {
        if (all == null) return Collections.emptyList();
        if (group == null) return all;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (e.category != null && e.category.group == group) out.add(e);
        }
        return out;
    }

    /** Result of looking up the car's historical odometer for a chosen calendar date. */
    public static final class OdoLookup {
        public final Double odoKm;
        public final boolean exact;
        public OdoLookup(Double odoKm, boolean exact) {
            this.odoKm = odoKm;
            this.exact = exact;
        }
    }

    /** Pure resolver for historical odometer lookup on a date.
     * Prefers same-day telemetry_sample, then same-day daily_stat, then brackets
     * between the closest prior day's end and closest later day's start (if the car
     * sat parked, both match; if no later day exists, uses the prior day's end).
     * Returns null for dates strictly before any recorded car history. */
    public static OdoLookup resolveOdometerForDate(Double sameDaySample, Double sameDayStat,
                                                   Double prevLastOdo, Double nextFirstOdo) {
        if (sameDaySample != null && sameDaySample > 0) return new OdoLookup(sameDaySample, true);
        if (sameDayStat != null && sameDayStat > 0) return new OdoLookup(sameDayStat, true);
        if (prevLastOdo != null && prevLastOdo > 0) {
            if (nextFirstOdo != null && nextFirstOdo >= prevLastOdo) {
                return new OdoLookup((prevLastOdo + nextFirstOdo) / 2.0, false);
            }
            return new OdoLookup(prevLastOdo, false);
        }
        return new OdoLookup(null, false);
    }

    /** Looks up the car's odometer for the given local calendar date from CarDb
     * (telemetry_sample + permanent daily_stat). Off the UI thread. */
    public static OdoLookup odometerForDate(Context ctx, java.time.LocalDate date) {
        if (ctx == null || date == null) return new OdoLookup(null, false);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        String iso = date.toString(); // YYYY-MM-DD

        Double sameSample = null, sameStat = null, prevOdo = null, nextOdo = null;
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            try (Cursor c = db.rawQuery(
                    "SELECT MAX(odo_km) FROM telemetry_sample WHERE ts_ms >= ? AND ts_ms < ? AND odo_km > 0",
                    new String[]{String.valueOf(dayStart), String.valueOf(dayEnd)})) {
                if (c.moveToFirst() && !c.isNull(0)) sameSample = c.getDouble(0);
            }
            if (sameSample == null) {
                try (Cursor c = db.rawQuery(
                        "SELECT CASE WHEN last_odo_km > 0 THEN last_odo_km ELSE first_odo_km END "
                        + "FROM daily_stat WHERE date = ? AND (last_odo_km > 0 OR first_odo_km > 0)",
                        new String[]{iso})) {
                    if (c.moveToFirst() && !c.isNull(0)) sameStat = c.getDouble(0);
                }
            }
            if (sameSample == null && sameStat == null) {
                // Closest prior day from daily_stat or telemetry_sample
                try (Cursor c = db.rawQuery(
                        "SELECT last_odo_km FROM daily_stat WHERE date < ? AND last_odo_km > 0 "
                        + "ORDER BY date DESC LIMIT 1", new String[]{iso})) {
                    if (c.moveToFirst() && !c.isNull(0)) prevOdo = c.getDouble(0);
                }
                try (Cursor c = db.rawQuery(
                        "SELECT odo_km FROM telemetry_sample WHERE ts_ms < ? AND odo_km > 0 "
                        + "ORDER BY ts_ms DESC LIMIT 1", new String[]{String.valueOf(dayStart)})) {
                    if (c.moveToFirst() && !c.isNull(0)) {
                        double sOdo = c.getDouble(0);
                        if (prevOdo == null || sOdo > prevOdo) prevOdo = sOdo;
                    }
                }
                // Closest later day from daily_stat or telemetry_sample
                try (Cursor c = db.rawQuery(
                        "SELECT first_odo_km FROM daily_stat WHERE date > ? AND first_odo_km > 0 "
                        + "ORDER BY date ASC LIMIT 1", new String[]{iso})) {
                    if (c.moveToFirst() && !c.isNull(0)) nextOdo = c.getDouble(0);
                }
                try (Cursor c = db.rawQuery(
                        "SELECT odo_km FROM telemetry_sample WHERE ts_ms >= ? AND odo_km > 0 "
                        + "ORDER BY ts_ms ASC LIMIT 1", new String[]{String.valueOf(dayEnd)})) {
                    if (c.moveToFirst() && !c.isNull(0)) {
                        double sOdo = c.getDouble(0);
                        if (nextOdo == null || sOdo < nextOdo) nextOdo = sOdo;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary odo lookup failed: " + t);
        }
        return resolveOdometerForDate(sameSample, sameStat, prevOdo, nextOdo);
    }

    /** Filters entries for the UI tabs. Pure. */
    public static List<Entry> filter(List<Entry> all, Filter f) {
        if (all == null || f == Filter.ALL) return all == null ? Collections.emptyList() : all;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (f == Filter.CARE && KIND_CARE.equals(e.kind)) out.add(e);
            else if (f == Filter.NOTES && e.isNoteKind()) out.add(e);
        }
        return out;
    }

    /** Timestamp of the latest BATTERY_12V replacement entry, or null if none logged. */
    public static Long lastBattery12vReplacementMs(Context ctx) {
        if (ctx == null) return null;
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT MAX(ts_ms) FROM diary_event WHERE category = ?",
                new String[]{Category.BATTERY_12V.name()})) {
            return (c.moveToFirst() && !c.isNull(0)) ? c.getLong(0) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads the latest 100% charge from charge_session (fallback daily_stat) for the quiet
     * Care Schedule reference line, or null if none recorded. */
    public static LastFullCharge last100PctCharge(Context ctx, Double currentOdoKm, long nowMs) {
        if (ctx == null) return null;
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            try (Cursor c = db.rawQuery(
                    "SELECT end_ms, odo_start_km FROM charge_session "
                    + "WHERE soc_end >= 100 ORDER BY end_ms DESC LIMIT 1", null)) {
                if (c.moveToFirst()) {
                    long ts = c.getLong(0);
                    Double odo = c.isNull(1) ? null : c.getDouble(1);
                    int days = (int) Math.max(0, Math.floor((nowMs - ts) / MS_PER_DAY));
                    Double km = (currentOdoKm != null && odo != null && currentOdoKm >= odo)
                        ? (currentOdoKm - odo) : null;
                    return new LastFullCharge(ts, days, km);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** True when the Diary dock icon should show the iOS-style notification dot:
     * any pending IMU jolt, any overdue care item, or 12V health in Watch/Weak/Fault. */
    public static boolean hasAttentionDot(Context ctx) {
        long now = System.currentTimeMillis();
        if (!pendingJolts(now).isEmpty()) return true;
        if (ctx == null) return false;
        Aux12vMonitor.Summary s12 = Aux12vMonitor.readSummary(ctx, now);
        if (s12.health == Aux12vMonitor.Health.WATCH
                || s12.health == Aux12vMonitor.Health.WEAK
                || s12.health == Aux12vMonitor.Health.FAULT) {
            return true;
        }
        List<Entry> entries = readEntries(ctx);
        Double odo = latestOdometerKm(ctx);
        Double outC = latestOutsideTempC();
        for (DueStatus d : computeDueStatuses(entries, odo, now, outC)) {
            if (d.overdue) return true;
        }
        return false;
    }

    // ---- Pure Due-Status Calculation Engine ----

    /** Computes due status for every tracked category from the given entries, current odometer,
     * current wall time, and optional outside temperature (for tire-pressure cold-snap nudge).
     * Recognizes items bundled inside SERVICE entries (meta_json.bundled). Pure & unit-tested. */
    public static List<DueStatus> computeDueStatuses(List<Entry> entries, Double currentOdoKm,
                                                     long nowMs, Double currentOutTempC) {
        Map<Category, Entry> latestByCat = new HashMap<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (e.parentId != null) continue;
                considerLatest(latestByCat, e.category, e);
                if (e.category == Category.SERVICE) {
                    for (Category bundled : parseBundled(e.metaJson)) {
                        considerLatest(latestByCat, bundled, e);
                    }
                }
            }
        }

        List<DueStatus> out = new ArrayList<>();
        for (Category cat : Category.values()) {
            if (!cat.userPickable || !cat.hasInterval()) continue;
            Entry last = latestByCat.get(cat);
            if (last == null) {
                out.add(new DueStatus(cat, null, null, null, null, null, false, false, false));
                continue;
            }
            Double kmSince = (currentOdoKm != null && last.odoKm != null && currentOdoKm >= last.odoKm)
                ? (currentOdoKm - last.odoKm) : null;
            Double kmRem = (cat.kmInterval != null && kmSince != null)
                ? (cat.kmInterval - kmSince) : null;

            int daysSince = (int) Math.max(0, Math.floor((nowMs - last.tsMs) / MS_PER_DAY));
            Integer daysRem = null;
            if (cat.monthInterval != null) {
                int limitDays = (int) Math.round(cat.monthInterval * DAYS_PER_MONTH);
                daysRem = limitDays - daysSince;
            }

            boolean overdueKm = kmRem != null && kmRem <= 0.0;
            boolean overdueTime = daysRem != null && daysRem <= 0;
            boolean overdue = overdueKm || overdueTime;

            boolean soonKm = !overdue && cat.kmInterval != null && kmRem != null
                && kmRem <= cat.kmInterval * 0.10;
            boolean soonTime = !overdue && cat.monthInterval != null && daysRem != null
                && daysRem <= Math.max(3, (int) Math.round(cat.monthInterval * DAYS_PER_MONTH * 0.10));

            boolean coldSnap = cat == Category.TIRE_PRESSURE && !overdue
                && currentOutTempC != null && last.outsideTempC != null
                && (last.outsideTempC - currentOutTempC) >= COLD_SNAP_DROP_C;

            out.add(new DueStatus(cat, last, kmSince, kmRem, daysSince, daysRem,
                overdue, soonKm || soonTime || coldSnap, coldSnap));
        }
        return out;
    }

    /** Picks the single most urgent DueStatus (overdue first, then dueSoon, then soonest tracked). Pure. */
    public static DueStatus mostUrgent(List<DueStatus> list) {
        if (list == null || list.isEmpty()) return null;
        DueStatus best = null;
        for (DueStatus d : list) {
            if (d.lastEntry == null) continue;
            if (best == null || urgencyScore(d) < urgencyScore(best)) best = d;
        }
        return best;
    }

    /** Lower score = more urgent. Negative = overdue fraction. */
    private static double urgencyScore(DueStatus d) {
        double score = Double.POSITIVE_INFINITY;
        if (d.category.kmInterval != null && d.kmRemaining != null) {
            score = Math.min(score, d.kmRemaining / d.category.kmInterval);
        }
        if (d.category.monthInterval != null && d.daysRemaining != null) {
            score = Math.min(score, d.daysRemaining / (d.category.monthInterval * DAYS_PER_MONTH));
        }
        if (d.coldSnapNudge && score > 0.05) score = 0.05;
        return score;
    }

    /** Selects up to 4 rows for the Care Schedule card: any overdue items first, then SCHEDULE_CORE. */
    public static List<DueStatus> scheduleRows(List<DueStatus> all) {
        Map<Category, DueStatus> byCat = new LinkedHashMap<>();
        for (DueStatus d : all) byCat.put(d.category, d);
        List<DueStatus> out = new ArrayList<>();
        Set<Category> picked = new HashSet<>();
        List<DueStatus> overdue = new ArrayList<>();
        for (DueStatus d : all) if (d.overdue) overdue.add(d);
        overdue.sort(Comparator.comparingDouble(CarDiary::urgencyScore));
        for (DueStatus d : overdue) {
            if (out.size() >= 4) break;
            out.add(d);
            picked.add(d.category);
        }
        for (Category c : SCHEDULE_CORE) {
            if (out.size() >= 4) break;
            if (!picked.contains(c) && byCat.containsKey(c)) {
                out.add(byCat.get(c));
                picked.add(c);
            }
        }
        return out;
    }

    private static void considerLatest(Map<Category, Entry> map, Category cat, Entry e) {
        if (cat == null) return;
        Entry prev = map.get(cat);
        if (prev == null || e.tsMs > prev.tsMs) map.put(cat, e);
    }

    // ---- Lightweight Pure meta_json Helpers (no org.json dependency in JVM unit tests) ----

    public static String buildTirePressureMeta(int fl, int fr, int rl, int rr, boolean cold) {
        return String.format(Locale.US,
            "{\"psi\":[%d,%d,%d,%d],\"cold\":%s}", fl, fr, rl, rr, cold ? "true" : "false");
    }

    public static String buildServiceBundledMeta(List<Category> bundled) {
        if (bundled == null || bundled.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{\"bundled\":[");
        for (int i = 0; i < bundled.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(bundled.get(i).name()).append('"');
        }
        return sb.append("]}").toString();
    }

    public static String buildJoltMeta(float peakG, Float speedKmh) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "{\"joltG\":%.2f", peakG));
        if (speedKmh != null) sb.append(String.format(Locale.US, ",\"speedKmh\":%.0f", speedKmh));
        return sb.append('}').toString();
    }

    /** Extracts bundled categories from meta_json (e.g. {"bundled":["CABIN_FILTER","TIRE_ROTATION"]}). */
    public static List<Category> parseBundled(String metaJson) {
        List<Category> out = new ArrayList<>();
        if (metaJson == null) return out;
        int idx = metaJson.indexOf("\"bundled\"");
        if (idx < 0) return out;
        int lb = metaJson.indexOf('[', idx);
        int rb = lb >= 0 ? metaJson.indexOf(']', lb) : -1;
        if (lb < 0 || rb < 0) return out;
        String inside = metaJson.substring(lb + 1, rb);
        for (String part : inside.split(",")) {
            String tok = part.replace("\"", "").trim();
            if (!tok.isEmpty()) {
                try { out.add(Category.valueOf(tok)); } catch (IllegalArgumentException ignored) {}
            }
        }
        return out;
    }

    /** Extracts [fl, fr, rl, rr] PSI from meta_json, or null. */
    public static int[] parsePsi(String metaJson) {
        if (metaJson == null) return null;
        int idx = metaJson.indexOf("\"psi\"");
        if (idx < 0) return null;
        int lb = metaJson.indexOf('[', idx);
        int rb = lb >= 0 ? metaJson.indexOf(']', lb) : -1;
        if (lb < 0 || rb < 0) return null;
        String[] parts = metaJson.substring(lb + 1, rb).split(",");
        if (parts.length != 4) return null;
        int[] out = new int[4];
        try {
            for (int i = 0; i < 4; i++) out[i] = Integer.parseInt(parts[i].trim());
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Boolean parseCold(String metaJson) {
        if (metaJson == null) return null;
        if (metaJson.contains("\"cold\":true")) return Boolean.TRUE;
        if (metaJson.contains("\"cold\":false")) return Boolean.FALSE;
        return null;
    }

    // ---- Hub / DB Helpers ----

    public static Double latestOdometerKm(Context ctx) {
        CarActor a = CarActor.get();
        if (a != null) {
            CarActor.Reading tick = a.get("telemetry.tick");
            if (tick != null && tick.status == CarActor.Reading.Status.OK && tick.value instanceof Map) {
                Object o = ((Map<?, ?>) tick.value).get("odometer");
                if (o instanceof Number && ((Number) o).doubleValue() > 0) {
                    return ((Number) o).doubleValue();
                }
            }
        }
        if (ctx == null) return null;
        try (Cursor c = CarDb.get(ctx).db().rawQuery(
                "SELECT odo_km FROM telemetry_sample WHERE odo_km > 0 ORDER BY id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getDouble(0) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Double latestOutsideTempC() {
        CarActor a = CarActor.get();
        if (a == null) return null;
        CarActor.Reading r = a.get("telemetry.outside_temp");
        if (r != null && r.status == CarActor.Reading.Status.OK && r.value instanceof Number) {
            return ((Number) r.value).doubleValue();
        }
        return null;
    }

    private static LocationHub.Fix latestLocation() {
        CarActor a = CarActor.get();
        if (a == null) return null;
        CarActor.Reading r = a.get(LocationHub.FIX);
        if (r != null && r.status == CarActor.Reading.Status.OK && r.value instanceof LocationHub.Fix) {
            return (LocationHub.Fix) r.value;
        }
        return null;
    }

    private static void publishChanged() {
        CarActor a = CarActor.get();
        if (a != null) {
            a.injectReading(EVENT_CHANGED, CarActor.Reading.ok(System.currentTimeMillis()));
        }
    }
}
