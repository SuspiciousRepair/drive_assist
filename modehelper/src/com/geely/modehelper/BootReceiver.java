package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts ModeHelperService and adb/WiFi guards at boot. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Log.i(CarMode.TAG, "boot received: " + intent.getAction() + " - starting helper");
        try {
            Intent svc = new Intent(ctx, ModeHelperService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) { Log.w(CarMode.TAG, "boot start failed: " + t); }

        // Open adb for 5 minutes at boot (to survive reconnects after reboots without
        // user intervention). WifiGuardReceiver extends this to indefinite on the
        // trusted "car" network, independent of this window.
        try {
            AdbControl.set(ctx.getApplicationContext(), true, 5);
            Log.i(CarMode.TAG, "adb open for 5 min at boot (longer if on \"car\" WiFi)");
        } catch (Throwable t) { Log.w(CarMode.TAG, "boot adb open failed: " + t); }

        // Start WiFi guard polling.
        try { WifiGuardReceiver.start(ctx); }
        catch (Throwable t) { Log.w(CarMode.TAG, "wifiguard start failed: " + t); }

        // Keep WiFi turned on (own implementation — see WifiKeepOnReceiver's header).
        try { WifiKeepOnReceiver.start(ctx); }
        catch (Throwable t) { Log.w(CarMode.TAG, "wifi-keepon start failed: " + t); }
    }
}
