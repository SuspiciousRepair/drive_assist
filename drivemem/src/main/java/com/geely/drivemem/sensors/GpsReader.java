package com.geely.drivemem.sensors;

import com.geely.drivemem.services.TelemetryService;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

/** Provides access to the head unit's GPS location via Android's LocationManager.
 *
 * The car's location does not come from the VHAL (GPS_INFO property is unavailable),
 * so this class reads directly from the device's location services.
 */
public class GpsReader {
    static final String TAG = "DriveMem";

    // "Live" fix fed by TelemetryService's LocationListener. getLastKnownLocation
    // on its own does NOT update (the GPS stays idle while nobody asks for
    // updates); with that listener active the position really does move.
    public static volatile Location live;

    /** Returns location data as [latitude, longitude, altitude, bearing, speed, accuracy] or null. */
    public static double[] read(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            Location best = live;   // prefers the live fix (the most recent one)
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l == null) continue;
                    if (best == null || l.getTime() > best.getTime()) best = l;
                } catch (SecurityException se) { Log.w(TAG, "gps without permission: " + se); return null; }
                catch (Throwable ignored) {}
            }
            if (best == null) return null;
            // rejects 0,0 (no fix)
            if (Math.abs(best.getLatitude()) < 0.0001 && Math.abs(best.getLongitude()) < 0.0001) return null;
            return new double[]{
                best.getLatitude(), best.getLongitude(), best.getAltitude(),
                best.getBearing(), best.getSpeed(), best.getAccuracy()
            };
        } catch (Throwable t) { Log.w(TAG, "gps read error: " + t); return null; }
    }
}
