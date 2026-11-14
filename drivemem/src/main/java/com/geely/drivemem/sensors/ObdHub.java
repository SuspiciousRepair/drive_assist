package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;

/** Puts the OBD2 dongle's values into the hub, like CanHub does for CAN
 * (CLAUDE.md rule 7). Same rules: a 2 s in-memory read per key, quantized,
 * and only while the OBD2 switch is on.
 *
 * The dongle answers about every 2 s, but a full poll cycle can take longer
 * on a slow Bluetooth link, so a value counts as current for
 * {@link #FRESH_MS}; after that its key becomes NOT_AVAILABLE.
 *
 * EnergyIntegrator still gets every reading straight from Obd2Reader's
 * listener: it integrates each sample, and the hub delivers changes only. */
public final class ObdHub {
    public static final String CONNECTED = "obd.connected";     // 1/0
    public static final String SOC = "obd.soc";                 // %, 0.1 steps
    public static final String PACK_V = "obd.pack_v";           // V, 0.1 steps
    public static final String PACK_A = "obd.pack_a";           // A, 0.1 steps; + = discharge
    public static final String POWER_KW = "obd.power_kw";       // kW, 0.01 steps; + = discharge
    public static final String BATT_TEMP_C = "obd.batt_temp_c"; // C, 1 step
    public static final String[] KEYS = {CONNECTED, SOC, PACK_V, PACK_A, POWER_KW, BATT_TEMP_C};

    public static final long FRESH_MS = 15_000;

    public interface Source {
        boolean connected();
        Float soc();
        Float packV();
        Float packA();
        Float powerKw();
        Float battTempC();
    }

    static final Source READER = new Source() {
        @Override public boolean connected() { return Obd2Reader.isConnected(); }
        @Override public Float soc() { return Obd2Reader.freshSoc(FRESH_MS); }
        @Override public Float packV() { return Obd2Reader.freshVoltage(FRESH_MS); }
        @Override public Float packA() { return Obd2Reader.freshCurrent(FRESH_MS); }
        @Override public Float powerKw() { return Obd2Reader.freshPowerKw(FRESH_MS); }
        @Override public Float battTempC() { return Obd2Reader.freshBattTempC(FRESH_MS); }
    };

    private ObdHub() {}

    public static void register(CarActor a) { register(a, READER); }

    public static void register(CarActor a, Source s) {
        if (a == null) return;
        a.registerPoll(CONNECTED, CanHub.POLL_MS, c -> CarActor.Reading.ok(s.connected() ? 1 : 0));
        a.registerPoll(SOC, CanHub.POLL_MS, c -> CanHub.reading(CanHub.quantize(s.soc(), 0.1)));
        a.registerPoll(PACK_V, CanHub.POLL_MS, c -> CanHub.reading(CanHub.quantize(s.packV(), 0.1)));
        a.registerPoll(PACK_A, CanHub.POLL_MS, c -> CanHub.reading(CanHub.quantize(s.packA(), 0.1)));
        a.registerPoll(POWER_KW, CanHub.POLL_MS, c -> CanHub.reading(CanHub.quantize(s.powerKw(), 0.01)));
        a.registerPoll(BATT_TEMP_C, CanHub.POLL_MS, c -> CanHub.reading(CanHub.quantize(s.battTempC(), 1)));
    }

    public static void unregister(CarActor a) {
        if (a == null) return;
        for (String k : KEYS) {
            a.unregisterPoll(k);
            a.injectReading(k, CarActor.Reading.NOT_AVAILABLE);
        }
    }

    public static boolean connected(CarActor a) {
        if (a == null) return false;
        CarActor.Reading r = a.get(CONNECTED);
        return r.status == CarActor.Reading.Status.OK && r.value instanceof Number && ((Number) r.value).intValue() == 1;
    }

    public static Float get(String key) { return CanHub.get(key); }
}
