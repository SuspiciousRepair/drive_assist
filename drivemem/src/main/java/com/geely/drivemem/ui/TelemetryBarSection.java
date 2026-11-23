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

        // One column (the car render fills the rest), one card per place
        // the toggles act on.
        LinearLayout col = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, col));
        LinearLayout top = ConfigCards.card(activity, activity.getString(R.string.cfg_bar_topbar_header));
        ConfigCards.add(col, top);
        LinearLayout home = ConfigCards.card(activity, activity.getString(R.string.cfg_bar_home_header));
        ConfigCards.add(col, home);

        top.addView(toggleRow(activity, activity.getString(R.string.cfg_bar_outtemp),
            prefs.getBoolean("outtemp_on", false), on -> {
                prefs.edit().putBoolean("outtemp_on", on).apply();
                Intent svc = new Intent(activity, OutTempService.class);
                if (on) activity.startService(svc); else activity.stopService(svc);
            }));

        top.addView(toggleRow(activity, activity.getString(R.string.cfg_bar_wifi),
            prefs.getBoolean("wifiicon_on", false), on -> {
                prefs.edit().putBoolean("wifiicon_on", on).apply();
                Intent svc = new Intent(activity, WifiIconService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
                } else activity.stopService(svc);
            }));

        top.addView(toggleRow(activity, activity.getString(R.string.cfg_bar_soc),
            prefs.getBoolean("soc_on", false), on -> {
                prefs.edit().putBoolean("soc_on", on).apply();
                Intent svc = new Intent(activity, SocIconService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
                } else activity.stopService(svc);
            }));

        home.addView(toggleRow(activity, activity.getString(R.string.cfg_drive_card),
            prefs.getBoolean("drive_card_enabled", true), on ->
                prefs.edit().putBoolean("drive_card_enabled", on).apply()));

        home.addView(toggleRow(activity, activity.getString(R.string.cfg_card_grayscale),
            com.geely.drivemem.util.Prefs.getCardGrayscale(activity), on ->
                com.geely.drivemem.util.Prefs.setCardGrayscale(activity, on)));

        home.addView(toggleRow(activity, activity.getString(R.string.cfg_overlay_label),
            prefs.getBoolean("overlay_on", false), on -> {
                prefs.edit().putBoolean("overlay_on", on).apply();
                Intent svc = new Intent(activity, com.geely.drivemem.services.OverlayService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
                } else activity.stopService(svc);
            }));

        LinearLayout loc = ConfigCards.card(activity, activity.getString(R.string.cfg_localization_header));
        ConfigCards.add(col, loc);
        loc.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_currency_hint)));

        final String[] presets = {"R$", "$", "€", "£", "¥"};
        final java.util.List<android.widget.TextView> presetBtns = new java.util.ArrayList<>();
        final android.widget.EditText[] customInputRef = new android.widget.EditText[1];

        Runnable syncPresetSelection = () -> {
            String cur = com.geely.drivemem.util.Prefs.getCurrency(activity);
            for (int i = 0; i < presets.length; i++) {
                boolean sel = presets[i].equals(cur);
                android.widget.TextView b = presetBtns.get(i);
                int fill = sel ? Style.CARD_ON : Style.CARD;
                b.setTextColor(Style.onFill(fill));
                b.setBackground(Style.card(fill, activity));
            }
            if (customInputRef[0] != null && !cur.equals(customInputRef[0].getText().toString().trim())) {
                customInputRef[0].setText(cur);
            }
        };

        LinearLayout presetRow = new LinearLayout(activity);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        for (String symbol : presets) {
            boolean sel = symbol.equals(com.geely.drivemem.util.Prefs.getCurrency(activity));
            android.widget.TextView b = Style.cardButton(activity, symbol, sel, () -> {
                com.geely.drivemem.util.Prefs.setCurrency(activity, symbol);
                syncPresetSelection.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            int m = Style.dp(activity, 4);
            lp.setMargins(m, Style.dp(activity, 6), m, 0);
            b.setLayoutParams(lp);
            presetBtns.add(b);
            presetRow.addView(b);
        }
        loc.addView(presetRow);

        android.widget.EditText customCurrency = Style.field(activity, loc,
            activity.getString(R.string.cfg_currency_label),
            com.geely.drivemem.util.Prefs.getCurrency(activity),
            android.text.InputType.TYPE_CLASS_TEXT);
        customInputRef[0] = customCurrency;
        LinearLayout saveRow = new LinearLayout(activity);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            String val = com.geely.drivemem.util.Prefs.normalizeCurrency(customCurrency.getText().toString());
            com.geely.drivemem.util.Prefs.setCurrency(activity, val);
            customCurrency.setText(val);
            syncPresetSelection.run();
            android.widget.Toast.makeText(activity, activity.getString(R.string.cfg_saved),
                android.widget.Toast.LENGTH_SHORT).show();
        }));
        loc.addView(saveRow);
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
