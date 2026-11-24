package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
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
 * cold-snap tire pressure nudge, accelerometer jolt sensitivity and EX2 defaults
 * reset. */
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

        CharSequence off = c.getString(R.string.cfg_diary_off);

        // 100% LFP balancing
        int bal = Prefs.getDiaryBalanceDays(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_balance_100),
            new CharSequence[]{off, d(7), d(15), d(30), d(60)},
            new int[]{0, 7, 15, 30, 60}, bal,
            v -> { Prefs.setDiaryBalanceDays(c, v); notifyChange(); }));

        // Tire pressure + cold-snap toggle
        int tp = Prefs.getDiaryTirePressureDays(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_tire_pressure),
            new CharSequence[]{off, d(15), d(30), d(60)},
            new int[]{0, 15, 30, 60}, tp,
            v -> { Prefs.setDiaryTirePressureDays(c, v); notifyChange(); }));

        schedCard.addView(Style.toggleRow(c, c.getString(R.string.cfg_diary_cold_snap),
            Prefs.getDiaryTireColdSnap(c),
            on -> { Prefs.setDiaryTireColdSnap(c, on); notifyChange(); }));

        // Tire rotation
        int rot = Prefs.getDiaryTireRotationKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_tire_rotation),
            new CharSequence[]{off, kKm(5), kKm(10), kKm(15)},
            new int[]{0, 5_000, 10_000, 15_000}, rot,
            v -> { Prefs.setDiaryTireRotationKm(c, v); notifyChange(); }));

        // Scheduled service (km + months paired)
        int srvKm = Prefs.getDiaryServiceKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_service),
            new CharSequence[]{off, kmM(10, 12), kmM(20, 12), kmM(30, 24)},
            new int[]{0, 10_000, 20_000, 30_000}, srvKm,
            v -> {
                int m = v == 0 ? 0 : (v >= 30_000 ? 24 : 12);
                Prefs.setDiaryServiceInterval(c, v, m);
                notifyChange();
            }));

        // Cabin filter
        int cabKm = Prefs.getDiaryCabinFilterKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_cabin_filter),
            new CharSequence[]{off, kmM(10, 12), kmM(20, 12), kmM(30, 24)},
            new int[]{0, 10_000, 20_000, 30_000}, cabKm,
            v -> {
                int m = v == 0 ? 0 : (v >= 30_000 ? 24 : 12);
                Prefs.setDiaryCabinFilterInterval(c, v, m);
                notifyChange();
            }));

        // Brake fluid
        int bfKm = Prefs.getDiaryBrakeFluidKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_brake_fluid),
            new CharSequence[]{off, kmM(20, 12), kmM(40, 24), kmM(60, 36)},
            new int[]{0, 20_000, 40_000, 60_000}, bfKm,
            v -> {
                int m = v == 0 ? 0 : (v == 20_000 ? 12 : (v == 60_000 ? 36 : 24));
                Prefs.setDiaryBrakeFluidInterval(c, v, m);
                notifyChange();
            }));

        // Coolant
        int coolKm = Prefs.getDiaryCoolantKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_coolant),
            new CharSequence[]{off, kmM(40, 24), kmM(80, 48)},
            new int[]{0, 40_000, 80_000}, coolKm,
            v -> {
                int m = v == 0 ? 0 : (v == 40_000 ? 24 : 48);
                Prefs.setDiaryCoolantInterval(c, v, m);
                notifyChange();
            }));

        // Gear oil
        int gearKm = Prefs.getDiaryGearOilKm(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_gear_oil),
            new CharSequence[]{off, kmM(40, 24), kmM(80, 48)},
            new int[]{0, 40_000, 80_000}, gearKm,
            v -> {
                int m = v == 0 ? 0 : (v == 40_000 ? 24 : 48);
                Prefs.setDiaryGearOilInterval(c, v, m);
                notifyChange();
            }));

        // Wipers
        int wipM = Prefs.getDiaryWipersMonths(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_wipers),
            new CharSequence[]{off, mo(6), mo(12), mo(24)},
            new int[]{0, 6, 12, 24}, wipM,
            v -> { Prefs.setDiaryWipersMonths(c, v); notifyChange(); }));

        // 12V battery age reminder
        int b12M = Prefs.getDiaryBattery12vMonths(c);
        schedCard.addView(presetRow(c.getString(R.string.diary_cat_battery_12v),
            new CharSequence[]{off, mo(24), mo(36), mo(48)},
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

        // ---- Right column: Jolt detection ----
        LinearLayout joltCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_jolt_title));
        ConfigCards.add(rightCol, joltCard);
        joltCard.addView(ConfigCards.hint(c, c.getString(R.string.cfg_diary_jolt_hint)));

        String curSens = Prefs.getDiaryJoltSensitivity(c);
        String[] sKeys = {ImuHub.JOLT_OFF, ImuHub.JOLT_LOW, ImuHub.JOLT_MEDIUM, ImuHub.JOLT_HIGH};
        CharSequence[] sLabels = {
            off,
            c.getString(R.string.cfg_diary_jolt_low),
            c.getString(R.string.cfg_diary_jolt_medium),
            c.getString(R.string.cfg_diary_jolt_high)
        };
        joltCard.addView(stringPillRow(sLabels, sKeys, curSens, key -> {
            Prefs.setDiaryJoltSensitivity(c, key);
            rebuild();
        }));

        // Live calibration feedback summary from driver's Logged / Dismissed verdicts
        final LinearLayout fbBox = new LinearLayout(c);
        fbBox.setOrientation(VERTICAL);
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fblp.topMargin = Style.dp(c, 12);
        joltCard.addView(fbBox, fblp);
        new Thread(() -> {
            com.geely.drivemem.sensors.JoltFeedback.Stats st =
                com.geely.drivemem.sensors.JoltFeedback.readStats(c);
            post(() -> {
                fbBox.removeAllViews();
                if (st.total() == 0) {
                    fbBox.addView(ConfigCards.hint(c, c.getString(R.string.cfg_diary_jolt_fb_empty)));
                    return;
                }
                // The g figure goes in as number + smaller unit (STYLE-GUIDE "Numbers and units").
                TextView summary = ConfigCards.hint(c, null);
                summary.setText(CarDiaryView.fill(
                    c.getString(R.string.cfg_diary_jolt_fb_summary,
                        st.dismissedCount, CarDiaryView.MARK_1, st.loggedCount),
                    Style.valueWithUnit(String.format(java.util.Locale.getDefault(), "%.2f", st.maxDismissedG),
                        null, "g", Style.UNIT_SCALE_ROW)));
                fbBox.addView(summary);
                if (st.suggestedSensitivity != null) {
                    String sugLabel = ImuHub.JOLT_LOW.equals(st.suggestedSensitivity)
                        ? c.getString(R.string.cfg_diary_jolt_low)
                        : c.getString(R.string.cfg_diary_jolt_medium);
                    TextView applyBtn = Style.cardButton(c,
                        c.getString(R.string.cfg_diary_jolt_fb_apply, sugLabel), true, () -> {
                            Prefs.setDiaryJoltSensitivity(c, st.suggestedSensitivity);
                            rebuild();
                        });
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    alp.topMargin = Style.dp(c, 8);
                    fbBox.addView(applyBtn, alp);
                }
            });
        }, "jolt-fb-read").start();
    }

    private void notifyChange() {
        CarDiary.notifyChanged();
        rebuild();
    }

    // Pill titles: a number with its unit smaller and dimmer (STYLE-GUIDE
    // "Numbers and units"). The unit letters are strings, one per language.
    private CharSequence d(int days) {
        return numUnit(String.valueOf(days), activity.getString(R.string.cfg_diary_unit_days));
    }
    private CharSequence mo(int months) {
        return numUnit(String.valueOf(months), activity.getString(R.string.cfg_diary_unit_months));
    }
    private CharSequence kKm(int thousandKm) {
        return numUnit(String.valueOf(thousandKm), activity.getString(R.string.cfg_diary_unit_thousand_km));
    }
    private CharSequence kmM(int thousandKm, int months) {
        SpannableStringBuilder sb = new SpannableStringBuilder(kKm(thousandKm));
        sb.append(" / ").append(mo(months));
        return sb;
    }

    /** "12" + " m": the unit is marked with UnitMark, and buildPill gives it
     * its size and colour (the dim colour depends on the pill being selected). */
    private static CharSequence numUnit(String number, String unit) {
        SpannableStringBuilder sb = new SpannableStringBuilder(number);
        int a = sb.length();
        sb.append(" ").append(unit);
        sb.setSpan(new UnitMark(), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Marks the unit part of a pill title; carries no look of its own. */
    private static final class UnitMark {}

    /** Turns each UnitMark into the real look: smaller, normal weight, dimmer
     * than the number in the same pill. */
    private static CharSequence styleUnits(CharSequence text, int numberColor, int fill) {
        if (!(text instanceof Spanned)) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int dim = Style.blend(numberColor, fill, 0.40f);
        for (UnitMark m : sb.getSpans(0, sb.length(), UnitMark.class)) {
            int a = sb.getSpanStart(m), b = sb.getSpanEnd(m);
            sb.removeSpan(m);
            sb.setSpan(new RelativeSizeSpan(Style.UNIT_SCALE_ROW), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.NORMAL), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(dim), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private interface IntChoice { void onPick(int v); }
    private interface StrChoice { void onPick(String v); }

    private View presetRow(String label, CharSequence[] titles, int[] values, int current, IntChoice onPick) {
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
        lbl.setTextSize(18f);
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

    private View stringPillRow(CharSequence[] titles, String[] keys, String current, StrChoice onPick) {
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

    private TextView buildPill(CharSequence text, boolean selected, Runnable onTap) {
        Context c = activity;
        TextView tv = new TextView(c);
        int fill = selected ? Style.ACCENT : Style.CARD_HI;
        int fg = selected ? Style.onFill(Style.ACCENT) : Style.TEXT;
        tv.setText(styleUnits(text, fg, fill));
        tv.setTextSize(20f);
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setMinimumHeight(Style.dp(c, 60));
        tv.setPadding(Style.dp(c, 8), 0, Style.dp(c, 8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Style.dp(c, 14));
        if (selected) {
            bg.setColor(Style.ACCENT);
        } else {
            bg.setColor(Style.CARD_HI);
            bg.setStroke(Style.dp(c, 1), Style.tileStrokeColor());
        }
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onTap.run());
        return tv;
    }
}
