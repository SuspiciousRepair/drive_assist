package com.geely.drivemem.car;

import android.util.Log;

/** The car's soft-tyre warning, one per wheel, into the hub.
 *
 * This car has no tyre pressure sensors: TPMSPRESSURE_FL..RR (557884502..505)
 * answer "not supported" and read 0. Its warning is indirect (the OEM calls
 * it ITPMS): the brakes compare wheel speeds, and a soft tyre turns a bit
 * faster. The car's own logcat names the property ITPMS_TYRE_WARN
 * (658548775, functionId TIRE_WARNING), one area per wheel. So there is no
 * pressure here, only "this tyre is low".
 *
 * Read 2026-11-16 on a car with no warning: 0 on every wheel. The value of
 * a real warning has not been seen yet, so any value other than 0 counts as
 * a warning, and the raw value is kept on the hub key and logged, to learn
 * what the car really sends. */
public final class TireWarning {
    private TireWarning() {}

    /** ITPMS_TYRE_WARN. */
    static final int PROP = 658548775;
    /** Every 10 s: the warning comes on slowly, a read is cheap. */
    static final int POLL_MS = 10_000;

    /** Hub keys, raw value per wheel. */
    public static final String FL = "car.tire_warn_fl", FR = "car.tire_warn_fr",
        RL = "car.tire_warn_rl", RR = "car.tire_warn_rr";
    /** Hub key: 1 when any wheel warns, else 0. */
    public static final String ANY = "car.tire_warn";

    /** Wheel keys, in the order of AREAS. */
    public static final String[] KEYS = {FL, FR, RL, RR};
    /** Android VehicleAreaWheel (android.car.jar, API 34): front left 1,
     * front right 2, rear left 4, rear right 8. */
    static final int[] AREAS = {1, 2, 4, 8};

    static void register(CarActor a) {
        for (int i = 0; i < KEYS.length; i++) {
            final String key = KEYS[i];
            final int area = AREAS[i];
            a.registerPoll(key, POLL_MS, c -> read(c, key, area));
        }
        a.deriveFrom(ANY, KEYS, (CarActor.DeriveFn) TireWarning::any);
    }

    private static CarActor.Reading read(CarAccess c, String key, int area) {
        String v = c.readAny(PROP, area, 'i');
        if (v == null) return CarActor.Reading.error("no reading");
        try {
            int raw = Integer.parseInt(v);
            if (raw != 0) Log.i(CarAccess.TAG, "tire: " + key + " raw=" + raw);
            return CarActor.Reading.ok(raw);
        } catch (NumberFormatException e) {
            return CarActor.Reading.error("bad value: " + v);
        }
    }

    /** 1 when any wheel warns, 0 when every wheel reads 0, an error when no
     * wheel could be read. A wheel that cannot be read does not hide the
     * others. Pure. */
    static CarActor.Reading any(CarActor.Reading... wheels) {
        boolean seen = false;
        for (CarActor.Reading r : wheels) {
            if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) continue;
            seen = true;
            if (warns(r)) return CarActor.Reading.ok(1);
        }
        return seen ? CarActor.Reading.ok(0) : CarActor.Reading.error("no wheel read");
    }

    /** True when this wheel's reading is a warning. */
    public static boolean warns(CarActor.Reading r) {
        return r != null && r.status == CarActor.Reading.Status.OK
            && r.value instanceof Number && ((Number) r.value).intValue() != 0;
    }
}
