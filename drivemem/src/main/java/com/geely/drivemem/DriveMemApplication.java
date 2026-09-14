package com.geely.drivemem;

import com.geely.drivemem.util.AppForeground;

import android.app.Application;

/** Only reason this class exists: AppForeground.ensureTracking() has to run
 * before ANY Activity in the process gets a chance to start, or that
 * Activity's own onActivityStarted callback is missed forever (Application
 * .ActivityLifecycleCallbacks only fires for events after registration).
 * Registering it lazily from OverlayService — whenever it happened to first
 * start — left a real race: if the overlay started at roughly the same
 * moment as ComfortActivity (both can happen off the same boot/wake), the
 * Activity's start could win that race, and the overlay would never learn
 * the app was already in front of it — both showing on screen together.
 * Application.onCreate() is the one place guaranteed to run first. */
public final class DriveMemApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppForeground.ensureTracking(this);
    }
}
