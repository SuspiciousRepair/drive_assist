package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Style;

import java.io.File;

/** System information and software updates panel of the Config screen. Application
 * version, APK SHA-256, OTA update URL, manual update check, and maintenance
 * operations (cleanup).
 *
 * Extracted from TelemetryActivity.buildSystem() so that 2800+ line file
 * stops growing with every new Config section -- one View subclass per
 * section, same pattern ChargeStatsView/DailyStatsView already use. */
public final class TelemetrySystemSection extends LinearLayout {
    private final Activity activity;
    private final SharedPreferences prefs;
    private final TextView status;
    private EditText fUpdateUrl;

    public TelemetrySystemSection(Activity activity) {
        super(activity);
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("drivemem", Activity.MODE_PRIVATE);
        setOrientation(VERTICAL);

        addView(Style.header(activity, activity.getString(R.string.cfg_system_header)));

        int pad = Style.dp(activity, 14);

        // ---- App Info Card ----
        addView(Style.header(activity, activity.getString(R.string.cfg_app_info_header)));
        LinearLayout infoCard = new LinearLayout(activity);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackground(Style.card(Style.CARD, activity));
        infoCard.setPadding(pad, pad, pad, pad);

        String verName = "Unknown";
        long verCode = 0;
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            verName = pi.versionName;
            verCode = pi.getLongVersionCode();
        } catch (Throwable ignored) {}

        TextView verView = new TextView(activity);
        verView.setText(activity.getString(R.string.cfg_version_label, verName, verCode));
        verView.setTextColor(Style.TEXT);
        verView.setTextSize(15);
        verView.setTypeface(null, android.graphics.Typeface.BOLD);
        infoCard.addView(verView);

        TextView devIdView = new TextView(activity);
        devIdView.setText("Device ID: " + com.geely.drivemem.net.MqttReporter.getDevId());
        devIdView.setTextColor(Style.TEXT_DIM);
        devIdView.setTextSize(13);
        devIdView.setPadding(0, Style.dp(activity, 4), 0, 0);
        infoCard.addView(devIdView);

        // For connecting over adb without guessing the car's address --
        // this head unit's IP moves around DHCP, and asking the driver to
        // dig through Android's own network settings mid-task isn't
        // reasonable when this screen already shows every other identifier.
        TextView ipView = new TextView(activity);
        ipView.setText(activity.getString(R.string.cfg_ip_label, wifiIpAddress(activity)));
        ipView.setTextColor(Style.TEXT_DIM);
        ipView.setTextSize(13);
        ipView.setTextIsSelectable(true);
        ipView.setPadding(0, Style.dp(activity, 2), 0, Style.dp(activity, 8));
        infoCard.addView(ipView);

        TextView shaLbl = Style.label(activity, activity.getString(R.string.cfg_sha_label) + ":");
        shaLbl.setTextSize(14);
        shaLbl.setTextColor(Style.TEXT_DIM);
        infoCard.addView(shaLbl);

        final TextView shaView = new TextView(activity);
        shaView.setText("...");
        shaView.setTextColor(Style.TEXT);
        shaView.setTextSize(12);
        shaView.setTypeface(android.graphics.Typeface.MONOSPACE);
        shaView.setPadding(0, Style.dp(activity, 4), 0, 0);
        shaView.setTextIsSelectable(true);
        infoCard.addView(shaView);

        new Thread(() -> {
            String sha = Updater.sha256(new File(activity.getPackageCodePath()));
            activity.runOnUiThread(() -> shaView.setText(sha != null ? sha : "N/A"));
        }).start();

        addView(infoCard);

        // ---- Software Update Card ----
        addView(Style.header(activity, activity.getString(R.string.cfg_update_header)));
        LinearLayout updateCard = new LinearLayout(activity);
        updateCard.setOrientation(LinearLayout.VERTICAL);
        updateCard.setBackground(Style.card(Style.CARD, activity));
        updateCard.setPadding(pad, pad, pad, pad);

        TextView urlLbl = Style.label(activity, activity.getString(R.string.cfg_update_url_label));
        urlLbl.setTextSize(14);
        urlLbl.setTextColor(Style.TEXT_DIM);
        urlLbl.setPadding(0, 0, 0, Style.dp(activity, 4));
        updateCard.addView(urlLbl);

        fUpdateUrl = new EditText(activity);
        fUpdateUrl.setText(prefs.getString("update_url", Updater.DEFAULT_URL));
        fUpdateUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fUpdateUrl.setHint(Updater.DEFAULT_URL);
        fUpdateUrl.setTextColor(Style.TEXT);
        fUpdateUrl.setTextSize(15);
        fUpdateUrl.setBackground(Style.card(Style.CARD_HI, activity));
        fUpdateUrl.setPadding(pad, Style.dp(activity, 10), pad, Style.dp(activity, 10));
        updateCard.addView(fUpdateUrl);

        LinearLayout urlBtnRow = new LinearLayout(activity);
        urlBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        urlBtnRow.addView(button(activity, activity.getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            String u = fUpdateUrl.getText().toString().trim();
            if (u.isEmpty()) { u = Updater.DEFAULT_URL; fUpdateUrl.setText(u); }
            prefs.edit().putString("update_url", u).apply();
            Toast.makeText(activity, activity.getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        }));
        urlBtnRow.addView(button(activity, activity.getString(R.string.cfg_btn_reset_default), 0xFF3A5A7A, () -> {
            fUpdateUrl.setText(Updater.DEFAULT_URL);
            prefs.edit().putString("update_url", Updater.DEFAULT_URL).apply();
            Toast.makeText(activity, activity.getString(R.string.cfg_btn_reset_default), Toast.LENGTH_SHORT).show();
        }));
        updateCard.addView(urlBtnRow);

