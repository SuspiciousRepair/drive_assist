package com.geely.drivemem.ui;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.OemTranslations;
import com.geely.drivemem.util.Style;

/** The "Car app translations" integration page: Install (or Update) and
 * Remove, with modehelper's state. See controls/OemTranslations. */
public final class TelemetryOemTextSection extends LinearLayout {
    static final long REFRESH_MS = 2000;

    private final Activity activity;
    private final TextView status;
    private final TextView installButton;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            OemTranslations.refresh(activity, (s, code) -> show(s));
            postDelayed(this, REFRESH_MS);
        }
    };

    public TelemetryOemTextSection(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);

        LinearLayout col = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, col));
        LinearLayout card = ConfigCards.card(activity, activity.getString(R.string.int_oem_name));
        ConfigCards.add(col, card);

        status = new TextView(activity);
        status.setTextColor(Style.TEXT); status.setTextSize(16);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(status);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        installButton = Style.button(activity, activity.getString(R.string.cfg_oem_install), Style.ACCENT,
            () -> OemTranslations.install(activity, this::afterAction));
        row.addView(installButton);
        row.addView(Style.button(activity, activity.getString(R.string.cfg_oem_remove), 0xFF8A3A3A,
            () -> OemTranslations.remove(activity, this::afterAction)));
        card.addView(row);
        card.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_oem_hint)));

        OemTranslations.Status last = OemTranslations.last();
        if (last != null) show(last);
    }

    private void afterAction(OemTranslations.Status s, String code) {
        if ("busy".equals(code) && !OemTranslations.BUSY.equals(s.state)) {
            status.setText(activity.getString(R.string.cfg_oem_st_busy));
            return;
        }
        show(s);
    }

    private void show(OemTranslations.Status s) {
        status.setText(text(s));
    }

    private String text(OemTranslations.Status s) {
        switch (s.state) {
            case OemTranslations.NO_HELPER:
                return activity.getString(R.string.cfg_lock_sound_needs_helper);
            case OemTranslations.BUSY:
                switch (s.step) {
                    case "download": return activity.getString(R.string.cfg_oem_st_download);
                    case "patch": return activity.getString(R.string.cfg_oem_st_patch);
                    case "remove": return activity.getString(R.string.cfg_oem_st_remove);
                    // The helper restarts the car's apps only in Park.
                    case "park": return activity.getString(R.string.cfg_oem_st_park);
                    default: return activity.getString(R.string.cfg_oem_st_mount);
                }
            case OemTranslations.ON: {
                String t = activity.getString(R.string.cfg_oem_st_on, s.apps);
                if (!s.crashed.isEmpty()) t += "\n" + activity.getString(R.string.cfg_oem_st_crashed, s.crashed);
                return t;
            }
            case OemTranslations.ERROR:
                switch (s.result) {
                    case "download": return activity.getString(R.string.cfg_oem_st_err_download);
                    case "adb": return activity.getString(R.string.cfg_oem_st_err_adb);
                    case "none": return activity.getString(R.string.cfg_oem_st_err_none);
                    default: return activity.getString(R.string.cfg_oem_st_err_other);
                }
            default:
                return activity.getString(R.string.cfg_oem_st_off);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
