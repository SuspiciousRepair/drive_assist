package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.Aux12vMonitor;
import com.geely.drivemem.sensors.BatteryHeat;
import com.geely.drivemem.sensors.ChargePeriodSummary;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.util.DiaryClipTrimmer;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Maintenance, TCO & Battery Health dock screen (Tab 3 of the 3-icon dock, 1920x1080 @ 1.0 density).
 *
 * Layout (Option A):
 * - Top row: primary "+ Add entry" pill on the driver's edge beside a calm 3-figure summary card
 *   (12V battery, Next maintenance, Open notes).
 * - Optional pending IMU jolt banner (Log incident / Dismiss).
 * - Two-column body:
 *   - Left (52%): Compact Total Cost of Ownership (TCO) card (R$/km, lifetime total,
 *     Energy vs Maintenance split bar) stacked above "Car Diary · recent first" with class filters,
 *     selectable 2-line rows, Edit button, and Delete icon button.
 *   - Right (48%): Maintenance schedule (Magic Sort + Settings shortcut) stacked above
 *     unified Battery Health card (HV Traction 60-day longevity + 12V 60-day morning trend). */
public final class CarDiaryView extends LinearLayout {

    private final Runnable onAttentionChanged;
    private CarDiary.Group activeGroupFilter = null; // null = All
    private long selectedEntryId = -1L;
    private int loadGen;

    private LinearLayout topSummaryCard;
    private LinearLayout pendingBannerHost;
    private LinearLayout tcoCard;
    private LinearLayout filterBar;
    private LinearLayout entriesList;
    private LinearLayout scheduleList;
    private LinearLayout hvHealthBox;
    private Aux12vTrendView trendView;

    private List<CarDiary.Entry> cachedEntries = Collections.emptyList();
    private List<CarDiary.DueStatus> cachedDue = Collections.emptyList();

    private final EntityBus.Listener diaryListener = (key, r) -> post(this::refresh);

