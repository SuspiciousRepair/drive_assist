package com.geely.drivemem.util;

import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

/** Waits for a view to receive an actual layout pass via OnLayoutChangeListener
 * instead of repeatedly re-posting until getWidth()/getHeight() are non-zero.
 * Invariant: views in View.GONE containers must not spin the main thread.
 * See docs/incidents.md#2026-09-21-layoutwait-anr
 */
public final class LayoutWait {
    // Weak keys: a view that gets rebuilt/discarded takes its pending entry
    // with it, rather than pinning it (and whatever the action's closure
    // captures) alive forever.
    private static final Map<View, Runnable> PENDING = new WeakHashMap<>();

    /** Runs `action` the next time `view` gets a real (nonzero) layout size.
     * Only the newest action per view is kept -- a second call before the
     * first fires replaces it, so a listener-driven caller never redraws a
     * stale value once layout finally happens. Main thread only, same as
     * every other View call here. */
    public static void onNextLayout(View view, Runnable action) {
        if (view == null || action == null) return;
        if (PENDING.put(view, action) != null) return;   // listener already attached
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                  int ol, int ot, int or, int ob) {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
                v.removeOnLayoutChangeListener(this);
                Runnable latest = PENDING.remove(v);
                if (latest != null) latest.run();
            }
        });
    }

    private LayoutWait() {}
}
