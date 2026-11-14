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
    }

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

    public static Float get(String key) { return CanHub.get(key); }
}
