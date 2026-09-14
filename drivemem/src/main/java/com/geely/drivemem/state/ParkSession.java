package com.geely.drivemem.state;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDb;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;

/** Records each period the car spends parked in Park gear, enabling measurement
 * of standby power loss. No snapshot columns; battery state is queried at read
 * time via telemetry_sample joins. Edge detection lives in TripSession, not
 * here — see CarState's class comment for why a second independent car.gear
 * subscription is exactly the bug this app already shipped once. */
public final class ParkSession {
    static final String TAG = CarAccess.TAG;

    private static volatile boolean subscribed = false;

    /** Subscribes to CarState's parked notifications to track park sessions;
     * idempotent. CarState only calls its listeners on a real change, so
     * there is no "is this actually an edge" check needed here any more. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Context app = ctx.getApplicationContext();
        CarState.addListener(parked -> onParkedChange(app, parked));
    }

    // State accessed only from CarActor's thread (CarState.reportParked()
    // runs there, from TripSession.onGear()).
    private static long startMs = 0, startSampleId = -1;

    private static void onParkedChange(Context ctx, boolean nowParked) {
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
    }

    private ParkSession() {}
}
