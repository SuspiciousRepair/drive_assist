package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;

/** Puts the CAN reader's values into the hub (CarActor's cache, change-only
 * delivery on EntityBus), so every consumer reads them like any other car
 * value: CLAUDE.md rule 7, "car data goes through the hub first".
 *
 * Cheap on purpose ("care for the hub"): one in-memory read per key every
 * {@link #POLL_MS}, values quantized so sensor jitter does not republish, and
 * the polls exist only while the "Exact energy" switch is on.
 *
 * The 10 Hz power samples do NOT come through here: that rate would load the
 * hub's single thread, so they go straight to EnergyIntegrator
 * (CanEnergyReader.PowerListener), in addition to this summary. */
public final class CanHub {
    /** 1 if the reader is live (heartbeat within CanPower.STALE_MS), else 0. */
    public static final String LIVE = "can.live";
    public static final String PACK_V = "can.pack_v";          // V, 0.1 steps
    public static final String PACK_A = "can.pack_a";          // A, 0.1 steps; + = discharge
    public static final String POWER_KW = "can.power_kw";      // kW, 0.01 steps; + = discharge
    public static final String CLIMATE_KW = "can.climate_kw";  // kW, 0.01 steps
    public static final String DRIVE_KW = "can.drive_kw";      // kW, 0.1 steps
    public static final String CHARGER_W = "can.charger_w";    // W at the mains, 10 W steps
    public static final String[] KEYS = {LIVE, PACK_V, PACK_A, POWER_KW, CLIMATE_KW, DRIVE_KW, CHARGER_W};

    /** 2 s, like car.charge_a: enough for screens, MQTT and the 30 s
     * telemetry tick, and it does not pull CarActor's master tick below the
     * 1 s the speed poll already uses while the Comfort screen is open. */
    public static final int POLL_MS = 2000;

    /** Where the values come from. CanEnergyReader in the app; a fake in tests. */
    public interface Source {
        boolean live();
        Float packV();
        Float packA();
        Float powerKw();
        Float climateKw();
        Float driveKw();
        Float chargerW();
    }

    static final Source READER = new Source() {
        @Override public boolean live() { return CanEnergyReader.isLive(); }
        @Override public Float packV() { return CanEnergyReader.freshPackVolts(); }
        @Override public Float packA() { return CanEnergyReader.freshPackAmps(); }
        @Override public Float powerKw() { return CanEnergyReader.freshPowerKw(); }
        @Override public Float climateKw() { return CanEnergyReader.freshClimateKw(); }
        @Override public Float driveKw() { return CanEnergyReader.freshDriveKw(); }
        @Override public Float chargerW() { return CanEnergyReader.freshChargerInputW(); }
    };

    private CanHub() {}

    public static void register(CarActor a) { register(a, READER); }

    public static void register(CarActor a, Source s) {
        if (a == null) return;
        a.registerPoll(LIVE, POLL_MS, c -> CarActor.Reading.ok(s.live() ? 1 : 0));
        a.registerPoll(PACK_V, POLL_MS, c -> reading(quantize(s.packV(), 0.1)));
        a.registerPoll(PACK_A, POLL_MS, c -> reading(quantize(s.packA(), 0.1)));
        a.registerPoll(POWER_KW, POLL_MS, c -> reading(quantize(s.powerKw(), 0.01)));
        a.registerPoll(CLIMATE_KW, POLL_MS, c -> reading(quantize(s.climateKw(), 0.01)));
        a.registerPoll(DRIVE_KW, POLL_MS, c -> reading(quantize(s.driveKw(), 0.1)));
        a.registerPoll(CHARGER_W, POLL_MS, c -> reading(quantize(s.chargerW(), 10)));
    }

    /** Removes the polls and marks every key not available, so nothing keeps
     * reading the last value of a switched-off reader. */
    public static void unregister(CarActor a) {
        if (a == null) return;
        for (String k : KEYS) {
            a.unregisterPoll(k);
            a.injectReading(k, CarActor.Reading.NOT_AVAILABLE);
        }
    }

    /** A hub value as a Float, or null when it is not there or not OK. */
    public static Float get(CarActor a, String key) {
        if (a == null) return null;
        CarActor.Reading r = a.get(key);
        if (r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) return null;
        return ((Number) r.value).floatValue();
    }

    public static Float get(String key) { return get(CarActor.get(), key); }

    static CarActor.Reading reading(Float v) {
        return v == null ? CarActor.Reading.NOT_AVAILABLE : CarActor.Reading.ok(v);
    }

    /** Rounds to the nearest multiple of {@code step}, so jitter below one
     * step is not a change (CarActor publishes only changes). */
    static Float quantize(Float v, double step) {
        if (v == null || v.isNaN() || v.isInfinite()) return null;
        return (float) (Math.round(v / step) * step);
    }
}
