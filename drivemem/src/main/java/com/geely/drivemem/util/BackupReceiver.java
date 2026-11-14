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
 *   adb shell am broadcast -a com.geely.drivemem.RESTORE \
 *       --es file "/sdcard/Drive Assist/Backups/<stamp>.dabackup" [--es password ...] \
 *       -n com.geely.drivemem/.util.BackupReceiver
 *
 * BACKUP takes an optional "password" too: it is used only when no backup
 * password is set yet. RESTORE needs "password" only when the key set in the
 * app did not lock that backup (after a reinstall).
 *
 * Guarded by android.permission.DUMP, which the adb shell holds and no
 * ordinary app can get: nothing else on the car can trigger a restore.
 * The work runs on its own thread and reports in logcat ("backup: ..."),
 * never with the password in it.
 */
public final class BackupReceiver extends BroadcastReceiver {
    public static final String ACTION_BACKUP = "com.geely.drivemem.BACKUP";
    public static final String ACTION_RESTORE = "com.geely.drivemem.RESTORE";

    @Override public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final String action = intent.getAction();
        final String file = intent.getStringExtra("file");
        final String pw = intent.getStringExtra("password");
        new Thread(() -> {
            try {
                if (ACTION_BACKUP.equals(action)) {
                    if (pw != null && !AppBackup.hasPassword(app)) AppBackup.setPassword(app, pw.toCharArray());
                    Log.i(CarAccess.TAG, "backup: ok " + AppBackup.backupNow(app));
                } else if (ACTION_RESTORE.equals(action) && file != null) {
                    // restarts the app on success
                    AppBackup.restore(app, new File(file), pw == null ? null : pw.toCharArray());
                }
            } catch (Throwable t) {
                Log.w(CarAccess.TAG, "backup: failed: " + t);
            }
        }, "backup-adb").start();
    }
}
