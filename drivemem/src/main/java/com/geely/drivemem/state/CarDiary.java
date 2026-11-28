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

    /** One worker thread for diary reads and clip work. Diary queries are too
     * heavy for the hub (car) thread and for the single DB writer thread. */
    private static final java.util.concurrent.ExecutorService IO =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "diary-io");
            t.setDaemon(true);
            return t;
        });

    /** Runs a diary read (or other slow diary work) off the hub and UI threads. */
    public static void runAsync(Runnable r) {
        if (r == null) return;
        IO.execute(() -> {
            try { r.run(); }
            catch (Throwable t) { Log.w(CarAccess.TAG, "diary async failed: " + t); }
        });
    }

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
        ROAD(R.string.diary_grp_road, R.drawable.ic_diary_road),
        BUREAUCRACY(R.string.diary_grp_bureaucracy, R.drawable.ic_diary_bureaucracy),
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
        // By time only. It had a hidden 1,500 km rule too, which no setting showed:
        // the schedule said "in 593 km" for an item set to 30 days.
        TIRE_PRESSURE(Group.TIRES, KIND_CARE, R.string.diary_cat_tire_pressure, null, 1, true),
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
        LIGHTS_FUSES(Group.ELECTRICAL, KIND_CARE, R.string.diary_cat_lights_fuses, null, null, true),
        // A thing to record, with no factory interval.
        KEY_BATTERY(Group.ELECTRICAL, KIND_CARE, R.string.diary_cat_key_battery, null, null, true),

        // Cosmetic / Aesthetics & Accessories
        WASH_DETAIL(Group.COSMETIC, KIND_CARE, R.string.diary_cat_wash_detail, null, null, true),
        // Not an electrical part: with the washer fluid, the other wiper item.
        WIPERS(Group.COSMETIC, KIND_CARE, R.string.diary_cat_wipers, null, 12, true),
        WASHER_FLUID(Group.COSMETIC, KIND_CARE, R.string.diary_cat_washer_fluid, null, null, true),
        POLISHING(Group.COSMETIC, KIND_CARE, R.string.diary_cat_polishing, null, null, true),
        WAX_CERAMIC(Group.COSMETIC, KIND_CARE, R.string.diary_cat_wax_ceramic, null, null, true),
        PAINT_DENT(Group.COSMETIC, KIND_CARE, R.string.diary_cat_paint_dent, null, null, true),
        ACCESSORIES(Group.COSMETIC, KIND_CARE, R.string.diary_cat_accessories, null, null, true),

        // On the road: what it costs to drive and stop somewhere (monthly parking prorates)
        PARKING(Group.ROAD, KIND_CARE, R.string.diary_cat_parking, null, null, true, 0),
        TOLL(Group.ROAD, KIND_CARE, R.string.diary_cat_toll, null, null, true, 0),
        MONTHLY_PARKING(Group.ROAD, KIND_CARE, R.string.diary_cat_monthly_parking, null, 1, true, 30),
        TRAFFIC_TICKET(Group.ROAD, KIND_CARE, R.string.diary_cat_traffic_ticket, null, null, true, 0),

        // Owning the car: fixed costs, the same with the car in the garage (prorated in TCO R$/km)
        INSURANCE(Group.BUREAUCRACY, KIND_CARE, R.string.diary_cat_insurance, null, 12, true, 365),
        TAX_LICENSE(Group.BUREAUCRACY, KIND_CARE, R.string.diary_cat_tax_license, null, 12, true, 365),

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
        /** Coverage span in days for TCO proration (0 = one-off, 30 = monthly, 365 = annual). */
        public final int coverageDays;

        Category(Group group, String kind, int labelRes, Double kmInterval,
                 Integer monthInterval, boolean userPickable) {
            this(group, kind, labelRes, kmInterval, monthInterval, userPickable, 0);
        }

        Category(Group group, String kind, int labelRes, Double kmInterval,
                 Integer monthInterval, boolean userPickable, int coverageDays) {
            this.group = group;
            this.kind = kind;
            this.labelRes = labelRes;
            this.kmInterval = kmInterval;
            this.monthInterval = monthInterval;
            this.userPickable = userPickable;
            this.coverageDays = coverageDays;
        }

        public boolean hasInterval() { return kmInterval != null || monthInterval != null; }
        public boolean isBureaucracy() { return group == Group.BUREAUCRACY; }
        public boolean isRoad() { return group == Group.ROAD; }

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
    /** Shorter intervals (tire pressure, a monthly wash) do not count from the pick-up date. */
    public static final int PICKUP_MIN_INTERVAL_DAYS = 150;
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
        /** When it last happened: the newest recurrence child's ts_ms, or
         * {@link #tsMs} when there is none. {@link #tsMs} stays the first-seen date. */
        public final long lastOccurrenceMs;
        /** Odometer looked up from the car's history for the entry's date, set by
         * {@link #readEntries} only when {@link #odoKm} is missing or above the current
         * odometer. Used for km-based reminders; never shown as the entry's odometer. */
        public final Double estOdoKm;

        public Entry(long id, String uuid, long tsMs, String kind, Category category,
                     String status, Double odoKm, Double outsideTempC, Double lat, Double lon,
                     Double cost, String title, String note, String tags, String clipName,
                     Long parentId, String metaJson, int occurrences) {
            this(id, uuid, tsMs, kind, category, status, odoKm, outsideTempC, lat, lon,
                cost, title, note, tags, clipName, parentId, metaJson, occurrences, tsMs, null);
        }

        public Entry(long id, String uuid, long tsMs, String kind, Category category,
                     String status, Double odoKm, Double outsideTempC, Double lat, Double lon,
                     Double cost, String title, String note, String tags, String clipName,
                     Long parentId, String metaJson, int occurrences,
                     long lastOccurrenceMs, Double estOdoKm) {
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
            this.lastOccurrenceMs = Math.max(tsMs, lastOccurrenceMs);
            this.estOdoKm = estOdoKm;
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
        /** The owner gave this item a schedule in Settings: it is shown in the
         * schedule card also before its first entry. */
        public boolean ownerScheduled;

        /** The "postponed" entry that sets this due point, or null. */
        public Entry postponedBy;

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
        /** Schedules the owner set for items with no setting of their own
         * (polishing every 30 days), and overrides of the built-in ones
         * (alignment, balancing, tires): {km, months, days}; all 0 = Off. */
        public Map<Category, int[]> extras = Collections.emptyMap();
        /** When the owner got the car (0 = not set) and the odometer then.
         * An item with no entry yet counts from here. */
        public long pickupMs = 0L;
        public double pickupKm = 0.0;

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
            ScheduleConfig cfg = new ScheduleConfig(
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
            cfg.extras = parseExtras(Prefs.getDiaryExtraSchedules(ctx));
            long day = Prefs.getDiaryPickupDay(ctx);
            if (day >= 0) {
                cfg.pickupMs = java.time.LocalDate.ofEpochDay(day)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            cfg.pickupKm = Prefs.getDiaryPickupKm(ctx);
            return cfg;
        }

        /** Items with a setting of their own: an extra never applies to them. */
        public static boolean hasOwnSetting(Category cat) {
            switch (cat) {
                case TIRE_PRESSURE: case TIRE_ROTATION: case SERVICE: case CABIN_FILTER:
                case BRAKE_FLUID: case COOLANT: case GEAR_OIL: case WIPERS: case BATTERY_12V:
                    return true;
                default: return false;
            }
        }

        public Double kmInterval(Category cat) {
            switch (cat) {
                case TIRE_PRESSURE: return null;   // by days only
                case TIRE_ROTATION: return tireRotationKm > 0 ? (double) tireRotationKm : null;
                case SERVICE: return serviceKm > 0 ? (double) serviceKm : null;
                case CABIN_FILTER: return cabinFilterKm > 0 ? (double) cabinFilterKm : null;
                case BRAKE_FLUID: return brakeFluidKm > 0 ? (double) brakeFluidKm : null;
                case COOLANT: return coolantKm > 0 ? (double) coolantKm : null;
                case GEAR_OIL: return gearOilKm > 0 ? (double) gearOilKm : null;
                default: {
                    int[] x = extras.get(cat);
                    if (x != null) return x[0] > 0 ? (Double) (double) x[0] : null;
                    return cat.kmInterval;
                }
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
                default: {
                    int[] x = extras.get(cat);
                    if (x != null) {
                        if (x[2] > 0) return x[2];
                        return x[1] > 0 ? (Integer) (int) Math.round(x[1] * DAYS_PER_MONTH) : null;
                    }
                    return cat.monthInterval != null
                        ? (int) Math.round(cat.monthInterval * DAYS_PER_MONTH) : null;
                }
            }
        }

        public boolean isEnabled(Category cat) {
            return kmInterval(cat) != null || dayInterval(cat) != null;
        }
    }

    /** Pure: "POLISHING:0:0:30;TIRE_ALIGNMENT:10000:12:0" to a map. Unknown
     * names (an item a newer version has) and broken parts are skipped. */
    public static Map<Category, int[]> parseExtras(String text) {
        Map<Category, int[]> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String part : text.split(";")) {
            String[] f = part.trim().split(":");
            if (f.length != 4) continue;
            try {
                Category cat = Category.valueOf(f[0]);
                if (ScheduleConfig.hasOwnSetting(cat)) continue;
                out.put(cat, new int[]{Math.max(0, Integer.parseInt(f[1])),
                    Math.max(0, Integer.parseInt(f[2])), Math.max(0, Integer.parseInt(f[3]))});
            } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    public static String formatExtras(Map<Category, int[]> extras) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Category, int[]> e : extras.entrySet()) {
            int[] x = e.getValue();
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey().name()).append(':').append(x[0]).append(':').append(x[1]).append(':').append(x[2]);
        }
        return sb.toString();
    }

    /** Time-period filter for the Diary list and TCO bar. Pure & unit-tested. */
    public static final class PeriodFilter {
        public enum Kind { ALL, DAYS_30, DAYS_90, YEAR }

        public static final PeriodFilter ALL = new PeriodFilter(Kind.ALL, 0);
        public static final PeriodFilter DAYS_30 = new PeriodFilter(Kind.DAYS_30, 0);
        public static final PeriodFilter DAYS_90 = new PeriodFilter(Kind.DAYS_90, 0);

        public final Kind kind;
        public final int year;

        private PeriodFilter(Kind kind, int year) {
            this.kind = kind;
            this.year = year;
        }

        public static PeriodFilter ofYear(int year) { return new PeriodFilter(Kind.YEAR, year); }

        /** Returns [startMs, endMsExcl) in {@code zone}. For ALL, startMs = 0 and endMs = nowMs. */
        public long[] boundsMs(long nowMs, java.time.ZoneId zone) {
            java.time.LocalDate today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
            switch (kind) {
                case DAYS_30: {
                    long start = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli();
                    return new long[]{start, nowMs};
                }
                case DAYS_90: {
                    long start = today.minusDays(89).atStartOfDay(zone).toInstant().toEpochMilli();
                    return new long[]{start, nowMs};
                }
                case YEAR: {
                    long start = java.time.LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli();
                    long nextYr = java.time.LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli();
                    return new long[]{start, Math.min(nextYr, nowMs)};
                }
                default:
                    return new long[]{0L, nowMs};
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PeriodFilter)) return false;
            PeriodFilter p = (PeriodFilter) o;
            return kind == p.kind && year == p.year;
        }

        @Override
        public int hashCode() { return kind.ordinal() * 31 + year; }
    }

    /** Total Cost of Ownership in four buckets: Energy (charges, moving the car) + Maintenance
     * + Road (parking, tolls, tickets) + Bureaucracy (owning the car, prorated). */
    public static final class TcoSummary {
        public final double chargeCost;
        public final double maintenanceCost;
        public final double roadCost;
        public final double bureaucracyCost;
        /** Sum of the three diary buckets (all but the charges). */
        public final double diaryCost;
        public final double totalCost;
        public final double trackedKm;
        public final double costPerKm;        // -1 when trackedKm < 1 or totalCost <= 0
        public final double chargeShare;      // 0..1, -1 when totalCost <= 0
        public final double maintenanceShare; // 0..1, -1 when totalCost <= 0
        public final double roadShare;        // 0..1, -1 when totalCost <= 0
        public final double bureaucracyShare; // 0..1, -1 when totalCost <= 0
        /** True when at least one annual/monthly fixed cost was prorated over the tracked window. */
        public final boolean prorated;

        private TcoSummary(double chargeCost, double maintenanceCost, double roadCost,
                           double bureaucracyCost, double trackedKm, boolean prorated) {
            this.chargeCost = chargeCost;
            this.maintenanceCost = maintenanceCost;
            this.roadCost = roadCost;
            this.bureaucracyCost = bureaucracyCost;
            this.diaryCost = maintenanceCost + roadCost + bureaucracyCost;
            this.totalCost = chargeCost + diaryCost;
            this.trackedKm = trackedKm;
            boolean any = totalCost > 0;
            this.costPerKm = (any && trackedKm >= 1.0) ? (totalCost / trackedKm) : -1.0;
            this.chargeShare = any ? chargeCost / totalCost : -1.0;
            this.maintenanceShare = any ? maintenanceCost / totalCost : -1.0;
            this.roadShare = any ? roadCost / totalCost : -1.0;
            this.bureaucracyShare = any ? bureaucracyCost / totalCost : -1.0;
            this.prorated = prorated;
        }

        public static TcoSummary compute(double chargeCost, double diaryCost, double trackedKm) {
            return compute(chargeCost, diaryCost, 0.0, 0.0, trackedKm, false);
        }

        public static TcoSummary compute(double chargeCost, double maintenanceCost, double roadCost,
                                         double bureaucracyCost, double trackedKm, boolean prorated) {
            return new TcoSummary(Math.max(0.0, chargeCost), Math.max(0.0, maintenanceCost),
                Math.max(0.0, roadCost), Math.max(0.0, bureaucracyCost),
                Math.max(0.0, trackedKm), prorated);
        }

        public boolean hasCosts() { return totalCost > 0.005; }

        /** Units the cost card shows its buckets in; a tap goes to the next one. */
        public static final int UNIT_MONEY = 0, UNIT_PER_KM = 1, UNIT_PCT = 2;

        /** The unit after this one. Cost per km is skipped when there is no distance. */
        public static int nextUnit(int unit, double km) {
            int n = (unit + 1) % 3;
            if (n == UNIT_PER_KM && !(km >= 1.0)) n = UNIT_PCT;
            return n;
        }

        /** The unit drawn: cost per km falls back to money when there is no distance. */
        public static int shownUnit(int unit, double km) {
            if (unit < UNIT_MONEY || unit > UNIT_PCT) return UNIT_MONEY;
            return unit == UNIT_PER_KM && !(km >= 1.0) ? UNIT_MONEY : unit;
        }
    }

    /** Computes an entry's cost contribution over [winStartMs, winEndMs).
     * Coverage-period entries (e.g. annual Insurance/IPVA = 365d, monthly parking = 30d)
     * are prorated by their overlap with the window so R$/km stays accurate instead of
     * spiking artificially on day 1. One-off entries count 100% when inside the window
     * (or unconditionally in ALL-time view). Pure & unit-tested. */
    public static double entryCostForWindow(Entry e, long winStartMs, long winEndMs, boolean allTime) {
        return entryCostForWindow(e, winStartMs, winEndMs, winStartMs, winEndMs, allTime);
    }

    /** Same, with two windows. A one-off cost is tested against the real period
     * [periodStartMs, periodEndInclMs], the bounds the list and the charge costs use.
     * A coverage cost is prorated over [winStartMs, winEndMs), which may start later
     * (the first tracked day), so the cost matches the km it is divided by. */
    public static double entryCostForWindow(Entry e, long periodStartMs, long periodEndInclMs,
                                            long winStartMs, long winEndMs, boolean allTime) {
        if (e == null || e.parentId != null || e.cost == null || e.cost <= 0) return 0.0;
        int covDays = e.category != null ? e.category.coverageDays : 0;
        if (covDays > 0) {
            if (winEndMs <= winStartMs) return 0.0;
            long covStart = e.tsMs;
            long covEnd = coverageEndMs(e);
            double spanMs = covEnd - covStart;
            long ovStart = Math.max(covStart, winStartMs);
            long ovEnd = Math.min(covEnd, winEndMs);
            double overlapMs = Math.max(0L, ovEnd - ovStart);
            // If logged today inside the active window, count at least 1 day so the entry is visible immediately
            if (e.tsMs >= winStartMs && e.tsMs <= winEndMs && overlapMs < MS_PER_DAY) {
                overlapMs = Math.min(MS_PER_DAY, winEndMs - winStartMs);
            }
            if (overlapMs <= 0) return 0.0;
            return e.cost * Math.min(1.0, overlapMs / spanMs);
        }
        if (allTime) return e.cost;
        return (e.tsMs >= periodStartMs && e.tsMs <= periodEndInclMs) ? e.cost : 0.0;
    }

    /** Last instant that belongs to a period. {@link PeriodFilter#boundsMs} returns an
     * exclusive end; a closed period (a past year ends at the next Jan 1 00:00) must not
     * take that instant in. A period that ends now keeps "now". Pure. */
    public static long inclusiveEndMs(long[] bounds, long nowMs) {
        return bounds[1] < nowMs ? bounds[1] - 1 : bounds[1];
    }

    /** {first, last} local day (yyyy-MM-dd, both inclusive) of a period, for daily_stat. Pure. */
    static String[] tcoDayRange(long[] bounds, long nowMs, java.time.ZoneId zone) {
        return new String[]{
            java.time.Instant.ofEpochMilli(bounds[0]).atZone(zone).toLocalDate().toString(),
            java.time.Instant.ofEpochMilli(inclusiveEndMs(bounds, nowMs)).atZone(zone).toLocalDate().toString()};
    }

    /** Km the car's history covers: 0 when there is no first reading or no distance
     * between first and last, so no cost/km is derived from the whole odometer. Pure. */
    static double trackedKmFrom(double firstOdo, double lastOdo, Double liveOdo) {
        double last = (liveOdo != null && liveOdo > lastOdo) ? liveOdo : lastOdo;
        return (firstOdo > 0 && last > firstOdo) ? (last - firstOdo) : 0.0;
    }

    /** Start of the window fixed costs are prorated over: the first tracked day when it
     * is inside the period (always for ALL-time), else the period start; the last 30 days
     * when neither is known. Only for proration, never to decide what is in the period. Pure. */
    static long prorationStartMs(long periodStartMs, long winEndMs, String firstDateIso,
                                 boolean allTime, java.time.ZoneId zone) {
        long start = periodStartMs;
        if (firstDateIso != null) {
            try {
                long firstTrackedMs = java.time.LocalDate.parse(firstDateIso)
                    .atStartOfDay(zone).toInstant().toEpochMilli();
                if (allTime || firstTrackedMs > start) start = firstTrackedMs;
            } catch (Throwable ignored) {}
        }
        if (start <= 0) start = Math.max(0L, winEndMs - (long) (30 * MS_PER_DAY));
        return start;
    }

    static final String TCO_CHARGE_COST_SQL =
        "SELECT COALESCE(SUM(cost), 0) FROM charge_session "
        + "WHERE cost > 0 AND start_ms >= ? AND start_ms <= ?";
    static final String TCO_TOLL_COST_SQL =
        "SELECT COALESCE(SUM(cost), 0) FROM toll_passage "
        + "WHERE dismissed = 0 AND cost > 0 AND ts_ms >= ? AND ts_ms <= ?";
    /** Parking paid at parked stops and Valet stays (state.ParkingCost), in road. */
    static final String TCO_PARKING_COST_SQL =
        "SELECT COALESCE(SUM(cost), 0) FROM parking_cost "
        + "WHERE cost > 0 AND start_ms >= ? AND start_ms <= ?";
    static final String TCO_ODO_SQL =
        "SELECT MIN(CASE WHEN first_odo_km > 0 THEN first_odo_km END), "
        + "MAX(CASE WHEN last_odo_km > 0 THEN last_odo_km END), "
        + "MIN(CASE WHEN first_odo_km > 0 THEN date END) FROM daily_stat";
    static final String TCO_ODO_PERIOD_SQL = TCO_ODO_SQL + " WHERE date >= ? AND date <= ?";

    /** Pure TCO aggregation over a window with automatic annual/monthly proration. */
    public static TcoSummary computeTco(double chargeCost, List<Entry> entries, double trackedKm,
                                        long winStartMs, long winEndMs, boolean allTime) {
        return computeTco(chargeCost, entries, trackedKm, winStartMs, winEndMs,
            winStartMs, winEndMs, allTime);
    }

    /** Same, with the period bounds (one-off costs) apart from the proration window. */
    public static TcoSummary computeTco(double chargeCost, List<Entry> entries, double trackedKm,
                                        long periodStartMs, long periodEndInclMs,
                                        long winStartMs, long winEndMs, boolean allTime) {
        return computeTco(chargeCost, 0.0, entries, trackedKm, periodStartMs, periodEndInclMs,
            winStartMs, winEndMs, allTime);
    }

    /** Same, plus the automatic road costs (toll passages and parking_cost
     * rows) folded into {@code road}, without cluttering Car Diary's manual
     * event list with every toll crossing or parked stop. */
    public static TcoSummary computeTco(double chargeCost, double autoTollCost, List<Entry> entries,
                                        double trackedKm, long periodStartMs, long periodEndInclMs,
                                        long winStartMs, long winEndMs, boolean allTime) {
        double maint = 0.0, road = Math.max(0.0, autoTollCost), bureau = 0.0;
        boolean prorated = false;
        if (entries != null) {
            for (Entry e : entries) {
                double contrib = entryCostForWindow(e, periodStartMs, periodEndInclMs,
                    winStartMs, winEndMs, allTime);
                if (contrib <= 0) continue;
                if (e.category != null && e.category.coverageDays > 0 && contrib < e.cost - 0.01) {
                    prorated = true;
                }
                if (e.category != null && e.category.isBureaucracy()) bureau += contrib;
                else if (e.category != null && e.category.isRoad()) road += contrib;
                else maint += contrib;
            }
        }
        return TcoSummary.compute(chargeCost, maint, road, bureau, trackedKm, prorated);
    }

    /** Filters entries by both optional Group and PeriodFilter. Pure & unit-tested. */
    public static List<Entry> filterEntries(List<Entry> all, Group group, PeriodFilter period,
                                            long nowMs, java.time.ZoneId zone) {
        if (all == null || all.isEmpty()) return Collections.emptyList();
        boolean filterPeriod = period != null && period.kind != PeriodFilter.Kind.ALL;
        long[] bounds = filterPeriod ? period.boundsMs(nowMs, zone) : null;
        long endIncl = filterPeriod ? inclusiveEndMs(bounds, nowMs) : 0L;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (group != null && (e.category == null || e.category.group != group)) continue;
            if (bounds != null) {
                if (e.tsMs < bounds[0] || e.tsMs > endIncl) continue;
            }
            out.add(e);
        }
        return out;
    }

    /** Descending list of years available for the Diary period picker (always includes current year). */
    public static List<Integer> availableYears(List<Entry> entries, long nowMs, java.time.ZoneId zone) {
        int curYear = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).getYear();
        Set<Integer> yrs = new HashSet<>();
        yrs.add(curYear);
        if (entries != null) {
            for (Entry e : entries) {
                int y = java.time.Instant.ofEpochMilli(e.tsMs).atZone(zone).getYear();
                if (y >= 2020 && y <= curYear + 1) yrs.add(y);
            }
        }
        List<Integer> list = new ArrayList<>(yrs);
        list.sort(Collections.reverseOrder());
        return list;
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
    /** Same prefs file as util/Prefs; the queue is one string, one jolt per line. */
    private static final String PREFS_FILE = "drivemem";
    private static final String KEY_PENDING_JOLTS = "diary_pending_jolts";
    /** Set by {@link #ensureSubscribed}; without it (unit tests) the queue is memory only. */
    private static volatile Context appCtx;
    private static boolean joltsLoaded;

    /** Loads the saved queue once, as soon as there is a Context. Caller holds the class lock. */
    private static void loadJoltsLocked() {
        Context app = appCtx;
        if (joltsLoaded || app == null) return;
        joltsLoaded = true;
        try {
            String raw = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_JOLTS, null);
            // Anything queued before the load is newer than what was saved.
            for (PendingJolt j : decodeJolts(raw)) {
                if (pendingJolts.size() >= MAX_PENDING_JOLTS) break;
                pendingJolts.add(j);
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary jolts load failed: " + t);
        }
    }

    private static void saveJoltsLocked() {
        Context app = appCtx;
        if (app == null) return;
        try {
            app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_JOLTS, encodeJolts(pendingJolts)).apply();
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary jolts save failed: " + t);
        }
    }

    /** One line per jolt, fields split by ';', an empty field for an unknown value. Pure. */
    static String encodeJolts(List<PendingJolt> jolts) {
        StringBuilder sb = new StringBuilder();
        if (jolts == null) return "";
        for (PendingJolt j : jolts) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(j.id).append(';').append(j.tsMs)
                .append(';').append(j.peakZG).append(';').append(j.peakXG).append(';').append(j.peakYG)
                .append(';').append(j.rmsZG).append(';').append(j.peakJerkZGs)
                .append(';').append(j.speedKmh == null ? "" : j.speedKmh.toString())
                .append(';').append(j.odoKm == null ? "" : j.odoKm.toString())
                .append(';').append(j.lat == null ? "" : j.lat.toString())
                .append(';').append(j.lon == null ? "" : j.lon.toString());
        }
        return sb.toString();
    }

    /** Reverse of {@link #encodeJolts}; a damaged line is skipped. Pure. */
    static List<PendingJolt> decodeJolts(String raw) {
        List<PendingJolt> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String[] f = line.split(";", -1);
            if (f.length != 11 || f[0].isEmpty()) continue;
            try {
                out.add(new PendingJolt(f[0], Long.parseLong(f[1]),
                    Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4]),
                    Float.parseFloat(f[5]), Float.parseFloat(f[6]),
                    f[7].isEmpty() ? null : Float.valueOf(f[7]),
                    f[8].isEmpty() ? null : Double.valueOf(f[8]),
                    f[9].isEmpty() ? null : Double.valueOf(f[9]),
                    f[10].isEmpty() ? null : Double.valueOf(f[10])));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

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
        loadJoltsLocked();
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
        saveJoltsLocked();
        publishChanged();
        return j;
    }

    public static synchronized List<PendingJolt> pendingJolts(long nowMs) {
        loadJoltsLocked();
        pruneExpiredJoltsLocked(nowMs);
        return new ArrayList<>(pendingJolts);
    }

    public static List<PendingJolt> pendingJolts() {
        return pendingJolts(System.currentTimeMillis());
    }

    public static synchronized void dismissPendingJolt(String id) {
        if (id == null) return;
        loadJoltsLocked();
        boolean removed = pendingJolts.removeIf(j -> id.equals(j.id));
        if (removed) {
            saveJoltsLocked();
            publishChanged();
        }
    }

    static synchronized void clearPendingJoltsForTest() {
        pendingJolts.clear();
    }

    private static void pruneExpiredJoltsLocked(long nowMs) {
        if (pendingJolts.removeIf(j -> nowMs - j.tsMs > JOLT_TTL_MS)) saveJoltsLocked();
    }

    // ---- Auto-subscriptions (TireWarning -> TPMS_AUTO) ----

    private static volatile boolean subscribed;
    private static volatile int lastTireWarn = 0;

    /** Idempotent; called once from DriveMemApplication.onCreate(). */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (ctx == null) return;
        if (appCtx == null) appCtx = ctx.getApplicationContext();
        if (subscribed) return;
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

    /** Counts the open auto tire entries. One open entry is enough: the in-memory
     * edge detector starts from 0 on every app start, so the same warning would
     * otherwise be logged again each time. */
    static final String OPEN_TIRE_AUTO_SQL =
        "SELECT COUNT(*) FROM diary_event WHERE category = 'TPMS_AUTO' "
        + "AND status = 'OPEN' AND parent_id IS NULL";

    /** Called on the hub thread: only reads the hub cache here; the database check
     * and the insert run on CarDb's writer thread. */
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
        final String note = wheels.length() > 0 ? "iTPMS: " + wheels : "iTPMS";
        final long now = System.currentTimeMillis();
        final Double outC = latestOutsideTempC();
        final LocationHub.Fix fix = latestLocation();
        final Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                try (Cursor c = db.rawQuery(OPEN_TIRE_AUTO_SQL, null)) {
                    if (c.moveToFirst() && c.getLong(0) > 0) return;
                }
                insertNow(app, UUID.randomUUID().toString(), Category.TPMS_AUTO, STATUS_OPEN, now,
                    latestOdometerKm(app), outC,
                    fix != null ? fix.lat : null, fix != null ? fix.lon : null,
                    null, null, note, null, null, null, null);
                publishChanged();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary tire auto entry failed: " + t);
            }
        });
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
                insertNow(app, uuid, cat, st, tsMs, odoKm, outsideTempC, lat, lon,
                    cost, title, note, tags, clipName, parentId, metaJson);
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary insert failed: " + t);
            }
        });
    }

    /** The row insert itself. Runs on CarDb's writer thread. Returns the row id. */
    private static long insertNow(Context app, String uuid, Category cat, String st, long tsMs,
                                  Double odoKm, Double outsideTempC, Double lat, Double lon,
                                  Double cost, String title, String note, String tags,
                                  String clipName, Long parentId, String metaJson) {
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
        return CarDb.get(app).db().insert("diary_event", null, v);
    }

    /** Inserts an incident now, on CarDb's writer thread. With captureClip, the
     * lossless 5-second dashcam clip is cut afterwards on the diary worker thread
     * (disk listing + MP4 remux are too slow for the writer thread) and attached
     * to the row with a short update. */
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
                long rowId = insertNow(app, uuid, Category.INCIDENT, st, tsMs, odoKm, outsideTempC,
                    finalLat, finalLon, cost, title, note, tags, null, null, metaJson);
                if (pendingJoltId != null) dismissPendingJolt(pendingJoltId);
                else publishChanged();
                if (onDone != null) onDone.run();

                if (captureClip && rowId > 0) {
                    runAsync(() -> {
                        // The dashcam segment covering tsMs is usually still RECORDING
                        // (.mp4.tmp): then wait for it to close and attach the clip later.
                        if (tryAttachClip(app, rowId, tsMs, uuid) == CLIP_RETRY) {
                            scheduleDelayedClipAttach(app, rowId, tsMs, uuid);
                        }
                    });
                }
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary incident insert failed: " + t);
            }
        });
    }

    private static final int CLIP_ATTACHED = 1, CLIP_RETRY = 0, CLIP_NONE = -1;

    /** Cuts the 5-second clip and posts the clip_name update to the writer thread.
     * CLIP_RETRY: no closed clip covers tsMs yet, but a segment that does is still
     * recording. Slow (disk listing + remux): never on the hub or DB writer thread. */
    private static int tryAttachClip(Context app, long rowId, long tsMs, String uuid) {
        String clip = DiaryClipTrimmer.captureFiveSecondClip(app, tsMs, uuid);
        if (clip == null) {
            if (DiaryClipTrimmer.hasLiveSegmentCovering(app, tsMs)) return CLIP_RETRY;
            // The segment may have closed between the two listings: look once more.
            clip = DiaryClipTrimmer.captureFiveSecondClip(app, tsMs, uuid);
            if (clip == null) return CLIP_NONE;
        }
        final String name = clip;
        CarDb.get(app).write(() -> {
            try {
                ContentValues upd = new ContentValues();
                upd.put("clip_name", name);
                int rows = CarDb.get(app).db().update("diary_event", upd, "id = ?",
                    new String[]{String.valueOf(rowId)});
                // Entry deleted while the clip was being cut: do not leave the file behind.
                if (rows == 0) DiaryClipTrimmer.deleteClip(app, name);
                else publishChanged();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary clip attach failed: " + t);
            }
        });
        return CLIP_ATTACHED;
    }

    /** Polls up to 6 minutes for an active dashcam segment covering tsMs to close,
     * then extracts the 5-second clip and updates diary_event.clip_name. Own thread:
     * the waits must not hold up the diary worker. */
    private static void scheduleDelayedClipAttach(Context app, long rowId, long tsMs, String uuid) {
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 24; attempt++) {
                try { Thread.sleep(15_000L); } catch (InterruptedException e) { return; }
                try {
                    if (tryAttachClip(app, rowId, tsMs, uuid) != CLIP_RETRY) return;
                } catch (Throwable e) {
                    Log.w(CarAccess.TAG, "diary delayed clip attach failed: " + e);
                    return;
                }
            }
        }, "diary-clip-retry");
        t.setDaemon(true);
        t.start();
    }

    /** Appends a '+1 happened again' recurrence child row linked to parentId. The
     * parent keeps its own ts_ms and odo_km (first seen; its cost stays in its TCO
     * period). The last occurrence is read back as {@link Entry#lastOccurrenceMs}. */
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
            Map<Long, Long> childLast = new HashMap<>();
            try (Cursor cc = db.rawQuery(CHILD_STATS_SQL, null)) {
                while (cc.moveToNext()) {
                    childCounts.put(cc.getLong(0), cc.getInt(1));
                    childLast.put(cc.getLong(0), cc.getLong(2));
                }
            }
            Double liveOdo = latestOdometerKm(ctx);
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            Map<java.time.LocalDate, Double> odoByDay = new HashMap<>();
            try (Cursor c = db.rawQuery(
                    "SELECT id, uuid, ts_ms, kind, category, status, odo_km, outside_temp_c, "
                    + "lat, lon, cost, title, note, tags, clip_name, parent_id, meta_json "
                    + "FROM diary_event WHERE parent_id IS NULL ORDER BY ts_ms DESC, id DESC", null)) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    int occ = 1 + (childCounts.containsKey(id) ? childCounts.get(id) : 0);
                    long tsMs = c.getLong(2);
                    Category cat = Category.fromName(c.getString(4));
                    Double odoKm = c.isNull(6) ? null : c.getDouble(6);
                    // Km reminders need an odometer: look one up for the entry's day when
                    // the entry has none (or an impossible one). Newest rows first, and a
                    // few lookups at most: only the latest entry of a category counts.
                    Double estOdo = null;
                    if (needsOdoEstimate(cat, odoKm, liveOdo)) {
                        java.time.LocalDate day = java.time.Instant.ofEpochMilli(tsMs).atZone(zone).toLocalDate();
                        if (odoByDay.containsKey(day)) {
                            estOdo = odoByDay.get(day);
                        } else if (odoByDay.size() < MAX_ODO_LOOKUPS) {
                            estOdo = odometerForDate(ctx, day).odoKm;
                            odoByDay.put(day, estOdo);
                        }
                    }
                    Long last = childLast.get(id);
                    out.add(new Entry(
                        id, c.getString(1), tsMs, c.getString(3),
                        cat, c.getString(5),
                        odoKm,
                        c.isNull(7) ? null : c.getDouble(7),
                        c.isNull(8) ? null : c.getDouble(8),
                        c.isNull(9) ? null : c.getDouble(9),
                        c.isNull(10) ? null : c.getDouble(10),
                        c.getString(11), c.getString(12), c.getString(13),
                        c.getString(14), c.isNull(15) ? null : c.getLong(15),
                        c.getString(16), occ, last != null ? last : tsMs, estOdo));
                }
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary read failed: " + t);
        }
        return out;
    }

    /** parent id, number of recurrence children, newest child ts_ms. */
    static final String CHILD_STATS_SQL =
        "SELECT parent_id, COUNT(*), MAX(ts_ms) FROM diary_event "
        + "WHERE parent_id IS NOT NULL GROUP BY parent_id";

    private static final int MAX_ODO_LOOKUPS = 12;

    /** True when a km reminder may read this entry and its own odometer cannot be
     * used: missing, or above the car's current odometer (a typo). Pure. */
    static boolean needsOdoEstimate(Category cat, Double odoKm, Double currentOdoKm) {
        if (cat == null || (cat.kmInterval == null && cat != Category.SERVICE)) return false;
        return odoForReminder(odoKm, null, currentOdoKm) == null;
    }

    /** The odometer a km reminder counts from: the entry's own when it is usable (set
     * and not above the current odometer), else the one looked up for its date, with
     * the same check; null when neither is usable. Pure. */
    static Double odoForReminder(Double odoKm, Double estOdoKm, Double currentOdoKm) {
        if (usableOdo(odoKm, currentOdoKm)) return odoKm;
        if (usableOdo(estOdoKm, currentOdoKm)) return estOdoKm;
        return null;
    }

    private static boolean usableOdo(Double odoKm, Double currentOdoKm) {
        return odoKm != null && odoKm > 0 && (currentOdoKm == null || odoKm <= currentOdoKm);
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

    /** Reads lifetime Total Cost of Ownership off-thread. */
    public static TcoSummary readTcoSummary(Context ctx) {
        return readTcoSummary(ctx, PeriodFilter.ALL, System.currentTimeMillis(), null);
    }

    /** Reads period-scoped Total Cost of Ownership (energy + maintenance + prorated bureaucracy)
     * and tracked distance off-thread. Reuses {@code entries} if provided to avoid double reads. */
    public static TcoSummary readTcoSummary(Context ctx, PeriodFilter period, long nowMs, List<Entry> entries) {
        if (ctx == null) return TcoSummary.compute(0, 0, 0);
        PeriodFilter pf = period != null ? period : PeriodFilter.ALL;
        boolean allTime = pf.kind == PeriodFilter.Kind.ALL;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long[] bounds = pf.boundsMs(nowMs, zone);
        double chargeCost = 0, autoRoadCost = 0, trackedKm = 0;
        long periodEndIncl = inclusiveEndMs(bounds, nowMs);
        long winEndMs = bounds[1];
        String firstDateIso = null;
        try {
            SQLiteDatabase db = CarDb.get(ctx).db();
            if (allTime) {
                try (Cursor c = db.rawQuery(
                        "SELECT COALESCE(SUM(cost), 0) FROM charge_session WHERE cost > 0", null)) {
                    if (c.moveToFirst()) chargeCost = c.getDouble(0);
                }
                try (Cursor c = db.rawQuery(
                        "SELECT COALESCE(SUM(cost), 0) FROM toll_passage WHERE dismissed = 0 AND cost > 0", null)) {
                    if (c.moveToFirst()) autoRoadCost = c.getDouble(0);
                }
                try (Cursor c = db.rawQuery(
                        "SELECT COALESCE(SUM(cost), 0) FROM parking_cost WHERE cost > 0", null)) {
                    if (c.moveToFirst()) autoRoadCost += c.getDouble(0);
                }
            } else {
                try (Cursor c = db.rawQuery(TCO_CHARGE_COST_SQL,
                        new String[]{String.valueOf(bounds[0]), String.valueOf(periodEndIncl)})) {
                    if (c.moveToFirst()) chargeCost = c.getDouble(0);
                }
                try (Cursor c = db.rawQuery(TCO_TOLL_COST_SQL,
                        new String[]{String.valueOf(bounds[0]), String.valueOf(periodEndIncl)})) {
                    if (c.moveToFirst()) autoRoadCost = c.getDouble(0);
                }
                try (Cursor c = db.rawQuery(TCO_PARKING_COST_SQL,
                        new String[]{String.valueOf(bounds[0]), String.valueOf(periodEndIncl)})) {
                    if (c.moveToFirst()) autoRoadCost += c.getDouble(0);
                }
            }

            double firstOdo = -1, lastOdo = -1;
            try (Cursor c = allTime
                    ? db.rawQuery(TCO_ODO_SQL, null)
                    : db.rawQuery(TCO_ODO_PERIOD_SQL, tcoDayRange(bounds, nowMs, zone))) {
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) firstOdo = c.getDouble(0);
                    if (!c.isNull(1)) lastOdo = c.getDouble(1);
                    if (!c.isNull(2)) firstDateIso = c.getString(2);
                }
            }

            // Include live odometer if the window reaches today
            boolean includesNow = bounds[1] >= nowMs - 60_000L;
            Double liveOdo = includesNow ? latestOdometerKm(ctx) : null;
            trackedKm = trackedKmFrom(firstOdo, lastOdo, liveOdo);
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary tco read failed: " + t);
        }
        // The first tracked day moves only the proration window; one-off costs are
        // tested against the real period bounds, the ones the charge costs use.
        long prorateStartMs = prorationStartMs(bounds[0], winEndMs, firstDateIso, allTime, zone);
        List<Entry> list = entries != null ? entries : readEntries(ctx);
        return computeTco(chargeCost, autoRoadCost, list, trackedKm, bounds[0], periodEndIncl,
            prorateStartMs, winEndMs, allTime);
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
        Map<Category, Entry> postponedByCat = new HashMap<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (e.parentId != null) continue;
                // A coolant top-up logs fluid added without resetting the 80k / 48 mo flush clock.
                if (e.category == Category.COOLANT && isTopUp(e.metaJson)) continue;
                // "Looked at, wait until ...": not a change, so the item's own
                // clock does not start again. It moves the due point instead.
                if (isPostponed(e.metaJson)) {
                    considerLatest(postponedByCat, e.category, e);
                    continue;
                }
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
            boolean owner = cfg.extras.containsKey(cat) && !cat.hasInterval();
            if (!cat.userPickable || (!cat.hasInterval() && !owner)) continue;
            Double kmLimit = cfg.kmInterval(cat);
            Integer dayLimit = cfg.dayInterval(cat);
            if (kmLimit == null && dayLimit == null) continue; // disabled (Off) in Settings

            Entry last = latestByCat.get(cat);
            if (last == null) {
                // No entry yet: the item counts from the day and the odometer the
                // owner got the car (0 km for a new one).
                Double kmSince = (currentOdoKm != null && currentOdoKm > 0)
                    ? Math.max(0.0, currentOdoKm - cfg.pickupKm) : null;
                // The months count only with a pick-up date, and only for long
                // items: a monthly check with no entry is not "late since delivery".
                boolean byTime = cfg.pickupMs > 0 && cfg.pickupMs <= nowMs
                    && dayLimit != null && dayLimit >= PICKUP_MIN_INTERVAL_DAYS;
                if (byTime) {
                    Double kmRem = (kmLimit != null && kmSince != null) ? (kmLimit - kmSince) : null;
                    int daysSince = (int) Math.floor((nowMs - cfg.pickupMs) / MS_PER_DAY);
                    int daysRem = dayLimit - daysSince;
                    boolean overdue = (kmRem != null && kmRem <= 0.0) || daysRem <= 0;
                    boolean soon = !overdue && ((kmRem != null && kmRem <= kmLimit * 0.10)
                        || daysRem <= Math.max(3, (int) Math.round(dayLimit * 0.10)));
                    out.add(new DueStatus(cat, null, kmSince, kmRem, daysSince, daysRem,
                        overdue, soon, false));
                } else if (kmSince != null && kmLimit != null && kmSince < kmLimit) {
                    // Without a date: count the km down, never a false overdue.
                    double kmRem = kmLimit - kmSince;
                    boolean soonKm = kmRem <= kmLimit * 0.10;
                    out.add(new DueStatus(cat, null, kmSince, kmRem, null, null,
                        false, soonKm, false));
                } else {
                    out.add(new DueStatus(cat, null, null, null, null, null, false, false, false));
                }
                continue;
            }
            // No odometer on the entry (or one above the current odometer): count from
            // the odometer the car's history has for that date, so km-only items come due.
            Double lastOdo = odoForReminder(last.odoKm, last.estOdoKm, currentOdoKm);
            Double kmSince = (currentOdoKm != null && lastOdo != null)
                ? (currentOdoKm - lastOdo) : null;
            Double kmRem = (kmLimit != null && kmSince != null)
                ? (kmLimit - kmSince) : null;

            int daysSince = (int) Math.max(0, Math.floor((nowMs - last.tsMs) / MS_PER_DAY));
            Integer daysRem = dayLimit != null ? (dayLimit - daysSince) : null;
            // A policy states its own end date: that date is the reminder, not
            // "entry date + interval" (a renewal can cover less than a year).
            Long validUntil = parseValidUntilMs(last.metaJson);
            if (daysRem != null && validUntil != null && validUntil > last.tsMs) {
                daysRem = (int) Math.ceil((validUntil - nowMs) / MS_PER_DAY);
            }

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
        for (int i = 0; i < out.size(); i++) {
            DueStatus d = out.get(i);
            Entry p = postponedByCat.get(d.category);
            // A change entered after the postponement ends it.
            if (p != null && (d.lastEntry == null || d.lastEntry.tsMs < p.tsMs)) {
                d = postponed(d, p, currentOdoKm, nowMs,
                    cfg.kmInterval(d.category), cfg.dayInterval(d.category));
                out.set(i, d);
            }
            d.ownerScheduled = cfg.extras.containsKey(d.category) && !d.category.hasInterval();
        }
        return out;
    }

    /** Pure: the due status of an item the shop postponed. The due point is
     * the odometer or the day in the entry, in place of "last change + interval". */
    static DueStatus postponed(DueStatus base, Entry p, Double currentOdoKm, long nowMs,
                               Double kmLimit, Integer dayLimit) {
        Double untilKm = parsePostponedKm(p.metaJson);
        Long untilDay = parsePostponedDay(p.metaJson);
        Double kmRem = (untilKm != null && currentOdoKm != null) ? (untilKm - currentOdoKm) : null;
        Integer daysRem = null;
        if (untilDay != null) {
            long untilMs = java.time.LocalDate.ofEpochDay(untilDay)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            daysRem = (int) Math.ceil((untilMs - nowMs) / MS_PER_DAY);
        }
        if (kmRem == null && daysRem == null) return base;   // nothing to count yet
        boolean overdue = (kmRem != null && kmRem <= 0.0) || (daysRem != null && daysRem <= 0);
        double soonKm = kmLimit != null ? kmLimit * 0.10 : 1_000.0;
        int soonDays = dayLimit != null ? Math.max(3, (int) Math.round(dayLimit * 0.10)) : 14;
        boolean soon = !overdue && ((kmRem != null && kmRem <= soonKm)
            || (daysRem != null && daysRem <= soonDays));
        DueStatus d = new DueStatus(base.category, base.lastEntry, base.kmSinceLast, kmRem,
            base.daysSinceLast, daysRem, overdue, soon, false);
        d.postponedBy = p;
        return d;
    }

    /** Meta of a "postponed" entry: {"postponeKm":30000} and/or {"postponeDay":20600}. */
    public static String buildPostponedMeta(Integer untilKm, Long untilEpochDay) {
        if (untilKm == null && untilEpochDay == null) return null;
        StringBuilder sb = new StringBuilder("{");
        if (untilKm != null) sb.append("\"postponeKm\":").append(untilKm);
        if (untilEpochDay != null) {
            if (sb.length() > 1) sb.append(',');
            sb.append("\"postponeDay\":").append(untilEpochDay);
        }
        return sb.append('}').toString();
    }

    public static boolean isPostponed(String metaJson) {
        return metaJson != null && (metaJson.contains("\"postponeKm\":")
            || metaJson.contains("\"postponeDay\":"));
    }

    public static Double parsePostponedKm(String metaJson) {
        Long v = metaLong(metaJson, "postponeKm");
        return v != null ? v.doubleValue() : null;
    }

    public static Long parsePostponedDay(String metaJson) {
        return metaLong(metaJson, "postponeDay");
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
        return d.lastEntry != null || d.overdue || d.dueSoon || d.ownerScheduled
            || coreRank(d.category) < SCHEDULE_CORE.length;
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

    // Coverage entries (insurance, tax and licensing): the end date of the
    // cover, and for insurance who to call. Kept in meta_json.
    public static final String META_VALID_UNTIL = "validUntil";
    public static final String META_PROVIDER = "provider";
    public static final String META_POLICY_NO = "policyNo";
    public static final String META_ASSIST_PHONE = "assistPhone";

    /** Builds the meta_json of a coverage entry; empty parts are left out. Null when all are empty. */
    public static String buildCoverageMeta(Long validUntilMs, String provider,
                                           String policyNo, String assistPhone) {
        StringBuilder sb = new StringBuilder("{");
        if (validUntilMs != null && validUntilMs > 0) {
            sb.append('"').append(META_VALID_UNTIL).append("\":").append(validUntilMs);
        }
        appendMetaText(sb, META_PROVIDER, provider);
        appendMetaText(sb, META_POLICY_NO, policyNo);
        appendMetaText(sb, META_ASSIST_PHONE, assistPhone);
        return sb.length() > 1 ? sb.append('}').toString() : null;
    }

    private static void appendMetaText(StringBuilder sb, String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (sb.length() > 1) sb.append(',');
        sb.append('"').append(key).append("\":\"");
        String v = value.trim();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(' ');
            else sb.append(ch);
        }
        sb.append('"');
    }

    /** End of cover in ms from meta_json, or null. */
    public static Long parseValidUntilMs(String metaJson) {
        if (metaJson == null) return null;
        String k = "\"" + META_VALID_UNTIL + "\":";
        int i = metaJson.indexOf(k);
        if (i < 0) return null;
        int s = i + k.length(), e = s;
        while (e < metaJson.length() && Character.isDigit(metaJson.charAt(e))) e++;
        if (e == s) return null;
        try { return Long.parseLong(metaJson.substring(s, e)); }
        catch (NumberFormatException ex) { return null; }
    }

    /** A text value written by {@link #buildCoverageMeta}, or null. */
    public static String parseMetaText(String metaJson, String key) {
        if (metaJson == null || key == null) return null;
        String k = "\"" + key + "\":\"";
        int i = metaJson.indexOf(k);
        if (i < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int p = i + k.length(); p < metaJson.length(); p++) {
            char ch = metaJson.charAt(p);
            if (ch == '\\' && p + 1 < metaJson.length()) { out.append(metaJson.charAt(++p)); continue; }
            if (ch == '"') return out.length() > 0 ? out.toString() : null;
            out.append(ch);
        }
        return null;
    }

    /** When the cost of a coverage entry stops counting: the end date written
     * on the entry, else entry date + the category's usual span. Pure. */
    public static long coverageEndMs(Entry e) {
        Long until = parseValidUntilMs(e.metaJson);
        if (until != null && until > e.tsMs) return until;
        int covDays = e.category != null ? e.category.coverageDays : 0;
        return e.tsMs + (long) (covDays * MS_PER_DAY);
    }

    // ---- Traffic ticket: the hour on the notice, and the place from the saved route ----

    /** A route point this close to the ticket's minute gives its place. */
    static final long TICKET_PLACE_GAP_MS = 5 * 60_000L;

    /** minuteOfDay: the hour on the notice (null = not given). speedKmh: the
     * car's speed at the route point used for the place (null = no point). */
    public static String buildTicketMeta(Integer minuteOfDay, Double speedKmh) {
        if (minuteOfDay == null) return null;
        StringBuilder sb = new StringBuilder("{\"timeMin\":").append(minuteOfDay);
        if (speedKmh != null) sb.append(",\"speedKmh\":").append(Math.round(speedKmh));
        return sb.append('}').toString();
    }

    public static Integer parseTicketMinute(String metaJson) {
        Long v = metaLong(metaJson, "timeMin");
        return v != null && v >= 0 && v < 1440 ? (Integer) v.intValue() : null;
    }

    public static Integer parseTicketSpeed(String metaJson) {
        Long v = metaLong(metaJson, "speedKmh");
        return v != null ? (Integer) v.intValue() : null;
    }

    private static Long metaLong(String metaJson, String key) {
        if (metaJson == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\":(-?\\d+)").matcher(metaJson);
        try { return m.find() ? Long.valueOf(m.group(1)) : null; }
        catch (NumberFormatException e) { return null; }
    }

    /** Pure: typed hour and minute to a minute of the day. Empty minutes are
     * :00. Null when the hour is empty or either part is out of range. */
    public static Integer clockMinute(String hh, String mm) {
        try {
            if (hh == null || hh.trim().isEmpty()) return null;
            int h = Integer.parseInt(hh.trim());
            int m = (mm == null || mm.trim().isEmpty()) ? 0 : Integer.parseInt(mm.trim());
            return (h < 0 || h > 23 || m < 0 || m > 59) ? null : (Integer) (h * 60 + m);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The saved route point nearest to tsMs (the middle of the ticket's
     * minute), within TICKET_PLACE_GAP_MS: {lat, lon, speed or NaN}. Null when
     * the car has no route for that time. Runs on CarDb's writer thread. */
    private static double[] routePointAt(SQLiteDatabase db, long tsMs) {
        long mid = tsMs + 30_000L;
        try (Cursor c = db.rawQuery(
                "SELECT lat, lon, speed_kmh FROM trip_point WHERE ts_ms BETWEEN ? AND ? "
                + "ORDER BY ABS(ts_ms - ?) ASC LIMIT 1",
                new String[]{String.valueOf(mid - TICKET_PLACE_GAP_MS),
                    String.valueOf(mid + TICKET_PLACE_GAP_MS), String.valueOf(mid)})) {
            if (!c.moveToFirst()) return null;
            return new double[]{c.getDouble(0), c.getDouble(1), c.isNull(2) ? Double.NaN : c.getDouble(2)};
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "diary ticket place failed: " + t);
            return null;
        }
    }

    /** A new ticket. Its place is where the car was at the hour on the notice
     * (from the saved route), never where the car is when the entry is typed:
     * a ticket is logged days later, at home. No hour or no route: no place. */
    public static void insertTicket(Context ctx, long tsMs, Integer minuteOfDay, Double odoKm,
                                    Double cost, String note, Runnable onDone) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        final String uuid = UUID.randomUUID().toString();
        CarDb.get(app).write(() -> {
            try {
                double[] p = minuteOfDay != null ? routePointAt(CarDb.get(app).db(), tsMs) : null;
                Double speed = (p != null && !Double.isNaN(p[2])) ? (Double) p[2] : null;
                insertNow(app, uuid, Category.TRAFFIC_TICKET, STATUS_DONE, tsMs, odoKm, null,
                    p != null ? (Double) p[0] : null, p != null ? (Double) p[1] : null,
                    cost, null, note, null, null, null, buildTicketMeta(minuteOfDay, speed));
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary ticket insert failed: " + t);
            }
        });
    }

    /** After an edit of a ticket: looks its place up again for the (new) hour. */
    public static void relocateTicket(Context ctx, long id, long tsMs, Integer minuteOfDay, Runnable onDone) {
        if (ctx == null || id <= 0) return;
        final Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                SQLiteDatabase db = CarDb.get(app).db();
                double[] p = minuteOfDay != null ? routePointAt(db, tsMs) : null;
                Double speed = (p != null && !Double.isNaN(p[2])) ? (Double) p[2] : null;
                ContentValues v = new ContentValues();
                if (p != null) { v.put("lat", p[0]); v.put("lon", p[1]); }
                else { v.putNull("lat"); v.putNull("lon"); }
                String meta = buildTicketMeta(minuteOfDay, speed);
                if (meta != null) v.put("meta_json", meta); else v.putNull("meta_json");
                db.update("diary_event", v, "id = ?", new String[]{String.valueOf(id)});
                publishChanged();
                if (onDone != null) onDone.run();
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "diary ticket place update failed: " + t);
            }
        });
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
