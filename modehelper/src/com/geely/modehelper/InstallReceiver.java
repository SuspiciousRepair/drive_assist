package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Receives install requests (INSTALL_APK action) with an APK URL. Exported to be
 * callable from other apps (drivemem). Safe because Installer verifies the payload
 * (HTTPS, package name, signature) rather than trusting the caller. */
public class InstallReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";
    public static final String INSTALL_APK = "com.geely.modehelper.INSTALL_APK";

    @Override public void onReceive(Context ctx, Intent i) {
        if (!INSTALL_APK.equals(i.getAction())) return;
        String url = i.getStringExtra("url");
        Log.i(TAG, "install: asked for " + url);
        // goAsync is not used: Installer spawns its own thread and the download
        // outlives any receiver window we could hold open anyway.
        Installer.install(ctx.getApplicationContext(), url);
    }
}
