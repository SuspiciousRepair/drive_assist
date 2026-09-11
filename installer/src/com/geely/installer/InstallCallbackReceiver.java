package com.geely.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BroadcastReceiver for PackageInstaller session results.
 * Statically registered to guarantee delivery on Android 9+ even across process boundaries.
 */
public class InstallCallbackReceiver extends BroadcastReceiver {
    private static final String TAG = "DriveAssistInstaller";

    public interface Callback {
        void onResult(int status, String message, Intent intent);
    }

    private static final Map<Integer, Callback> CALLBACKS = new ConcurrentHashMap<>();

    public static void registerCallback(int sessionId, Callback cb) {
        CALLBACKS.put(sessionId, cb);
    }

    public static void unregisterCallback(int sessionId) {
        CALLBACKS.remove(sessionId);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "InstallCallback: sessionId=" + sessionId + " status=" + status + " msg=" + msg);

        Callback cb = CALLBACKS.remove(sessionId);
        if (cb != null) {
            cb.onResult(status, msg, intent);
        } else {
            Log.w(TAG, "InstallCallback: no registered callback for sessionId " + sessionId);
        }
    }
}
