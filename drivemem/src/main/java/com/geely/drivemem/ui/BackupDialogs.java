package com.geely.drivemem.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.AppBackup;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** The backup screens: "back up now", "pick a backup to restore", and the
 * one-time "restore your backup?" question after a fresh install.
 *
 * Restore restarts the app, so it only runs in Park, like an update. The
 * fresh-install question is a modal, so it is also Park-only and is closed
 * by {@link #dismissOffer()} when the car leaves Park. */
public final class BackupDialogs {
    private BackupDialogs() {}

    private static AlertDialog offer;
    private static boolean offerLookup;

    static String describe(Activity a, AppBackup.Found f) {
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(new Date(f.createdMs));
        return when + " — " + a.getString(f.onUsb ? R.string.backup_where_usb : R.string.backup_where_car);
    }

    /** Back up now, with a toast for the result. */
    public static void backupNow(Activity a, Runnable after) {
        Toast.makeText(a, a.getString(R.string.backup_running), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                List<File> w = AppBackup.backupNow(a);
                StringBuilder where = new StringBuilder();
                for (File d : w) {
                    if (where.length() > 0) where.append(", ");
                    where.append(a.getString(AppBackup.onUsb(d) ? R.string.backup_where_usb : R.string.backup_where_car));
                }
                msg = a.getString(R.string.backup_ok, where.toString());
            } catch (Throwable t) {
                msg = a.getString(R.string.backup_failed, String.valueOf(t.getMessage()));
            }
            final String m = msg;
            a.runOnUiThread(() -> {
                Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                if (after != null) after.run();
            });
        }, "backup-now").start();
    }

    /** Lists the backups and restores the one the user picks. */
    public static void pickAndRestore(Activity a) {
        if (!CarState.isParked()) {
            Toast.makeText(a, a.getString(R.string.backup_park_first), Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            List<AppBackup.Found> found = AppBackup.list();
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (found.isEmpty()) {
                    new AlertDialog.Builder(a)
                        .setTitle(a.getString(R.string.backup_restore))
                        .setMessage(a.getString(R.string.backup_none_found))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                String[] items = new String[found.size()];
                for (int i = 0; i < items.length; i++) items[i] = describe(a, found.get(i));
                new AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.backup_pick_title))
                    .setItems(items, (d, which) -> confirm(a, found.get(which)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        }, "backup-list").start();
    }

    private static void confirm(Activity a, AppBackup.Found f) {
        new AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.backup_restore))
            .setMessage(a.getString(R.string.backup_restore_confirm, describe(a, f)))
            .setPositiveButton(a.getString(R.string.backup_restore), (d, w) -> run(a, f))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void run(Activity a, AppBackup.Found f) {
        if (!CarState.isParked()) {
            Toast.makeText(a, a.getString(R.string.backup_park_first), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(a, a.getString(R.string.backup_restoring), Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                AppBackup.restore(a, f.dir);   // restarts the app on success
            } catch (Throwable t) {
                a.runOnUiThread(() -> new AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.backup_restore))
                    .setMessage(a.getString(R.string.backup_restore_failed, String.valueOf(t.getMessage())))
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
            }
        }, "backup-restore").start();
    }

    /** From ComfortActivity.onResume: after a restore, say what came back;
     * on a fresh install, offer the newest backup once. */
    public static void onResume(Activity a) {
        String[] note = AppBackup.takeNote(a);
        if (note != null) {
            boolean already = Boolean.parseBoolean(note[2]);
            String msg = already
                ? a.getString(R.string.backup_restored_trips_there, Integer.parseInt(note[0]))
                : a.getString(R.string.backup_restored, Integer.parseInt(note[0]), Long.parseLong(note[1]));
            Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
            return;
        }
        if (offerLookup || (offer != null && offer.isShowing())) return;
        if (AppBackup.offerDone(a) || !CarState.isParked()) return;
        if (!AppBackup.looksFresh(a)) { AppBackup.setOfferDone(a); return; }
        offerLookup = true;
        new Thread(() -> {
            List<AppBackup.Found> found = AppBackup.list();
            a.runOnUiThread(() -> {
                offerLookup = false;
                // No backup now is not "no" for good: a stick put in later
                // gets asked about on a later resume.
                if (found.isEmpty() || a.isFinishing() || !CarState.isParked()) return;
                AppBackup.Found newest = found.get(0);
                offer = new AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.backup_offer_title))
                    .setMessage(a.getString(R.string.backup_offer_msg, describe(a, newest)))
                    .setPositiveButton(a.getString(R.string.backup_restore), (d, w) -> run(a, newest))
                    .setNegativeButton(a.getString(R.string.backup_offer_no), (d, w) -> AppBackup.setOfferDone(a))
                    .setNeutralButton(a.getString(R.string.backup_offer_other), (d, w) -> pickAndRestore(a))
                    .create();
                offer.show();
            });
        }, "backup-offer").start();
    }

    /** DRIVING SAFETY: the car left Park. */
    public static void dismissOffer() {
        if (offer != null && offer.isShowing()) {
            try { offer.dismiss(); } catch (Throwable ignored) {}
        }
        offer = null;
    }
}
