package com.geely.drivemem.state;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.util.Modes;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;

/** Records each period the car spends parked in Park gear, enabling measurement
 * of standby power loss. Uses gear-based state (GEAR_PARK_ADAPTED). No snapshot
 * columns; battery state is queried at read time via telemetry_sample joins. */
public final class ParkSession {
    static final String TAG = CarAccess.TAG;

    private static volatile boolean subscribed = false;

    /** Subscribes to gear changes to track park sessions; idempotent. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        EntityBus.subscribe("car.gear", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer)
                onGear(app, (Integer) reading.value);
        });
    }

    // State accessed only from CarActor's thread.
    private static boolean wasParked = true;
    private static long startMs = 0, startSampleId = -1;

    private static void onGear(Context ctx, int gear) {
        boolean nowParked = gear == Modes.GEAR_PARK_ADAPTED;
        if (nowParked == wasParked) return;   // no edge
        if (nowParked) {
            // Driving -> parked: a park session starts.
            startMs = System.currentTimeMillis();
            startSampleId = CarDb.get(ctx).latestSampleId();
        } else {
            // Parked -> driving: the park session ends. Skip degenerate
            // sessions with no telemetry at all, same reasoning as TripSession.
            if (startSampleId >= 0) {
                final long endMs = System.currentTimeMillis();
                final long endSampleId = CarDb.get(ctx).latestSampleId();
                final ContentValues v = new ContentValues();
                v.put("start_ms", startMs);
                v.put("end_ms", endMs);
                v.put("start_sample_id", startSampleId);
                v.put("end_sample_id", endSampleId);
                CarDb.get(ctx).write(() -> {
                    try { CarDb.get(ctx).db().insert("park_session", null, v); }
                    catch (Throwable t) { Log.w(TAG, "parksession: " + t); }
                });
            }
        }
        wasParked = nowParked;
    }

    private ParkSession() {}
}
