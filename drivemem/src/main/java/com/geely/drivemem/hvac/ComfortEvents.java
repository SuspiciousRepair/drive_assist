package com.geely.drivemem.hvac;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDb;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * Logs HVAC comfort events (user actions and system state) to CarDb. Records
 * user requests (tap/apply/etc) and car state snapshots for analysis and learning.
 */
public final class ComfortEvents {
    static final String TAG = CarAccess.TAG;

    private ComfortEvents() {}

    /** One point-in-time snapshot of car state and timing information. */
    public static final class Snap {
        long wallMs = System.currentTimeMillis();
        long monoMs = SystemClock.elapsedRealtime();
        long upMs   = SystemClock.uptimeMillis();
        int pointer, fan = -1;
        Float outC;
        float setpoint = Float.NaN;
        Boolean ac, recirc, power;
    }

    /** Creates a minimal snapshot with only timing and pointer (no car I/O). */
    public static Snap lite(int pointer) {
        Snap s = new Snap();
        s.pointer = pointer;
        return s;
    }

    /** Creates a full snapshot including all readable car state. */
    public static Snap snap(CarAccess car, int pointer) {
        Snap s = new Snap();
        s.pointer = pointer;
        if (car != null && car.isReady()) {
            s.outC     = car.readOutsideTempC();
            s.setpoint = car.readSetpoint();
            s.fan      = car.readFan();
            s.ac       = car.readHvacFlag(CarAccess.HVAC_AC_ON);
            s.recirc   = car.readHvacFlag(CarAccess.HVAC_RECIRC_ON);
            s.power    = car.readHvacFlag(CarAccess.HVAC_POWER_ON);
        }
        return s;
    }

    /**
     * Logs an event with the given snapshot. Event types (tap, apply, sample, etc)
     * are free-form for query-based analysis.
     */
    public static void event(Context ctx, String event, Snap s, String detail) {
        final Context app = ctx.getApplicationContext();
        CarDb.get(app).write(() -> {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("ts_ms", s.wallMs);
                v.put("event", event);
                v.put("pointer", s.pointer);
                if (s.outC != null) v.put("out_c", s.outC);
                if (!Float.isNaN(s.setpoint)) v.put("setpoint", s.setpoint);
                if (s.fan >= 0) v.put("fan", s.fan);
                if (s.ac != null) v.put("ac", s.ac ? 1 : 0);
                if (s.recirc != null) v.put("recirc", s.recirc ? 1 : 0);
                if (s.power != null) v.put("power", s.power ? 1 : 0);
                if (detail != null) v.put("detail", detail);
                CarDb.get(app).db().insert("comfort_event", null, v);
            } catch (Throwable t) {
                // logging must never be able to break the climate control
                Log.w(TAG, "comfortlog: " + t);
            }
        });
    }
}
