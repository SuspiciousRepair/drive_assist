package com.geely.drivemem;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.sensors.EnergyIntegrator;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.sensors.TelemetryRollup;
import com.geely.drivemem.sensors.TelemetrySampler;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.state.CarplayState;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.state.ParkSession;
import com.geely.drivemem.state.ParkingState;
import com.geely.drivemem.state.TripSession;
import com.geely.drivemem.state.ValetSession;
import com.geely.drivemem.util.AppForeground;
import com.geely.drivemem.util.DbMigration;

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
 * Application.onCreate() is the one place guaranteed to run first.
 *
 * The same reasoning now applies to every piece of core car-state tracking
 * below: it used to live in TelemetryService.onStartCommand(), on the theory
 * that the service is the one thing guaranteed to start early. It is not —
 * logcat shows it crashed and got force-restarted four times in one evening
 * (2026-09-14, ~22:17-22:46), and it is skipped entirely by BootReceiver
 * whenever MQTT telemetry itself is turned off. Every time either happens,
 * TripSession/ParkSession/ParkingState/ChargeSession/EnergyIntegrator and
 * the rest silently stop reacting to the car at all until something else
 * happens to start the service again — which is exactly how ParkingState's
 * "parked since" clock was found stuck at a park from the previous day,
 * surviving a real drive in between because the listener that should have
 * cleared it was never registered. These are the car's own shared state —
 * they must not depend on a feature-toggleable service's uptime. */
public final class DriveMemApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppForeground.ensureTracking(this);

        ComfortHub.get(this);
        CarActor.get(this);
        DbMigration.runOnce(this);
        TelemetryRollup.runIfDue(this);
        CarState.ensureSubscribed();
        ChargeSession.ensureSubscribed(this);
        TelemetrySampler.ensureSubscribed(this);
        TripSession.ensureSubscribed(this);
        ParkSession.ensureSubscribed(this);
        ParkingState.ensureSubscribed(this);
        ValetSession.ensureSubscribed(this);
        EnergyIntegrator.ensureSubscribed(this);
        Obd2Reader.ensureStarted(this);   // no-ops unless obd2_enabled is set
        AbrpUploader.ensureSubscribed(this);
        CarplayState.ensureSubscribed(this);
    }
}
