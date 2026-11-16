package com.geely.drivemem.sensors;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;

/** Provides access to the head unit's GPS location via Android's LocationManager.
 *
 * The car's location does not come from the VHAL (GPS_INFO property is unavailable),
 * so this class reads directly from the device's location services.
 */
public class GpsReader {
    static final String TAG = "DriveMem";

    /** A live fix older than this is not served as the current position. */
    static final long LIVE_MAX_AGE_MS = 10_000L;

    // "Live" fix fed by GpsOwner's LocationListener. getLastKnownLocation
    // on its own does NOT update (the GPS stays idle while nobody asks for
    // updates); with that listener active the position really does move.
    private static volatile Location live;

    /** True when a fix from this provider may become the live fix. Only the
     * GPS itself: a NETWORK fix on the same listener must not overwrite a
     * good GPS fix (no real altitude, rough position). Pure. */
    static boolean acceptLive(String provider) {
        return LocationManager.GPS_PROVIDER.equals(provider);
    }

    /** True when a live fix of this age is still the current position. A
     * negative age (clock oddity) is not trusted. Pure. */
    static boolean liveFresh(long ageMs) {
        return ageMs >= 0 && ageMs <= LIVE_MAX_AGE_MS;
    }

    /** The altitude, or NaN ("no value") when the fix has none. A fix
     * without altitude reports 0.0, which is not a height. Pure. */
    static double altitudeOrNaN(boolean hasAltitude, double altitude) {
        return hasAltitude && Double.isFinite(altitude) ? altitude : Double.NaN;
    }

    /** GpsOwner's listener hands each fix here. */
    static void offerLive(Location loc) {
        if (loc != null && acceptLive(loc.getProvider())) live = loc;
    }

    /** GpsOwner removed its listener: the fix will not be renewed. */
    static void clearLive() { live = null; }

    /** Returns location data as [latitude, longitude, altitude, bearing, speed, accuracy] or null.
     * The altitude is NaN when the fix has none. */
    // Package-private: consumers read LocationHub.FIX from the hub.
    static double[] read(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            Location best = live;   // the live GPS fix, if GpsOwner's listener has one yet
            // Too old (listener gone, or no signal): do not serve it as current.
            if (best != null && !liveFresh(
                    (SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1_000_000L)) {
                best = null;
            }
            if (best == null) {
                // Strict quality order, NOT "whoever has the newest timestamp
                // wins": NETWORK_PROVIDER has no real vertical signal (its
                // altitude is routinely 0 or a rough cell/WiFi estimate), and
                // comparing raw fix age let a fresher network fix silently
                // outrank an older but genuinely GPS-derived one. That
                // corrupted every ascent/descent total downstream, since
                // TripSession diffs consecutive altitude reads from here --
                // one bad network altitude reads as a huge fake climb or
                // drop. GPS_PROVIDER's own last-known fix first, then
                // PASSIVE (relayed from whatever provider another app last
                // used, quality unknown), then NETWORK only as the final
                // fallback when nothing GPS-based exists at all.
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null) { best = l; break; }
                    } catch (SecurityException se) { Log.w(TAG, "gps without permission: " + se); return null; }
                    catch (Throwable ignored) {}
                }
            }
            if (best == null) return null;
            // rejects 0,0 (no fix)
            if (Math.abs(best.getLatitude()) < 0.0001 && Math.abs(best.getLongitude()) < 0.0001) return null;
            return new double[]{
                best.getLatitude(), best.getLongitude(),
                altitudeOrNaN(best.hasAltitude(), best.getAltitude()),
                best.getBearing(), best.getSpeed(), best.getAccuracy()
            };
        } catch (Throwable t) { Log.w(TAG, "gps read error: " + t); return null; }
    }
}
