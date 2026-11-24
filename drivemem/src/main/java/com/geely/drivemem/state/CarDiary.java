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
import com.geely.drivemem.sensors.BatteryHealth;
import com.geely.drivemem.sensors.BatteryHeat;
import com.geely.drivemem.sensors.ChargePeriodSummary;
import com.geely.drivemem.sensors.LocationHub;
import com.geely.drivemem.sensors.SocHistory;
import com.geely.drivemem.util.DiaryClipTrimmer;
import com.geely.drivemem.util.Prefs;

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

    /** Top-level category groups shown in the Add Entry dialog. */
    public enum Group {
        TIRES(R.string.diary_grp_tires, R.drawable.ic_diary_tires),
        SERVICE(R.string.diary_grp_service, R.drawable.ic_diary_service),
        ELECTRICAL(R.string.diary_grp_electrical, R.drawable.ic_diary_electrical),
        COSMETIC(R.string.diary_grp_cosmetic, R.drawable.ic_diary_cosmetic),
        NOTES(R.string.diary_grp_notes, R.drawable.ic_diary_notes);

        public final int labelRes;
        public final int iconRes;
        Group(int labelRes, int iconRes) {
            this.labelRes = labelRes;
            this.iconRes = iconRes;
        }
    }

    /** Second-level subcategories (intervals aligned with Geely Brasil official EX2 schedule). */
    public enum Category {
        // Tires (owner-care intervals)
        TIRE_PRESSURE(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_pressure, 1_500.0, 1, true),
        TIRE_ROTATION(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_rotation, 10_000.0, null, true),
        TIRE_BALANCING(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_balancing, 10_000.0, null, true),
        TIRE_ALIGNMENT(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_alignment, 10_000.0, 12, true),
        TIRES(Group.TIRES, KIND_CARE, R.string.diary_cat_tires, 40_000.0, 60, true),

        // Service & Fluids — official Geely Brasil EX2 schedule (20k/12m base, 40k/24m brake fluid, 80k/48m coolant + reducer oil)
        SERVICE(Group.SERVICE, KIND_CARE, R.string.diary_cat_service, 20_000.0, 12, true),
        CABIN_FILTER(Group.SERVICE, KIND_CARE, R.string.diary_cat_cabin_filter, 20_000.0, 12, true),
        BRAKE_FLUID(Group.SERVICE, KIND_CARE, R.string.diary_cat_brake_fluid, 40_000.0, 24, true),
        BRAKE_PADS(Group.SERVICE, KIND_CARE, R.string.diary_cat_brake_pads, null, null, true),
        COOLANT(Group.SERVICE, KIND_CARE, R.string.diary_cat_coolant, 80_000.0, 48, true),
        GEAR_OIL(Group.SERVICE, KIND_CARE, R.string.diary_cat_gear_oil, 80_000.0, 48, true),

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
        Category.TIRE_PRESSURE, Category.SERVICE, Category.TIRE_ROTATION, Category.BRAKE_FLUID
    };

    /** Categories that can be bundled inside a scheduled Service Visit (Geely EX2 20k/40k/80k). */
    public static final Category[] SERVICE_BUNDLABLE = {
        Category.CABIN_FILTER, Category.BRAKE_FLUID, Category.COOLANT,
        Category.GEAR_OIL, Category.TIRE_ROTATION, Category.BRAKE_PADS
    };

    public static final double COLD_SNAP_DROP_C = 10.0;
    public static final long COLD_TIRE_PARK_MS = 3L * 3600_000L;
    public static final double DEFAULT_AVG_DAILY_KM = 40.0;
    public static final int BALANCE_INTERVAL_DAYS = 30;
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

    /** Configurable maintenance intervals (0 = disabled / Off). Pure & unit-tested. */
    public static final class ScheduleConfig {
        public final int balanceDays;
        public final int tirePressureDays;
        public final boolean coldSnapEnabled;
        public final int tireRotationKm;
        public final int serviceKm, serviceMonths;
        public final int cabinFilterKm, cabinFilterMonths;
        public final int brakeFluidKm, brakeFluidMonths;
        public final int coolantKm, coolantMonths;
        public final int gearOilKm, gearOilMonths;
        public final int wipersMonths;
        public final int battery12vMonths;

        public ScheduleConfig(int balanceDays, int tirePressureDays, boolean coldSnapEnabled,
                              int tireRotationKm, int serviceKm, int serviceMonths,
                              int cabinFilterKm, int cabinFilterMonths,
                              int brakeFluidKm, int brakeFluidMonths,
                              int coolantKm, int coolantMonths,
                              int gearOilKm, int gearOilMonths,
                              int wipersMonths, int battery12vMonths) {
            this.balanceDays = Math.max(0, balanceDays);
            this.tirePressureDays = Math.max(0, tirePressureDays);
            this.coldSnapEnabled = coldSnapEnabled;
            this.tireRotationKm = Math.max(0, tireRotationKm);
            this.serviceKm = Math.max(0, serviceKm);
            this.serviceMonths = Math.max(0, serviceMonths);
            this.cabinFilterKm = Math.max(0, cabinFilterKm);
            this.cabinFilterMonths = Math.max(0, cabinFilterMonths);
            this.brakeFluidKm = Math.max(0, brakeFluidKm);
            this.brakeFluidMonths = Math.max(0, brakeFluidMonths);
            this.coolantKm = Math.max(0, coolantKm);
            this.coolantMonths = Math.max(0, coolantMonths);
            this.gearOilKm = Math.max(0, gearOilKm);
            this.gearOilMonths = Math.max(0, gearOilMonths);
            this.wipersMonths = Math.max(0, wipersMonths);
            this.battery12vMonths = Math.max(0, battery12vMonths);
        }

        public static ScheduleConfig defaults() {
            return new ScheduleConfig(
                BALANCE_INTERVAL_DAYS, 30, true,
                10_000, 20_000, 12,
                20_000, 12,
                40_000, 24,
                80_000, 48,
                80_000, 48,
                12, 48);
        }

        public static ScheduleConfig fromPrefs(Context ctx) {
            if (ctx == null) return defaults();
            return new ScheduleConfig(
                Prefs.getDiaryBalanceDays(ctx),
                Prefs.getDiaryTirePressureDays(ctx),
                Prefs.getDiaryTireColdSnap(ctx),
                Prefs.getDiaryTireRotationKm(ctx),
                Prefs.getDiaryServiceKm(ctx), Prefs.getDiaryServiceMonths(ctx),
                Prefs.getDiaryCabinFilterKm(ctx), Prefs.getDiaryCabinFilterMonths(ctx),
                Prefs.getDiaryBrakeFluidKm(ctx), Prefs.getDiaryBrakeFluidMonths(ctx),
                Prefs.getDiaryCoolantKm(ctx), Prefs.getDiaryCoolantMonths(ctx),
                Prefs.getDiaryGearOilKm(ctx), Prefs.getDiaryGearOilMonths(ctx),
                Prefs.getDiaryWipersMonths(ctx),
                Prefs.getDiaryBattery12vMonths(ctx));
        }

        public Double kmInterval(Category cat) {
            switch (cat) {
                case TIRE_PRESSURE: return tirePressureDays > 0 ? cat.kmInterval : null;
                case TIRE_ROTATION: return tireRotationKm > 0 ? (double) tireRotationKm : null;
                case SERVICE: return serviceKm > 0 ? (double) serviceKm : null;
                case CABIN_FILTER: return cabinFilterKm > 0 ? (double) cabinFilterKm : null;
                case BRAKE_FLUID: return brakeFluidKm > 0 ? (double) brakeFluidKm : null;
                case COOLANT: return coolantKm > 0 ? (double) coolantKm : null;
                case GEAR_OIL: return gearOilKm > 0 ? (double) gearOilKm : null;
                default: return cat.kmInterval;
            }
        }

        public Integer dayInterval(Category cat) {
            switch (cat) {
                case TIRE_PRESSURE: return tirePressureDays > 0 ? tirePressureDays : null;
                case SERVICE: return serviceMonths > 0 ? (int) Math.round(serviceMonths * DAYS_PER_MONTH) : null;
                case CABIN_FILTER: return cabinFilterMonths > 0 ? (int) Math.round(cabinFilterMonths * DAYS_PER_MONTH) : null;
                case BRAKE_FLUID: return brakeFluidMonths > 0 ? (int) Math.round(brakeFluidMonths * DAYS_PER_MONTH) : null;
                case COOLANT: return coolantMonths > 0 ? (int) Math.round(coolantMonths * DAYS_PER_MONTH) : null;
                case GEAR_OIL: return gearOilMonths > 0 ? (int) Math.round(gearOilMonths * DAYS_PER_MONTH) : null;
                case WIPERS: return wipersMonths > 0 ? (int) Math.round(wipersMonths * DAYS_PER_MONTH) : null;
                case BATTERY_12V: return battery12vMonths > 0 ? (int) Math.round(battery12vMonths * DAYS_PER_MONTH) : null;
                default:
                    return cat.monthInterval != null
                        ? (int) Math.round(cat.monthInterval * DAYS_PER_MONTH) : null;
            }
        }

        public boolean isEnabled(Category cat) {
            return kmInterval(cat) != null || dayInterval(cat) != null;
        }
    }

    /** Lifetime Total Cost of Ownership combining Energy (charges) + Maintenance (diary). */
    public static final class TcoSummary {
        public final double chargeCost;
        public final double diaryCost;
        public final double totalCost;
        public final double trackedKm;
        public final double costPerKm;     // -1 when trackedKm <= 0 or totalCost <= 0
        public final double chargeShare;   // 0..1, -1 when totalCost <= 0

        public TcoSummary(double chargeCost, double diaryCost, double trackedKm,
                          double costPerKm, double chargeShare) {
            this.chargeCost = chargeCost;
            this.diaryCost = diaryCost;
            this.totalCost = chargeCost + diaryCost;
            this.trackedKm = trackedKm;
            this.costPerKm = costPerKm;
            this.chargeShare = chargeShare;
        }

        public static TcoSummary compute(double chargeCost, double diaryCost, double trackedKm) {
            double c = Math.max(0.0, chargeCost);
            double d = Math.max(0.0, diaryCost);
            double tot = c + d;
            double km = Math.max(0.0, trackedKm);
            double perKm = (tot > 0 && km >= 1.0) ? (tot / km) : -1.0;
            double share = tot > 0 ? (c / tot) : -1.0;
            return new TcoSummary(c, d, km, perKm, share);
        }

        public boolean hasCosts() { return totalCost > 0.005; }
    }

    /** 60-day HV traction battery longevity summary (moved from ChargeStatsView). */
    public static final class HvHealthSummary {
        public final Double soh;
        public final BatteryHeat.Stats heat;
        public final ChargePeriodSummary charges;
        public final java.time.LocalDate windowStart;

        public HvHealthSummary(Double soh, BatteryHeat.Stats heat,
                               ChargePeriodSummary charges, java.time.LocalDate windowStart) {
            this.soh = soh;
            this.heat = heat;
            this.charges = charges;
            this.windowStart = windowStart;
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

    /** Accelerometer-detected jolt waiting for driver review. */
    public static final class PendingJolt {
        public final String id;
        public final long tsMs;
        public final float peakZG, peakXG, peakYG;
        public final float rmsZG, peakJerkZGs;
        public final Float speedKmh;
        public final Double odoKm;
        public final Double lat, lon;

        public PendingJolt(String id, long tsMs, float peakZG, float peakXG, float peakYG,
                           Float speedKmh, Double odoKm, Double lat, Double lon) {
            this(id, tsMs, peakZG, peakXG, peakYG, 0f, 0f, speedKmh, odoKm, lat, lon);
        }

        public PendingJolt(String id, long tsMs, float peakZG, float peakXG, float peakYG,
                           float rmsZG, float peakJerkZGs,
                           Float speedKmh, Double odoKm, Double lat, Double lon) {
            this.id = id;
            this.tsMs = tsMs;
            this.peakZG = peakZG;
            this.peakXG = peakXG;
            this.peakYG = peakYG;
            this.rmsZG = rmsZG;
            this.peakJerkZGs = peakJerkZGs;
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

    /** Hook for ImuHub (or tests) to queue a sharp driving jolt for review. */
    public static synchronized PendingJolt recordPendingJolt(
            long tsMs, float peakZG, float peakXG, float peakYG,
            Float speedKmh, Double odoKm, Double lat, Double lon) {
        return recordPendingJolt(tsMs, peakZG, peakXG, peakYG, 0f, 0f, speedKmh, odoKm, lat, lon);
    }

    public static synchronized PendingJolt recordPendingJolt(
            long tsMs, float peakZG, float peakXG, float peakYG,
            float rmsZG, float peakJerkZGs,
            Float speedKmh, Double odoKm, Double lat, Double lon) {
        pruneExpiredJoltsLocked(tsMs);
        if (!pendingJolts.isEmpty()) {
            PendingJolt last = pendingJolts.get(0);
            if (Math.abs(tsMs - last.tsMs) < JOLT_COOLDOWN_MS) {
                float g = Math.max(Math.abs(peakZG), Math.max(Math.abs(peakXG), Math.abs(peakYG)));
                if (g <= last.maxG()) return last;
                pendingJolts.remove(0);
            }
        }
        PendingJolt j = new PendingJolt(UUID.randomUUID().toString(), tsMs,
            peakZG, peakXG, peakYG, rmsZG, peakJerkZGs, speedKmh, odoKm, lat, lon);
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
                Double finalLat = lat, finalLon = lon;
                if (finalLat == null || finalLon == null) {
                    // Fallback to nearest recorded trip_point within +-15 s of the impact
                    try (Cursor tc = db.rawQuery(
                            "SELECT lat, lon FROM trip_point WHERE ts_ms BETWEEN ? AND ? "
                            + "ORDER BY ABS(ts_ms - ?) ASC LIMIT 1",
                            new String[]{String.valueOf(tsMs - 15_000L),
                                String.valueOf(tsMs + 15_000L), String.valueOf(tsMs)})) {
                        if (tc.moveToFirst()) {
                            finalLat = tc.getDouble(0);
                            finalLon = tc.getDouble(1);
                        }
                    } catch (Throwable ignored) {}
                }
                ContentValues v = new ContentValues();
                v.put("uuid", uuid);
                v.put("ts_ms", tsMs);
                v.put("kind", KIND_INCIDENT);
                v.put("category", Category.INCIDENT.name());
                v.put("status", st);
                if (odoKm != null && odoKm > 0) v.put("odo_km", odoKm);
                if (outsideTempC != null) v.put("outside_temp_c", outsideTempC);
                if (finalLat != null) v.put("lat", finalLat);
                if (finalLon != null) v.put("lon", finalLon);
                if (cost != null && cost > 0) v.put("cost", cost);
                if (title != null && !title.trim().isEmpty()) v.put("title", title.trim());
                if (note != null && !note.trim().isEmpty()) v.put("note", note.trim());
                if (tags != null && !tags.trim().isEmpty()) v.put("tags", tags.trim());
                if (clip != null) v.put("clip_name", clip);
                if (metaJson != null && !metaJson.trim().isEmpty()) v.put("meta_json", metaJson);
                long rowId = db.insert("diary_event", null, v);
                if (pendingJoltId != null) dismissPendingJolt(pendingJoltId);
                else publishChanged();
                if (onDone != null) onDone.run();

                // If the dashcam segment covering tsMs is still RECORDING (.mp4.tmp),
                // wait in the background for it to close and attach the 5s clip automatically.
                if (captureClip && clip == null && rowId > 0
                        && DiaryClipTrimmer.hasLiveSegmentCovering(app, tsMs)) {
                    scheduleDelayedClipAttach(app, rowId, tsMs, uuid);
                }
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary incident insert failed: " + t);
            }
        });
    }

    /** Polls up to 6 minutes for an active dashcam segment covering tsMs to close,
     * then extracts the 5-second clip and updates diary_event.clip_name. */
    private static void scheduleDelayedClipAttach(Context app, long rowId, long tsMs, String uuid) {
        new Thread(() -> {
            for (int attempt = 0; attempt < 24; attempt++) {
                try { Thread.sleep(15_000L); } catch (InterruptedException e) { return; }
                String clip = DiaryClipTrimmer.captureFiveSecondClip(app, tsMs, uuid);
                if (clip != null) {
                    CarDb.get(app).write(() -> {
                        try {
                            ContentValues upd = new ContentValues();
                            upd.put("clip_name", clip);
                            CarDb.get(app).db().update("diary_event", upd, "id = ?",
                                new String[]{String.valueOf(rowId)});
                            publishChanged();
                        } catch (Throwable t) {
                            Log.w(CarAccess.TAG, "diary delayed clip attach failed: " + t);
                        }
                    });
                    return;
                }
                if (!DiaryClipTrimmer.hasLiveSegmentCovering(app, tsMs)) return;
            }
        }, "diary-clip-retry").start();
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

    /** Updates an existing diary entry asynchronously on CarDb's writer thread. */
    public static void update(Context ctx, long id, Category cat, String status, long tsMs,
                              Double odoKm, Double cost, String title, String note, String tags,
                              String metaJson, Runnable onDone) {
        if (ctx == null || cat == null || id <= 0) return;
        final Context app = ctx.getApplicationContext();
        final String st = (status != null) ? status
            : ((cat == Category.NOISE || cat == Category.INCIDENT) ? STATUS_OPEN : STATUS_DONE);
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                ContentValues v = new ContentValues();
                v.put("ts_ms", tsMs);
                v.put("kind", cat.kind);
                v.put("category", cat.name());
                v.put("status", st);
                if (odoKm != null && odoKm > 0) v.put("odo_km", odoKm);
                else v.putNull("odo_km");
                if (cost != null && cost > 0) v.put("cost", cost);
                else v.putNull("cost");
                if (title != null && !title.trim().isEmpty()) v.put("title", title.trim());
                else v.putNull("title");
                if (note != null && !note.trim().isEmpty()) v.put("note", note.trim());
                else v.putNull("note");
                if (tags != null && !tags.trim().isEmpty()) v.put("tags", tags.trim());
                else v.putNull("tags");
                if (metaJson != null && !metaJson.trim().isEmpty()) v.put("meta_json", metaJson);
                else v.putNull("meta_json");
                db.update("diary_event", v, "id = ?", new String[]{String.valueOf(id)});
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary update failed: " + t);
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
        ScheduleConfig cfg = ScheduleConfig.fromPrefs(ctx);
        for (DueStatus d : computeDueStatuses(entries, odo, now, outC, cfg)) {
            if (d.overdue) return true;
        }
        return false;
    }

    /** Reads lifetime Total Cost of Ownership (charging + diary) and tracked distance off-thread. */
    public static TcoSummary readTcoSummary(Context ctx) {
        if (ctx == null) return TcoSummary.compute(0, 0, 0);
        double chargeCost = 0, diaryCost = 0, trackedKm = 0;
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            try (Cursor c = db.rawQuery(
                    "SELECT COALESCE(SUM(cost), 0) FROM charge_session WHERE cost > 0", null)) {
                if (c.moveToFirst()) chargeCost = c.getDouble(0);
            }
            try (Cursor c = db.rawQuery(
                    "SELECT COALESCE(SUM(cost), 0) FROM diary_event WHERE parent_id IS NULL AND cost > 0", null)) {
                if (c.moveToFirst()) diaryCost = c.getDouble(0);
            }
            double firstOdo = -1, lastOdo = -1;
            try (Cursor c = db.rawQuery(
                    "SELECT MIN(CASE WHEN first_odo_km > 0 THEN first_odo_km END), "
                    + "MAX(CASE WHEN last_odo_km > 0 THEN last_odo_km END) FROM daily_stat", null)) {
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) firstOdo = c.getDouble(0);
                    if (!c.isNull(1)) lastOdo = c.getDouble(1);
                }
            }
            Double liveOdo = latestOdometerKm(ctx);
            if (liveOdo != null && liveOdo > lastOdo) lastOdo = liveOdo;
            if (firstOdo > 0 && lastOdo > firstOdo) {
                trackedKm = lastOdo - firstOdo;
            } else if (liveOdo != null && liveOdo > 0) {
                trackedKm = liveOdo;
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary tco read failed: " + t);
        }
        return TcoSummary.compute(chargeCost, diaryCost, trackedKm);
    }

    /** Reads 60-day HV traction battery longevity summary off-thread. */
    public static HvHealthSummary readHvHealthSummary(Context ctx, long nowMs) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        java.time.LocalDate start = today.minusDays(59);
        java.time.LocalDate endExcl = today.plusDays(1);
        if (ctx == null) {
            return new HvHealthSummary(null, new BatteryHeat.Stats(),
                new ChargePeriodSummary(), start);
        }
        long fromMs = start.atStartOfDay(zone).toInstant().toEpochMilli();
        long toMs = endExcl.atStartOfDay(zone).toInstant().toEpochMilli();
        Double soh = null;
        BatteryHeat.Stats heat = new BatteryHeat.Stats();
        ChargePeriodSummary charges = new ChargePeriodSummary();
        try {
            soh = BatteryHealth.latestBefore(ctx, nowMs);
            heat = BatteryHeat.read(ctx, start, endExcl);
            List<SocHistory.DayRange> ranges = SocHistory.dailyRanges(ctx, start, today);
            List<ChargeSession.Summary> all = ChargeSession.readLog(ctx);
            charges = ChargePeriodSummary.of(all, fromMs, toMs, nowMs, ranges);
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary hv health read failed: " + t);
        }
        return new HvHealthSummary(soh, heat, charges, start);
    }

    // ---- Pure Due-Status Calculation Engine ----

    /** Computes due status using default Geely Brasil EX2 intervals. Pure & unit-tested. */
    public static List<DueStatus> computeDueStatuses(List<Entry> entries, Double currentOdoKm,
                                                     long nowMs, Double currentOutTempC) {
        return computeDueStatuses(entries, currentOdoKm, nowMs, currentOutTempC, ScheduleConfig.defaults());
    }

    /** Computes due status for every enabled tracked category from the given entries,
     * current odometer, wall time, outside temp, and ScheduleConfig. Pure & unit-tested. */
    public static List<DueStatus> computeDueStatuses(List<Entry> entries, Double currentOdoKm,
                                                     long nowMs, Double currentOutTempC,
                                                     ScheduleConfig config) {
        ScheduleConfig cfg = config != null ? config : ScheduleConfig.defaults();
        Map<Category, Entry> latestByCat = new HashMap<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (e.parentId != null) continue;
                // A coolant top-up logs fluid added without resetting the 80k / 48 mo flush clock.
                if (e.category == Category.COOLANT && isTopUp(e.metaJson)) continue;
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
            Double kmLimit = cfg.kmInterval(cat);
            Integer dayLimit = cfg.dayInterval(cat);
            if (kmLimit == null && dayLimit == null) continue; // disabled (Off) in Settings

            Entry last = latestByCat.get(cat);
            if (last == null) {
                // On a new car that has not yet reached its first factory km milestone
                // (e.g. 7,077 km < 20,000 km Scheduled Service or 10,000 km Tire Rotation),
                // count down cleanly from 0 km without ever firing a false overdue alert.
                if (currentOdoKm != null && currentOdoKm > 0 && kmLimit != null
                        && currentOdoKm < kmLimit) {
                    double kmRem = kmLimit - currentOdoKm;
                    boolean soonKm = kmRem <= kmLimit * 0.10;
                    out.add(new DueStatus(cat, null, currentOdoKm, kmRem, null, null,
                        false, soonKm, false));
                } else {
                    out.add(new DueStatus(cat, null, null, null, null, null, false, false, false));
                }
                continue;
            }
            Double kmSince = (currentOdoKm != null && last.odoKm != null && currentOdoKm >= last.odoKm)
                ? (currentOdoKm - last.odoKm) : null;
            Double kmRem = (kmLimit != null && kmSince != null)
                ? (kmLimit - kmSince) : null;

            int daysSince = (int) Math.max(0, Math.floor((nowMs - last.tsMs) / MS_PER_DAY));
            Integer daysRem = dayLimit != null ? (dayLimit - daysSince) : null;

            boolean overdueKm = kmRem != null && kmRem <= 0.0;
            boolean overdueTime = daysRem != null && daysRem <= 0;
            boolean overdue = overdueKm || overdueTime;

            boolean soonKm = !overdue && kmLimit != null && kmRem != null
                && kmRem <= kmLimit * 0.10;
            boolean soonTime = !overdue && dayLimit != null && daysRem != null
                && daysRem <= Math.max(3, (int) Math.round(dayLimit * 0.10));

            boolean coldSnap = cfg.coldSnapEnabled && cat == Category.TIRE_PRESSURE && !overdue
                && currentOutTempC != null && last.outsideTempC != null
                && (last.outsideTempC - currentOutTempC) >= COLD_SNAP_DROP_C;

            out.add(new DueStatus(cat, last, kmSince, kmRem, daysSince, daysRem,
                overdue, soonKm || soonTime || coldSnap, coldSnap));
        }
        return out;
    }

    /** Magic-sort key: estimated days until due, combining both time and distance
     * (using the car's driving pace avgDailyKm). Negative = overdue; +Inf = untracked. Pure. */
    public static double estimatedDaysUntilDue(DueStatus d, double avgDailyKm) {
        if (d == null) return Double.POSITIVE_INFINITY;
        double pace = Math.max(1.0, avgDailyKm);
        double est = Double.POSITIVE_INFINITY;
        if (d.daysRemaining != null) {
            est = Math.min(est, d.daysRemaining.doubleValue());
        }
        if (d.kmRemaining != null) {
            est = Math.min(est, d.kmRemaining / pace);
        }
        if (d.coldSnapNudge && est > 2.0) est = 2.0;
        return est;
    }

    /** Picks the single most urgent DueStatus using Magic Sort (overdue first, then closest
     * upcoming by time or distance across tracked & core factory milestones). Pure. */
    public static DueStatus mostUrgent(List<DueStatus> list) {
        return mostUrgent(list, DEFAULT_AVG_DAILY_KM);
    }

    public static DueStatus mostUrgent(List<DueStatus> list, double avgDailyKm) {
        if (list == null || list.isEmpty()) return null;
        DueStatus best = null;
        double bestDays = Double.POSITIVE_INFINITY;
        for (DueStatus d : list) {
            if (!isScheduleCandidate(d)) continue;
            double est = estimatedDaysUntilDue(d, avgDailyKm);
            if (Double.isInfinite(est)) continue;
            if (best == null || est < bestDays) {
                best = d;
                bestDays = est;
            }
        }
        return best;
    }

    /** Selects up to {@code limit} rows for the Care Schedule card in Magic Sort order
     * (closest upcoming by time or distance first; untracked core items last). Pure. */
    public static List<DueStatus> scheduleRows(List<DueStatus> all) {
        return scheduleRows(all, DEFAULT_AVG_DAILY_KM, 4);
    }

    public static List<DueStatus> scheduleRows(List<DueStatus> all, double avgDailyKm, int limit) {
        if (all == null || all.isEmpty()) return Collections.emptyList();
        List<DueStatus> candidates = new ArrayList<>();
        for (DueStatus d : all) {
            if (isScheduleCandidate(d)) candidates.add(d);
        }
        candidates.sort((a, b) -> {
            double da = estimatedDaysUntilDue(a, avgDailyKm);
            double db = estimatedDaysUntilDue(b, avgDailyKm);
            int cmp = Double.compare(da, db);
            if (cmp != 0) return cmp;
            return Integer.compare(coreRank(a.category), coreRank(b.category));
        });
        if (candidates.size() <= limit) return candidates;
        return new ArrayList<>(candidates.subList(0, limit));
    }

    private static boolean isScheduleCandidate(DueStatus d) {
        return d.lastEntry != null || d.overdue || d.dueSoon || coreRank(d.category) < SCHEDULE_CORE.length;
    }

    private static int coreRank(Category c) {
        for (int i = 0; i < SCHEDULE_CORE.length; i++) {
            if (SCHEDULE_CORE[i] == c) return i;
        }
        return SCHEDULE_CORE.length;
    }

    private static void considerLatest(Map<Category, Entry> map, Category cat, Entry e) {
        if (cat == null) return;
        Entry prev = map.get(cat);
        if (prev == null || e.tsMs > prev.tsMs) map.put(cat, e);
    }

    // ---- Lightweight Pure meta_json Helpers (no org.json dependency in JVM unit tests) ----

    public static String buildTirePressureMeta(int fl, int fr, int rl, int rr, Boolean cold) {
        if (cold == null) {
            return String.format(Locale.US, "{\"psi\":[%d,%d,%d,%d]}", fl, fr, rl, rr);
        }
        return String.format(Locale.US,
            "{\"psi\":[%d,%d,%d,%d],\"cold\":%s}", fl, fr, rl, rr, cold ? "true" : "false");
    }

    /** Pure inference for whether tires are cold (parked >= 3 h) or warm (recent trip < 3 h).
     * Returns null when there is no trip/park evidence — never guesses. */
    public static Boolean inferColdTires(long nowMs, Long lastTripEndMs, Long activeParkStartMs) {
        if (activeParkStartMs != null && activeParkStartMs > 0 && nowMs >= activeParkStartMs) {
            if (nowMs - activeParkStartMs >= COLD_TIRE_PARK_MS) return Boolean.TRUE;
            if (lastTripEndMs != null && lastTripEndMs > 0 && nowMs >= lastTripEndMs
                    && nowMs - lastTripEndMs < COLD_TIRE_PARK_MS) {
                return Boolean.FALSE;
            }
            return null;
        }
        if (lastTripEndMs != null && lastTripEndMs > 0 && nowMs >= lastTripEndMs) {
            return (nowMs - lastTripEndMs >= COLD_TIRE_PARK_MS) ? Boolean.TRUE : Boolean.FALSE;
        }
        return null;
    }

    /** Infers cold/warm tire state for an entry logged right now from CarDb trip & park history.
     * Call off the UI thread. Returns null if no reliable history is available. */
    public static Boolean inferColdTires(Context ctx, long nowMs) {
        if (ctx == null) return null;
        Long lastTripEnd = null;
        Long activeParkStart = null;
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            try (Cursor c = db.rawQuery(
                    "SELECT MAX(COALESCE(end_ms, start_ms)) FROM trip WHERE start_ms <= ?",
                    new String[]{String.valueOf(nowMs)})) {
                if (c.moveToFirst() && !c.isNull(0)) lastTripEnd = c.getLong(0);
            }
            try (Cursor c = db.rawQuery(
                    "SELECT start_ms FROM park_session WHERE end_ms IS NULL AND start_ms <= ? "
                    + "ORDER BY start_ms DESC LIMIT 1",
                    new String[]{String.valueOf(nowMs)})) {
                if (c.moveToFirst() && !c.isNull(0)) activeParkStart = c.getLong(0);
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary cold-tire infer failed: " + t);
        }
        return inferColdTires(nowMs, lastTripEnd, activeParkStart);
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

    public static String buildCoolantMeta(boolean topUp) {
        return topUp ? "{\"topUp\":true}" : "{\"topUp\":false}";
    }

    public static boolean isTopUp(String metaJson) {
        return Boolean.TRUE.equals(parseTopUp(metaJson));
    }

    public static Boolean parseTopUp(String metaJson) {
        if (metaJson == null) return null;
        if (metaJson.contains("\"topUp\":true")) return Boolean.TRUE;
        if (metaJson.contains("\"topUp\":false")) return Boolean.FALSE;
        return null;
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

    /** Estimates the car's recent driving pace (km/day over the last 60 days from daily_stat)
     * for Magic Sort comparison between distance-based and time-based care items.
     * Falls back to {@link #DEFAULT_AVG_DAILY_KM} (40 km/day) when history is short. */
    public static double avgDailyKm(Context ctx, long nowMs) {
        if (ctx == null) return DEFAULT_AVG_DAILY_KM;
        try {
            java.time.LocalDate today = java.time.Instant.ofEpochMilli(nowMs)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            String sinceIso = today.minusDays(60).toString();
            try (Cursor c = CarDb.get(ctx).db().rawQuery(
                    "SELECT MIN(date), SUM(CASE WHEN last_odo_km > first_odo_km "
                    + "THEN last_odo_km - first_odo_km ELSE 0 END), COUNT(*) "
                    + "FROM daily_stat WHERE date >= ?", new String[]{sinceIso})) {
                if (c.moveToFirst() && !c.isNull(0) && c.getInt(2) >= 5) {
                    java.time.LocalDate first = java.time.LocalDate.parse(c.getString(0));
                    long spanDays = Math.max(7L, java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1L);
                    double totalKm = c.getDouble(1);
                    if (totalKm > 0) {
                        return Math.max(15.0, Math.min(200.0, totalKm / (double) spanDays));
                    }
                }
            }
        } catch (Throwable ignored) {}
        return DEFAULT_AVG_DAILY_KM;
    }

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

    /** Notifies listeners (CarDiaryView, dock badge) that diary data or schedule settings changed. */
    public static void notifyChanged() {
        publishChanged();
    }

    private static void publishChanged() {
        CarActor a = CarActor.get();
        if (a != null) {
            a.injectReading(EVENT_CHANGED, CarActor.Reading.ok(System.currentTimeMillis()));
        }
    }
}
