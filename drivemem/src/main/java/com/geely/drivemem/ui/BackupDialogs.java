package com.geely.drivemem.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.AppBackup;
import com.geely.drivemem.util.BackupCrypto;
import com.geely.drivemem.util.Style;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** The backup screens: "back up now", "set the backup password", "pick a
 * backup to restore", and the one-time "restore your backup?" question after
 * a fresh install.
 *
 * Restore restarts the app, so it only runs in Park, like an update. Typing
 * a password is also Park-only. The fresh-install question and the password
 * boxes are modals, closed by {@link #dismissOffer()} when the car leaves
 * Park. */
public final class BackupDialogs {
    private BackupDialogs() {}

    private static AlertDialog offer;
    private static AlertDialog pwDialog;
    private static boolean offerLookup;

    static String describe(Activity a, AppBackup.Found f) {
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(new Date(f.createdMs));
        return when + " — " + a.getString(f.onUsb ? R.string.backup_where_usb : R.string.backup_where_car);
    }

    /** Back up now, with a toast for the result. With no password set yet,
     * asks for one first. */
    public static void backupNow(Activity a, Runnable after) {
        if (!AppBackup.hasPassword(a)) {
            setPassword(a, () -> backupNow(a, after));
            return;
        }
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

    /** Sets or changes the backup password: typed twice, 6 characters or
     * more. then runs after it is saved. */
    public static void setPassword(Activity a, Runnable then) {
        if (!CarState.isParked()) {
            Toast.makeText(a, a.getString(R.string.backup_park_first), Toast.LENGTH_LONG).show();
            return;
        }
        final boolean change = AppBackup.hasPassword(a);
        EditText pw = passwordBox(a, R.string.backup_pw_hint, EditorInfo.IME_ACTION_NEXT);
        EditText again = passwordBox(a, R.string.backup_pw_again_hint, EditorInfo.IME_ACTION_DONE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(a, 24);
        box.setPadding(pad, 0, pad, 0);
        box.addView(pw);
        box.addView(again);
        String msg = a.getString(R.string.backup_pw_set_msg)
            + (change ? "\n" + a.getString(R.string.backup_pw_change_msg) : "");
        AlertDialog d = new AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.backup_pw_set_title))
            .setMessage(msg)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)   // set below: stays open on a mistake
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        d.setOnShowListener(x -> {
            Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String p1 = pw.getText().toString();
                if (p1.length() < AppBackup.MIN_PASSWORD) {
                    pw.requestFocus();
                    pw.setError(a.getString(R.string.backup_pw_short));
                    return;
                }
                if (!p1.equals(again.getText().toString())) {
                    again.requestFocus();
                    again.setError(a.getString(R.string.backup_pw_mismatch));
                    return;
                }
                ok.setEnabled(false);
                new Thread(() -> {
                    String err = null;
                    try { AppBackup.setPassword(a, p1.toCharArray()); } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
                    final String e = err;
                    a.runOnUiThread(() -> {
                        try { d.dismiss(); } catch (Throwable ignored) {}
                        Toast.makeText(a, e == null ? a.getString(R.string.backup_pw_saved)
                            : a.getString(R.string.backup_failed, e), Toast.LENGTH_LONG).show();
                        if (e == null && then != null) then.run();
                    });
                }, "backup-password").start();
            });
            // The car keyboard covers the lower half of the screen, OK
            // included: its Enter key goes to the next box, then presses OK.
            again.setOnEditorActionListener((v, id, ev) -> { ok.performClick(); return true; });
        });
        showPw(d);
    }

    /** Asks the password that locked f, then restores. Asks again when wrong. */
    private static void askPassword(Activity a, AppBackup.Found f, boolean wrongBefore) {
        if (a.isFinishing() || !CarState.isParked()) return;
        EditText pw = passwordBox(a, R.string.backup_pw_enter_title, EditorInfo.IME_ACTION_DONE);
        if (wrongBefore) pw.setError(a.getString(R.string.backup_pw_wrong));
        LinearLayout box = new LinearLayout(a);
        int pad = Style.dp(a, 24);
        box.setPadding(pad, 0, pad, 0);
        box.addView(pw);
        AlertDialog d = new AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.backup_pw_enter_title))
            .setMessage(a.getString(R.string.backup_pw_enter_msg, describe(a, f)))
            .setView(box)
            .setPositiveButton(a.getString(R.string.backup_restore),
                (x, w) -> restoreWith(a, f, pw.getText().toString().toCharArray()))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        pw.setOnEditorActionListener((v, id, ev) -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        showPw(d);
    }

    private static EditText passwordBox(Activity a, int hint, int imeAction) {
        EditText e = new EditText(a);
        // setSingleLine FIRST: called after, it drops the password dots and
        // shows the password in plain text.
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        e.setImeOptions(imeAction | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setHint(a.getString(hint));
        e.setTextSize(28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        e.setLayoutParams(lp);
        return e;
    }

    private static void showPw(AlertDialog d) {
        if (pwDialog != null && pwDialog.isShowing()) {
            try { pwDialog.dismiss(); } catch (Throwable ignored) {}
        }
        pwDialog = d;
        // Shrink the dialog above the keyboard instead of under it.
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        // Wider than the default, so the text takes fewer lines and the
        // boxes and buttons stay above the keyboard.
        if (d.getWindow() != null) {
            d.getWindow().setLayout(Style.dp(d.getContext(), 1200),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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
        new Thread(() -> {
            if (AppBackup.needsPassword(a, f.file)) a.runOnUiThread(() -> askPassword(a, f, false));
            else a.runOnUiThread(() -> restoreWith(a, f, null));
        }, "backup-check").start();
    }

    private static void restoreWith(Activity a, AppBackup.Found f, char[] password) {
        if (!CarState.isParked()) {
            Toast.makeText(a, a.getString(R.string.backup_park_first), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(a, a.getString(R.string.backup_restoring), Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                AppBackup.restore(a, f.file, password);   // restarts the app on success
            } catch (BackupCrypto.WrongPassword | AppBackup.NeedPassword t) {
                a.runOnUiThread(() -> askPassword(a, f, true));
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
        for (AlertDialog d : new AlertDialog[]{offer, pwDialog}) {
            if (d != null && d.isShowing()) {
                try { d.dismiss(); } catch (Throwable ignored) {}
            }
        }
        offer = null;
        pwDialog = null;
    }
}
