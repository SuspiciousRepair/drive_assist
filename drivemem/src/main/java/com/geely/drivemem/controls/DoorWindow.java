package com.geely.drivemem.controls;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.hvac.ComfortHub;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** Cracks open windows when doors open, then closes them when doors close.
 *
 * Drops windows by a finger's width to break cabin seal pressure, making doors
 * easier to close. Uses event-driven door state changes (not polling). First
 * event is ignored (no previous state to compare). Windows already open further
 * than CRACK are not touched by this feature; only cracks are managed.
 * Disabled by default; user preference gates reactions.
 *
 * More than a crack threshold (MORE_THAN_CRACK = 14) handles transit scenarios:
 * since glass takes time to move, a door that latches while glass is traveling
 * may read slightly above CRACK; this threshold ensures the window is closed.
 * The trade-off: windows manually set below 14 are also closed by door events.
 */
public final class DoorWindow {
    static final String TAG = CarAccess.TAG;
    public static final String KEY = "window_on_door";

    /** Window positions: a small crack that breaks cabin seal pressure, and fully shut. */
    public static final int CRACK = 10, SHUT = 0;

    /** Threshold above which a window is considered manually opened. Asymmetric to
     * CRACK (14 vs 10) to account for glass movement time; doors may latch while
     * glass is in transit, so any position below this is treated as a controlled
     * crack that should be closed. */
    public static final int MORE_THAN_CRACK = 14;

    /** Mapping from door area IDs to corresponding window area IDs. */
    static final Map<Integer, Integer> DOOR_TO_WINDOW = new HashMap<>();
    static {
        DOOR_TO_WINDOW.put(1, 16);      // front left  (driver)
        DOOR_TO_WINDOW.put(4, 64);      // front right
        DOOR_TO_WINDOW.put(16, 256);    // rear left
        DOOR_TO_WINDOW.put(64, 1024);   // rear right
    }

    private static volatile boolean subscribed = false;

    /** Subscribes to door position events; idempotent. */
    public static void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        DoorWindow instance = new DoorWindow(ctx.getApplicationContext());
        EntityBus.subscribe("car.door_pos", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof int[]) {
                int[] av = (int[]) reading.value;
                instance.onDoor(av[0], av[1]);
            }
        });
    }

    /** Returns whether the door-window feature is enabled. */
    public static boolean enabled(Context c) {
        return c.getSharedPreferences("drivemem", Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Map<Integer, Boolean> wasOpen = new HashMap<>();

    private DoorWindow(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
    }

    void onDoor(int doorArea, int pos) {
        if (pos != CarAccess.DOOR_OPEN && pos != CarAccess.DOOR_CLOSED) return;
        boolean open = (pos == CarAccess.DOOR_OPEN);

        // Act on state change (edge), not state itself (level). Ignore first event.
        Boolean before = wasOpen.put(doorArea, open);
        if (before == null || before == open) return;

        // Checked AFTER the state is recorded: turning the feature on mid-session
        // must not make the next event look like the first one.
        if (!prefs.getBoolean(KEY, false)) return;

        Integer win = DOOR_TO_WINDOW.get(doorArea);
        if (win == null) return;

        CarActor.get(ctx).readRawOnce(CarAccess.WINDOW_POS, win, v -> {
            Integer now = (v instanceof Integer) ? (Integer) v : null;
            if (now == null) { Log.i(TAG, "doorwindow: window " + win + " unreadable — leaving it"); return; }

            if (open) {
                // Only manage windows closed or at crack position; skip if already open.
                if (now >= CRACK) {
                    Log.i(TAG, "doorwindow: window " + win + " already at " + now + " — not ours to move");
                    return;
                }
                CarActor.get(ctx).castRawOnce(CarAccess.WINDOW_POS, win, CRACK, null);
                Log.i(TAG, "doorwindow: door " + doorArea + " opened -> window " + win + " to " + CRACK);
            } else {
                if (now >= MORE_THAN_CRACK) {
                    Log.i(TAG, "doorwindow: window " + win + " is at " + now + ", more than a crack — a hand opened it");
                    return;
                }
                CarActor.get(ctx).castRawOnce(CarAccess.WINDOW_POS, win, SHUT, null);
                Log.i(TAG, "doorwindow: door " + doorArea + " closed -> window " + win + " to " + SHUT);
            }
        });
    }
}
