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
                // Rarely seen: the process being replaced is usually gone first.
                Log.i(TAG, "install: SUCCESS");
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
}
