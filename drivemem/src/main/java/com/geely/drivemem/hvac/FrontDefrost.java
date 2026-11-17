package com.geely.drivemem.hvac;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;

import android.content.Context;

/**
 * The physical front-windshield defogger button (MAX_DEFROST), from the hub.
 * CarActor watches it into {@link CarActor#HVAC_FRONT_DEFROST}; this class
 * turns each press into a comfort_event row ("defrost", "front on"/"front off")
 * and wakes the ruler so its "defrosting" state shows at once instead of on
 * the next 30 s beat.
 */
public final class FrontDefrost {
    private FrontDefrost() {}

    private static volatile boolean subscribed = false;
    // Last value seen. Delivery is on CarActor's thread only.
    private static Boolean last = null;

    /** On/off from a hub reading; null when it cannot be read. */
    public static Boolean on(CarActor.Reading r) {
        if (r == null || r.status != CarActor.Reading.Status.OK) return null;
        if (r.value instanceof Boolean) return (Boolean) r.value;
        if (r.value instanceof Number) return ((Number) r.value).intValue() != 0;
        return null;
    }

    /**
     * The comfort_event detail for a change, or null when nothing is logged:
     * the first value (that is the state at connect, not a press), an
     * unreadable value, or no change.
     */
    static String transition(Boolean was, Boolean now) {
        if (was == null || now == null || was.equals(now)) return null;
        return now ? "front on" : "front off";
    }

    /** Subscribes once per process. Called from ComfortHub. */
    static void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        final Context app = ctx.getApplicationContext();
        EntityBus.subscribe(CarActor.HVAC_FRONT_DEFROST, (key, reading) -> {
            Boolean now = on(reading);
            String t = transition(last, now);
            if (now != null) last = now;
            if (t == null) return;
            ComfortRuler r = ComfortHub.peek();
            int pointer = r == null ? 0 : r.pointer();
            CarAccess car = CarActor.get(app).rawAccess();
            ComfortEvents.event(app, "defrost", ComfortEvents.snap(car, pointer), t);
            if (r != null) r.maintenance();
        });
    }
}
