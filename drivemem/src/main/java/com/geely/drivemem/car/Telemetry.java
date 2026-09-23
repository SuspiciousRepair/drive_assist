package com.geely.drivemem.car;

import com.geely.drivemem.sensors.EnergyIntegrator;
import com.geely.drivemem.sensors.Obd2Reader;

/** Reads telemetry properties from the car's OBD system via Geely's
 * adaptation layer. Properties are read via CarPropertyManager through CarAccess. */
public class Telemetry {

    // Battery nameplate capacity (39.6 kWh). Power window for SOC-delta
    // estimates; matches common third-party implementations. Only accessed
    // from CarActor's thread (read() is only called from there).
    /** Nameplate capacity used only for explicitly-labelled SoC estimates. */
    public static final double BATTERY_CAPACITY_KWH = 39.6;
    private static final double CAPACITY_WH = BATTERY_CAPACITY_KWH * 1000.0;
    private static final long POWER_WINDOW_MS = 30_000;
    private static Double lastPowerSoc = null;
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

    /** Reads current vehicle property values into a map. Side-effect-free: does not
     * drain energy accumulators or advance the SoC-delta tracking window.
     * For display, test, and debug purposes.
     * authoritativeCharging must come from CarActor's own "car.is_charging"
     * poll (see its comment) — the one place that decides charging state.
     * Null means that poll hasn't produced a reading yet (e.g. cold start):
     * "is_charging" is then left OUT of the returned map entirely, the same
     * "not available" convention every other field here already uses on a
     * failed read — never collapsed into a guessed 0, which would just be
     * this method making its own assumption again. */
    public static java.util.LinkedHashMap<String, Object> snapshot(CarAccess car, Boolean authoritativeCharging) {
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

        // Plug/port connected — independent of active charging. The cable can
        // remain plugged in after a session completes, which is useful for
        // "unplug me" reminders. Property 557887621 reads 3 (connected) or 0 (not).
        // Derived here rather than declared as a Field since the raw value
        // is not a plain bool/float. Plain passthrough of the raw prop — not
        // a state decision, so it stays here unlike is_charging below.
        String plugRaw = car.readAny(557887621, 0, 'i');
        if (plugRaw != null) {
            try { out.put("plug_connected", Integer.parseInt(plugRaw) != 0 ? 1 : 0); }
            catch (NumberFormatException ignored) {}
        }

        // "is_charging" is NOT computed here. It used to be re-derived from
        // charge_a (current > 0.5A) independently of CarActor's own
        // "car.is_charging" poll, which already does this — and does it
        // correctly, cross-checked against the plug (see that poll's
        // comment). Two places computing the same fact drifted apart:
        // charge_a is known to latch at its last non-zero reading and never
        // fall back to 0 on its own, and this copy had no plug cross-check
        // to catch that — so it kept reporting "charging" while actually
        // driving with nothing plugged in (seen live 2026-09-23, ABRP/HA
        // payload: is_charging=1, is_dcfc=1, speed=64.6 km/h, current=-7.7A/
        // discharging). Now this method only asks for the one answer
        // CarActor already computed and relays it — see authoritativeCharging.
        //
        // Unknown (null) is a real state, not "assume not charging" — that
        // would just move the guessing back in here under a different name.
        // Left out of the map on unknown; every consumer of "is_charging"
        // already treats a missing key as "don't know", not false
        // (TelemetrySampler.putIfPresent, TripSession's instanceof check,
        // AbrpUploader's asInt() null check).
        if (authoritativeCharging != null) {
            boolean charging = authoritativeCharging;
            out.put("is_charging", charging ? 1 : 0);
            if (!charging) {
                if (out.containsKey("charge_a")) out.put("charge_a", 0f);
                if (out.containsKey("charge_v")) out.put("charge_v", 0f);
            }
        }

        // Parking Mode (0 = off; otherwise the low byte is the chosen duration)
        Integer pm = car.readParkMode();
        if (pm != null) {
            out.put("park_mode", (pm != 0) ? 1 : 0);
            out.put("park_timer", (pm != 0) ? parkTimerLabel(pm) : "—");
        }
        return out;
    }

    /** Periodic telemetry tick: reads snapshot values and integrates energy over the
     * window (CarActor.TICK_INTERVAL_MS). Drains and resets EnergyIntegrator's window
     * accumulators and advances the SoC-delta tracking window. */
    public static java.util.LinkedHashMap<String, Object> tick(CarAccess car, Boolean authoritativeCharging) {
        java.util.LinkedHashMap<String, Object> out = snapshot(car, authoritativeCharging);

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
        double fallbackWindowHours = 0;
        // Tracked always, not only when OBD2 is absent -- so a mid-trip
        // disconnect doesn't cold-start this estimate (the SoC tracker was
        // already warm), and every window has a recorded estimate alongside
        // whatever the "best available" (OBD-priority) energy turned out to
        // be. See EnergySource / CarDb v21.
        // battery_raw_pct, not "battery": that's the rounded-to-integer value
        // published for display/HA, and a 30s SoC delta is frequently well
        // under half a percent -- reading the rounded field made this estimate
        // see "no change" most windows, then a whole 1% jump the next.
        Object socObj = out.get("battery_raw_pct");
        if (socObj instanceof Float) {
            double socNow = (Float) socObj;
            long now = System.currentTimeMillis();
            if (lastPowerSoc != null) {
                long elapsedMs = now - lastPowerSocAtMs;
                if (elapsedMs >= POWER_WINDOW_MS) {
                    double deltaFrac = (lastPowerSoc - socNow) / 100.0;
                    double hours = elapsedMs / 3_600_000.0;
                    double watts = (deltaFrac * CAPACITY_WH) / hours;
                    fallbackSocPower = (float) (watts / 1000.0);
                    fallbackWindowHours = hours;
                    lastPowerSoc = socNow;
                    lastPowerSocAtMs = now;
                }
            } else {
                lastPowerSoc = socNow;
                lastPowerSocAtMs = now;
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
        out.put("energy_measured", snap.sampleCount > 0 ? 1 : 0);
        // Human-readable twin of energy_measured, for HA: surfaced as a
        // json_attr_t attribute on the energy sensors (see MqttReporter) so
        // this window's spent/regen/net numbers carry the same "is this a
        // real reading or a guess" signal the app's own UI already shows via
        // the "~" mark (see EnergySource / DailyStatsView.withEnergySourceMark).
        out.put("energy_quality", snap.sampleCount > 0 ? "measured"
            : (fallbackSocPower != null ? "estimated" : "no_data"));
        // Always-recorded SoC-delta estimate, independent of whether OBD2
        // backed this window's "best available" energy above.
        if (fallbackSocPower != null) {
            out.put("energy_spent_est_kwh",
                fallbackSocPower >= 0 ? (float) (fallbackSocPower * fallbackWindowHours) : 0f);
            out.put("energy_regen_est_kwh",
                fallbackSocPower < 0 ? (float) (-fallbackSocPower * fallbackWindowHours) : 0f);
        }
        return out;
    }

    /** Legacy alias for {@link #tick(CarAccess, Boolean)}. */
    public static java.util.LinkedHashMap<String, Object> read(CarAccess car, Boolean authoritativeCharging) {
        return tick(car, authoritativeCharging);
    }

    public static void resetForTesting() {
        lastPowerSoc = null;
        lastPowerSocAtMs = 0;
    }

    public static Double getLastPowerSocForTesting() { return lastPowerSoc; }
    public static long getLastPowerSocAtMsForTesting() { return lastPowerSocAtMs; }

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
