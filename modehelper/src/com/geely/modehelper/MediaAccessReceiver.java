package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** Grants (on=true) or removes (on=false) Drive Assist's notification-
 * listener access, which Android requires before an app may read the car's
 * media sessions -- the song the phone plays over Bluetooth, for the music
 * card. Drive Assist's own listener reads no notifications.
 *
 * Exported so Drive Assist can ask; the only component it ever touches is
 * MediaAccessPolicy.COMPONENT, so no other app can use it to grant itself
 * anything. Needs WRITE_SECURE_SETTINGS, which this platform-signed helper has. */
public class MediaAccessReceiver extends BroadcastReceiver {
    static final String TAG = "ModeHelper";
    static final String KEY = "enabled_notification_listeners";

    @Override public void onReceive(Context ctx, Intent intent) {
        boolean on = intent != null && intent.getBooleanExtra("on", false);
        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(), KEY);
            String next = MediaAccessPolicy.edit(cur, on);
            if (next == null) { Log.i(TAG, "media-access: already " + (on ? "granted" : "off")); return; }
            Settings.Secure.putString(ctx.getContentResolver(), KEY, next);
            Log.i(TAG, "media-access: " + (on ? "granted" : "removed") + " for Drive Assist");
        } catch (Throwable t) { Log.w(TAG, "media-access: " + t); }
    }
}
