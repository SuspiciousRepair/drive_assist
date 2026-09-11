package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts or stops dashcam recording. Receives DASHCAM broadcasts from drivemem.
 * Exported because drivemem has a different signer (signature-level permissions
 * cannot fence it). Starting/stopping recording is low-risk: it's user-visible
 * and bounded by the ring buffer. */
public class DashReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        int on = i.getIntExtra("on", -1);
        Log.i(CarMode.TAG, "dashcam: broadcast on=" + on);
        Intent svc = new Intent(ctx, ModeHelperService.class)
                        .setAction(ModeHelperService.ACTION_DASHCAM)
                        .putExtra("on", on);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) { Log.w(CarMode.TAG, "dashcam: start failed: " + t); }
    }
}
