package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Explicit Drive Assist control for the default-off parked-monitoring test. */
public final class ParkedMonitoringReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int on = intent.getIntExtra("on", 0);
        Intent service = new Intent(context, ModeHelperService.class)
            .setAction(ModeHelperService.ACTION_PARKED_MONITORING)
            .putExtra("on", on);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Throwable t) {
            Log.w(ModeHelperService.TAG, "parked runtime: could not apply setting", t);
        }
    }
}
