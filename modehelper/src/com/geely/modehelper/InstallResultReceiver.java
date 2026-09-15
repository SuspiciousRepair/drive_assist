package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

// Receives PackageInstaller status callbacks. On success, the install replaces
// drivemem, which terminates its process — so drivemem cannot be notified of
// success. Instead, MY_PACKAGE_REPLACED triggers BootReceiver, which restarts
// telemetry and re-arms the watchdog; the car's return to MQTT `online` status
// is the success signal. This receiver exists only to report failures, when the
// app is still alive to hear it.
public class InstallResultReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";

    @Override public void onReceive(Context ctx, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                   PackageInstaller.STATUS_FAILURE);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                // Rarely seen for drivemem/modehelper themselves: the process
                // being replaced is usually gone first. Reliably seen for the
                // installer package, though -- installing IT never kills this
                // process, since it's a third, independent package. That's
                // exactly the case that needs a nudge: being installed does
                // not run it, so launch it to actually do its job (install
                // fresh drivemem + modehelper, then delete itself).
                Log.i(TAG, "install: SUCCESS");
                if ("com.geely.installer".equals(i.getStringExtra("targetPkg"))) {
                    launchInstaller(ctx);
                }
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // Indicates INSTALL_PACKAGES is not granted, meaning the build is
                // not platform-signed or running with the correct uid.
                Log.e(TAG, "install: the system wants user confirmation — "
                         + "INSTALL_PACKAGES is NOT in effect (platform signature? uid system?)");
                break;
            default:
                Log.e(TAG, "install: FAILED status=" + status + " " + msg);
        }
    }

    private static void launchInstaller(Context ctx) {
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage("com.geely.installer");
            if (launch == null) {
                Log.w(TAG, "install: com.geely.installer has no launch intent");
                return;
            }
            // Recorded BEFORE launching, not after: this line races
            // ModeHelperService's own restart (this install just replaced
            // modehelper itself) against ModeHelperService.cleanupInstaller(),
            // which otherwise sees the installer package this launch just
            // put there and treats it as a stale leftover from an OLD,
            // abandoned run -- silently uninstalling it out from under
            // itself mid-flight. cleanupInstaller() checks this timestamp
            // and gives a fresh launch a grace window instead.
            ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE).edit()
                .putLong("installer_launched_ms", System.currentTimeMillis()).apply();
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
            Log.i(TAG, "install: launched com.geely.installer to finish refreshing drivemem + modehelper");
        } catch (Throwable t) {
            Log.w(TAG, "install: failed to launch installer: " + t, t);
        }
    }
}
