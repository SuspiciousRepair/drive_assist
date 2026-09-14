package com.geely.drivemem.util;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

/** Whether ANY of our own Activities is currently on screen — process-level,
 * PubSub, same shape as CarState: one tracker, everyone else subscribes
 * instead of polling. Built for OverlayService, which has to hide itself
 * while ComfortActivity/TelemetryActivity are what the driver is actually
 * looking at (the overlay exists for OTHER apps, not to duplicate our own
 * screen's controls on top of itself). */
public final class AppForeground {
    public interface Listener { void onForeground(boolean foreground); }
    private static final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public static void removeListener(Listener l) { if (l != null) listeners.remove(l); }

    // started, not resumed: a system dialog or transient overlay pausing our
    // Activity without stopping it should not flip this — "is one of our
    // screens still on screen at all" is the question, not "has it got
    // touch focus this instant."
    private static volatile int startedCount = 0;
    private static volatile boolean subscribed = false;

    public static boolean isForeground() { return startedCount > 0; }

    public static synchronized void ensureTracking(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        Application app = (Application) ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                boolean was = startedCount > 0;
                startedCount++;
                if (!was) fanOut(true);
            }
            @Override public void onActivityStopped(Activity a) {
                startedCount = Math.max(0, startedCount - 1);
                if (startedCount == 0) fanOut(false);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static void fanOut(boolean foreground) {
        for (Listener l : listeners) {
            try { l.onForeground(foreground); } catch (Throwable ignored) {}
        }
    }

    private AppForeground() {}
}
