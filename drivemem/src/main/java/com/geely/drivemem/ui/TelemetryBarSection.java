package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.LinearLayout;

import com.geely.drivemem.R;
import com.geely.drivemem.services.OutTempService;
import com.geely.drivemem.services.SocIconService;
import com.geely.drivemem.services.WifiIconService;
import com.geely.drivemem.util.Style;

/** Menu bar customization panel of the Config screen. Toggles for showing
 * outside temperature, Wi-Fi icon, and battery SOC icon in the status bar,
 * plus home screen card visibility controls.
 *
 * Extracted from TelemetryActivity.buildBar() so that 2800+ line file
 * stops growing with every new Config section -- one View subclass per
 * section, same pattern ChargeStatsView/DailyStatsView already use. */
public final class TelemetryBarSection extends LinearLayout {
    private final Activity activity;
    private final SharedPreferences prefs;

    public TelemetryBarSection(Activity activity) {
        super(activity);
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("drivemem", Activity.MODE_PRIVATE);
        setOrientation(VERTICAL);

        addView(Style.header(activity, activity.getString(R.string.cfg_bar_header)));

        addView(Style.header(activity, activity.getString(R.string.cfg_bar_topbar_header)));

        addView(toggleRow(activity, activity.getString(R.string.cfg_bar_outtemp),
            prefs.getBoolean("outtemp_on", false), on -> {
                prefs.edit().putBoolean("outtemp_on", on).apply();
                Intent svc = new Intent(activity, OutTempService.class);
                if (on) activity.startService(svc); else activity.stopService(svc);
            }));

        addView(toggleRow(activity, activity.getString(R.string.cfg_bar_wifi),
            prefs.getBoolean("wifiicon_on", false), on -> {
                prefs.edit().putBoolean("wifiicon_on", on).apply();
                Intent svc = new Intent(activity, WifiIconService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
                } else activity.stopService(svc);
            }));

        addView(toggleRow(activity, activity.getString(R.string.cfg_bar_soc),
            prefs.getBoolean("soc_on", false), on -> {
                prefs.edit().putBoolean("soc_on", on).apply();
                Intent svc = new Intent(activity, SocIconService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
                } else activity.stopService(svc);
            }));

        addView(Style.header(activity, activity.getString(R.string.cfg_bar_home_header)));

        addView(toggleRow(activity, activity.getString(R.string.cfg_drive_card),
            prefs.getBoolean("drive_card_enabled", true), on ->
                prefs.edit().putBoolean("drive_card_enabled", on).apply()));

        addView(toggleRow(activity, activity.getString(R.string.cfg_overlay_label),
            prefs.getBoolean("overlay_on", false), on -> {
                prefs.edit().putBoolean("overlay_on", on).apply();
                Intent svc = new Intent(activity, com.geely.drivemem.services.OverlayService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
                } else activity.stopService(svc);
            }));
    }

    private LinearLayout toggleRow(Activity activity, String label, boolean on, com.geely.drivemem.controls.GeelySwitch.OnToggle cb) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Style.dp(activity, 8), 0, Style.dp(activity, 8));
        com.geely.drivemem.controls.GeelySwitch sw = new com.geely.drivemem.controls.GeelySwitch(activity);
        sw.setLockSeconds(prefs.getInt("switch_lock_s", com.geely.drivemem.controls.GeelySwitch.DEFAULT_LOCK_S));
        sw.setCheckedSilently(on);
        sw.setOnToggle(cb);
        row.addView(sw);
        android.widget.TextView lbl = Style.label(activity, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Style.dp(activity, 14);
        lbl.setLayoutParams(lp);
        row.addView(lbl);
        return row;
    }
}
