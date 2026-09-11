package com.geely.drivemem.controls;

import com.geely.drivemem.car.CarAccess;

import android.util.Log;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opens all windows halfway, then restores them to original positions.
 *
 * Uses a present-human button press to control window state, so no timer, alarm,
 * or watchdog is needed — the person who opens the windows closes them.
 *
 * Follows three core rules: (1) read before writing to avoid moving glass that
 * is already where the user set it; (2) only close windows that were opened by
 * this action, restoring each to its original position; (3) determine action by
 * reading glass position directly, never from cached state (which can become
 * stale via manual moves, auto-close on lock, or app replacement).
 */
public final class Purge {
    static final String TAG = CarAccess.TAG;

    // Window lock is not readable (AOSP WINDOW_LOCK id is null) and is not
    // enforced by the car's VHAL against writes, so this button drops rear windows
    // regardless of lock state. This is acceptable because a present human initiates
    // the action — not automatic. See field-catalog.md for full details.
    /** Window area IDs for the four fitted panes. */
    public static final int[] AREAS = {16, 64, 256, 1024};

    // Position values: 0 = shut, increasing values = more open (e.g. 50 = halfway).
    public static final int OPEN = 50, SHUT = 0;

    // Threshold for counting a pane as open. Uses DoorWindow.MORE_THAN_CRACK to
    // align with door-crack behavior (position 10); panes below this are not
    // considered open by the purge action.
    public static final int OPEN_AT_LEAST = DoorWindow.MORE_THAN_CRACK;

    // Where each pane was before we moved it. Only panes WE moved are in here,
    // which is what makes "only close what we opened" true.
    private final Map<Integer, Integer> before = new LinkedHashMap<>();

    /** Returns null if windows cannot be read (distinct from shut), true if any
     * window is open, false if all read windows are closed. */
    public Boolean anyOpen(CarAccess car) {
        if (!car.isReady()) return null;
        boolean readAny = false, open = false;
        for (int area : AREAS) {
            Integer pos = car.readIntRaw(CarAccess.WINDOW_POS, area);
            if (pos == null) continue;
            readAny = true;
            if (pos >= OPEN_AT_LEAST) open = true;
        }
        return readAny ? open : null;
    }

    /** Opens all panes to the target position. Returns count of panes that moved. */
    public int open(CarAccess car) {
        if (!car.isReady()) return 0;
        int moved = 0;
        for (int area : AREAS) {
            Integer pos = car.readIntRaw(CarAccess.WINDOW_POS, area);
            if (pos == null) { Log.i(TAG, "purge: window " + area + " unreadable — leaving it"); continue; }
            // Already further down than we would take it: somebody put it there.
            if (pos >= OPEN) {
                Log.i(TAG, "purge: window " + area + " already at " + pos + " — not ours to move");
                continue;
            }
            before.put(area, pos);
            if (car.setIntRaw(CarAccess.WINDOW_POS, area, OPEN)) moved++;
            Log.i(TAG, "purge: window " + area + " " + pos + " -> " + OPEN);
        }
        return moved;
    }

    /** Restores panes to their pre-open positions. If no positions were recorded
     * (e.g., due to app replacement between open and close), closes all windows
     * as a safe fallback. Returns count of panes that moved. */
    public int close(CarAccess car) {
        if (!car.isReady()) return 0;
        Map<Integer, Integer> targets = new HashMap<>();
        if (before.isEmpty()) {
            for (int area : AREAS) targets.put(area, SHUT);
            Log.i(TAG, "purge: nothing recorded — shutting whatever is open");
        } else {
            targets.putAll(before);
        }

        int moved = 0;
        for (int area : AREAS) {
            Integer want = targets.get(area);
            if (want == null) continue;
            Integer pos = car.readIntRaw(CarAccess.WINDOW_POS, area);
            if (pos == null) { Log.i(TAG, "purge: window " + area + " unreadable — leaving it"); continue; }
            if (pos <= SHUT && want == SHUT) continue;   // already there
            if (car.setIntRaw(CarAccess.WINDOW_POS, area, want)) moved++;
            Log.i(TAG, "purge: window " + area + " " + pos + " -> " + want);
        }
        before.clear();
        return moved;
    }
}
