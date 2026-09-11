package com.geely.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Headless receiver allowing ADB automation:
 * `adb shell am broadcast -a com.geely.installer.INSTALL -n com.geely.installer/.InstallerReceiver`
 */
public class InstallerReceiver extends BroadcastReceiver {
    private static final String TAG = "DriveAssistInstaller";
    public static final String ACTION_INSTALL = "com.geely.installer.INSTALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL.equals(intent.getAction())) {
            return;
        }

        Log.i(TAG, "InstallerReceiver: received ACTION_INSTALL");
        InstallerCore.run(context.getApplicationContext(), new InstallerCore.Listener() {
            @Override
            public void onLog(String message) {
                Log.i(TAG, "CLI: " + message);
            }

            @Override
            public void onProgress(String statusText, int progressPercent) {
                Log.i(TAG, "CLI [" + progressPercent + "%]: " + statusText);
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "CLI ERROR: " + errorMessage);
            }

            @Override
            public void onSuccess() {
                Log.i(TAG, "CLI SUCCESS: Drive Assist setup complete.");
            }
        });
    }
}
