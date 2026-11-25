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

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.ImuHub;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

/** Settings -> Maintenance & Diary (SEC_DIARY).
 *
 * The care intervals are in groups (Battery, Tires, Service & fluids), one
 * card each. A row is one line: the name, the chosen value, and an arrow on
 * each side of it that walks the presets (Off first). A tap on the value sets
 * any other value (steppers, no keyboard). When the value is not the factory
 * one, an icon puts that row back. Plus the cold-snap tire pressure nudge and
 * the accelerometer jolt sensitivity. */
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

        CharSequence off = c.getString(R.string.cfg_diary_off);

        // ---- Left column: Battery, Tires, Jolt detection ----
        LinearLayout batCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_grp_battery));
        ConfigCards.add(leftCol, batCard);
        batCard.addView(ConfigCards.hint(c, c.getString(R.string.cfg_diary_schedule_hint)));

        batCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_balance_100), K_DAYS,
            new int[][]{{0, 0}, {7, 0}, {15, 0}, {30, 0}, {60, 0}},
            Prefs.DIARY_DEF_BALANCE_DAYS, 0, Prefs.getDiaryBalanceDays(c), 0,
            (a, m) -> Prefs.setDiaryBalanceDays(c, a))));
        batCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_battery_12v), K_MONTHS,
            new int[][]{{0, 0}, {24, 0}, {36, 0}, {48, 0}},
            Prefs.DIARY_DEF_BATTERY_12V_MONTHS, 0, Prefs.getDiaryBattery12vMonths(c), 0,
            (a, m) -> Prefs.setDiaryBattery12vMonths(c, a))));

        LinearLayout tireCard = ConfigCards.card(c, c.getString(R.string.diary_grp_tires));
        ConfigCards.add(leftCol, tireCard);
        tireCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_tire_pressure), K_DAYS,
            new int[][]{{0, 0}, {15, 0}, {30, 0}, {60, 0}},
            Prefs.DIARY_DEF_TIRE_PRESSURE_DAYS, 0, Prefs.getDiaryTirePressureDays(c), 0,
            (a, m) -> Prefs.setDiaryTirePressureDays(c, a))));
        tireCard.addView(Style.toggleRow(c, c.getString(R.string.cfg_diary_cold_snap),
            Prefs.getDiaryTireColdSnap(c),
            on -> { Prefs.setDiaryTireColdSnap(c, on); notifyChange(); }));
        tireCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_tire_rotation), K_KM,
            new int[][]{{0, 0}, {5_000, 0}, {10_000, 0}, {15_000, 0}},
            Prefs.DIARY_DEF_TIRE_ROTATION_KM, 0, Prefs.getDiaryTireRotationKm(c), 0,
            (a, m) -> Prefs.setDiaryTireRotationKm(c, a))));

        // ---- Right column: Service & fluids ----
        LinearLayout srvCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_grp_service));
        ConfigCards.add(rightCol, srvCard);
        srvCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_service), K_KM_MONTHS,
            new int[][]{{0, 0}, {10_000, 12}, {20_000, 12}, {30_000, 24}},
            Prefs.DIARY_DEF_SERVICE_KM, Prefs.DIARY_DEF_SERVICE_MONTHS,
            Prefs.getDiaryServiceKm(c), Prefs.getDiaryServiceMonths(c),
            (a, m) -> Prefs.setDiaryServiceInterval(c, a, m))));
        srvCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_cabin_filter), K_KM_MONTHS,
            new int[][]{{0, 0}, {10_000, 12}, {20_000, 12}, {30_000, 24}},
            Prefs.DIARY_DEF_CABIN_FILTER_KM, Prefs.DIARY_DEF_CABIN_FILTER_MONTHS,
            Prefs.getDiaryCabinFilterKm(c), Prefs.getDiaryCabinFilterMonths(c),
            (a, m) -> Prefs.setDiaryCabinFilterInterval(c, a, m))));
        srvCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_brake_fluid), K_KM_MONTHS,
            new int[][]{{0, 0}, {20_000, 12}, {40_000, 24}, {60_000, 36}},
            Prefs.DIARY_DEF_BRAKE_FLUID_KM, Prefs.DIARY_DEF_BRAKE_FLUID_MONTHS,
            Prefs.getDiaryBrakeFluidKm(c), Prefs.getDiaryBrakeFluidMonths(c),
            (a, m) -> Prefs.setDiaryBrakeFluidInterval(c, a, m))));
        srvCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_coolant), K_KM_MONTHS,
            new int[][]{{0, 0}, {40_000, 24}, {80_000, 48}},
            Prefs.DIARY_DEF_COOLANT_KM, Prefs.DIARY_DEF_COOLANT_MONTHS,
            Prefs.getDiaryCoolantKm(c), Prefs.getDiaryCoolantMonths(c),
            (a, m) -> Prefs.setDiaryCoolantInterval(c, a, m))));
        srvCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_gear_oil), K_KM_MONTHS,
            new int[][]{{0, 0}, {40_000, 24}, {80_000, 48}},
            Prefs.DIARY_DEF_GEAR_OIL_KM, Prefs.DIARY_DEF_GEAR_OIL_MONTHS,
            Prefs.getDiaryGearOilKm(c), Prefs.getDiaryGearOilMonths(c),
            (a, m) -> Prefs.setDiaryGearOilInterval(c, a, m))));
        srvCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_wipers), K_MONTHS,
            new int[][]{{0, 0}, {6, 0}, {12, 0}, {24, 0}},
            Prefs.DIARY_DEF_WIPERS_MONTHS, 0, Prefs.getDiaryWipersMonths(c), 0,
            (a, m) -> Prefs.setDiaryWipersMonths(c, a))));

        // ---- Left column, last: Jolt detection ----
        LinearLayout joltCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_jolt_title));
        ConfigCards.add(leftCol, joltCard);
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

    // ---- Interval rows: presets, any other value, back to factory per row ----

    private static final int K_DAYS = 0, K_MONTHS = 1, K_KM = 2, K_KM_MONTHS = 3;

    /** a = days, months or km (by kind); b = months, for K_KM_MONTHS only. 0 = Off. */
    private interface Setter { void set(int a, int b); }

    private static final class Row {
        final String label; final int kind; final int[][] presets;
        final int defA, defB, curA, curB; final Setter set;
        Row(String label, int kind, int[][] presets, int defA, int defB, int curA, int curB, Setter set) {
            this.label = label; this.kind = kind; this.presets = presets;
            this.defA = defA; this.defB = defB; this.curA = curA; this.curB = curB; this.set = set;
        }
        boolean is(int a, int b) { return curA == a && (kind != K_KM_MONTHS || a == 0 || curB == b); }
    }

    private CharSequence valueTitle(int kind, int a, int b) {
        if (a == 0) return activity.getString(R.string.cfg_diary_off);
        switch (kind) {
            case K_DAYS: return d(a);
            case K_MONTHS: return mo(a);
            case K_KM: return kKm(a / 1000);
            default: return kmM(a / 1000, b);
        }
    }

    private void apply(Row r, int a, int b) {
        r.set.set(a, a == 0 ? 0 : b);
        notifyChange();
    }

    /** Pure: the preset a tap on the left (dir -1) or right (dir +1) arrow goes
     * to, or -1 at the end. From a value that is not a preset, it goes to the
     * nearest preset on that side. Presets are in rising order, Off first. */
    static int neighbour(int[][] presets, int curA, int curB, boolean pairs, int dir) {
        for (int i = 0; i < presets.length; i++) {
            boolean same = presets[i][0] == curA && (!pairs || curA == 0 || presets[i][1] == curB);
            if (same) {
                int n = i + dir;
                return n >= 0 && n < presets.length ? n : -1;
            }
        }
        if (dir > 0) {
            for (int i = 0; i < presets.length; i++) if (presets[i][0] > curA) return i;
        } else {
            for (int i = presets.length - 1; i >= 0; i--) if (presets[i][0] < curA) return i;
        }
        return -1;
    }

    /** One line per item, all columns aligned: name, the row's reset icon
     * (only when the value is not the factory one), left arrow, the value,
     * right arrow. The arrows walk the presets; a tap on the value opens the
     * steppers for any other value. */
    private View intervalRow(Row r) {
        Context c = activity;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 60));
        wlp.topMargin = Style.dp(c, 6);
        row.setLayoutParams(wlp);

        TextView lbl = new TextView(c);
        lbl.setText(r.label);
        lbl.setTextColor(Style.TEXT);
        lbl.setTextSize(20f);
        lbl.setSingleLine(true);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageView reset = new android.widget.ImageView(c);
        reset.setImageResource(R.drawable.ic_sync_reset);
        reset.setColorFilter(Style.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
        reset.setContentDescription(c.getString(R.string.cfg_diary_row_reset));
        int rp = Style.dp(c, 18);
        reset.setPadding(rp, rp, rp, rp);
        reset.setVisibility(r.is(r.defA, r.defB) ? INVISIBLE : VISIBLE);   // keeps the columns aligned
        reset.setOnClickListener(v -> apply(r, r.defA, r.defB));
        row.addView(reset, new LinearLayout.LayoutParams(Style.dp(c, 60), Style.dp(c, 60)));

        boolean pairs = r.kind == K_KM_MONTHS;
        row.addView(arrow(r, neighbour(r.presets, r.curA, r.curB, pairs, -1), true));

        TextView val = new TextView(c);
        boolean isOff = r.curA == 0;
        int fg = isOff ? Style.TEXT_DIM : Style.TEXT;
        val.setText(styleUnits(valueTitle(r.kind, r.curA, r.curB), fg, Style.CARD_HI));
        val.setTextColor(fg);
        val.setTextSize(20f);
        val.setTypeface(null, isOff ? Typeface.NORMAL : Typeface.BOLD);
        val.setGravity(Gravity.CENTER);
        val.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Style.dp(c, 14));
        bg.setColor(Style.CARD_HI);
        bg.setStroke(Style.dp(c, 1), Style.tileStrokeColor());
        val.setBackground(bg);
        val.setContentDescription(c.getString(R.string.cfg_diary_custom));
        val.setOnClickListener(v -> showCustomDialog(r));
        row.addView(val, new LinearLayout.LayoutParams(Style.dp(c, 220), Style.dp(c, 52)));

        row.addView(arrow(r, neighbour(r.presets, r.curA, r.curB, pairs, 1), false));
        return row;
    }

    /** A plain chevron, no box. Dim and without action at the end of the presets. */
    private View arrow(Row r, int target, boolean left) {
        Context c = activity;
        android.widget.ImageView a = new android.widget.ImageView(c);
        a.setImageResource(R.drawable.ic_chevron_right);
        if (left) a.setRotation(180f);
        int p = Style.dp(c, 16);
        a.setPadding(p, p, p, p);
        if (target >= 0) {
            a.setColorFilter(Style.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
            final int ta = r.presets[target][0], tb = r.presets[target][1];
            a.setOnClickListener(v -> apply(r, ta, tb));
        } else {
            a.setColorFilter((Style.TEXT_DIM & 0x00FFFFFF) | 0x40000000, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        a.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 60), Style.dp(c, 60)));
        return a;
    }

    /** Steppers for any value, no keyboard. Small step and big step per unit. */
    private void showCustomDialog(Row r) {
        Context c = activity;
        android.app.Dialog dialog = new android.app.Dialog(c);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(VERTICAL);
        int pad = Style.dp(c, 32);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Style.card(Style.cardFillColor(), c));
        root.addView(Style.title(c, r.label));

        // Start from the current value, or from the factory one when the row is Off.
        final int[] val = {r.curA > 0 ? r.curA : r.defA, r.curA > 0 ? r.curB : r.defB};
        if (r.kind == K_KM_MONTHS && val[1] <= 0) val[1] = r.defB;

        int firstKind = (r.kind == K_KM_MONTHS) ? K_KM : r.kind;
        root.addView(stepper(val, 0, firstKind));
        if (r.kind == K_KM_MONTHS) root.addView(stepper(val, 1, K_MONTHS));

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(HORIZONTAL);
        btns.setGravity(Gravity.END);
        TextView cancel = Style.cardButton(c, c.getString(R.string.cfg_diary_cancel), false, dialog::dismiss);
        TextView save = Style.cardButton(c, c.getString(R.string.diary_btn_save), true, () -> {
            dialog.dismiss();
            apply(r, val[0], val[1]);
        });
        btns.addView(cancel);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Style.dp(c, 12);
        btns.addView(save, slp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(c, 24);
        root.addView(btns, blp);

        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout(Style.dp(c, 760), ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
        if (window != null) window.setLayout(Style.dp(c, 760), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Pure: one stepper press. Days 1..365, months 1..120, km 1,000..200,000. */
    static int step(int kind, int value, int delta) {
        int min = kind == K_KM ? 1_000 : 1;
        int max = kind == K_KM ? 200_000 : (kind == K_MONTHS ? 120 : 365);
        return Math.max(min, Math.min(max, value + delta));
    }

    /** [--] [-]  value  [+] [++] for val[idx]. */
    private View stepper(int[] val, int idx, int kind) {
        Context c = activity;
        final int small = kind == K_KM ? 1_000 : 1;
        final int big = kind == K_KM ? 5_000 : (kind == K_MONTHS ? 6 : 5);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Style.dp(c, 20);
        row.setLayoutParams(rlp);

        final TextView num = new TextView(c);
        num.setTextSize(32f);
        num.setTypeface(null, Typeface.BOLD);
        num.setTextColor(Style.TEXT);
        num.setGravity(Gravity.CENTER);
        final Runnable show = () -> num.setText(styleUnits(
            valueTitle(kind, val[idx], 0), Style.TEXT, Style.cardFillColor()));
        show.run();

        int[] deltas = {-big, -small, small, big};
        String[] names = {"\u2212" + stepName(kind, big), "\u2212" + stepName(kind, small),
            "+" + stepName(kind, small), "+" + stepName(kind, big)};
        for (int i = 0; i < 4; i++) {
            if (i == 2) row.addView(num, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final int dlt = deltas[i];
            TextView b = buildPill(names[i], false, () -> {
                val[idx] = step(kind, val[idx], dlt);
                show.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Style.dp(c, 96), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == 1 || i == 3) lp.leftMargin = Style.dp(c, 8);
            row.addView(b, lp);
        }
        return row;
    }

    /** "5" for days and months, "5" (thousand) for km: the unit is in the value. */
    private static String stepName(int kind, int step) {
        return String.valueOf(kind == K_KM ? step / 1000 : step);
    }

    private interface StrChoice { void onPick(String v); }

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
