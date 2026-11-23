package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.Aux12vMonitor;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.DiaryClipTrimmer;
import com.geely.drivemem.util.Style;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Car Diary & 12V Health dock screen (1920x1080 @ 1.0 density).
 *
 * Layout:
 * - Top row: single primary "+ Add entry" pill on the driver's side (left in
 *   LHD, mirrored in RHD) beside a calm 3-figure summary card (12V battery,
 *   Next due, Open notes).
 * - Optional pending-jolt review banner (shown when parked and an IMU jolt is
 *   queued for review: Log incident / Dismiss).
 * - Two-column body:
 *   - Left (52%): "Car Diary · recent first" with All / Care / Notes filters,
 *     spacious selectable 2-line rows, and inline actions on the selected row.
 *   - Right (48%): Care Schedule card (plus quiet 100% balance reference line)
 *     stacked above the 60-day 12V Morning Trend chart. */
public final class CarDiaryView extends LinearLayout {

    private final Runnable onAttentionChanged;
    private CarDiary.Filter activeFilter = CarDiary.Filter.ALL;
    private long selectedEntryId = -1L;
    private int loadGen;

    private LinearLayout topSummaryCard;
    private LinearLayout pendingBannerHost;
    private LinearLayout filterBar;
    private LinearLayout entriesList;
    private LinearLayout scheduleList;
    private Aux12vTrendView trendView;

    private List<CarDiary.Entry> cachedEntries = Collections.emptyList();

