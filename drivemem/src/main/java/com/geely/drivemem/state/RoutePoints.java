package com.geely.drivemem.state;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.EnergyHub;
import com.geely.drivemem.sensors.EnergyIntegrator;
import com.geely.drivemem.sensors.LocationHub;
import com.geely.drivemem.util.Prefs;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;

/** The route of the open trip: one trip_point row every 5 s, only when the
 * car moved 25 m or more since the last one (issue #19).
 *
 * Raw data, analysed later: each point also keeps the drive mode, the regen
 * level, the battery power and the trip's energy so far, so efficiency per
 * mode, per road or per speed can be computed from the points alone.
 *
 * Points are written with trip_id NULL while the trip is open (so a process
 * restart mid-drive loses nothing) and claimed by TripSession when the trip
 * row is saved ({@link CarDb#CLAIM_TRIP_POINTS_SQL}). Hub thread only. */
public final class RoutePoints {
    static final String TAG = CarAccess.TAG;
    static final long MIN_GAP_MS = 5_000L;
    static final double MIN_MOVE_M = 25.0;

    private static long lastTs = -1;
    private static double lastLat, lastLon;

    private RoutePoints() {}

    /** A new trip starts with no last point. */
    static void reset() {
        lastTs = -1;
        com.geely.drivemem.ui.MapPreload.reset();
    }

    /** True when this fix should become a point. */
    static boolean keep(long lastTs, double lastLat, double lastLon, long ts, double lat, double lon) {
        if (lastTs < 0) return true;
        if (ts - lastTs < MIN_GAP_MS) return false;
        return LocationHub.distanceM(lastLat, lastLon, lat, lon) >= MIN_MOVE_M;
    }

    /** Called by TripSession for each published fix while a trip is open
     * and the car is out of Park. */
    static void onFix(Context ctx, LocationHub.Fix f, long now) {
        if (ctx == null || f == null || !Prefs.getRecordRoutes(ctx)) return;
        if (!keep(lastTs, lastLat, lastLon, now, f.lat, f.lon)) return;
        lastTs = now; lastLat = f.lat; lastLon = f.lon;
        final ContentValues v = row(ctx, f, now);
        CarDb.get(ctx).write(() -> {
            try { CarDb.get(ctx).db().insert("trip_point", null, v); }
            catch (Throwable t) { Log.w(TAG, "trip point: " + t); }
        });
        // The trip screen's map, saved on the car while there is a signal.
        com.geely.drivemem.ui.MapPreload.onPoint(ctx, f.lat, f.lon, now);
    }

    private static ContentValues row(Context ctx, LocationHub.Fix f, long now) {
        ContentValues v = new ContentValues();
        v.put("ts_ms", now);
        v.put("lat", f.lat);
        v.put("lon", f.lon);
        if (f.hasAlt()) v.put("alt_m", f.altM);   // no altitude: NULL, not 0
        v.put("speed_kmh", f.speedKmh);
        v.put("bearing", f.bearing);
        v.put("accuracy_m", f.accuracyM);
        CarActor a = CarActor.get(ctx);
        putInt(v, "drive_mode", a.get("car.drive_mode"));
        putInt(v, "regen_mode", a.get("car.regen_mode"));
        CarActor.Reading p = a.get(EnergyHub.POWER_KW);
        if (p.status == CarActor.Reading.Status.OK && p.value instanceof Number) {
            v.put("power_kw", ((Number) p.value).doubleValue());
        }
        EnergyIntegrator.TripSnapshot ts = EnergyIntegrator.currentTrip();
        if (ts.sampleCount > 0) {
            v.put("spent_kwh", ts.spentKwh);
            v.put("regen_kwh", ts.regenKwh);
        }
        float[] road = com.geely.drivemem.sensors.ImuHub.drainRouteSegment();
        if (road != null) {
            v.put("road_rms_z", road[0]);
            v.put("road_peak_z", road[1]);
        }
        return v;
    }

    private static void putInt(ContentValues v, String col, CarActor.Reading r) {
        if (r.status == CarActor.Reading.Status.OK && r.value instanceof Integer) v.put(col, (Integer) r.value);
    }
}
