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
            if (AdbControl.alwaysOn(ctx)) {
                Log.i(TAG, "adb: window expired, but \"always on\" is set -- keeping it open");
                return;
            }
            Log.i(TAG, "adb: window expired, closing");
            AdbControl.set(ctx.getApplicationContext(), false, 0);
            return;
        }
        if (AdbControl.ACTION_SET_ALWAYS.equals(a)) {
            boolean on = i.getBooleanExtra("on", false);
            boolean was = AdbControl.alwaysOn(ctx);
            if (on != was) {
                AdbControl.setAlwaysOn(ctx, on);
                Log.i(TAG, "adb: \"always on\" is now " + (on ? "ON" : "OFF"));
                // Turning it on opens adb now; turning it off closes it now
                // (WifiGuardReceiver re-opens it on its next tick if the car
                // is on the trusted Wi-Fi). A sync of an unchanged value
                // (sent at every app start) touches nothing.
                if (on) AdbControl.setIndefinite(ctx.getApplicationContext(), true);
                else AdbControl.set(ctx.getApplicationContext(), false, 0);
            } else if (on && !AdbControl.isEnabled(ctx)) {
                AdbControl.setIndefinite(ctx.getApplicationContext(), true);
            }
            return;
        }
        if (!AdbControl.ACTION_SET.equals(a)) return;

        boolean on = i.getBooleanExtra("enable", false);
        int minutes = i.getIntExtra("minutes", AdbControl.DEFAULT_MINUTES);
        Log.i(TAG, "adb: asked to turn " + (on ? "ON for " + minutes + " min" : "OFF"));
        AdbControl.set(ctx.getApplicationContext(), on, minutes);
    }
}