    private final EntityBus.Listener diaryListener = (key, r) -> post(this::refresh);
    private final CarState.Listener parkListener = parked -> post(this::refresh);

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
        CarState.addListener(parkListener);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EntityBus.unsubscribe(CarDiary.EVENT_CHANGED, diaryListener);
        CarState.removeListener(parkListener);
    }

    private void buildSkeleton() {
        Context c = getContext();

        // 1. Top row: single "+ Add entry" button on driver's edge + 3-figure summary card
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
        addBtn.setOnClickListener(v -> DiaryEntryDialog.show(c, null, null, () -> post(this::refresh)));

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

        // 2. Optional pending IMU jolt banner (hidden when empty or moving)
        pendingBannerHost = new LinearLayout(c);
        pendingBannerHost.setOrientation(LinearLayout.VERTICAL);
        pendingBannerHost.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = Style.dp(c, Style.CARD_GAP_DP);
        addView(pendingBannerHost, pLp);

        // 3. Bottom two-column grid (52% Diary list | 48% Schedule + 12V Trend)
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.topMargin = Style.dp(c, Style.COLUMN_GAP_DP);
        addView(grid, gLp);

        // Left card: Car Diary · recent first
        LinearLayout leftCard = new LinearLayout(c);
        leftCard.setOrientation(LinearLayout.VERTICAL);
        leftCard.setBackground(Style.card(Style.cardFillColor(), c));
        int cp = Style.dp(c, 20);
        leftCard.setPadding(cp, cp, cp, cp);
        leftCard.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 52f));

        LinearLayout listHead = new LinearLayout(c);
        listHead.setOrientation(LinearLayout.HORIZONTAL);
        listHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView listTitle = new TextView(c);
        listTitle.setText(c.getString(R.string.diary_list_title));
        listTitle.setTextColor(Style.TEXT);
        listTitle.setTextSize(21f);
        listTitle.setTypeface(null, Typeface.BOLD);
        listHead.addView(listTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        filterBar = new LinearLayout(c);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        listHead.addView(filterBar);
        leftCard.addView(listHead);

        entriesList = new LinearLayout(c);
        entriesList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Style.dp(c, 16);
        leftCard.addView(entriesList, elp);

        // Right column: Care Schedule + 12V Morning Trend
        LinearLayout rightCol = new LinearLayout(c);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 48f));

        LinearLayout schedCard = new LinearLayout(c);
        schedCard.setOrientation(LinearLayout.VERTICAL);
        schedCard.setBackground(Style.card(Style.cardFillColor(), c));
        schedCard.setPadding(cp, cp, cp, cp);
        TextView schedTitle = new TextView(c);
        schedTitle.setText(c.getString(R.string.diary_schedule_title));
        schedTitle.setTextColor(Style.TEXT);
        schedTitle.setTextSize(21f);
        schedTitle.setTypeface(null, Typeface.BOLD);
        schedCard.addView(schedTitle);

        scheduleList = new LinearLayout(c);
        scheduleList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Style.dp(c, 14);
        schedCard.addView(scheduleList, slp);
        rightCol.addView(schedCard, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 12V Morning Trend card
        LinearLayout trendCard = new LinearLayout(c);
        trendCard.setOrientation(LinearLayout.VERTICAL);
        trendCard.setBackground(Style.card(Style.cardFillColor(), c));
        trendCard.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tcLp.topMargin = Style.dp(c, Style.CARD_GAP_DP);

        LinearLayout trendHead = new LinearLayout(c);
        trendHead.setOrientation(LinearLayout.HORIZONTAL);
        trendHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView trendTitle = new TextView(c);
        trendTitle.setText(c.getString(R.string.diary_12v_trend_title));
        trendTitle.setTextColor(Style.TEXT);
        trendTitle.setTextSize(21f);
        trendTitle.setTypeface(null, Typeface.BOLD);
        trendHead.addView(trendTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView limitLegend = new TextView(c);
        limitLegend.setText("--- " + c.getString(R.string.diary_12v_healthy_limit));
        limitLegend.setTextColor(Style.WARN);
        limitLegend.setTextSize(15f);
        trendHead.addView(limitLegend);
        trendCard.addView(trendHead);

        trendView = new Aux12vTrendView(c);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 210));
        tvLp.topMargin = Style.dp(c, 12);
        trendCard.addView(trendView, tvLp);
        rightCol.addView(trendCard, tcLp);

        Style.addMirrored(grid, Style.COLUMN_GAP_DP, leftCard, rightCol);
        rebuildFilters();
    }

    /** Reloads diary entries, due statuses, 12V summary, and pending jolts off the UI thread. */
    public void refresh() {
        final Context c = getContext().getApplicationContext();
        final int gen = ++loadGen;
        CarActor actor = CarActor.get(c);
        Runnable load = () -> {
            long now = System.currentTimeMillis();
            List<CarDiary.Entry> entries = CarDiary.readEntries(c);
            Double odo = CarDiary.latestOdometerKm(c);
            Double outC = CarDiary.latestOutsideTempC();
            List<CarDiary.DueStatus> due = CarDiary.computeDueStatuses(entries, odo, now, outC);
            CarDiary.LastFullCharge full = CarDiary.last100PctCharge(c, odo, now);
            Aux12vMonitor.Summary s12 = Aux12vMonitor.readSummary(c, now);
            List<CarDiary.PendingJolt> jolts = CarDiary.pendingJolts(now);
            post(() -> {
                if (gen != loadGen) return;
                cachedEntries = entries;
                renderSummary(entries, due, s12);
                renderPendingJolts(jolts, now);
                renderEntries();
                renderSchedule(due, full);
                trendView.setData(s12.morningTrend, s12.replacedAtMs);
                if (onAttentionChanged != null) onAttentionChanged.run();
            });
        };
        if (actor != null) actor.runOnCarThread(load);
        else new Thread(load, "diary-load").start();
    }

    private void renderSummary(List<CarDiary.Entry> entries, List<CarDiary.DueStatus> due,
                               Aux12vMonitor.Summary s12) {
        Context c = getContext();
        topSummaryCard.removeAllViews();

        // Figure 1: 12V battery
        Double shownV = s12.latestRestingV != null ? s12.latestRestingV : s12.latestReadyV;
        CharSequence v12Text = shownV != null
            ? Style.valueWithUnit(String.format(Locale.US, "%.1f", shownV),
                healthColor(s12.health), "V", Style.UNIT_SCALE_HERO)
            : "—";
        String v12Sub = healthSubLabel(c, s12);
        int v12SubColor = (s12.health == Aux12vMonitor.Health.WATCH
            || s12.health == Aux12vMonitor.Health.WEAK
            || s12.health == Aux12vMonitor.Health.FAULT) ? healthColor(s12.health) : Style.TEXT_DIM;
        topSummaryCard.addView(summaryFigure(v12Text, c.getString(R.string.diary_sum_12v),
            v12Sub, v12SubColor, false),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topSummaryCard.addView(verticalDivider(c));

        // Figure 2: Next due
        CarDiary.DueStatus next = CarDiary.mostUrgent(due);
        CharSequence dueVal = next != null
            ? c.getString(next.category.labelRes)
            : c.getString(R.string.diary_all_clear);
        CharSequence dueSub = next != null ? formatDueStatus(c, next) : c.getString(R.string.diary_nothing_due);
        int dueSubColor = (next != null && (next.overdue || next.dueSoon)) ? Style.WARN : Style.TEXT_DIM;
        topSummaryCard.addView(summaryFigure(dueVal, c.getString(R.string.diary_sum_next_due),
            dueSub, dueSubColor, true),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topSummaryCard.addView(verticalDivider(c));

        // Figure 3: Open notes (noises + incidents)
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
        topSummaryCard.addView(summaryFigure(String.valueOf(totalOpen),
            c.getString(R.string.diary_sum_open_notes), openSub, Style.TEXT_DIM, false),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private LinearLayout summaryFigure(CharSequence hero, String label, CharSequence sub,
                                       int subColor, boolean wordHero) {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Style.dp(c, 8), 0, Style.dp(c, 8), 0);

        TextView v = new TextView(c);
        v.setText(hero);
        v.setTextColor(Style.TEXT);
        v.setTextSize(wordHero ? 26f : 34f);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(1);
        col.addView(v);

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

    private void renderPendingJolts(List<CarDiary.PendingJolt> jolts, long nowMs) {
        pendingBannerHost.removeAllViews();
        if (jolts == null || jolts.isEmpty() || !CarState.isParked()) {
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
            () -> DiaryEntryDialog.show(c, CarDiary.Category.INCIDENT, j, () -> post(this::refresh)));
        card.addView(logBtn);

        View g = new View(c);
        card.addView(g, new LinearLayout.LayoutParams(Style.dp(c, 12), 1));

        TextView disBtn = Style.cardButton(c, c.getString(R.string.diary_jolt_dismiss_btn), false, () -> {
            CarDiary.dismissPendingJolt(j.id);
            refresh();
        });
        card.addView(disBtn);

        pendingBannerHost.addView(card, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void rebuildFilters() {
        Context c = getContext();
        filterBar.removeAllViews();
        addFilterPill(c.getString(R.string.diary_filter_all), CarDiary.Filter.ALL);
        addFilterPill(c.getString(R.string.diary_filter_care), CarDiary.Filter.CARE);
        addFilterPill(c.getString(R.string.diary_filter_notes), CarDiary.Filter.NOTES);
    }

    private void addFilterPill(String label, CarDiary.Filter f) {
        Context c = getContext();
        boolean active = (f == activeFilter);
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
            if (activeFilter == f) return;
            activeFilter = f;
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
        List<CarDiary.Entry> shown = CarDiary.filter(cachedEntries, activeFilter);
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

        // Inline action strip when selected
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
            act.addView(inlineBtn(c, c.getString(R.string.diary_btn_delete), false, () -> confirmDelete(e)));
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

    private void renderSchedule(List<CarDiary.DueStatus> allDue, CarDiary.LastFullCharge full) {
        Context c = getContext();
        scheduleList.removeAllViews();
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM", Locale.getDefault());
        List<CarDiary.DueStatus> rows = CarDiary.scheduleRows(allDue);
        for (int i = 0; i < rows.size(); i++) {
            CarDiary.DueStatus d = rows.get(i);
            if (i > 0) scheduleList.addView(hairline(c));
            scheduleList.addView(scheduleRow(c, d, fmt));
        }
        if (full != null) {
            scheduleList.addView(hairline(c));
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Style.dp(c, 12), 0, Style.dp(c, 2));

            View dot = statusDot(c, Style.TEXT_DIM);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Style.dp(c, 8), Style.dp(c, 8));
            dlp.rightMargin = Style.dp(c, 12);
            row.addView(dot, dlp);

            TextView left = new TextView(c);
            left.setText(c.getString(R.string.diary_balance_last, fmt.format(new Date(full.tsMs))));
            left.setTextColor(Style.TEXT_DIM);
            left.setTextSize(17f);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView right = new TextView(c);
            right.setText(c.getString(R.string.diary_days_ago, full.daysAgo));
            right.setTextColor(Style.TEXT_DIM);
            right.setTextSize(17f);
            row.addView(right);
            scheduleList.addView(row);
        }
    }

    private LinearLayout scheduleRow(Context c, CarDiary.DueStatus d, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Style.dp(c, 12), 0, Style.dp(c, 12));

        int dotCol = d.lastEntry == null ? Style.TEXT_DIM
            : ((d.overdue || d.dueSoon) ? Style.WARN : Style.GOOD);
        View dot = statusDot(c, dotCol);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Style.dp(c, 10), Style.dp(c, 10));
        dlp.rightMargin = Style.dp(c, 12);
        row.addView(dot, dlp);

        LinearLayout nameCol = new LinearLayout(c);
        nameCol.setOrientation(LinearLayout.HORIZONTAL);
        nameCol.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(c.getString(d.category.labelRes));
        name.setTextColor(Style.TEXT);
        name.setTextSize(19f);
        name.setTypeface(null, Typeface.BOLD);
        nameCol.addView(name);

        TextView last = new TextView(c);
        last.setText(d.lastEntry != null
            ? "  " + c.getString(R.string.diary_last_prefix, fmt.format(new Date(d.lastEntry.tsMs)))
            : "  " + c.getString(R.string.diary_not_logged));
        last.setTextColor(Style.TEXT_DIM);
        last.setTextSize(16f);
        nameCol.addView(last);
        row.addView(nameCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(c);
        status.setText(formatDueStatus(c, d));
        status.setTextColor((d.overdue || d.dueSoon) ? Style.WARN : Style.TEXT);
        status.setTextSize(18f);
        status.setTypeface(null, Typeface.BOLD);
        row.addView(status);

        row.setOnClickListener(v -> DiaryEntryDialog.show(c, d.category, null, () -> post(this::refresh)));
        return row;
    }

    private CharSequence formatDueStatus(Context c, CarDiary.DueStatus d) {
        if (d.lastEntry == null) return c.getString(R.string.diary_tap_to_log);
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
