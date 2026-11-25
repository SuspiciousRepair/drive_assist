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

        // ---- Left column: the maintenance schedule, one card per group ----
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

        addGroupRows(batCard, CarDiary.Group.ELECTRICAL);

        LinearLayout tireCard = ConfigCards.card(c, c.getString(R.string.diary_grp_tires));
        ConfigCards.add(leftCol, tireCard);
        Row pressure = new Row(c.getString(R.string.diary_cat_tire_pressure), K_DAYS,
            new int[][]{{0, 0}, {15, 0}, {30, 0}, {60, 0}},
            Prefs.DIARY_DEF_TIRE_PRESSURE_DAYS, 0, Prefs.getDiaryTirePressureDays(c), 0,
            (a, m) -> Prefs.setDiaryTirePressureDays(c, a));
        // The cold-snap nudge belongs to this item: it is in its detail window.
        pressure.extra = () -> Style.toggleRow(c, c.getString(R.string.cfg_diary_cold_snap),
            Prefs.getDiaryTireColdSnap(c),
            on -> { Prefs.setDiaryTireColdSnap(c, on); CarDiary.notifyChanged(); });
        tireCard.addView(intervalRow(pressure));
        tireCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_tire_rotation), K_KM,
            new int[][]{{0, 0}, {5_000, 0}, {10_000, 0}, {15_000, 0}},
            Prefs.DIARY_DEF_TIRE_ROTATION_KM, 0, Prefs.getDiaryTireRotationKm(c), 0,
            (a, m) -> Prefs.setDiaryTireRotationKm(c, a))));

        LinearLayout srvCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_grp_service));
        ConfigCards.add(leftCol, srvCard);
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


        addGroupRows(tireCard, CarDiary.Group.TIRES);
        addGroupRows(srvCard, CarDiary.Group.SERVICE);

        LinearLayout lookCard = ConfigCards.card(c, c.getString(R.string.diary_grp_cosmetic));
        ConfigCards.add(leftCol, lookCard);
        lookCard.addView(intervalRow(new Row(c.getString(R.string.diary_cat_wipers), K_MONTHS,
            new int[][]{{0, 0}, {6, 0}, {12, 0}, {24, 0}},
            Prefs.DIARY_DEF_WIPERS_MONTHS, 0, Prefs.getDiaryWipersMonths(c), 0,
            (a, m) -> Prefs.setDiaryWipersMonths(c, a))));
        addGroupRows(lookCard, CarDiary.Group.COSMETIC);

        // ---- Right column: Jolt detection ----
        LinearLayout joltCard = ConfigCards.card(c, c.getString(R.string.cfg_diary_jolt_title));
        ConfigCards.add(rightCol, joltCard);
        joltCard.addView(ConfigCards.hint(c, c.getString(R.string.cfg_diary_jolt_hint)));

        String curSens = Prefs.getDiaryJoltSensitivity(c);
        String[] sKeys = {ImuHub.JOLT_OFF, ImuHub.JOLT_LOW, ImuHub.JOLT_MEDIUM, ImuHub.JOLT_HIGH};
        CharSequence[] sLabels = {
            off,
            nameFigure(c.getString(R.string.cfg_diary_jolt_low)),
            nameFigure(c.getString(R.string.cfg_diary_jolt_medium)),
            nameFigure(c.getString(R.string.cfg_diary_jolt_high))
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

    /** "Medium (0.85 g)" -> "Medium" + a smaller, dimmer "0.85 g", so four
     * pills fit the card without clipping. */
    static CharSequence nameFigure(String label) {
        int open = label.indexOf(" (");
        if (open < 0 || !label.endsWith(")")) return label;
        return numUnit(label.substring(0, open), label.substring(open + 2, label.length() - 1));
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
    private interface Extra { View build(); }

    private static final class Row {
        final String label; final int kind; final int[][] presets;
        final int defA, defB, curA, curB; final Setter set;
        /** A setting that belongs to this item, shown in its detail window. */
        Extra extra;
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
            default: return b > 0 ? kmM(a / 1000, b) : kKm(a / 1000);
        }
    }

    // ---- Items with no setting of their own: built-in ones, and the owner's ----

    private static final int[][] P_DAYS = {{0, 0}, {15, 0}, {30, 0}, {60, 0}, {90, 0}, {180, 0}, {365, 0}};
    private static final int[][] P_MONTHS = {{0, 0}, {6, 0}, {12, 0}, {24, 0}, {36, 0}, {60, 0}};
    private static final int[][] P_KM = {{0, 0}, {5_000, 0}, {10_000, 0}, {15_000, 0}, {20_000, 0}};
    private static final int[][] P_KM_MONTHS = {{0, 0}, {10_000, 12}, {20_000, 24}, {40_000, 48}};

    /** Pure: the row kind of {km, months, days}; -1 when all are 0. */
    static int kindOf(int km, int months, int days) {
        if (days > 0) return K_DAYS;
        if (km > 0) return months > 0 ? K_KM_MONTHS : K_KM;
        return months > 0 ? K_MONTHS : -1;
    }

    /** Pure: a row value back to {km, months, days}. */
    static int[] toExtra(int kind, int a, int b) {
        switch (kind) {
            case K_DAYS: return new int[]{0, 0, a};
            case K_MONTHS: return new int[]{0, a, 0};
            case K_KM: return new int[]{a, 0, 0};
            default: return new int[]{a, a == 0 ? 0 : b, 0};
        }
    }

    /** After a card's own rows: one row for each other item of the group that
     * has a schedule (built into the app, or added by the owner), then
     * "Add an item" for the ones that have none. */
    private void addGroupRows(LinearLayout card, CarDiary.Group group) {
        Context c = activity;
        final java.util.Map<CarDiary.Category, int[]> extras =
            CarDiary.parseExtras(Prefs.getDiaryExtraSchedules(c));
        final java.util.List<CarDiary.Category> free = new java.util.ArrayList<>();
        for (CarDiary.Category cat : CarDiary.Category.forGroup(group)) {
            if (!cat.userPickable || CarDiary.ScheduleConfig.hasOwnSetting(cat)) continue;
            int[] x = extras.get(cat);
            final boolean builtIn = cat.hasInterval();
            if (x == null && !builtIn) { free.add(cat); continue; }
            final int defKm = cat.kmInterval != null ? (int) Math.round(cat.kmInterval) : 0;
            final int defMo = cat.monthInterval != null ? cat.monthInterval : 0;
            int kind = builtIn ? kindOf(defKm, defMo, 0) : kindOf(x[0], x[1], x[2]);
            if (kind < 0) kind = K_DAYS;
            final int k = kind;
            int[] cur = x != null ? x : new int[]{defKm, defMo, 0};
            int curA = k == K_DAYS ? cur[2] : (k == K_MONTHS ? cur[1] : cur[0]);
            int curB = k == K_KM_MONTHS ? cur[1] : 0;
            int defA = !builtIn ? curA : (k == K_MONTHS ? defMo : defKm);   // an owner's item has no factory value
            int defB = !builtIn ? curB : (k == K_KM_MONTHS ? defMo : 0);
            int[][] presets = k == K_DAYS ? P_DAYS : k == K_MONTHS ? P_MONTHS : k == K_KM ? P_KM : P_KM_MONTHS;
            card.addView(intervalRow(new Row(c.getString(cat.labelRes), k, presets, defA, defB, curA, curB,
                (a, m) -> {
                    java.util.Map<CarDiary.Category, int[]> now =
                        CarDiary.parseExtras(Prefs.getDiaryExtraSchedules(c));
                    boolean isFactory = builtIn && a == (k == K_MONTHS ? defMo : defKm)
                        && (k != K_KM_MONTHS || m == defMo);
                    // Off takes an owner's item off the list; the factory value needs no override.
                    if ((a == 0 && !builtIn) || isFactory) now.remove(cat);
                    else now.put(cat, toExtra(k, a, m));
                    Prefs.setDiaryExtraSchedules(c, CarDiary.formatExtras(now));
                })));
        }
        if (free.isEmpty()) return;

        TextView add = new TextView(c);
        add.setText("+  " + c.getString(R.string.cfg_diary_add_item));
        add.setTextColor(Style.ACCENT);
        add.setTextSize(20f);
        add.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 60));
        alp.topMargin = Style.dp(c, 6);
        add.setLayoutParams(alp);
        add.setOnClickListener(v -> showAddMenu(add, group, free));
        card.addView(add);
    }

    /** The items of the group with no schedule yet. A pick adds one with a
     * usual first value (km and months for tires and service, else 30 days). */
    private void showAddMenu(View anchor, CarDiary.Group group, java.util.List<CarDiary.Category> free) {
        Context c = activity;
        LinearLayout menu = new LinearLayout(c);
        menu.setOrientation(VERTICAL);
        menu.setBackground(Style.card(Style.CARD, c));
        int pad = Style.dp(c, 6);
        menu.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(menu);
        android.widget.PopupWindow popup = new android.widget.PopupWindow(scroll,
            Style.dp(c, 420), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(Style.dp(c, 8));
        final boolean byKm = group == CarDiary.Group.TIRES || group == CarDiary.Group.SERVICE;
        for (CarDiary.Category cat : free) {
            TextView row = new TextView(c);
            row.setText(c.getString(cat.labelRes));
            row.setTextSize(20f);
            row.setTextColor(Style.TEXT);
            int rp = Style.dp(c, 18);
            row.setPadding(rp, rp, rp, rp);
            row.setOnClickListener(v -> {
                popup.dismiss();
                java.util.Map<CarDiary.Category, int[]> now =
                    CarDiary.parseExtras(Prefs.getDiaryExtraSchedules(c));
                now.put(cat, byKm ? new int[]{10_000, 12, 0} : new int[]{0, 0, 30});
                Prefs.setDiaryExtraSchedules(c, CarDiary.formatExtras(now));
                notifyChange();
            });
            menu.addView(row);
        }
        // The list opens below the link, or above it when the room below is
        // too small (the last card sits at the bottom of the screen).
        int w = Style.dp(c, 420);
        menu.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int want = menu.getMeasuredHeight();
        android.graphics.Rect frame = new android.graphics.Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int gap = Style.dp(c, 8);
        int[] fit = menuPlace(at[1], anchor.getHeight(), want,
            frame.top + gap, Math.min(frame.bottom, SCREEN_BOTTOM_PX) - gap);
        popup.setHeight(fit[1]);
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, at[0], fit[0]);
    }

    /** The dock of the head unit covers the screen below this line. */
    static final int SCREEN_BOTTOM_PX = 968;

    /** Pure: where a menu of height want goes for an anchor at anchorTop.
     * Below the anchor when it fits, else on the side with more room, cut to
     * that room. Returns {top, height}. */
    static int[] menuPlace(int anchorTop, int anchorH, int want, int top, int bottom) {
        int below = bottom - (anchorTop + anchorH);
        int above = anchorTop - top;
        if (want <= below) return new int[]{anchorTop + anchorH, want};
        if (above >= below) {
            int h = Math.min(want, Math.max(above, 0));
            return new int[]{anchorTop - h, h};
        }
        return new int[]{anchorTop + anchorH, Math.max(below, 0)};
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

    /** Any value: per quantity a big figure and a slider, with a minus and a
     * plus that go in round steps (hold to repeat). A tap on the figure opens
     * the keyboard for an exact value. */
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
        // The window takes the first focus, so the keyboard waits for a tap on a figure.
        root.setFocusableInTouchMode(true);

        // Start from the current value, or from the factory one when the row is Off.
        final int[] val = {r.curA > 0 ? r.curA : r.defA, r.curA > 0 ? r.curB : r.defB};
        if (r.kind == K_KM_MONTHS && val[1] <= 0) val[1] = r.defB;

        int firstKind = (r.kind == K_KM_MONTHS) ? K_KM : r.kind;
        root.addView(quantity(dialog, val, 0, firstKind));
        if (r.kind == K_KM_MONTHS) root.addView(quantity(dialog, val, 1, K_MONTHS));

        if (r.extra != null) {
            View extra = r.extra.build();
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = Style.dp(c, 20);
            root.addView(extra, elp);
        }

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
        blp.topMargin = Style.dp(c, 28);
        root.addView(btns, blp);

        // The toggle in the detail window saves at once: show it on the page too.
        dialog.setOnDismissListener(d -> { if (r.extra != null) rebuild(); });
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout(Style.dp(c, 760), ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
        if (window != null) window.setLayout(Style.dp(c, 760), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Slider units: days, months, or thousands of km. */
    static int unitOf(int kind) { return kind == K_KM ? 1_000 : 1; }

    /** The top of the slider: 90 days, 60 months, 100,000 km; more when the
     * saved value is already above it. */
    static int sliderMax(int kind, int value) {
        int max = kind == K_KM ? 100 : (kind == K_MONTHS ? 60 : 90);
        return Math.max(max, value / unitOf(kind));
    }

    /** A tap on minus or plus: 5 days, 6 months, 5,000 km. */
    static int gridOf(int kind) { return kind == K_MONTHS ? 6 : 5; }

    /** Pure: the next value on the step grid in direction dir (-1 or +1), in
     * slider units and kept inside 1..max. 7 days goes to 10 or 5, not 12 or 2. */
    static int step(int kind, int value, int dir, int max) {
        int u = unitOf(kind), g = gridOf(kind), v = value / u;
        int next = dir > 0 ? (v / g + 1) * g : ((v + g - 1) / g - 1) * g;
        return Math.max(1, Math.min(Math.max(max, v), next)) * u;
    }

    /** One quantity: the figure, and under it [-] slider [+]. A tap on the
     * figure moves the window up and opens the keyboard for a direct value
     * (in Park only). */
    private View quantity(android.app.Dialog dialog, int[] val, int idx, int kind) {
        Context c = activity;
        final int u = unitOf(kind);
        final int max = sliderMax(kind, val[idx]);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(c, 20);
        box.setLayoutParams(blp);

        // The figure: an editable number and its unit, smaller and dimmer.
        LinearLayout fig = new LinearLayout(c);
        fig.setOrientation(HORIZONTAL);
        fig.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        final android.widget.EditText num = new android.widget.EditText(c);
        num.setTextSize(40f);
        num.setTypeface(null, Typeface.BOLD);
        num.setTextColor(Style.TEXT);
        num.setBackground(null);
        num.setPadding(Style.dp(c, 12), 0, Style.dp(c, 6), 0);
        num.setIncludeFontPadding(false);
        num.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        num.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        num.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        num.setSelectAllOnFocus(true);
        num.setMinWidth(Style.dp(c, 60));
        num.setGravity(Gravity.END);
        fig.addView(num, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView unit = new TextView(c);
        unit.setText(c.getString(kind == K_KM ? R.string.cfg_diary_unit_thousand_km
            : kind == K_MONTHS ? R.string.cfg_diary_unit_months : R.string.cfg_diary_unit_days));
        unit.setTextSize(40f * Style.UNIT_SCALE_ROW);
        unit.setTextColor(Style.TEXT_DIM);
        unit.setPadding(0, 0, 0, Style.dp(c, 5));
        fig.addView(unit);
        box.addView(fig);

        final android.widget.SeekBar bar = new android.widget.SeekBar(c);
        bar.setMax(max - 1);
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(Style.ACCENT);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Style.TEXT_DIM));

        final boolean[] typing = {false};
        // From the slider or the minus/plus: write the figure and move the thumb.
        final Runnable show = () -> {
            typing[0] = true;
            num.setText(String.valueOf(val[idx] / u));
            num.setSelection(num.getText().length());
            typing[0] = false;
            bar.setProgress(val[idx] / u - 1);
        };
        num.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (typing[0]) return;
                int n = typed(e.toString());
                if (n < 1) return;              // empty or 0: keep the last good value
                val[idx] = n * u;
                bar.setProgress(n - 1);          // the slider stops at its end; the value does not
            }
        });
        // Typing is for a parked car, like the other number fields.
        num.setOnTouchListener((v, ev) -> !com.geely.drivemem.state.CarState.isParked());
        num.setOnFocusChangeListener((v, has) -> {
            android.view.Window w = dialog.getWindow();
            if (!has || w == null) return;
            // Up, clear of the keyboard.
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Style.dp(c, 70);
            w.setAttributes(lp);
        });
        num.setOnEditorActionListener((v, action, ev) -> {
            if (val[idx] >= u) show.run();       // an empty field shows the kept value again
            return false;
        });
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                val[idx] = (progress + 1) * u;
                show.run();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });

        LinearLayout line = new LinearLayout(c);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(nudge("\u2212", () -> { val[idx] = step(kind, val[idx], -1, max); show.run(); }));
        line.addView(bar, new LinearLayout.LayoutParams(0, Style.dp(c, 60), 1f));
        line.addView(nudge("+", () -> { val[idx] = step(kind, val[idx], 1, max); show.run(); }));
        box.addView(line);
        show.run();
        return box;
    }

    /** Pure: the typed figure, or -1 when it is not a number. */
    static int typed(String text) {
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    /** A plain minus or plus, no box. One step per tap; hold to repeat. */
    private View nudge(String sign, Runnable act) {
        Context c = activity;
        TextView t = new TextView(c);
        t.setText(sign);
        t.setTextSize(34f);
        t.setTextColor(Style.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 64), Style.dp(c, 60)));
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> { act.run(); t.postDelayed(repeat[0], 70); };
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    act.run();
                    t.postDelayed(repeat[0], 450);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    t.removeCallbacks(repeat[0]);
                    return true;
                default: return true;
            }
        });
        return t;
    }

    private interface StrChoice { void onPick(String v); }

    private View stringPillRow(CharSequence[] titles, String[] keys, String current, StrChoice onPick) {
        Context c = activity;
        LinearLayout pills = new LinearLayout(c);
        pills.setOrientation(HORIZONTAL);
        // Pills with bold or two-size text have other baselines: without this
        // the row moves the plain ones down and cuts their bottom edge.
        pills.setBaselineAligned(false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Style.dp(c, 10);
        pills.setLayoutParams(plp);
        for (int i = 0; i < titles.length; i++) {
            final String k = keys[i];
            boolean sel = k.equals(current);
            TextView pill = buildPill(titles[i], sel, () -> onPick.onPick(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Style.dp(c, 60), 1f);
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
