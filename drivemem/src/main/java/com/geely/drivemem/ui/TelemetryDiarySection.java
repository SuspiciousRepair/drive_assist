package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.ImuHub;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

/** Settings -> Maintenance & Diary (SEC_DIARY).
 *
 * Zero-keyboard preset pills for every tracked care interval (including Off),
 * cold-snap tire pressure nudge, accelerometer jolt sensitivity, EX2 defaults
 * reset, and plain-language explainers for deadlines and HV / 12V battery health. */
public final class TelemetryDiarySection extends LinearLayout {

    private final Activity activity;

    public TelemetryDiarySection(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        Context c = activity;

        LinearLayout leftCol = ConfigCards.column(c);
        LinearLayout rightCol = ConfigCards.column(c);
        addView(ConfigCards.columns(c, leftCol, rightCol));

        // ---- Left column: Maintenance intervals ----
        LinearLayout schedCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_schedule_title));
        ConfigCards.add(leftCol, schedCard);
        schedCard.addView(ConfigCards.hint(c, c.getString(R.string.cfg_diary_schedule_hint)));

        String off = c.getString(R.string.cfg_diary_off);

        // 100% LFP balancing
        int bal = Prefs.getDiaryBalanceDays(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_balance_100),
            new String[]{off, d(7), d(15), d(30), d(60)},
            new int[]{0, 7, 15, 30, 60}, bal,
            v -> { Prefs.setDiaryBalanceDays(c, v); notifyChange(); }));

        // Tire pressure + cold-snap toggle
        int tp = Prefs.getDiaryTirePressureDays(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_tire_pressure),
            new String[]{off, d(15), d(30), d(60)},
            new int[]{0, 15, 30, 60}, tp,
            v -> { Prefs.setDiaryTirePressureDays(c, v); notifyChange(); }));

        schedCard.addView(Style.toggleRow(c, c.getString(R.string.cfg_diary_cold_snap),
            Prefs.getDiaryTireColdSnap(c),
            on -> { Prefs.setDiaryTireColdSnap(c, on); notifyChange(); }));

        // Tire rotation
        int rot = Prefs.getDiaryTireRotationKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_tire_rotation),
            new String[]{off, "5k", "10k", "15k"},
            new int[]{0, 5_000, 10_000, 15_000}, rot,
            v -> { Prefs.setDiaryTireRotationKm(c, v); notifyChange(); }));

        // Scheduled service (km + months paired)
        int srvKm = Prefs.getDiaryServiceKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_service),
            new String[]{off, kmM(10, 12), kmM(20, 12), kmM(30, 24)},
            new int[]{0, 10_000, 20_000, 30_000}, srvKm,
            v -> {
                int m = v == 0 ? 0 : (v >= 30_000 ? 24 : 12);
                Prefs.setDiaryServiceInterval(c, v, m);
                notifyChange();
            }));

        // Cabin filter
        int cabKm = Prefs.getDiaryCabinFilterKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_cabin_filter),
            new String[]{off, kmM(10, 12), kmM(20, 12), kmM(30, 24)},
            new int[]{0, 10_000, 20_000, 30_000}, cabKm,
            v -> {
                int m = v == 0 ? 0 : (v >= 30_000 ? 24 : 12);
                Prefs.setDiaryCabinFilterInterval(c, v, m);
                notifyChange();
            }));

        // Brake fluid
        int bfKm = Prefs.getDiaryBrakeFluidKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_brake_fluid),
            new String[]{off, kmM(20, 12), kmM(40, 24), kmM(60, 36)},
            new int[]{0, 20_000, 40_000, 60_000}, bfKm,
            v -> {
                int m = v == 0 ? 0 : (v == 20_000 ? 12 : (v == 60_000 ? 36 : 24));
                Prefs.setDiaryBrakeFluidInterval(c, v, m);
                notifyChange();
            }));

        // Coolant
        int coolKm = Prefs.getDiaryCoolantKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_coolant),
            new String[]{off, kmM(40, 24), kmM(80, 48)},
            new int[]{0, 40_000, 80_000}, coolKm,
            v -> {
                int m = v == 0 ? 0 : (v == 40_000 ? 24 : 48);
                Prefs.setDiaryCoolantInterval(c, v, m);
                notifyChange();
            }));

        // Gear oil
        int gearKm = Prefs.getDiaryGearOilKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_gear_oil),
            new String[]{off, kmM(40, 24), kmM(80, 48)},
            new int[]{0, 40_000, 80_000}, gearKm,
            v -> {
                int m = v == 0 ? 0 : (v == 40_000 ? 24 : 48);
                Prefs.setDiaryGearOilInterval(c, v, m);
                notifyChange();
            }));

        // Wipers
        int wipM = Prefs.getDiaryWipersMonths(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_wipers),
            new String[]{off, mo(6), mo(12), mo(24)},
            new int[]{0, 6, 12, 24}, wipM,
            v -> { Prefs.setDiaryWipersMonths(c, v); notifyChange(); }));

        // 12V battery age reminder
        int b12M = Prefs.getDiaryBattery12vMonths(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_battery_12v),
            new String[]{off, mo(24), mo(36), mo(48)},
            new int[]{0, 24, 36, 48}, b12M,
            v -> { Prefs.setDiaryBattery12vMonths(c, v); notifyChange(); }));

        // Reset button
        TextView resetBtn = Style.cardButton(c, c.getString(R.string.cfg_diary_reset_btn), false, () -> {
            Prefs.resetDiaryDefaults(c);
            CarDiary.notifyChanged();
            Toast.makeText(c, c.getString(R.string.cfg_diary_reset_done), Toast.LENGTH_SHORT).show();
            rebuild();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Style.dp(c, 18);
        schedCard.addView(resetBtn, rlp);

        // ---- Right column: Jolt detection + How Battery Health works ----
        LinearLayout joltCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_jolt_title));
        ConfigCards.add(rightCol, joltCard);
        joltCard.addView(ConfigCards.hint(c, c.getString(R.string.cfg_diary_jolt_hint)));

        String curSens = Prefs.getDiaryJoltSensitivity(c);
        String[] sKeys = {ImuHub.JOLT_OFF, ImuHub.JOLT_LOW, ImuHub.JOLT_MEDIUM, ImuHub.JOLT_HIGH};
        String[] sLabels = {
            off,
            c.getString(R.string.cfg_diary_jolt_low),
            c.getString(R.string.cfg_diary_jolt_medium),
            c.getString(R.string.cfg_diary_jolt_high)
        };
        joltCard.addView(stringPillRow(sLabels, sKeys, curSens, key -> {
            Prefs.setDiaryJoltSensitivity(c, key);
            rebuild();
        }));

        LinearLayout healthCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_health_title));
        ConfigCards.add(rightCol, healthCard);
        addExplainer(healthCard, c.getString(R.string.cfg_diary_health_12v_title),
            c.getString(R.string.cfg_diary_health_12v_body));
        addExplainer(healthCard, c.getString(R.string.cfg_diary_health_hv_title),
            c.getString(R.string.cfg_diary_health_hv_body));
        addExplainer(healthCard, c.getString(R.string.cfg_diary_health_due_title),
            c.getString(R.string.cfg_diary_health_due_body));
    }

    private void notifyChange() {
        CarDiary.notifyChanged();
        rebuild();
    }

    private String d(int days) { return days + "d"; }
    private String mo(int months) { return months + "m"; }
    private String kmM(int k, int m) { return k + "k/" + m + "m"; }

    private interface IntChoice { void onPick(int v); }
    private interface StrChoice { void onPick(String v); }

    private View presetRow(String label, String[] titles, int[] values, int current, IntChoice onPick) {
        Context c = activity;
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Style.dp(c, 12);
        wrap.setLayoutParams(wlp);

        TextView lbl = new TextView(c);
        lbl.setText(label);
        lbl.setTextColor(Style.TEXT);
        lbl.setTextSize(17.5f);
        lbl.setTypeface(null, Typeface.BOLD);
        wrap.addView(lbl);

        LinearLayout pills = new LinearLayout(c);
        pills.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Style.dp(c, 8);
        for (int i = 0; i < titles.length; i++) {
            final int val = values[i];
            boolean sel = (val == current);
            TextView pill = buildPill(titles[i], sel, () -> onPick.onPick(val));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = Style.dp(c, 8);
            pills.addView(pill, lp);
        }
        wrap.addView(pills, plp);
        return wrap;
    }

    private View stringPillRow(String[] titles, String[] keys, String current, StrChoice onPick) {
        Context c = activity;
        LinearLayout pills = new LinearLayout(c);
        pills.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Style.dp(c, 10);
        pills.setLayoutParams(plp);
        for (int i = 0; i < titles.length; i++) {
            final String k = keys[i];
            boolean sel = k.equals(current);
            TextView pill = buildPill(titles[i], sel, () -> onPick.onPick(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = Style.dp(c, 8);
            pills.addView(pill, lp);
        }
        return pills;
    }

    private TextView buildPill(String text, boolean selected, Runnable onTap) {
        Context c = activity;
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setTextColor(selected ? Style.onFill(Style.ACCENT) : Style.TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(Style.dp(c, 10), Style.dp(c, 12), Style.dp(c, 10), Style.dp(c, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Style.dp(c, 14));
        if (selected) {
            bg.setColor(Style.ACCENT);
        } else {
            bg.setColor(Style.CARD_HI);
            bg.setStroke(Style.dp(c, 1), Style.LIGHT ? 0x1E000000 : 0x1EFFFFFF);
        }
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onTap.run());
        return tv;
    }

    private void addExplainer(LinearLayout card, String heading, String body) {
        Context c = activity;
        TextView h = new TextView(c);
        h.setText(heading);
        h.setTextColor(Style.TEXT);
        h.setTextSize(17.5f);
        h.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Style.dp(c, 14);
        card.addView(h, hlp);

        TextView b = new TextView(c);
        b.setText(body);
        b.setTextColor(Style.TEXT_DIM);
        b.setTextSize(15.5f);
        b.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(c, 4);
        card.addView(b, blp);
    }
}
