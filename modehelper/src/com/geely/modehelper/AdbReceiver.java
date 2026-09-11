package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Receives adb control requests from drivemem (Drive Assist), which cannot write
 * Settings.Global.ADB_ENABLED itself. Exported (signed differently from Drive Assist)
 * so it is reachable. Exported does not mean unsecured: every adb enable has a
 * deadline timeout; re-enables are logged and visible. MQTT-based remote enables
 * are gated separately in drivemem (only on home network). */
public class AdbReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";

    @Override public void onReceive(Context ctx, Intent i) {
        String a = i.getAction();
        if (AdbControl.ACTION_AUTO_OFF.equals(a)) {
            Log.i(TAG, "adb: window expired, closing");
            AdbControl.set(ctx.getApplicationContext(), false, 0);
            return;
        }
        if (!AdbControl.ACTION_SET.equals(a)) return;

        boolean on = i.getBooleanExtra("enable", false);
        int minutes = i.getIntExtra("minutes", AdbControl.DEFAULT_MINUTES);
        Log.i(TAG, "adb: asked to turn " + (on ? "ON for " + minutes + " min" : "OFF"));
        AdbControl.set(ctx.getApplicationContext(), on, minutes);
    }
}
