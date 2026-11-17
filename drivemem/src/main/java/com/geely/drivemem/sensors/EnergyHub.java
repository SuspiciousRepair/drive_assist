package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;

/** The battery's measured power, voltage and current, from the best source
 * there is right now: CAN when it is live, else the OBD2 dongle. Derived in
 * the hub (CarActor.deriveFrom) from CanHub's and ObdHub's keys, so the
 * choice is made once, in one place, and every consumer (telemetry, ABRP,
 * screens) gets the same answer.
 *
 * NOT_AVAILABLE when neither measures. Then consumers fall back to their own
 * estimate (the SoC delta); that is never published here as a measurement. */
public final class EnergyHub {
    public static final String POWER_KW = "energy.power_kw";
    public static final String PACK_V = "energy.pack_v";
    public static final String PACK_A = "energy.pack_a";
    /** "can" or "obd2". */
    public static final String SOURCE = "energy.source";
    /** Climate's real power, kW: can.climate_kw through ClimatePower, plus
     * the fan (car.hvac_fan through FanPower, a 12 V load). CAN only. */
    public static final String CLIMATE_KW = "energy.climate_kw";
    /** Battery power not used by the drive motor or the climate, kW: 12 V
     * loads and losses. can.power_kw - can.drive_kw - energy.climate_kw, all
     * CAN, so the three always add up to can.power_kw (never mixed with the
     * dongle's reading). CAN only. */
    public static final String OTHER_KW = "energy.other_kw";

    private EnergyHub() {}

    /** Idempotent (deriveFrom replaces by key). Costs nothing until a CAN or
     * OBD2 key changes. */
    public static void register(CarActor a) {
        if (a == null) return;
        derive(a, POWER_KW, CanHub.POWER_KW, ObdHub.POWER_KW);
        derive(a, PACK_V, CanHub.PACK_V, ObdHub.PACK_V);
        derive(a, PACK_A, CanHub.PACK_A, ObdHub.PACK_A);
        a.deriveFrom(SOURCE, new String[]{CanHub.LIVE, CanHub.POWER_KW, ObdHub.POWER_KW},
            d -> pickSource(d[0], d[1], d[2]));
        a.deriveFrom(CLIMATE_KW, new String[]{CanHub.LIVE, CanHub.CLIMATE_KW, CarActor.HVAC_FAN},
            d -> climate(d[0], d[1], d[2]));
        a.deriveFrom(OTHER_KW, new String[]{CanHub.LIVE, CanHub.POWER_KW, CanHub.DRIVE_KW, CanHub.CLIMATE_KW,
            CarActor.HVAC_FAN}, d -> other(d[0], d[1], d[2], d[3], d[4]));
    }

    /** Compressor/heater "never observed" counts as 0 kW, like in other(). */
    static CarActor.Reading climate(CarActor.Reading canLive, CarActor.Reading raw, CarActor.Reading fan) {
        if (!isOne(canLive)) return CarActor.Reading.NOT_AVAILABLE;
        return CarActor.Reading.ok(round2(climateKw(raw, fan)));
    }

    private static double climateKw(CarActor.Reading raw, CarActor.Reading fan) {
        double c = isNumber(raw) ? ClimatePower.correctedKw(((Number) raw.value).doubleValue()) : 0;
        return c + FanPower.kw(isNumber(fan) ? ((Number) fan.value).intValue() : null);
    }

    /** Drive or climate "never observed" (their frame has not changed since
     * the reader started, e.g. parked with the climate off) counts as 0 kW. */
    static CarActor.Reading other(CarActor.Reading canLive, CarActor.Reading power,
                                  CarActor.Reading drive, CarActor.Reading climate, CarActor.Reading fan) {
        if (!isOne(canLive) || !isNumber(power)) return CarActor.Reading.NOT_AVAILABLE;
        double d = isNumber(drive) ? ((Number) drive.value).doubleValue() : 0;
        return CarActor.Reading.ok(round2(((Number) power.value).doubleValue() - d - climateKw(climate, fan)));
    }

    private static boolean isNumber(CarActor.Reading r) {
        return r.status == CarActor.Reading.Status.OK && r.value instanceof Number;
    }

    /** Float, 0.01 kW: the hub publishes only changes. */
    private static float round2(double v) { return (float) (Math.round(v * 100) / 100.0); }

    private static void derive(CarActor a, String key, String canKey, String obdKey) {
        a.deriveFrom(key, new String[]{CanHub.LIVE, canKey, obdKey}, d -> pick(d[0], d[1], d[2]));
    }

    /** CAN if it is live and has the value, else OBD2, else NOT_AVAILABLE. */
    static CarActor.Reading pick(CarActor.Reading canLive, CarActor.Reading can, CarActor.Reading obd) {
        if (isOne(canLive) && can.status == CarActor.Reading.Status.OK) return can;
        if (obd.status == CarActor.Reading.Status.OK) return obd;
        return CarActor.Reading.NOT_AVAILABLE;
    }

    static CarActor.Reading pickSource(CarActor.Reading canLive, CarActor.Reading can, CarActor.Reading obd) {
        if (isOne(canLive) && can.status == CarActor.Reading.Status.OK) return CarActor.Reading.ok("can");
        if (obd.status == CarActor.Reading.Status.OK) return CarActor.Reading.ok("obd2");
        return CarActor.Reading.NOT_AVAILABLE;
    }

    private static boolean isOne(CarActor.Reading r) {
        return r.status == CarActor.Reading.Status.OK && r.value instanceof Number && ((Number) r.value).intValue() == 1;
    }

    /** True while {@link #SOURCE} says "can": CAN is live AND has a power
     * value. EnergyIntegrator asks this before it takes an OBD2 sample, so
     * what it integrates and what energy.source says cannot differ. Safe from
     * any thread (the hub cache is concurrent). */
    public static boolean canIsSource(CarActor a) {
        if (a == null) return false;
        CarActor.Reading r = a.get(SOURCE);
        return r.status == CarActor.Reading.Status.OK && "can".equals(r.value);
    }

    public static Float get(String key) { return CanHub.get(key); }
}
