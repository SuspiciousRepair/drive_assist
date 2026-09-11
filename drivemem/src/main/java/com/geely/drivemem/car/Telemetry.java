package com.geely.drivemem.car;

import com.geely.drivemem.sensors.EnergyIntegrator;
import com.geely.drivemem.sensors.Obd2Reader;

/** Reads telemetry properties from the car's OBD system via Geely's
 * adaptation layer. Properties are read via CarPropertyManager through CarAccess. */
public class Telemetry {

    // Battery nameplate capacity (39.6 kWh). Power window for SOC-delta
    // estimates; matches common third-party implementations. Only accessed
    // from CarActor's thread (read() is only called from there).
    private static final double CAPACITY_WH = 39600;
    private static final long POWER_WINDOW_MS = 30_000;
    private static Integer lastPowerSoc = null;
    private static long lastPowerSocAtMs = 0;

    /** Metadata for a telemetry field: property ID, type, scaling divisor, and display information. */
    public static final class Field {
        public final int prop; public final char type; public final float div;
        public final String key, name, unit, devClass;
        // decimal places when publishing: 0 = integer, >0 = round, -1 = raw value
        public final int round;
        Field(int prop, char type, float div, String key, String name, String unit, String devClass) {
            this(prop, type, div, key, name, unit, devClass, -1);
        }
        Field(int prop, char type, float div, String key, String name, String unit, String devClass, int round) {
            this.prop = prop; this.type = type; this.div = div;
            this.key = key; this.name = name; this.unit = unit; this.devClass = devClass;
            this.round = round;
        }
    }

    // Geely's adaptation layer delivers already-scaled values via getFloatProperty
    // (e.g., battery 94.1%, odo 2969.8 km), so div=1 for floats — do NOT divide again.
    //
    // `name` is only ever read by MqttReporter, to fill the "name" field of each
    // Home Assistant MQTT-discovery sensor — it is the friendly name HA shows for
    // that entity, not app UI text, so it does not belong in res/values/strings.xml
    // (that mechanism is for screens this app itself renders) and stays in
    // Portuguese on purpose, matching the rest of this user's HA instance.
    public static final Field[] FIELDS = {
        new Field(557885165, 'f', 1f, "battery",  "Bateria",     "%",  "battery", 0),  // integer
        new Field(289407492, 'f', 1f, "odometer", "Odometro",    "km", "distance"),
        new Field(289407752, 'f', 1f, "range",    "Autonomia",   "km", "distance"),
        new Field(291504647, 'f', 1f, "speed",    "Velocidade",  "km/h", null),
        new Field(289408001, 'i', 1f, "gear",     "Marcha",      null, null),
        // 'b' = boolean: the RAW value is 2 when it is off; only the adapted one
        // (getBooleanProperty) tells the truth. Reading it as an int published
        // "on".
        new Field(354419984, 'b', 1f, "ac_on",    "AC ligado",   null, null),
        new Field(658548345, 'f', 1f, "trip_km",  "Trip total",  "km", "distance"),
        // charging (they only show up when plugged in/charging; skipped if null)
        new Field(605291008, 'f', 1f, "charge_a", "Corrente de carga", "A", "current"),
        // 'charging' does not come from a Field: it is derived from the charge
        // switch at the end of read(), because the current alone does not tell
        // "stopped" apart from "cable unplugged" — both give a residual reading.
        new Field(605290752, 'f', 1f, "charge_v", "Tensão de carga",   "V", "voltage"),
    };

    /** Returns the rounding precision for a field key, or null if not a standard field. */
    static Integer roundFor(String key) {
        for (Field f : FIELDS) if (f.key.equals(key)) return f.round;
        return null;
    }

    /** Reads all declared telemetry fields from the car. Returns a map of
     * key -> value (Float or Integer); fields that fail to read are skipped. */
    public static java.util.LinkedHashMap<String, Object> read(CarAccess car) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        for (Field f : FIELDS) {
            for (int a : new int[]{0, 1, 16777216}) {
                if (f.type == 'b') {
                    // boolean: read the ADAPTED value (the raw one lies — 2 = off)
                    Boolean bv = car.readBool(f.prop, a);
                    if (bv == null) continue;
                    out.put(f.key, bv ? 1 : 0);
                    break;
                }
                String v = car.readAny(f.prop, a, f.type);
                if (v == null) continue;
                try {
                    if (f.type == 'f') {
                        float val = Float.parseFloat(v) / f.div;
                        if (f.round == 0) {
                            out.put(f.key, Math.round(val));            // publish as integer
                        } else if (f.round > 0) {
                            double p = Math.pow(10, f.round);
                            out.put(f.key, (float) (Math.round(val * p) / p));
                        } else {
                            out.put(f.key, val);                        // raw value
                        }
                    } else {
                        int val = (int) Float.parseFloat(v);
                        out.put(f.key, val);
                    }
                    break;
                } catch (NumberFormatException ignored) {}
            }
        }
        // human-readable gear
        Object g = out.get("gear");
        if (g instanceof Integer) out.put("gear_label", gearLabel((Integer) g));

