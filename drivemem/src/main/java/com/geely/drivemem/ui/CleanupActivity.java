package com.geely.drivemem.ui;

import com.geely.drivemem.R;

import com.geely.drivemem.services.OutTempService;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.services.WifiIconService;
import com.geely.drivemem.util.BootReceiver;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Cleanup screen: removes Drive Assist and everything of ours from the head
 * unit, so it looks the way a workshop expects. It touches only our own
 * things — never another app on the car.
 *
 * "Remove" hands the privileged work to modehelper ({@link #HELPER_CLEANUP}):
 * it stops the CAN reader and deletes its file, gives the car's own lock
 * sound back, sets ADB, then uninstalls Drive Assist and itself. The owner
 * chooses whether ADB is closed: closing it is for a workshop visit, and
 * then the way back in is by hand (car Settings, Developer options, ADB on,
 * reinstall from the USB stick). Nothing is left behind to re-open it.
 *
 * The deep root from recovery (ro.secure=0, test-keys) is not ours and is not
 * touched. Every action needs two taps. */
public class CleanupActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView log;
    private View armedView;          // the one button armed for its second tap

    private static final String HELPER_PKG = "com.geely.modehelper";
    private static final String HELPER_CLEANUP = "com.geely.modehelper.CLEANUP";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);                 // before any View
        Style.edgeToEdge(this);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackground(Style.screenBg());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f));
        int pad = Style.dp(this, 22);
        root.setPadding(pad, pad + Style.statusBarHeight(this), pad, pad);

        // header: back + title (a destructive screen needs a visible way out)
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Style.backButton(this, this::finish));
        TextView t = Style.title(this, getString(R.string.cleanup_title)); t.setTextSize(22);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Style.dp(this, 14);
        t.setLayoutParams(tlp);
        head.addView(t);
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setTextColor(Style.TEXT_DIM); sub.setTextSize(14);
        sub.setText(getString(R.string.cleanup_subtitle));
        sub.setPadding(0, Style.dp(this,4), 0, 0);
        root.addView(sub);

        root.addView(Style.header(this, getString(R.string.cleanup_step1_header)));
        root.addView(action(getString(R.string.cleanup_action_stop_wipe), 0xFF3A4048, this::stopAndWipeDriveAssistData));

        root.addView(Style.header(this, getString(R.string.cleanup_step2_header)));
        root.addView(action(getString(R.string.cleanup_action_uninstall_self), 0xFF7A4A20, () -> remove(false)));
        root.addView(action(getString(R.string.cleanup_action_remove_close_adb), 0xFF8A3A3A, () -> remove(true)));

        TextView note = new TextView(this);
        note.setTextColor(Style.TEXT_DIM); note.setTextSize(13);
        note.setText(getString(R.string.cleanup_adb_note));
        note.setPadding(0, Style.dp(this, 8), 0, 0);
        root.addView(note);

        log = new TextView(this);
        log.setTextColor(Style.TEXT); log.setTextSize(14);
        log.setPadding(0, Style.dp(this,14), 0, 0);
        log.setText(getString(R.string.cleanup_log_idle));
        ScrollView sv = new ScrollView(this);
        sv.addView(log);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(sv);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        outer.addView(root); outer.addView(spacer);
        setContentView(outer);
    }

    private TextView action(String label, int color, Runnable onClick) {
        TextView v = new TextView(this);
        v.setText(label); v.setTextColor(Style.onFill(color)); v.setTextSize(17);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(Style.card(color, this));
        int p = Style.dp(this, 16); v.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(this, 8); v.setLayoutParams(lp);
        // Confirmation: the first tap arms THIS button, the second runs it.
        // Armed per-button, so tapping a different button is a fresh arm, not
        // a second tap that would fire the wrong (destructive) action.
        v.setOnClickListener(x -> {
            if (armedView != v) {
                armedView = v;
                say(getString(R.string.cleanup_confirm_prompt, label));
                ui.postDelayed(() -> { if (armedView == v) armedView = null; }, 6000);
            } else { armedView = null; onClick.run(); }
        });
        return v;
    }

    // Step 1: stops the services, takes the icons off the bar and wipes the preferences
    private void stopAndWipeDriveAssistData() {
        say(getString(R.string.cleanup_stopping));
        try {
            stopService(new Intent(this, TelemetryService.class));
            stopService(new Intent(this, OutTempService.class));
            stopService(new Intent(this, WifiIconService.class));
        } catch (Throwable ignored) {}
        // cancels the status bar icons
        try {
            android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancelAll();
        } catch (Throwable ignored) {}
        // cancels the watchdog alarm
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, BootReceiver.class).setAction("com.geely.drivemem.WATCHDOG");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 1001, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
            am.cancel(pi);
        } catch (Throwable ignored) {}
        // wipes the preferences (MQTT credentials, saved modes, etc.)
        try {
            Prefs.clearAll(this);
        } catch (Throwable ignored) {}
        say(getString(R.string.cleanup_wiped));
    }

    // Step 2: hand the privileged removal to modehelper, which stops the CAN
    // reader, restores the lock sound, sets ADB, and uninstalls both apps.
    // closeAdb true leaves ADB off (for a workshop visit); false keeps it on.
    private void remove(boolean closeAdb) {
        stopAndWipeDriveAssistData();
        if (!helperHasCleanup()) {
            // No helper (not installed, or too old): we cannot stop the CAN
            // reader or set ADB from here. Say so, then ask for our own
            // removal the plain way.
            say(getString(R.string.cleanup_helper_missing));
            ui.postDelayed(this::uninstallSelf, 1500);
            return;
        }
        say(getString(R.string.cleanup_removing_self));
        try {
            Intent i = new Intent(HELPER_CLEANUP)
                .setClassName(HELPER_PKG, "com.geely.modehelper.CleanupReceiver")
                .putExtra("close_adb", closeAdb);
            sendBroadcast(i);
            // modehelper now does the rest and uninstalls both apps. This
            // screen disappears with Drive Assist; nothing more to do here.
        } catch (Throwable t) {
            say(getString(R.string.cleanup_failed, String.valueOf(t)));
        }
    }

    // Fallback only (no helper): the app asks for its own removal.
    private void uninstallSelf() {
        say(getString(R.string.cleanup_removing_self));
        ui.postDelayed(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) { say(getString(R.string.cleanup_failed, String.valueOf(t))); }
        }, 1200);
    }

    private boolean helperHasCleanup() {
        try {
            return !getPackageManager().queryBroadcastReceivers(
                new Intent(HELPER_CLEANUP).setPackage(HELPER_PKG), 0).isEmpty();
        } catch (Throwable t) { return false; }
    }

    private void say(String s) { ui.post(() -> log.setText(s)); }
}
