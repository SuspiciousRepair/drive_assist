package com.geely.drivemem.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;

import java.io.File;

/** The same backup and restore, driven from a computer (tools/car-backup.sh):
 *
 *   adb shell am broadcast -a com.geely.drivemem.BACKUP -n com.geely.drivemem/.util.BackupReceiver
 *   adb shell am broadcast -a com.geely.drivemem.RESTORE --es dir "/sdcard/Drive Assist/Backups/<stamp>" \
 *       -n com.geely.drivemem/.util.BackupReceiver
 *
 * Guarded by android.permission.DUMP, which the adb shell holds and no
 * ordinary app can get: nothing else on the car can trigger a restore.
 * The work runs on its own thread and reports in logcat ("backup: ...").
 */
public final class BackupReceiver extends BroadcastReceiver {
    public static final String ACTION_BACKUP = "com.geely.drivemem.BACKUP";
    public static final String ACTION_RESTORE = "com.geely.drivemem.RESTORE";

    @Override public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final String action = intent.getAction();
        final String dir = intent.getStringExtra("dir");
        new Thread(() -> {
            try {
                if (ACTION_BACKUP.equals(action)) {
                    Log.i(CarAccess.TAG, "backup: ok " + AppBackup.backupNow(app));
                } else if (ACTION_RESTORE.equals(action) && dir != null) {
                    AppBackup.restore(app, new File(dir));   // restarts the app on success
                }
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "backup: failed: " + t);
            }
        }, "backup-adb").start();
    }
}
