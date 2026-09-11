package com.geely.drivemem.ui;

import com.geely.drivemem.R;

import com.geely.drivemem.services.OutTempService;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.services.WifiIconService;
import com.geely.drivemem.util.BootReceiver;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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

// TODO: Another app opened a new option to remove AVAS sound completely. I want to revert to original (no option to silence it) now, independent of a clean-up. But we should also remove it in the clean-up
// TODO: We have changed the app a lot after this was introduced. We should review the cleanup process and make sure it is still valid and complete. Also remove reference to Drive Assist,
/** Cleanup screen: undoes Drive Assist's modifications to restore the head unit
 * toward factory state. Both cleanup actions require confirmation. System
 * apps and ADB settings are not touched. */
public class CleanupActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView log;
    private boolean armed = false;   // demands 2 taps for the destructive actions

    // custom apps installed on this unit (touches nothing belonging to the system)
    private static final String[] CUSTOM_APPS = {
        "app.revanced.android.youtube",
        "app.revanced.android.gms",
        "app.revanced.manager.flutter",
        "com.cxinventor.file.explorer",
        "org.mozilla.firefox",
        "com.waze",
    };

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
        root.addView(action(getString(R.string.cleanup_action_uninstall_apps), 0xFF7A4A20, this::uninstallCustomApps));
        root.addView(action(getString(R.string.cleanup_action_uninstall_self), 0xFF8A3A3A, this::uninstallSelf));

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
        // confirmation: the first tap arms, the second one executes
        v.setOnClickListener(x -> {
            if (!armed) {
                armed = true;
                say(getString(R.string.cleanup_confirm_prompt, label));
                ui.postDelayed(() -> { armed = false; }, 6000);
            } else { armed = false; onClick.run(); }
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
            SharedPreferences p = getSharedPreferences("drivemem", MODE_PRIVATE);
            p.edit().clear().commit();
        } catch (Throwable ignored) {}
        say(getString(R.string.cleanup_wiped));
    }

    // Step 2a: asks for the custom apps to be uninstalled (the system confirms each one)
    private void uninstallCustomApps() {
        int asked = 0;
        // whole sentence lives in the resources; the list (package ids) is assembled here
        StringBuilder sb = new StringBuilder(getString(R.string.cleanup_uninstall_header)).append("\n");
        for (String pkg : CUSTOM_APPS) {
            if (!isInstalled(pkg)) continue;
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                sb.append(" • ").append(pkg).append("\n");
                asked++;
            } catch (Throwable t) {
                sb.append(" ! ").append(getString(R.string.cleanup_uninstall_item_failed, pkg)).append("\n");
            }
        }
        say(asked == 0 ? getString(R.string.cleanup_none_found)
            : sb + "\n" + getString(R.string.cleanup_uninstall_footer));
    }

    // Step 2b: last of all — the app asks for its own removal
    private void uninstallSelf() {
        stopAndWipeDriveAssistData();
        say(getString(R.string.cleanup_removing_self));
        ui.postDelayed(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) { say(getString(R.string.cleanup_failed, String.valueOf(t))); }
        }, 1200);
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Throwable t) { return false; }
    }

    private void say(String s) { ui.post(() -> log.setText(s)); }
}
