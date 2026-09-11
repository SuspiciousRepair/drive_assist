package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDataHub;
import com.geely.drivemem.util.Diagnostics;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Diagnostic probe to correlate vehicle power properties against independent OBD2 readings.
 *
 * Several power candidates exist in the VHAL but have not been confirmed as real
 * instantaneous power measurements. This probe logs all candidates under all areaTypes
 * along with speed, altitude, and wall-clock timestamps for correlation against
 * simultaneous OBD2 measurements. See field-catalog.md for detailed analysis.
 */
public final class PowerProbe {
    static final String TAG = CarAccess.TAG;
    static final String NAME = "power-probe.log";
    static final String KEY = "debug.powerprobe";

    // Single-areaType context candidates — already well understood at
    // areaType=1, no need to multiply these.
    static final LinkedHashMap<String, Integer> CONTEXT_CANDIDATES = new LinkedHashMap<>();
    static {
        CONTEXT_CANDIDATES.put("trip_drive_pct", 612385024);
        CONTEXT_CANDIDATES.put("trip_battery_pct", 612385536);
        CONTEXT_CANDIDATES.put("trip_other_pct", 612385792);
    }
    static final int CONTEXT_AREA_TYPE = 1;

    // Confirmed real charging properties (read directly, no wrapFuncId).
    // Known to work correctly at AC charge scale; included to verify behavior at DCFC scale.
    static final LinkedHashMap<String, Integer> CHARGE_SCALE_CANDIDATES = new LinkedHashMap<>();
    static {
        CHARGE_SCALE_CANDIDATES.put("charge_current_a", CarAccess.CHARGE_WORK_CURRENT);
        CHARGE_SCALE_CANDIDATES.put("charge_voltage_v", CarAccess.CHARGE_WORK_VOLTAGE);
    }

    // Power candidates — the ones actually being hunted. Resolved under
    // EVERY areaType below, since a wrong areaType (not a dead property)
    // is the one untested explanation left for the flat-0.0 result.
    static final LinkedHashMap<String, Integer> POWER_CANDIDATES = new LinkedHashMap<>();
    static {
        POWER_CANDIDATES.put("moment_consumption", 612369664);        // TRIP_FUNC_ED_ENE_CONSUMPTION_MOMENT
        POWER_CANDIDATES.put("current_consumption_info", 612380672);  // TRIP_ED_CURRENT_ENE_CONSUMPTION_INFO
        // CHARGE_FUNC_BATTERY_DISCHARGING_CURRENT_POWER — the CHARGE_FUNC_
        // prefix (unlike the DRIVE_ ones below) makes this more likely V2L
        // (powering an appliance through the charge port) than drive power;
        // a normal drive never exercises that, so its flat 0.0 there proved
        // nothing either way. Kept here rather than removed — the field-
        // catalog note explains this cleanly, no need to duplicate it.
        POWER_CANDIDATES.put("discharge_power", 606099200);
        POWER_CANDIDATES.put("drive_power_pct", 606108928);           // DRIVE_POWER_PERCENTAGE
        POWER_CANDIDATES.put("recovery_power", 606110208);            // DRIVE_RECOVERY_POWER — also the regen_kwh lead
        POWER_CANDIDATES.put("charging_power", 606098432);            // CHARGE_FUNC_BATTERY_CHARGING_CURRENT_POWER
        POWER_CANDIDATES.put("max_power_limit", 606109184);           // DRIVE_MAXIMUM_ELECTIC_POWER_LIMIT
    }
    static final int[] POWER_AREA_TYPES = {0, 1, 2, 3};

    // Read power candidates at all defined areas, not just area 0, to detect
    // powertrain splits (e.g. front/rear motor draw) like other multi-area properties.
    // Uses all DEFAULT_AREAS to avoid hardware-specific assumptions; increases Binder
    // call count (28 properties × 9 areas = 252), so use longer intervals (3-5s) for real drives.
    static final String[] POWER_READ_AREAS = Diagnostics.DEFAULT_AREAS.split(",");

    private static final SimpleDateFormat FMT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private PowerProbe() {}

