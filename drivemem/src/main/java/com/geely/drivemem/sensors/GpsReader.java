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

    static final int SRC_LIVE = 0, SRC_NONE = 1, SRC_LAST_KNOWN = 2;

    /** Where the position comes from. A fresh live fix is used. A stale one
     * means the listener is on but the fix stopped (a tunnel, no sky): no
     * position, because the last-known fallback would hand back that same
     * old fix as if it were current. No live fix at all (GpsOwner removed
     * its listener in Park and cleared it, or no first fix yet): the last
     * known place is the best there is. Pure. */
    static int source(boolean hasLive, long liveAgeMs) {
        if (!hasLive) return SRC_LAST_KNOWN;
        return liveFresh(liveAgeMs) ? SRC_LIVE : SRC_NONE;
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
            int src = source(best != null, best == null ? 0
                : (SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1_000_000L);
            if (src == SRC_NONE) return null;
            if (src == SRC_LAST_KNOWN) {
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
