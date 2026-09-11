package com.geely.drivemem.hvac;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.controls.DoorWindow;
import com.geely.drivemem.ui.ComfortActivity;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-level singleton for the HVAC comfort ruler. Outlives any single screen
 * or activity so remote (MQTT) commands work even when the screen is closed.
 * Delegates all car I/O to CarActor. Maintains a heartbeat for maintenance and
 * sampling, and broadcasts state changes to multiple listeners.
 */
// Not a duplicate of EntityBus, on purpose, not an oversight: EntityBus carries
// raw car-property changes, published only by CarActor (publish() is
// package-private to com.geely.drivemem.car) and delivered as (key, Reading)
// pairs on CarActor's own thread. This listener list carries a different
// signal — "the ruler's derived HVAC level changed" — raised from .hvac, with
// no car-property key or Reading attached, to consumers in .ui and .net.
// Bolting that onto EntityBus would mean either widening its publish() past
// CarActor or forcing a synthetic key/Reading onto a value that isn't one.
// Two buses, two different shapes of event; keeping them separate is the
// simpler design, not the deferred cleanup this TODO assumed it was.
public final class ComfortHub {
    static final String TAG = CarAccess.TAG;
    // Maintenance heartbeat interval. Finer than minute-level timers; nothing
    // executes on a tick unless a timer expires.
    static final long BEAT_MS = 30_000;

    private static ComfortRuler ruler;
    private static Handler beat;
    private static Context ctxRef;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ComfortHub() {}

    /** Returns the process-level ruler instance, creating it if necessary. */
    public static synchronized ComfortRuler get(Context c) {
        if (ruler == null) {
            Context app = c.getApplicationContext();
            ctxRef = app;
            ruler = new ComfortRuler(app);
            ruler.setOnChange(ComfortHub::fanOut);
            HandlerThread t = new HandlerThread("comfortbeat");
            t.start();
            beat = new Handler(t.getLooper());
            // Early retries with short delays (underlying connect may take ~6s).
            beat.post(ensureArmed);
            beat.postDelayed(ensureArmed, 5_000);
            beat.postDelayed(ensureArmed, 15_000);
            DoorWindow.ensureSubscribed(app);
            beat.postDelayed(tick, BEAT_MS);
            Log.i(TAG, "comfort hub: ruler now owned by the process");
        }
        return ruler;
    }

    /** Returns the ruler if initialized, or null. Does not create it. */
    public static synchronized ComfortRuler peek() { return ruler; }

    // Periodic sampling interval. Samples the state whether or not the ruler is armed,
    // since baseline (no user input) is a meaningful data point.
    static final long SAMPLE_MS = 5 * 60 * 1000L;
    private static long lastSampleMs = 0;

    // Read-only arming. Initializes display without writing to car.
    private static final Runnable ensureArmed = new Runnable() {
        @Override public void run() {
            try {
                if (ruler != null) ruler.ensureArmedForDisplay();
            } catch (Throwable t) { Log.w(TAG, "comfort hub ensureArmed: " + t); }
        }
    };

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            ensureArmed.run();
            ComfortRuler r = peek();
            if (r != null) r.maintenance();
            long now = android.os.SystemClock.elapsedRealtime();
            if (ctxRef != null && now - lastSampleMs >= SAMPLE_MS) {
                lastSampleMs = now;
                final int lvl = (r == null) ? 0 : r.pointer();
                final String armState = (r == null) ? "unarmed" : "armed";
                // Must run on CarActor's thread (snap() reads shared CarAccess).
                CarActor.get(ctxRef).runOnCarThread(() ->
                    ComfortEvents.event(ctxRef, "sample",
                        ComfortEvents.snap(CarActor.get(ctxRef).rawAccess(), lvl), armState));
            }
            if (beat != null) beat.postDelayed(this, BEAT_MS);
        }
    };

    /** Adds a listener to be called when the ruler state changes. */
    public static void addListener(Runnable r) { if (r != null) listeners.add(r); }

    /** Removes a listener. */
    public static void removeListener(Runnable r) { listeners.remove(r); }

    /** Broadcasts state changes to all listeners (runs on main thread). */
    private static void fanOut() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "comfort listener: " + t); }
        }
    }
}