    public CarDiaryView(Context context, Runnable onAttentionChanged) {
        super(context);
        this.onAttentionChanged = onAttentionChanged;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 16);
        setPadding(pad, pad, pad, Style.dp(context, 32));
        buildSkeleton();
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EntityBus.subscribe(CarDiary.EVENT_CHANGED, diaryListener);
        EntityBus.subscribe("charge.cost_updated", diaryListener);
        EntityBus.subscribe("charge.completed", diaryListener);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EntityBus.unsubscribe(CarDiary.EVENT_CHANGED, diaryListener);
        EntityBus.unsubscribe("charge.cost_updated", diaryListener);
        EntityBus.unsubscribe("charge.completed", diaryListener);
    }

    private void buildSkeleton() {
        Context c = getContext();

        // 1. Top row: "+ Add entry" on driver's edge + 3-figure summary card
        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView addBtn = new TextView(c);
        addBtn.setText(c.getString(R.string.diary_add_btn));
        addBtn.setTextColor(Style.onFill(Style.ACCENT));
        addBtn.setTextSize(22f);
        addBtn.setTypeface(null, Typeface.BOLD);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setBackground(Style.card(Style.ACCENT, c, 28));
        int bh = Style.dp(c, 28);
        int bv = Style.dp(c, 22);
        addBtn.setPadding(bh, bv, bh, bv);
        addBtn.setLayoutParams(new LinearLayout.LayoutParams(
            Style.dp(c, 230), ViewGroup.LayoutParams.MATCH_PARENT));
        addBtn.setOnClickListener(v -> DiaryEntryDialog.show(c, null, null, cachedDue, () -> post(this::refresh)));

        topSummaryCard = new LinearLayout(c);
        topSummaryCard.setOrientation(LinearLayout.HORIZONTAL);
        topSummaryCard.setBackground(Style.card(Style.cardFillColor(), c));
        int sp = Style.dp(c, 18);
        topSummaryCard.setPadding(Style.dp(c, 10), sp, Style.dp(c, 10), sp);
        topSummaryCard.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Style.addMirrored(topRow, Style.COLUMN_GAP_DP, addBtn, topSummaryCard);
        addView(topRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Optional pending IMU jolt banner
        pendingBannerHost = new LinearLayout(c);
        pendingBannerHost.setOrientation(LinearLayout.VERTICAL);
        pendingBannerHost.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = Style.dp(c, Style.CARD_GAP_DP);
        addView(pendingBannerHost, pLp);

        // 3. Two-column body (52% TCO + Diary list | 48% Maintenance + Unified Battery Health)
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.topMargin = Style.dp(c, Style.COLUMN_GAP_DP);
        addView(grid, gLp);

        int cp = Style.dp(c, 20);

        // ---- Left column (52%): Compact TCO card + Car Diary list ----
        LinearLayout leftCol = new LinearLayout(c);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 52f));

        tcoCard = new LinearLayout(c);
        tcoCard.setOrientation(LinearLayout.VERTICAL);
        tcoCard.setBackground(Style.card(Style.cardFillColor(), c));
        tcoCard.setPadding(cp, Style.dp(c, 16), cp, Style.dp(c, 16));
        leftCol.addView(tcoCard, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout diaryCard = new LinearLayout(c);
        diaryCard.setOrientation(LinearLayout.VERTICAL);
        diaryCard.setBackground(Style.card(Style.cardFillColor(), c));
        diaryCard.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dcLp.topMargin = Style.dp(c, Style.CARD_GAP_DP);

        LinearLayout listHead = new LinearLayout(c);
        listHead.setOrientation(LinearLayout.VERTICAL);
        TextView listTitle = new TextView(c);
        listTitle.setText(c.getString(R.string.diary_list_title));
        listTitle.setTextColor(Style.TEXT);
        listTitle.setTextSize(21f);
        listTitle.setTypeface(null, Typeface.BOLD);
        listHead.addView(listTitle);

        filterBar = new LinearLayout(c);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView fScroll = new android.widget.HorizontalScrollView(c);
        fScroll.setHorizontalScrollBarEnabled(false);
        fScroll.addView(filterBar);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = Style.dp(c, 12);
        listHead.addView(fScroll, fLp);
        diaryCard.addView(listHead);

        entriesList = new LinearLayout(c);
        entriesList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Style.dp(c, 16);
        diaryCard.addView(entriesList, elp);
        leftCol.addView(diaryCard, dcLp);

        // ---- Right column (48%): Maintenance Schedule + Unified Battery Health (HV + 12V) ----
        LinearLayout rightCol = new LinearLayout(c);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 48f));

        LinearLayout schedCard = new LinearLayout(c);
        schedCard.setOrientation(LinearLayout.VERTICAL);
        schedCard.setBackground(Style.card(Style.cardFillColor(), c));
        schedCard.setPadding(cp, cp, cp, cp);

        LinearLayout schedHead = new LinearLayout(c);
        schedHead.setOrientation(LinearLayout.HORIZONTAL);
        schedHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView schedTitle = new TextView(c);
        schedTitle.setText(c.getString(R.string.diary_schedule_title));
        schedTitle.setTextColor(Style.TEXT);
        schedTitle.setTextSize(21f);
        schedTitle.setTypeface(null, Typeface.BOLD);
        schedHead.addView(schedTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Quick settings shortcut -> TelemetryActivity SEC_DIARY
        ImageView tuneBtn = new ImageView(c);
        tuneBtn.setImageResource(R.drawable.ic_tune);
        tuneBtn.setColorFilter(Style.TEXT_DIM, PorterDuff.Mode.SRC_IN);
        tuneBtn.setBackground(Style.tile(c));
        int tp = Style.dp(c, 9);
        tuneBtn.setPadding(tp, tp, tp, tp);
        int tsz = Style.dp(c, 40);
        tuneBtn.setLayoutParams(new LinearLayout.LayoutParams(tsz, tsz));
        tuneBtn.setOnClickListener(v -> c.startActivity(
            new Intent(c, TelemetryActivity.class).putExtra("section", TelemetryActivity.SEC_DIARY)));
        schedHead.addView(tuneBtn);
        schedCard.addView(schedHead);

        scheduleList = new LinearLayout(c);
        scheduleList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Style.dp(c, 12);
        schedCard.addView(scheduleList, slp);
        rightCol.addView(schedCard, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Unified Battery Health card: HV Traction (top) + 12V Morning Trend (bottom)
        LinearLayout healthCard = new LinearLayout(c);
        healthCard.setOrientation(LinearLayout.VERTICAL);
        healthCard.setBackground(Style.card(Style.cardFillColor(), c));
        healthCard.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams hcLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hcLp.topMargin = Style.dp(c, Style.CARD_GAP_DP);

        LinearLayout healthHead = new LinearLayout(c);
        healthHead.setOrientation(LinearLayout.HORIZONTAL);
        healthHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView healthTitle = new TextView(c);
        healthTitle.setText(c.getString(R.string.diary_battery_health_title));
        healthTitle.setTextColor(Style.TEXT);
        healthTitle.setTextSize(21f);
        healthTitle.setTypeface(null, Typeface.BOLD);
        healthHead.addView(healthTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView infoIco = new ImageView(c);
        infoIco.setImageResource(R.drawable.ic_info_outline);
        infoIco.setColorFilter(Style.TEXT_DIM, PorterDuff.Mode.SRC_IN);
        int isz = Style.dp(c, 26);
        infoIco.setLayoutParams(new LinearLayout.LayoutParams(isz, isz));
        infoIco.setOnClickListener(v -> ChargeStatsView.showInfo(c,
            c.getString(R.string.diary_battery_health_title),
            c.getString(R.string.diary_hv_info_body)));
        healthHead.addView(infoIco);
        healthCard.addView(healthHead);

        hvHealthBox = new LinearLayout(c);
        hvHealthBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hvbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hvbLp.topMargin = Style.dp(c, 12);
        healthCard.addView(hvHealthBox, hvbLp);

        View div = hairline(c);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 1));
        divLp.topMargin = Style.dp(c, 16);
        divLp.bottomMargin = Style.dp(c, 14);
        healthCard.addView(div, divLp);

        LinearLayout trendHead = new LinearLayout(c);
        trendHead.setOrientation(LinearLayout.HORIZONTAL);
        trendHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView trendTitle = new TextView(c);
        trendTitle.setText(c.getString(R.string.diary_12v_subtitle));
        trendTitle.setTextColor(Style.TEXT);
        trendTitle.setTextSize(17.5f);
        trendTitle.setTypeface(null, Typeface.BOLD);
        trendHead.addView(trendTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView limitLegend = new TextView(c);
        limitLegend.setText("--- " + c.getString(R.string.diary_12v_healthy_limit));
        limitLegend.setTextColor(Style.WARN);
        limitLegend.setTextSize(14.5f);
        trendHead.addView(limitLegend);
        healthCard.addView(trendHead);

        trendView = new Aux12vTrendView(c);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 190));
        tvLp.topMargin = Style.dp(c, 10);
        healthCard.addView(trendView, tvLp);

        rightCol.addView(healthCard, hcLp);

        Style.addMirrored(grid, Style.COLUMN_GAP_DP, leftCol, rightCol);
        rebuildFilters();
    }

    /** Reloads diary entries, due statuses, TCO, HV health, 12V summary, and pending jolts off-thread. */
    public void refresh() {
        final Context c = getContext().getApplicationContext();
        final int gen = ++loadGen;
        CarActor actor = CarActor.get(c);
        Runnable load = () -> {
            long now = System.currentTimeMillis();
            CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.fromPrefs(c);
            List<CarDiary.Entry> entries = CarDiary.readEntries(c);
            Double odo = CarDiary.latestOdometerKm(c);
            Double outC = CarDiary.latestOutsideTempC();
            List<CarDiary.DueStatus> due = CarDiary.computeDueStatuses(entries, odo, now, outC, cfg);
            double avgDailyKm = CarDiary.avgDailyKm(c, now);
            CarDiary.LastFullCharge full = CarDiary.last100PctCharge(c, odo, now);
            CarDiary.TcoSummary tco = CarDiary.readTcoSummary(c);
            CarDiary.HvHealthSummary hv = CarDiary.readHvHealthSummary(c, now);
            Aux12vMonitor.Summary s12 = Aux12vMonitor.readSummary(c, now);
            List<CarDiary.PendingJolt> jolts = CarDiary.pendingJolts(now);
            post(() -> {
                if (gen != loadGen) return;
                cachedEntries = entries;
                cachedDue = due;
                renderSummary(entries, due, avgDailyKm, s12);
                renderPendingJolts(jolts, now);
                renderTcoCard(tco);
                renderEntries();
                renderSchedule(due, full, avgDailyKm, cfg);
                renderHvHealth(hv);
                trendView.setData(s12.morningTrend, s12.replacedAtMs);
                if (onAttentionChanged != null) onAttentionChanged.run();
            });
        };
        if (actor != null) actor.runOnCarThread(load);
        else new Thread(load, "diary-load").start();
    }

    // ---- Top 3-Figure Summary ----

    private void renderSummary(List<CarDiary.Entry> entries, List<CarDiary.DueStatus> due,
                               double avgDailyKm, Aux12vMonitor.Summary s12) {
        Context c = getContext();
        topSummaryCard.removeAllViews();

        Double shownV = s12.latestRestingV != null ? s12.latestRestingV : s12.latestReadyV;
        CharSequence v12Text = shownV != null
            ? Style.valueWithUnit(String.format(Locale.US, "%.1f", shownV),
                healthColor(s12.health), "V", Style.UNIT_SCALE_HERO)
            : "—";
        String v12Sub = healthSubLabel(c, s12);
        int v12SubColor = (s12.health == Aux12vMonitor.Health.WATCH
            || s12.health == Aux12vMonitor.Health.WEAK
            || s12.health == Aux12vMonitor.Health.FAULT) ? healthColor(s12.health) : Style.TEXT_DIM;
        topSummaryCard.addView(summaryFigure(v12Text, 0, c.getString(R.string.diary_sum_12v),
            v12Sub, v12SubColor, false),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topSummaryCard.addView(verticalDivider(c));

        CarDiary.DueStatus next = CarDiary.mostUrgent(due, avgDailyKm);
        CharSequence dueVal = next != null
            ? c.getString(next.category.labelRes)
            : c.getString(R.string.diary_all_clear);
        int dueIconRes = next != null ? next.category.group.iconRes : 0;
        CharSequence dueSub = next != null ? formatDueStatus(c, next) : c.getString(R.string.diary_nothing_due);
        int dueSubColor = (next != null && (next.overdue || next.dueSoon)) ? Style.WARN : Style.TEXT_DIM;
        topSummaryCard.addView(summaryFigure(dueVal, dueIconRes, c.getString(R.string.diary_sum_next_due),
            dueSub, dueSubColor, true),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topSummaryCard.addView(verticalDivider(c));

        int openNoises = 0, openIncidents = 0;
        for (CarDiary.Entry e : entries) {
            if (!e.isOpen()) continue;
            if (CarDiary.KIND_NOISE.equals(e.kind)) openNoises++;
            else openIncidents++;
        }
        int totalOpen = openNoises + openIncidents;
        String openSub = totalOpen == 0
            ? c.getString(R.string.diary_open_none)
            : c.getString(R.string.diary_open_breakdown, openNoises, openIncidents);
        topSummaryCard.addView(summaryFigure(String.valueOf(totalOpen), 0,
            c.getString(R.string.diary_sum_open_notes), openSub, Style.TEXT_DIM, false),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private LinearLayout summaryFigure(CharSequence hero, int heroIconRes, String label, CharSequence sub,
                                       int subColor, boolean wordHero) {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Style.dp(c, 8), 0, Style.dp(c, 8), 0);

        LinearLayout heroRow = new LinearLayout(c);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.CENTER);
        if (heroIconRes != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(heroIconRes);
            iv.setColorFilter(Style.ACCENT, PorterDuff.Mode.SRC_IN);
            int sz = Style.dp(c, 26);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
            ilp.rightMargin = Style.dp(c, 10);
            heroRow.addView(iv, ilp);
        }

        TextView v = new TextView(c);
        v.setText(hero);
        v.setTextColor(Style.TEXT);
        v.setTextSize(wordHero ? 26f : 34f);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(1);
        heroRow.addView(v);
        col.addView(heroRow);

        TextView l = new TextView(c);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(16f);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Style.dp(c, 4), 0, 0);
        col.addView(l);

        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextColor(subColor);
        s.setTextSize(16f);
        s.setTypeface(null, subColor != Style.TEXT_DIM ? Typeface.BOLD : Typeface.NORMAL);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Style.dp(c, 6), 0, 0);
        col.addView(s);
        return col;
    }

    private View verticalDivider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Style.LIGHT ? 0x18000000 : 0x18FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            Style.dp(c, 1), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.topMargin = Style.dp(c, 8);
        lp.bottomMargin = Style.dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    // ---- Option A: Compact Total Cost of Ownership (TCO) Card ----

    private void renderTcoCard(CarDiary.TcoSummary tco) {
        Context c = getContext();
        tcoCard.removeAllViews();

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        title.setText(c.getString(R.string.diary_tco_title));
        title.setTextColor(Style.TEXT);
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (tco.hasCosts() && tco.trackedKm >= 1.0) {
            TextView kmTag = new TextView(c);
            kmTag.setText(c.getString(R.string.diary_tco_over_km,
                String.format(Locale.getDefault(), "%,.0f", tco.trackedKm)));
            kmTag.setTextColor(Style.TEXT_DIM);
            kmTag.setTextSize(14.5f);
            head.addView(kmTag);
        }
        tcoCard.addView(head);

        if (!tco.hasCosts()) {
            TextView empty = new TextView(c);
            empty.setText(c.getString(R.string.diary_tco_empty));
            empty.setTextColor(Style.TEXT_DIM);
            empty.setTextSize(15.5f);
            empty.setPadding(0, Style.dp(c, 8), 0, 0);
            tcoCard.addView(empty);
            return;
        }

        // Hero row: R$/km (left) + Total lifetime R$ (right)
        LinearLayout heroRow = new LinearLayout(c);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Style.dp(c, 8);

        TextView perKmTv = new TextView(c);
        if (tco.costPerKm > 0) {
            SpannableStringBuilder sb = new SpannableStringBuilder(
                Style.moneyWithUnit(tco.costPerKm, Style.TEXT, Style.UNIT_SCALE_HERO));
            Style.appendUnit(sb, " / km", Style.UNIT_SCALE_HERO);
            perKmTv.setText(sb);
        } else {
            perKmTv.setText(Style.moneyWithUnit(tco.totalCost, Style.TEXT, Style.UNIT_SCALE_HERO));
        }
        perKmTv.setTextSize(30f);
        perKmTv.setTypeface(null, Typeface.BOLD);
        heroRow.addView(perKmTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView totalTv = new TextView(c);
        SpannableStringBuilder totSb = new SpannableStringBuilder();
        Style.appendUnit(totSb, c.getString(R.string.diary_tco_total) + "  ", Style.UNIT_SCALE_ROW);
        totSb.append(Style.moneyWithUnit(tco.totalCost, Style.TEXT, Style.UNIT_SCALE_ROW));
        totalTv.setText(totSb);
        totalTv.setTextSize(19f);
        totalTv.setTypeface(null, Typeface.BOLD);
        heroRow.addView(totalTv);
        tcoCard.addView(heroRow, hlp);

        // Energy vs Maintenance proportional bar
        float energyShare = (float) Math.max(0.0, Math.min(1.0, tco.chargeShare));
        float maintShare = Math.max(0f, 1f - energyShare);
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 8));
        blp.topMargin = Style.dp(c, 12);
        if (energyShare > 0.005f) {
            bar.addView(barSeg(c, Style.ACCENT, true, maintShare <= 0.005f),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, energyShare));
        }
        if (maintShare > 0.005f) {
            bar.addView(barSeg(c, Style.WARN, energyShare <= 0.005f, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, maintShare));
        }
        tcoCard.addView(bar, blp);

        // Legend row
        int pEnergy = (int) Math.round(energyShare * 100);
        int pMaint = 100 - pEnergy;
        SpannableStringBuilder leg = new SpannableStringBuilder();
        appendDot(leg, Style.ACCENT,
            c.getString(R.string.diary_tco_energy) + ": " + Prefs.formatMoney(tco.chargeCost) + " (" + pEnergy + "%)");
        leg.append("    ");
        appendDot(leg, Style.WARN,
            c.getString(R.string.diary_tco_maintenance) + ": " + Prefs.formatMoney(tco.diaryCost) + " (" + pMaint + "%)");

        TextView legTv = new TextView(c);
        legTv.setText(leg);
        legTv.setTextColor(Style.TEXT_DIM);
        legTv.setTextSize(14.5f);
        legTv.setPadding(0, Style.dp(c, 8), 0, 0);
        tcoCard.addView(legTv);
    }

    private View barSeg(Context c, int color, boolean leftR, boolean rightR) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = Style.dp(c, 4);
        g.setCornerRadii(new float[]{
            leftR ? r : 0, leftR ? r : 0,
            rightR ? r : 0, rightR ? r : 0,
            rightR ? r : 0, rightR ? r : 0,
            leftR ? r : 0, leftR ? r : 0
        });
        v.setBackground(g);
        return v;
    }

    private static void appendDot(SpannableStringBuilder sb, int color, String text) {
        int a = sb.length();
        sb.append("● ");
        sb.setSpan(new ForegroundColorSpan(color), a, a + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(text);
    }

    // ---- Pending IMU Jolts Banner (park-gate relieved) ----

    private void renderPendingJolts(List<CarDiary.PendingJolt> jolts, long nowMs) {
        pendingBannerHost.removeAllViews();
        if (jolts == null || jolts.isEmpty()) {
            pendingBannerHost.setVisibility(View.GONE);
            return;
        }
        Context c = getContext();
        pendingBannerHost.setVisibility(View.VISIBLE);
        CarDiary.PendingJolt j = jolts.get(0);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Style.outlinedCard(Style.WARN, c));
        int p = Style.dp(c, 18);
        card.setPadding(p, Style.dp(c, 14), p, Style.dp(c, 14));

        long minsAgo = Math.max(0L, (nowMs - j.tsMs) / 60_000L);
        String detail = j.speedKmh != null
            ? c.getString(R.string.diary_jolt_pending_full, minsAgo, j.speedKmh, j.maxG())
            : c.getString(R.string.diary_jolt_pending_short, minsAgo, j.maxG());

        TextView msg = new TextView(c);
        msg.setText(detail);
        msg.setTextColor(Style.TEXT);
        msg.setTextSize(19f);
        msg.setTypeface(null, Typeface.BOLD);
        card.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView logBtn = Style.cardButton(c, c.getString(R.string.diary_jolt_log_btn), true,
            () -> DiaryEntryDialog.show(c, CarDiary.Category.INCIDENT, j, cachedDue, () -> post(this::refresh)));
        card.addView(logBtn);

        card.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 12), 1));

        TextView disBtn = Style.cardButton(c, c.getString(R.string.diary_jolt_dismiss_btn), false, () -> {
            CarDiary.dismissPendingJolt(j.id);
            refresh();
        });
        card.addView(disBtn);

        pendingBannerHost.addView(card, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---- Diary Filters & Entries List (with Edit button + Delete trash icon) ----

    private void rebuildFilters() {
        Context c = getContext();
        filterBar.removeAllViews();
        addFilterPill(c.getString(R.string.diary_filter_all), null);
        for (CarDiary.Group g : CarDiary.Group.values()) {
            addFilterPill(c.getString(g.labelRes), g);
        }
    }

    private void addFilterPill(String label, CarDiary.Group g) {
        Context c = getContext();
        boolean active = (g == activeGroupFilter);
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(16f);
        b.setTextColor(active ? Style.TEXT : Style.TEXT_DIM);
        b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        b.setBackground(active ? Style.card(Style.CARD_HI, c, 20) : null);
        int h = Style.dp(c, 16);
        int v = Style.dp(c, 8);
        b.setPadding(h, v, h, v);
        b.setOnClickListener(view -> {
            if (activeGroupFilter == g) return;
            activeGroupFilter = g;
            rebuildFilters();
            renderEntries();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (filterBar.getChildCount() > 0) lp.leftMargin = Style.dp(c, 6);
        filterBar.addView(b, lp);
    }

    private void renderEntries() {
        Context c = getContext();
        entriesList.removeAllViews();
        List<CarDiary.Entry> shown = CarDiary.filterByGroup(cachedEntries, activeGroupFilter);
        if (shown.isEmpty()) {
            TextView empty = new TextView(c);
            empty.setText(c.getString(R.string.diary_empty));
            empty.setTextColor(Style.TEXT_DIM);
            empty.setTextSize(18f);
            empty.setPadding(Style.dp(c, 8), Style.dp(c, 28), Style.dp(c, 8), Style.dp(c, 28));
            empty.setGravity(Gravity.CENTER);
            entriesList.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM", Locale.getDefault());
        for (int i = 0; i < shown.size(); i++) {
            CarDiary.Entry e = shown.get(i);
            View row = buildEntryRow(e, fmt);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = Style.dp(c, Style.CARD_GAP_DP);
            entriesList.addView(row, lp);
        }
    }

    private View buildEntryRow(CarDiary.Entry e, SimpleDateFormat fmt) {
        Context c = getContext();
        boolean selected = (e.id == selectedEntryId);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(selected ? Style.outlinedCard(Style.ACCENT, c)
            : Style.card(Style.CARD_HI, c, 18));
        int px = Style.dp(c, 18);
        int py = Style.dp(c, 14);
        box.setPadding(px, py, px, py);

        // Line 1: title + date
        LinearLayout l1 = new LinearLayout(c);
        l1.setOrientation(LinearLayout.HORIZONTAL);
        l1.setGravity(Gravity.CENTER_VERTICAL);

        View dot = statusDot(c, entryDotColor(e));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Style.dp(c, 10), Style.dp(c, 10));
        dlp.rightMargin = Style.dp(c, 12);
        l1.addView(dot, dlp);

        TextView title = new TextView(c);
        String label = (e.title != null && !e.title.isEmpty()) ? e.title : c.getString(e.category.labelRes);
        title.setText(label);
        title.setTextColor(Style.TEXT);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        l1.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView date = new TextView(c);
        date.setText(fmt.format(new Date(e.tsMs)));
        date.setTextColor(Style.TEXT_DIM);
        date.setTextSize(17f);
        l1.addView(date);
        box.addView(l1);

        // Line 2: concise detail + cost + odometer
        LinearLayout l2 = new LinearLayout(c);
        l2.setOrientation(LinearLayout.HORIZONTAL);
        l2.setGravity(Gravity.CENTER_VERTICAL);
        l2.setPadding(Style.dp(c, 22), Style.dp(c, 6), 0, 0);

        TextView detail = new TextView(c);
        detail.setText(buildDetailLine(c, e));
        detail.setTextColor(Style.TEXT_DIM);
        detail.setTextSize(17f);
        detail.setMaxLines(2);
        l2.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (e.cost != null && e.cost > 0) {
            TextView cost = new TextView(c);
            cost.setText(Style.moneyWithUnit(e.cost, Style.ACCENT, Style.UNIT_SCALE_ROW));
            cost.setTextSize(18f);
            cost.setTypeface(null, Typeface.BOLD);
            cost.setPadding(Style.dp(c, 14), 0, 0, 0);
            l2.addView(cost);
        }

        if (e.odoKm != null && e.odoKm > 0) {
            TextView odo = new TextView(c);
            odo.setText(Style.valueWithUnit(
                String.format(Locale.getDefault(), "%,.0f", e.odoKm),
                Style.TEXT, "km", Style.UNIT_SCALE_ROW));
            odo.setTextSize(18f);
            odo.setTypeface(null, Typeface.BOLD);
            odo.setPadding(Style.dp(c, 14), 0, 0, 0);
            l2.addView(odo);
        }
        box.addView(l2);

        // Inline action strip when selected: status actions + Edit button + Delete icon
        if (selected) {
            LinearLayout act = new LinearLayout(c);
            act.setOrientation(LinearLayout.HORIZONTAL);
            act.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            act.setPadding(0, Style.dp(c, 12), 0, 0);

            if (CarDiary.KIND_NOISE.equals(e.kind) && e.isOpen()) {
                act.addView(inlineBtn(c, c.getString(R.string.diary_btn_happened_again), false, () ->
                    CarDiary.addRecurrence(c, e.id, () -> post(this::refresh))));
            }
            if (e.isOpen()) {
                act.addView(inlineBtn(c, c.getString(R.string.diary_btn_resolve), true, () ->
                    CarDiary.resolve(c, e.id, () -> post(this::refresh))));
            }
            if (e.clipName != null) {
                File clipFile = DiaryClipTrimmer.resolveClip(c, e.clipName);
                if (clipFile != null) {
                    act.addView(inlineBtn(c, c.getString(R.string.diary_btn_play_clip), false, () ->
                        c.startActivity(new Intent(c, ClipPlayerActivity.class)
                            .putExtra(ClipPlayerActivity.EXTRA_PATH, clipFile.getAbsolutePath()))));
                }
            }
            if (e.category != null && e.category.userPickable) {
                act.addView(inlineIconTextBtn(c, R.drawable.ic_edit_outline, c.getString(R.string.diary_btn_edit),
                    () -> DiaryEntryDialog.showEdit(c, e, cachedDue, () -> post(this::refresh))));
            }
            act.addView(inlineIconOnlyBtn(c, R.drawable.ic_delete_outline, Style.DANGER, () -> confirmDelete(e)));
            box.addView(act);
        }

        box.setOnClickListener(v -> {
            selectedEntryId = (selectedEntryId == e.id) ? -1L : e.id;
            renderEntries();
        });
        return box;
    }

    private TextView inlineBtn(Context c, String label, boolean primary, Runnable onTap) {
        TextView b = Style.cardButton(c, label, primary, onTap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Style.dp(c, 10);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView inlineIconTextBtn(Context c, int iconRes, String label, Runnable onTap) {
        TextView b = Style.cardButton(c, label, false, onTap);
        android.graphics.drawable.Drawable ic = c.getDrawable(iconRes).mutate();
        int sz = Style.dp(c, 18);
        ic.setBounds(0, 0, sz, sz);
        ic.setTint(b.getCurrentTextColor());
        b.setCompoundDrawables(ic, null, null, null);
        b.setCompoundDrawablePadding(Style.dp(c, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Style.dp(c, 10);
        b.setLayoutParams(lp);
        return b;
    }

    private ImageView inlineIconOnlyBtn(Context c, int iconRes, int tint, Runnable onTap) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        iv.setBackground(Style.card(Style.CARD, c, 14));
        int pad = Style.dp(c, 11);
        iv.setPadding(pad, pad, pad, pad);
        int sz = Style.dp(c, 46);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.leftMargin = Style.dp(c, 10);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(v -> onTap.run());
        return iv;
    }

    private void confirmDelete(CarDiary.Entry e) {
        Context c = getContext();
        new AlertDialog.Builder(c)
            .setMessage(c.getString(R.string.diary_delete_q))
            .setPositiveButton(R.string.diary_btn_delete, (d, w) -> {
                if (selectedEntryId == e.id) selectedEntryId = -1L;
                CarDiary.delete(c, e.id, e.clipName, () -> post(this::refresh));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private String buildDetailLine(Context c, CarDiary.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.category == CarDiary.Category.TIRE_PRESSURE) {
            int[] psi = CarDiary.parsePsi(e.metaJson);
            Boolean cold = CarDiary.parseCold(e.metaJson);
            if (psi != null) {
                boolean allEq = (psi[0] == psi[1] && psi[1] == psi[2] && psi[2] == psi[3]);
                sb.append(allEq
                    ? c.getString(R.string.diary_detail_psi_all, psi[0])
                    : String.format(Locale.US, "%d/%d/%d/%d PSI", psi[0], psi[1], psi[2], psi[3]));
                if (cold != null) {
                    sb.append(", ").append(c.getString(cold ? R.string.diary_psi_cold : R.string.diary_psi_warm));
                }
            }
        }
        if (e.category == CarDiary.Category.COOLANT) {
            Boolean topUp = CarDiary.parseTopUp(e.metaJson);
            if (topUp != null) {
                sb.append(c.getString(topUp ? R.string.diary_coolant_topup : R.string.diary_coolant_change));
            }
        }
        if (e.category == CarDiary.Category.SERVICE) {
            List<CarDiary.Category> b = CarDiary.parseBundled(e.metaJson);
            if (!b.isEmpty()) {
                for (int i = 0; i < b.size(); i++) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(c.getString(b.get(i).labelRes));
                }
            }
        }
        if (e.tags != null && !e.tags.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.tags);
        }
        if (e.note != null && !e.note.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.note);
        }
        if (CarDiary.KIND_NOISE.equals(e.kind) && e.occurrences > 1) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(c.getString(R.string.diary_occurrences, e.occurrences));
        }
        if (e.clipName != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(c.getString(R.string.diary_clip_attached));
        }
        if (e.isOpen()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(c.getString(R.string.diary_status_open));
        } else if (CarDiary.STATUS_RESOLVED.equals(e.status)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(c.getString(R.string.diary_status_resolved));
        }
        return sb.toString();
    }

    // ---- Maintenance Schedule (Magic Sort + Custom Intervals) ----

    private static final class ScheduleItem {
        final double sortDays;
        final int iconRes;
        final int iconTint;
        final String title;
        final String subtitle;
        final CharSequence statusText;
        final int statusColor;
        final Runnable onTap;

        ScheduleItem(double sortDays, int iconRes, int iconTint, String title,
                     String subtitle, CharSequence statusText, int statusColor, Runnable onTap) {
            this.sortDays = sortDays;
            this.iconRes = iconRes;
            this.iconTint = iconTint;
            this.title = title;
            this.subtitle = subtitle;
            this.statusText = statusText;
            this.statusColor = statusColor;
            this.onTap = onTap;
        }
    }

    private void renderSchedule(List<CarDiary.DueStatus> allDue, CarDiary.LastFullCharge full,
                                double avgDailyKm, CarDiary.ScheduleConfig cfg) {
        Context c = getContext();
        scheduleList.removeAllViews();
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM", Locale.getDefault());

        List<ScheduleItem> items = new ArrayList<>();
        for (CarDiary.DueStatus d : CarDiary.scheduleRows(allDue, avgDailyKm, 5)) {
            boolean hasCountdown = d.lastEntry != null || d.kmRemaining != null || d.daysRemaining != null;
            int iconTint = !hasCountdown ? Style.TEXT_DIM
                : ((d.overdue || d.dueSoon) ? Style.WARN : Style.ACCENT);
            String sub;
            Double cfgKm = cfg != null ? cfg.kmInterval(d.category) : d.category.kmInterval;
            if (d.lastEntry != null) {
                sub = c.getString(R.string.diary_last_prefix, fmt.format(new Date(d.lastEntry.tsMs)));
            } else if (d.kmRemaining != null && cfgKm != null) {
                sub = c.getString(R.string.diary_at_km_prefix,
                    String.format(Locale.getDefault(), "%,.0f", cfgKm));
            } else {
                sub = c.getString(R.string.diary_not_logged);
            }
            int stCol = !hasCountdown ? Style.TEXT_DIM
                : ((d.overdue || d.dueSoon) ? Style.WARN : Style.TEXT);
            items.add(new ScheduleItem(
                CarDiary.estimatedDaysUntilDue(d, avgDailyKm),
                d.category.group.iconRes,
                iconTint,
                c.getString(d.category.labelRes),
                sub,
                formatDueStatus(c, d),
                stCol,
                () -> DiaryEntryDialog.show(c, d.category, null, cachedDue, () -> post(this::refresh))));
        }

        // Auto-tracked 100% LFP balancing (respects Settings interval; 0 = hidden)
        int balDays = cfg != null ? cfg.balanceDays : CarDiary.BALANCE_INTERVAL_DAYS;
        if (full != null && balDays > 0) {
            int rem = balDays - full.daysAgo;
            boolean overdue = rem <= 0;
            boolean dueSoon = !overdue && rem <= Math.max(2, balDays / 10);
            CharSequence stText = overdue
                ? c.getString(R.string.diary_overdue_days, Math.max(1, -rem))
                : c.getString(R.string.diary_in_days, Math.max(1, rem));
            int tint = (overdue || dueSoon) ? Style.WARN : Style.ACCENT;
            int stCol = (overdue || dueSoon) ? Style.WARN : Style.TEXT;
            items.add(new ScheduleItem(
                rem,
                R.drawable.ic_battery,
                tint,
                c.getString(R.string.diary_cat_balance_100),
                c.getString(R.string.diary_last_prefix, fmt.format(new Date(full.tsMs))),
                stText,
                stCol,
                null));
        }

        items.sort(Comparator.comparingDouble(it -> it.sortDays));
        int count = Math.min(5, items.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) scheduleList.addView(hairline(c));
            scheduleList.addView(buildScheduleRow(c, items.get(i)));
        }
    }

    private LinearLayout buildScheduleRow(Context c, ScheduleItem item) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Style.dp(c, 11), 0, Style.dp(c, 11));

        ImageView ico = new ImageView(c);
        ico.setImageResource(item.iconRes);
        ico.setColorFilter(item.iconTint, PorterDuff.Mode.SRC_IN);
        int sz = Style.dp(c, 22);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = Style.dp(c, 12);
        row.addView(ico, ilp);

        LinearLayout nameCol = new LinearLayout(c);
        nameCol.setOrientation(LinearLayout.HORIZONTAL);
        nameCol.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(item.title);
        name.setTextColor(Style.TEXT);
        name.setTextSize(19f);
        name.setTypeface(null, Typeface.BOLD);
        nameCol.addView(name);

        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(c);
            sub.setText("  " + item.subtitle);
            sub.setTextColor(Style.TEXT_DIM);
            sub.setTextSize(15.5f);
            nameCol.addView(sub);
        }
        row.addView(nameCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(c);
        status.setText(item.statusText);
        status.setTextColor(item.statusColor);
        status.setTextSize(18f);
        status.setTypeface(null, Typeface.BOLD);
        row.addView(status);

        if (item.onTap != null) {
            row.setOnClickListener(v -> item.onTap.run());
        }
        return row;
    }

    // ---- HV Traction Battery 60-Day Longevity (top half of Unified Battery Health card) ----

    private void renderHvHealth(CarDiary.HvHealthSummary hv) {
        Context c = getContext();
        hvHealthBox.removeAllViews();

        BatteryHeat.Stats h = hv != null && hv.heat != null ? hv.heat : new BatteryHeat.Stats();
        ChargePeriodSummary ch = hv != null && hv.charges != null ? hv.charges : new ChargePeriodSummary();
        BatteryHeat.Temps t = h.shown();

        // Subtitle row: HV Traction (60d) + SoH (or Min SoC) pill
        LinearLayout subRow = new LinearLayout(c);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView sub = new TextView(c);
        sub.setText(c.getString(R.string.diary_hv_subtitle));
        sub.setTextColor(Style.TEXT);
        sub.setTextSize(17.5f);
        sub.setTypeface(null, Typeface.BOLD);
        subRow.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(c);
        badge.setTextSize(15.5f);
        badge.setTypeface(null, Typeface.BOLD);
        if (hv != null && hv.soh != null) {
            badge.setText(c.getString(R.string.diary_hv_soh, String.format(Locale.getDefault(), "%.1f", hv.soh)));
            badge.setTextColor(Style.GOOD);
        } else if (ch.lowest != null) {
            badge.setText(c.getString(R.string.diary_hv_min_soc, ch.lowest.min));
            badge.setTextColor(ch.lowest.min < 10 ? Style.DANGER : Style.TEXT_DIM);
        } else {
            badge.setText("—");
            badge.setTextColor(Style.TEXT_DIM);
        }
        subRow.addView(badge);
        hvHealthBox.addView(subRow);

        // 2x2 HV Longevity Tiles
        CharSequence stayVal = h.fullCount == 0 ? "—"
            : withDimWord(hoursVal(h.avgFullMs()), c.getString(R.string.charge_avg_short));
        CharSequence fullTempVal = t.fullN == 0 ? "—"
            : withDimWord(degC(t.fullMax), c.getString(R.string.charge_max_short));
        int fullTempCol = (t.fullN > 0 && t.fullMax >= 35) ? Style.WARN : Style.TEXT;

        SpannableStringBuilder lowVal = new SpannableStringBuilder();
        lowVal.append(hoursVal(h.below20Ms));
        Style.appendUnit(lowVal, " / ", Style.UNIT_SCALE_ROW);
        int b10Start = lowVal.length();
        lowVal.append(hoursVal(h.below10Ms));
        if (h.below10Ms > 0) {
            lowVal.setSpan(new ForegroundColorSpan(Style.DANGER), b10Start, lowVal.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        CharSequence peakVal = t.n > 0 ? degC(t.max) : "—";
        int peakCol = t.n > 0 ? (t.max >= 40 ? Style.DANGER : t.max >= 35 ? Style.WARN : Style.TEXT) : Style.TEXT;
        String peakSub = null;
        if (ch.count > 0) {
            double totalKwh = ch.totalKwh();
            int acPct = totalKwh > 0 ? (int) Math.round(ch.acKwh / totalKwh * 100) : 100;
            peakSub = c.getString(R.string.diary_hv_ac_dc, acPct, 100 - acPct);
        }

        LinearLayout row1 = new LinearLayout(c);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setBaselineAligned(false);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = Style.dp(c, 10);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLp.rightMargin = Style.dp(c, 10);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);

        row1.addView(hvTile(c, c.getString(R.string.charge_card_full_time), stayVal, Style.TEXT, null), leftLp);
        row1.addView(hvTile(c, c.getString(R.string.charge_card_full_temp), fullTempVal, fullTempCol, null), rightLp);
        hvHealthBox.addView(row1, r1Lp);

        LinearLayout row2 = new LinearLayout(c);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setBaselineAligned(false);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = Style.dp(c, 10);
        LinearLayout.LayoutParams leftLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLp2.rightMargin = Style.dp(c, 10);
        LinearLayout.LayoutParams rightLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);

        row2.addView(hvTile(c, c.getString(R.string.diary_hv_low_hours), lowVal,
            h.below20Ms == 0 ? Style.GOOD : Style.TEXT, null), leftLp2);
        row2.addView(hvTile(c, c.getString(R.string.diary_hv_peak_temp), peakVal, peakCol, peakSub), rightLp2);
        hvHealthBox.addView(row2, r2Lp);
    }

    private View hvTile(Context c, String label, CharSequence value, int valColor, String sub) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Style.tile(c));
        int p = Style.dp(c, 12);
        t.setPadding(p, Style.dp(c, 8), p, Style.dp(c, 8));

        TextView l = new TextView(c);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(14f);
        l.setSingleLine(true);
        t.addView(l);

        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(valColor);
        v.setTextSize(20f);
        v.setTypeface(null, Typeface.BOLD);
        v.setSingleLine(true);
        t.addView(v);

        if (sub != null) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextColor(Style.TEXT_DIM);
            s.setTextSize(13.5f);
            s.setSingleLine(true);
            t.addView(s);
        }
        return t;
    }

    private static CharSequence hoursVal(long ms) {
        if (ms <= 0) return Style.valueWithUnit("0", null, "h", Style.UNIT_SCALE_ROW);
        double h = ms / 3_600_000.0;
        return Style.valueWithUnit(String.format(Locale.getDefault(), h >= 10 ? "%.0f" : "%.1f", h),
            null, "h", Style.UNIT_SCALE_ROW);
    }

    private static CharSequence degC(double temp) {
        return Style.valueWithUnit(String.format(Locale.getDefault(), "%.0f", temp),
            null, "°C", Style.UNIT_SCALE_ROW);
    }

    private static CharSequence withDimWord(CharSequence val, String word) {
        SpannableStringBuilder sb = new SpannableStringBuilder(val);
        Style.appendUnit(sb, " " + word, Style.UNIT_SCALE_ROW);
        return sb;
    }

    // ---- Shared Formatters & Small Helpers ----

    private CharSequence formatDueStatus(Context c, CarDiary.DueStatus d) {
        if (d.lastEntry == null && d.kmRemaining == null && d.daysRemaining == null) {
            return c.getString(R.string.diary_tap_to_log);
        }
        if (d.coldSnapNudge) return c.getString(R.string.diary_cold_snap);
        if (d.overdue) {
            if (d.daysRemaining != null && d.daysRemaining <= 0) {
                return c.getString(R.string.diary_overdue_days, Math.max(1, -d.daysRemaining));
            }
            if (d.kmRemaining != null && d.kmRemaining <= 0) {
                return c.getString(R.string.diary_overdue_km, Math.max(1, Math.round(-d.kmRemaining)));
            }
            return c.getString(R.string.diary_due_overdue);
        }
        if (d.kmRemaining != null && (d.daysRemaining == null
                || (d.category.kmInterval != null && d.category.monthInterval != null
                    && d.kmRemaining / d.category.kmInterval
                        < d.daysRemaining / (d.category.monthInterval * 30.4375)))) {
            return Style.valueWithUnit(
                c.getString(R.string.diary_in_prefix) + " "
                    + String.format(Locale.getDefault(), "%,.0f", d.kmRemaining),
                null, "km", Style.UNIT_SCALE_ROW);
        }
        if (d.daysRemaining != null) {
            if (d.daysRemaining >= 365) {
                int yrs = (int) Math.round(d.daysRemaining / 365.0);
                return c.getString(R.string.diary_in_years, yrs);
            }
            if (d.daysRemaining >= 45) {
                int mos = (int) Math.round(d.daysRemaining / 30.4375);
                return c.getString(R.string.diary_in_months, mos);
            }
            return c.getString(R.string.diary_in_days, Math.max(1, d.daysRemaining));
        }
        return "";
    }

    private int healthColor(Aux12vMonitor.Health h) {
        switch (h) {
            case WATCH: return Style.WARN;
            case WEAK:
            case FAULT: return Style.DANGER;
            case GOOD: return Style.TEXT;
            default: return Style.TEXT_DIM;
        }
    }

    private String healthSubLabel(Context c, Aux12vMonitor.Summary s) {
        switch (s.health) {
            case GOOD: return c.getString(R.string.diary_12v_good);
            case WATCH: return c.getString(R.string.diary_12v_watch);
            case WEAK: return c.getString(R.string.diary_12v_weak);
            case FAULT: return c.getString(R.string.diary_12v_fault);
            default: return c.getString(R.string.diary_12v_waiting);
        }
    }

    private int entryDotColor(CarDiary.Entry e) {
        if (e.isOpen()) return Style.WARN;
        if (CarDiary.KIND_AUTO.equals(e.kind)) return Style.ACCENT;
        return Style.GOOD;
    }

    private View statusDot(Context c, int color) {
        View d = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        d.setBackground(g);
        return d;
    }

    private View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Style.LIGHT ? 0x16000000 : 0x14FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 1)));
        return v;
    }
}