    /** Runs the power probe for a specified duration, logging samples at regular intervals.
     * Uses CarActor's poll registration to avoid blocking the shared actor thread. */
    public static void run(Context ctx, int durationS, int intervalMs) {
        final Context app = ctx.getApplicationContext();
        final long endMono = SystemClock.elapsedRealtime() + durationS * 1000L;
        final File f = file(app);
        // Always a fresh file per run — this is a one-drive-at-a-time test
        // tool, not a forever-append log; an old run's header/columns
        // (this probe's shape has changed more than once) must never mix
        // with a new run's rows in the same file.
        if (f.exists()) f.delete();
        final int[] rows = {0};
        // Resolved once per candidate+areaType, outside the per-tick
        // callback — repeating the wrapper lookup every sample would be
        // needless Binder traffic at a 1-5s cadence over a whole drive.
        // Key: "name@areaType" (context) or "name@areaType" -> read at every
        // POWER_READ_AREAS entry, not just one (power candidates).
        final Map<String, Integer> resolvedContext = new LinkedHashMap<>();
        final Map<String, Integer> resolvedPower = new LinkedHashMap<>();
        // No resolving needed -- direct raw ids, added straight from the
        // constant (see CHARGE_SCALE_CANDIDATES' own comment).
        final Map<String, Integer> resolvedChargeScale = new LinkedHashMap<>(CHARGE_SCALE_CANDIDATES);

        CarActor.get(app).registerPoll(KEY, intervalMs, car -> {
            if (resolvedContext.isEmpty() && resolvedPower.isEmpty()) {
                for (Map.Entry<String, Integer> e : CONTEXT_CANDIDATES.entrySet()) {
                    Integer real = car.wrapFuncId(app, CONTEXT_AREA_TYPE, e.getValue());
                    resolvedContext.put(e.getKey() + "@" + CONTEXT_AREA_TYPE, real != null ? real : e.getValue());
                }
                for (Map.Entry<String, Integer> e : POWER_CANDIDATES.entrySet()) {
                    for (int at : POWER_AREA_TYPES) {
                        Integer real = car.wrapFuncId(app, at, e.getValue());
                        resolvedPower.put(e.getKey() + "@" + at, real != null ? real : e.getValue());
                    }
                }
            }
            long wall = System.currentTimeMillis();
            long mono = SystemClock.elapsedRealtime();
            Float speed = car.readSpeed();
            Integer gear = car.readGear();
            double[] loc = GpsReader.read(app);   // [lat, lon, alt, bearing, speed, accuracy] or null
            Double altitude = (loc != null) ? loc[2] : null;

            StringBuilder line = new StringBuilder();
            line.append(FMT.format(new Date(wall))).append('\t').append(mono).append('\t')
                .append(fmt(speed)).append('\t').append(fmt(gear)).append('\t').append(fmt(altitude));
            // Context candidates: already confirmed matching the OEM screen at
            // area 0 (see field-catalog.md) — no need to sweep areas here.
            for (Map.Entry<String, Integer> e : resolvedContext.entrySet()) {
                line.append('\t').append(fmt(car.readGeneric(e.getValue(), 0)));
            }
            // Charge-scale candidates: already confirmed real at AC scale;
            // the DCFC question is fidelity at high current, not resolution.
            for (Map.Entry<String, Integer> e : resolvedChargeScale.entrySet()) {
                line.append('\t').append(fmt(car.readGeneric(e.getValue(), 0)));
            }
            // Power candidates: genuinely unresolved, so read at EVERY area,
            // not just 0 — see POWER_READ_AREAS' own comment for why.
            for (Map.Entry<String, Integer> e : resolvedPower.entrySet()) {
                for (String areaStr : POWER_READ_AREAS) {
                    int area = Integer.parseInt(areaStr.trim());
                    line.append('\t').append(fmt(car.readGeneric(e.getValue(), area)));
                }
            }

            try {
                FileWriter w = new FileWriter(f, true);
                try {
                    if (rows[0] == 0) w.write(header(resolvedContext.keySet(), resolvedChargeScale.keySet(), resolvedPower.keySet()));
                    w.write(line.toString());
                    w.write("\n");
                } finally { w.close(); }
            } catch (Throwable t) {
                Log.w(TAG, "powerprobe write: " + t);
            }
            rows[0]++;
            if (SystemClock.elapsedRealtime() >= endMono) {
                CarActor.get(app).unregisterPoll(KEY);
                Log.i(TAG, "powerprobe: wrote " + rows[0] + " rows to " + f);
            }
            return CarActor.Reading.ok(rows[0]);
        });
    }

    private static String header(Iterable<String> contextColumns, Iterable<String> chargeScaleColumns,
                                  Iterable<String> powerColumns) {
        StringBuilder h = new StringBuilder("wall\tmono\tspeed\tgear\taltitude");
        for (String name : contextColumns) h.append('\t').append(name);
        for (String name : chargeScaleColumns) h.append('\t').append(name);
        for (String name : powerColumns) {
            for (String area : POWER_READ_AREAS) h.append('\t').append(name).append("@a").append(area.trim());
        }
        return h + "\n";
    }

    private static String fmt(Object o) { return o == null ? "" : String.valueOf(o); }

    /** Returns the file path where probe logs are written. */
    public static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, NAME);
    }
}