        // "is_charging" is derived from charge_a (actual current flow, >0.5A
        // to ignore idle-line noise) rather than CHARGE_SWITCH, which can read
        // stale values — including a nonzero idle-sense voltage/current with
        // the cable plugged in but no session active. When not charging, both
        // are zeroed so HA doesn't show a residual reading. Named
        // "is_charging" rather than "charging" because CarDataHub already
        // uses "charging" for the write-side on/off switch entity.
        Object ca = out.get("charge_a");
        Float chargeA = (ca instanceof Float) ? (Float) ca : null;
        boolean charging = (chargeA != null && chargeA > 0.5f);
        out.put("is_charging", charging ? 1 : 0);
        if (!charging) {
            if (out.containsKey("charge_a")) out.put("charge_a", 0f);
            if (out.containsKey("charge_v")) out.put("charge_v", 0f);
        }

        // Plug/port connected — independent of active charging. The cable can
        // remain plugged in after a session completes, which is useful for
        // "unplug me" reminders. Property 557887621 reads 3 (connected) or 0 (not).
        // Derived here rather than declared as a Field since the raw value
        // is not a plain bool/float.
        String plugRaw = car.readAny(557887621, 0, 'i');
        if (plugRaw != null) {
            try { out.put("plug_connected", Integer.parseInt(plugRaw) != 0 ? 1 : 0); }
            catch (NumberFormatException ignored) {}
        }

        // Instant power and continuous energy integration.
        // Direct BMS measurement via Obd2Reader takes priority; falls back
        // to SOC delta over a rolling window if OBD2 is unavailable.
        // EnergyIntegrator calculates three distinct metrics over the high-frequency
        // (~2s) readings:
        // 1. Power (net: kW and net kWh = spent - regen)
        // 2. Power spent (gross consumption: kW and kWh >= 0)
        // 3. Regen (energy recovered: kW and kWh >= 0)
        Float obdPower = Obd2Reader.freshPowerKw(POWER_WINDOW_MS / 2);
        Float fallbackSocPower = null;
        if (obdPower == null) {
            Object socObj = out.get("battery");
            if (socObj instanceof Integer) {
                int socNow = (Integer) socObj;
                long now = System.currentTimeMillis();
                if (lastPowerSoc != null) {
                    long elapsedMs = now - lastPowerSocAtMs;
                    if (elapsedMs >= POWER_WINDOW_MS) {
                        double deltaFrac = (lastPowerSoc - socNow) / 100.0;
                        double hours = elapsedMs / 3_600_000.0;
                        double watts = (deltaFrac * CAPACITY_WH) / hours;
                        fallbackSocPower = (float) (watts / 1000.0);
                        lastPowerSoc = socNow;
                        lastPowerSocAtMs = now;
                    }
                } else {
                    lastPowerSoc = socNow;
                    lastPowerSocAtMs = now;
                }
            }
        }

        EnergyIntegrator.WindowSnapshot snap = EnergyIntegrator.drainWindow(
            obdPower != null ? obdPower : fallbackSocPower);

        if (snap.instantPowerKw != null) {
            out.put("instant_power_kw_est", snap.instantPowerKw.floatValue());
        }
        if (snap.instantPowerSpentKw != null) {
            out.put("power_spent_kw", snap.instantPowerSpentKw.floatValue());
        }
        if (snap.instantPowerRegenKw != null) {
            out.put("power_regen_kw", snap.instantPowerRegenKw.floatValue());
        }
        out.put("energy_spent_kwh", (float) snap.spentKwh);
        out.put("energy_regen_kwh", (float) snap.regenKwh);
        out.put("energy_net_kwh", (float) snap.netKwh);

        // Parking Mode (0 = off; otherwise the low byte is the chosen duration)
        Integer pm = car.readParkMode();
        if (pm != null) {
            out.put("park_mode", (pm != 0) ? 1 : 0);
            out.put("park_timer", (pm != 0) ? parkTimerLabel(pm) : "—");
        }
        return out;
    }

    /** Decodes parking mode duration from the low byte to a human-readable
     * label (e.g., "1 h", "Ilimitado"). Thin wrapper: CarDataHub.PARK_DURATIONS
     * is the single source of truth, shared with the ValueSet that validates
     * park_mode writes, so the two cannot drift apart. */
    public static String parkTimerLabel(int raw) { return CarDataHub.parkLabel(raw); }

    /** Converts a parking mode label to its code (low byte). Default is "Ilimitado". */
    public static int parkTimerCode(String label) { return CarDataHub.parkCode(label); }

    /** Converts a gear enum value to a single-letter label (N/R/P/D). */
    public static String gearLabel(int g) {
        switch (g) {
            case 1: return "N"; case 2: return "R"; case 4: return "P"; case 8: return "D";
            default: return String.valueOf(g);
        }
    }
}
