package com.geely.drivemem.car;

import android.content.Context;

/** files/car-events.log: when the position lamps went on and off.
 *
 * Why: we want a real automatic dark mode, and the position lamps
 * ({@link CarActor#POSITION_LIGHTS}) are the only light signal the car
 * gives (the light-sensor properties read 0 on this car). On 2026-11-26, in
 * dim morning light, they went on and off while driving, and nothing was
 * left to say when or how often: logcat keeps only minutes. This log
 * records it, so the dark-mode rule (how long to wait before a switch) can
 * be set from real drives. It only records; it changes nothing on screen.
 *
 * The lines go to the same file as the lock lines ({@link LockEventLog}),
 * because a lock also turns the lamps off: one timeline shows both.
 * One line per change, with the gear and, when "Exact energy" (CAN) is on,
 * the speed. */
public final class LightEventLog {
    private static volatile boolean subscribed;

    private LightEventLog() {}

    /** Subscribes to the hub's position-lamp key; idempotent. */
    public static void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        final Context app = ctx.getApplicationContext();
        final Tracker t = new Tracker();
        EntityBus.subscribe(CarActor.POSITION_LIGHTS, (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Number) {
                String line = t.onValue(((Number) reading.value).intValue());
                if (line != null) LockEventLog.write(app, line + context());
            }
        });
    }

    /** ", gear D, 32 km/h": what the car was doing, from the hub. */
    private static String context() {
        CarActor a = CarActor.get();
        if (a == null) return "";
        return describe(a.get("car.gear"), a.get(com.geely.drivemem.sensors.CanHub.SPEED_KMH));
    }

    /** Pure: the gear and speed readings to text; a missing one is left out. */
    static String describe(CarActor.Reading gear, CarActor.Reading speed) {
        StringBuilder b = new StringBuilder();
        if (ok(gear)) b.append(", gear ").append(Telemetry.gearLabel(((Number) gear.value).intValue()));
        if (ok(speed)) b.append(", ").append(Math.round(((Number) speed.value).doubleValue())).append(" km/h");
        return b.toString();
    }

    private static boolean ok(CarActor.Reading r) {
        return r != null && r.status == CarActor.Reading.Status.OK && r.value instanceof Number;
    }

    /** Pure: a reading in, a line out when the lamps change. */
    static final class Tracker {
        private Boolean on;

        /** "lights on" / "lights off" on a change, else null. The first
         * reading is written too, so the log says the state at app start. */
        String onValue(int value) {
            boolean now = value != 0;
            if (on != null && on == now) return null;
            boolean first = on == null;
            on = now;
            return (now ? "lights on" : "lights off") + (first ? " (first reading)" : "");
        }
    }
}
