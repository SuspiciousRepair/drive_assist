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
 * Rule: bootstrap all shared vehicle state tracking in Application.onCreate(),
 * never inside TelemetryService.
 * Invariant: core car tracking must survive background service restarts and
 * run even when MQTT telemetry is toggled off.
 * See docs/incidents.md#2026-09-14-core-state-lifecycle
 */
public final class DriveMemApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // ClipRecoveryService runs in its own ":cliprecovery" process (MediaMuxer
        // can abort native code). Android runs this onCreate there too, and
        // without this guard that process started a SECOND CarActor, trip and
        // charge state machines, sampler, ABRP and OBD2 reader — double rows in
        // car.db and two readers fighting for the dongle. Recovery needs none of it.
        if (!isMainProcess(Application.getProcessName(), getPackageName())) return;
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

    /** True for the app's main process (no ":suffix"). An unknown name counts
     * as main, so a failed lookup never switches the app off. */
    static boolean isMainProcess(String processName, String packageName) {
        return processName == null || processName.equals(packageName);
    }
}