        Style.gap(updateCard, activity, 14);

        LinearLayout actRow = new LinearLayout(activity);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.addView(action(activity, activity.getString(R.string.cfg_btn_check_update), Style.ACCENT, () -> {
            String u = fUpdateUrl.getText().toString().trim();
            if (u.isEmpty()) { u = Updater.DEFAULT_URL; fUpdateUrl.setText(u); }
            prefs.edit().putString("update_url", u).apply();
            updateCheck();
        }));
        updateCard.addView(actRow);

        status = new TextView(activity);
        status.setTextColor(Style.TEXT_DIM);
        status.setTextSize(13);
        status.setPadding(0, Style.dp(activity, 10), 0, 0);
        updateCard.addView(status);

        addView(updateCard);

        // ---- Maintenance Card ----
        addView(Style.header(activity, activity.getString(R.string.cfg_maintenance_header)));
        LinearLayout maintCard = new LinearLayout(activity);
        maintCard.setOrientation(LinearLayout.VERTICAL);
        maintCard.setBackground(Style.card(Style.CARD, activity));
        maintCard.setPadding(pad, pad, pad, pad);

        LinearLayout maintRow = new LinearLayout(activity);
        maintRow.setOrientation(LinearLayout.HORIZONTAL);
        maintRow.addView(button(activity, activity.getString(R.string.cfg_cleanup), 0xFF8A3A3A, () -> {
            activity.startActivity(new Intent(activity, CleanupActivity.class));
        }));
        maintCard.addView(maintRow);

        addView(maintCard);
    }

    private void updateCheck() {
        if (status != null) status.setText(activity.getString(R.string.update_checking));
        Updater.check(activity.getApplicationContext(), null, new Updater.CheckCallback() {
            @Override
            public void onUpdateAvailable(Updater.UpdateInfo info) {
                activity.runOnUiThread(() -> {
                    if (status != null) status.setText("");
                    if (!CarState.isParked()) {
                        if (status != null) status.setText(activity.getString(R.string.update_not_parked));
                        Toast.makeText(activity, activity.getString(R.string.update_not_parked), Toast.LENGTH_LONG).show();
                        return;
                    }
                    UpdateDialog.show(activity, info, () -> {
                        if (status != null) status.setText(activity.getString(R.string.update_downloading, info.versionName));
                        Updater.update(activity.getApplicationContext(), info.apkUrl, s -> activity.runOnUiThread(() -> {
                            if (status != null) status.setText(s);
                        }));
                    });
                });
            }

            @Override
            public void onAlreadyUpToDate(String currentVer) {
                activity.runOnUiThread(() -> {
                    if (status != null) status.setText(activity.getString(R.string.update_up_to_date, currentVer));
                });
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() -> {
                    if (status != null) status.setText(activity.getString(R.string.update_check_failed, error));
                });
            }
        });
        updateCheckHelper();
    }

    private void updateCheckHelper() {
        String base = Updater.resolveUrl(activity.getApplicationContext(), null);
        int slash = base.lastIndexOf('/');
        if (slash < 0) return;
        String helperUrl = base.substring(0, slash + 1) + "modehelper.apk";
        Updater.check(activity.getApplicationContext(), helperUrl, Updater.HELPER_PKG, new Updater.CheckCallback() {
            @Override
            public void onUpdateAvailable(Updater.UpdateInfo info) {
                activity.runOnUiThread(() -> {
                    if (!CarState.isParked()) {
                        return;
                    }
                    UpdateDialog.show(activity, info, () -> {
                        Updater.updateHelper(activity.getApplicationContext(), info.apkUrl,
                            s -> activity.runOnUiThread(() -> {}));
                    });
                });
            }

            @Override
            public void onAlreadyUpToDate(String currentVer) {
                activity.runOnUiThread(() -> {});
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() -> {
                    if (error != null && error.contains("predates self-update")) {
                        Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private String wifiIpAddress(Activity activity) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                activity.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm == null) return "N/A";
            int ip = wm.getConnectionInfo().getIpAddress();
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." +
                   ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Throwable e) {
            return "N/A";
        }
    }

    private TextView button(Activity activity, String label, int color, Runnable onClick) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextColor(Style.onFill(color));
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(Style.card(color, activity));
        int ph = Style.dp(activity, 18);
        int pv = Style.dp(activity, 12);
        t.setPadding(ph, pv, ph, pv);
        t.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Style.dp(activity, 4);
        lp.setMargins(m, Style.dp(activity, 8), m, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView action(Activity activity, String label, int color, Runnable onClick) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextColor(Style.onFill(color));
        t.setTextSize(18);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(Style.card(color, activity));
        int pv = Style.dp(activity, 16);
        t.setPadding(pv, pv, pv, pv);
        t.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(activity, 4);
        lp.setMargins(m, Style.dp(activity, 8), m, 0);
        t.setLayoutParams(lp);
        return t;
    }
}
