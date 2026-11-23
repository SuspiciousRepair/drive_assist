package com.geely.drivemem.car;

/** Publishes the car's 12V auxiliary battery voltage and factory low-battery
 * flags into the hub (CLAUDE.md rule 7).
 *
 * Verified live on the EX2 / IHU629G (2026-11-23):
 * - VHAL 557875206 (0x21408006, BODY_BATTERY_U_BATT_INFO): integer in tenths
 *   of a volt (128 = 12.8 V parked; 139..140 = 13.9..14.0 V in READY).
 * - VHAL 557883432 (0x2140a028, VPOWERINFO_BATTERY_LOW) and 557887632
 *   (0x2140b090, PEPS_WARNING_BATTERY_SAVING): 0 normally, non-zero on low 12V.
 *
 * Works on every car without an OBD2 dongle or CAN daemon. */
public final class Aux12vHub {
    private Aux12vHub() {}

    /** BODY_BATTERY_U_BATT_INFO (0x21408006): tenths of a volt. */
    static final int PROP_VOLTAGE = 557875206;
    /** VPOWERINFO_BATTERY_LOW (0x2140a028). */
    static final int PROP_LOW_BATT = 557883432;
    /** PEPS_WARNING_BATTERY_SAVING (0x2140b090). */
    static final int PROP_BATT_SAVE = 557887632;

    static final int VOLT_POLL_MS = 10_000;
    static final int WARN_POLL_MS = 15_000;

    /** Plausible automotive 12V rail bounds (rejects 0 / uninitialized / bogus spikes). */
    static final float MIN_VALID_V = 6.0f;
    static final float MAX_VALID_V = 18.0f;

    /** Hub key: 12V auxiliary voltage in volts, quantized to 0.1 V (Float). */
    public static final String VOLTAGE = "car.aux_12v_v";
    /** Hub key: 1 when either factory low-12V flag is active, else 0 (Integer). */
    public static final String LOW_WARN = "car.aux_12v_low";

    static void register(CarActor a) {
        a.registerPoll(VOLTAGE, VOLT_POLL_MS, Aux12vHub::readVoltage);
        a.registerPoll(LOW_WARN, WARN_POLL_MS, Aux12vHub::readLowWarn);
    }

    private static CarActor.Reading readVoltage(CarAccess c) {
        String raw = c.readAny(PROP_VOLTAGE, 0, 'i');
        if (raw == null) return CarActor.Reading.error("no reading");
        Float v = decodeRawVoltage(raw);
        return v != null ? CarActor.Reading.ok(v) : CarActor.Reading.error("out of range: " + raw);
    }

    private static CarActor.Reading readLowWarn(CarAccess c) {
        String a = c.readAny(PROP_LOW_BATT, 0, 'i');
        String b = c.readAny(PROP_BATT_SAVE, 0, 'i');
        if (a == null && b == null) return CarActor.Reading.error("no reading");
        boolean warn = isNonZero(a) || isNonZero(b);
        return CarActor.Reading.ok(warn ? 1 : 0);
    }

    /** Pure decoder: raw tenths of a volt -> volts, or null if out of band. */
    public static Float decodeRawVoltage(String raw) {
        if (raw == null) return null;
        try {
            double d = Double.parseDouble(raw.trim());
            float v = Math.round(d) / 10.0f;
            if (v < MIN_VALID_V || v > MAX_VALID_V) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNonZero(String s) {
        if (s == null) return false;
        try { return Integer.parseInt(s.trim()) != 0; }
        catch (NumberFormatException e) { return false; }
    }

    /** Convenience read of the latest cached 12V voltage from the hub, or null. */
    public static Float latestVoltage() {
        CarActor a = CarActor.get();
        if (a == null) return null;
        CarActor.Reading r = a.get(VOLTAGE);
        if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) return null;
        return ((Number) r.value).floatValue();
    }

    /** Convenience read of the factory low-12V warning flag from the hub. */
    public static boolean lowWarningActive() {
        CarActor a = CarActor.get();
        if (a == null) return false;
        CarActor.Reading r = a.get(LOW_WARN);
        return r != null && r.status == CarActor.Reading.Status.OK
            && r.value instanceof Number && ((Number) r.value).intValue() != 0;
    }
}
