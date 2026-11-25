package com.geely.drivemem.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.Aux12vMonitor;
import com.geely.drivemem.sensors.BatteryHeat;
import com.geely.drivemem.sensors.ChargePeriodSummary;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.util.CsvExport;
import com.geely.drivemem.util.DiaryClipTrimmer;
import com.geely.drivemem.util.Style;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Maintenance, TCO & Battery Health dock screen (Tab 3 of the 3-icon dock, 1920x1080 @ 1.0 density).
 *
 * Layout:
 * - Top row: primary "+ Add entry" pill on the driver's edge beside a 3-section summary strip:
 *   TCO (2/4), Next maintenance (1/4), and Open notes (1/4).
 * - Optional pending IMU jolt banner (Log incident / Dismiss).
 * - Two-column body:
 *   - Left (52%): "Car Diary · recent first" with class filters, selectable 2-line rows,
 *     Map / Edit buttons, and Delete icon button.
 *   - Right (48%): Maintenance schedule (Magic Sort + Settings shortcut) stacked above
 *     unified Battery Health card (HV Traction 60-day longevity + 12V current reading & 60-day trend). */
public final class CarDiaryView extends LinearLayout {

    private final Runnable onAttentionChanged;
    private CarDiary.Group activeGroupFilter = null; // null = All
    private CarDiary.PeriodFilter activePeriodFilter = CarDiary.PeriodFilter.ALL;
    private long selectedEntryId = -1L;
    private int loadGen;

    private LinearLayout topSummaryCard;
    private LinearLayout pendingBannerHost;
    private TextView periodPillLabel;
    private LinearLayout filterBar;
    private LinearLayout entriesList;
    private LinearLayout scheduleList;
    private LinearLayout hvHealthBox;
    private TextView aux12vBadge;
    private Aux12vTrendView trendView;

    private List<CarDiary.Entry> cachedEntries = Collections.emptyList();
    private List<CarDiary.DueStatus> cachedDue = Collections.emptyList();
    private CarDiary.ScheduleConfig cachedCfg;

    /** True while a load is queued for this frame, so several refresh() calls
     * in a row (attach + becoming visible + a bus event) start one load. */
    private boolean refreshQueued;

    // Everything this screen opens, so it can be closed when the screen goes away.
    private AlertDialog entryDialog;
    private android.app.Dialog sideDialog;
    private PopupWindow openPopup;

    /** Smallest touch target on this screen, in px (1 dp = 1 px on the panel). */
    private static final int TOUCH_DP = 60;

    private final EntityBus.Listener diaryListener = (key, r) -> post(this::refresh);

    public CarDiaryView(Context context, Runnable onAttentionChanged) {
        super(context);
        this.onAttentionChanged = onAttentionChanged;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 16);
        setPadding(pad, pad, pad, Style.dp(context, 32));
        buildSkeleton();
        // No load here: the view is not on screen yet. onAttachedToWindow /
        // onVisibilityChanged start the first load.
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
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // The data may have moved on while the screen was hidden (refresh()
        // does nothing then), so reload each time it comes back.
        if (visibility == VISIBLE) refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EntityBus.unsubscribe(CarDiary.EVENT_CHANGED, diaryListener);
        EntityBus.unsubscribe("charge.cost_updated", diaryListener);
        EntityBus.unsubscribe("charge.completed", diaryListener);
        refreshQueued = false;
        dismissOpenWindows();
    }

    private void dismissOpenWindows() {
        if (openPopup != null) {
            try { openPopup.dismiss(); } catch (Throwable ignored) {}
            openPopup = null;
        }
        if (sideDialog != null) {
            try { sideDialog.dismiss(); } catch (Throwable ignored) {}
            sideDialog = null;
        }
        if (entryDialog != null) {
            try { entryDialog.dismiss(); } catch (Throwable ignored) {}
            entryDialog = null;
        }
    }

    /** Remembers the entry dialog just opened (closing an older one first). */
    private void trackEntryDialog(AlertDialog d) {
        if (entryDialog != null && entryDialog != d && entryDialog.isShowing()) {
            try { entryDialog.dismiss(); } catch (Throwable ignored) {}
        }
        entryDialog = d;
    }

    private void openEntryDialog(CarDiary.Category cat, CarDiary.PendingJolt jolt) {
        trackEntryDialog(DiaryEntryDialog.show(getContext(), cat, jolt, cachedDue, () -> post(this::refresh)));
    }

    private void buildSkeleton() {
        Context c = getContext();

        // 1. Optional pending IMU jolt banner
        pendingBannerHost = new LinearLayout(c);
        pendingBannerHost.setOrientation(LinearLayout.VERTICAL);
        pendingBannerHost.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = Style.dp(c, Style.CARD_GAP_DP);
        addView(pendingBannerHost, pLp);

        // 2. Two-column body (52% Cost + Diary list | 48% Maintenance + Unified Battery Health)
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(grid, gLp);

        int cp = Style.dp(c, 20);

        // ---- Left column (52%): Cost of use (the main figure of the page) + Car Diary list ----
        LinearLayout leftCol = new LinearLayout(c);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 52f));

        topSummaryCard = new LinearLayout(c);
        topSummaryCard.setOrientation(LinearLayout.VERTICAL);
        topSummaryCard.setBackground(Style.card(Style.cardFillColor(), c));
        topSummaryCard.setPadding(Style.dp(c, 24), cp, Style.dp(c, 24), cp);
        leftCol.addView(topSummaryCard, new LinearLayout.LayoutParams(
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

        // Title row: "Car Diary" + Period filter pill + Export button (USB / Phone QR)
        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView listTitle = new TextView(c);
        listTitle.setText(c.getString(R.string.diary_list_title));
        listTitle.setTextColor(Style.TEXT);
        listTitle.setTextSize(21f);
        listTitle.setTypeface(null, Typeface.BOLD);
        listTitle.setSingleLine(true);
        titleRow.addView(listTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Period dropdown pill (All time / 30d / 90d / Year)
        LinearLayout periodPill = new LinearLayout(c);
        periodPill.setOrientation(LinearLayout.HORIZONTAL);
        periodPill.setGravity(Gravity.CENTER_VERTICAL);
        periodPill.setBackground(Style.tile(c));
        periodPill.setPadding(Style.dp(c, 18), 0, Style.dp(c, 14), 0);

        periodPillLabel = new TextView(c);
        periodPillLabel.setText(periodLabel(c, activePeriodFilter));
        periodPillLabel.setTextColor(Style.TEXT);
        periodPillLabel.setTextSize(18f);
        periodPillLabel.setTypeface(null, Typeface.BOLD);
        periodPill.addView(periodPillLabel);

        ImageView pChev = new ImageView(c);
        pChev.setImageResource(R.drawable.ic_chevron_right);
        pChev.setColorFilter(Style.TEXT_DIM, PorterDuff.Mode.SRC_IN);
        pChev.setRotation(90f);
        LinearLayout.LayoutParams pChevLp = new LinearLayout.LayoutParams(Style.dp(c, 18), Style.dp(c, 18));
        pChevLp.leftMargin = Style.dp(c, 8);
        periodPill.addView(pChev, pChevLp);

        periodPill.setOnClickListener(v -> showPeriodMenu(periodPill));
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, Style.dp(c, TOUCH_DP));
        ppLp.leftMargin = Style.dp(c, 10);
        titleRow.addView(periodPill, ppLp);

        // Export button (USB CSV+clips / Phone QR)
        ImageView exportBtn = new ImageView(c);
        exportBtn.setImageResource(R.drawable.ic_export);
        exportBtn.setColorFilter(Style.TEXT_DIM, PorterDuff.Mode.SRC_IN);
        exportBtn.setBackground(Style.tile(c));
        int ep = Style.dp(c, 16);
        exportBtn.setPadding(ep, ep, ep, ep);
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(Style.dp(c, TOUCH_DP), Style.dp(c, TOUCH_DP));
        exLp.leftMargin = Style.dp(c, 8);
        exportBtn.setLayoutParams(exLp);
        exportBtn.setOnClickListener(v -> showExportMenu(exportBtn));
        titleRow.addView(exportBtn);

        // Add entry: accent "+" at the end of the title row
        TextView addBtn = new TextView(c);
        addBtn.setText("+");
        addBtn.setContentDescription(c.getString(R.string.diary_add_btn));
        addBtn.setTextColor(Style.onFill(Style.ACCENT));
        addBtn.setTextSize(34f);
        addBtn.setTypeface(null, Typeface.BOLD);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setIncludeFontPadding(false);
        addBtn.setBackground(Style.card(Style.ACCENT, c, 18));
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
            Style.dp(c, TOUCH_DP + 24), Style.dp(c, TOUCH_DP));
        abLp.leftMargin = Style.dp(c, 8);
        addBtn.setOnClickListener(v -> openEntryDialog(null, null));
        titleRow.addView(addBtn, abLp);

        listHead.addView(titleRow);

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
        int tp = Style.dp(c, 16);
        tuneBtn.setPadding(tp, tp, tp, tp);
        int tsz = Style.dp(c, TOUCH_DP);
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
        infoIco.setBackground(Style.tile(c));
        int ip = Style.dp(c, 16);
        infoIco.setPadding(ip, ip, ip, ip);
        int isz = Style.dp(c, TOUCH_DP);
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
        trendTitle.setTextSize(18f);
        trendTitle.setSingleLine(true);
        trendTitle.setEllipsize(TextUtils.TruncateAt.END);
        trendTitle.setTypeface(null, Typeface.BOLD);
        trendHead.addView(trendTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        aux12vBadge = new TextView(c);
        aux12vBadge.setTextSize(18f);
        aux12vBadge.setTypeface(null, Typeface.BOLD);
        aux12vBadge.setSingleLine(true);
        aux12vBadge.setPadding(Style.dp(c, 12), 0, 0, 0);
        trendHead.addView(aux12vBadge);
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

    /** Reloads the screen. Does nothing while the screen is hidden (it reloads
     * when it is shown again), and several calls in one frame start one load. */
    public void refresh() {
        if (!isShown() || refreshQueued) return;
        refreshQueued = true;
        post(this::startLoad);
    }

    /** Reads diary entries, due statuses, TCO, HV health, 12V summary and pending
     * jolts on the diary worker thread (never the hub thread), then renders. */
    private void startLoad() {
        if (!refreshQueued) return;
        refreshQueued = false;
        if (!isShown()) return;
        final Context c = getContext().getApplicationContext();
        final int gen = ++loadGen;
        final CarDiary.PeriodFilter periodSnap = activePeriodFilter;
        CarDiary.runAsync(() -> {
            long now = System.currentTimeMillis();
            CarDiary.ScheduleConfig cfg = CarDiary.ScheduleConfig.fromPrefs(c);
            List<CarDiary.Entry> entries = CarDiary.readEntries(c);
            Double odo = CarDiary.latestOdometerKm(c);
            Double outC = CarDiary.latestOutsideTempC();
            List<CarDiary.DueStatus> due = CarDiary.computeDueStatuses(entries, odo, now, outC, cfg);
            double avgDailyKm = CarDiary.avgDailyKm(c, now);
            CarDiary.LastFullCharge full = CarDiary.last100PctCharge(c, odo, now);
            CarDiary.TcoSummary tco = CarDiary.readTcoSummary(c, periodSnap, now, entries);
            CarDiary.HvHealthSummary hv = CarDiary.readHvHealthSummary(c, now);
            Aux12vMonitor.Summary s12 = Aux12vMonitor.readSummary(c, now);
            List<CarDiary.PendingJolt> jolts = CarDiary.pendingJolts(now);
            post(() -> {
                if (gen != loadGen) return;
                cachedEntries = entries;
                cachedDue = due;
                cachedCfg = cfg;
                renderSummary(tco);
                renderPendingJolts(jolts, now);
                renderEntries();
                renderSchedule(due, full, avgDailyKm, cfg);
                renderHvHealth(hv);
                render12vBadge(s12);
                trendView.setData(s12.morningTrend, s12.replacedAtMs);
                if (onAttentionChanged != null) onAttentionChanged.run();
            });
        });
    }

    // ---- Cost card (head of the left column): cost per km (hero) + total, share bar, buckets ----

    private void renderSummary(CarDiary.TcoSummary tco) {
        Context c = getContext();
        topSummaryCard.removeAllViews();

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        title.setText(c.getString(R.string.diary_tco_title));
        title.setTextColor(Style.TEXT);
        title.setTextSize(21f);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (tco.hasCosts() && tco.trackedKm >= 1.0) {
            TextView kmTag = new TextView(c);
            SpannableStringBuilder kmStr = new SpannableStringBuilder();
            if (activePeriodFilter.kind != CarDiary.PeriodFilter.Kind.ALL) {
                kmStr.append(periodLabel(c, activePeriodFilter)).append(" · ");
            }
            kmStr.append(fill(c.getString(R.string.diary_tco_over_km, MARK_1),
                km(tco.trackedKm, Style.TEXT)));
            kmTag.setText(kmStr);
            kmTag.setTextColor(Style.TEXT_DIM);
            kmTag.setTextSize(18f);
            kmTag.setSingleLine(true);
            kmTag.setPadding(Style.dp(c, 12), 0, 0, 0);
            head.addView(kmTag);
        }
        topSummaryCard.addView(head);

        if (!tco.hasCosts()) {
            TextView empty = new TextView(c);
            empty.setText(c.getString(R.string.diary_tco_empty));
            empty.setTextColor(Style.TEXT_DIM);
            empty.setTextSize(18f);
            empty.setPadding(0, Style.dp(c, 8), 0, 0);
            topSummaryCard.addView(empty);
            return;
        }

        // Hero row: cost per km (left) + total (right), on one baseline
        LinearLayout heroRow = new LinearLayout(c);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setBaselineAligned(true);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Style.dp(c, 6);

        TextView perKmTv = new TextView(c);
        if (tco.costPerKm > 0) {
            SpannableStringBuilder sb = new SpannableStringBuilder(
                Style.moneyWithUnit(tco.costPerKm, Style.TEXT, Style.UNIT_SCALE_HERO));
            Style.appendUnit(sb, " / km", Style.UNIT_SCALE_HERO);
            perKmTv.setText(sb);
        } else {
            perKmTv.setText(Style.moneyWithUnit(tco.totalCost, Style.TEXT, Style.UNIT_SCALE_HERO));
        }
        perKmTv.setTextSize(48f);
        perKmTv.setTypeface(null, Typeface.BOLD);
        perKmTv.setSingleLine(true);
        heroRow.addView(perKmTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (tco.costPerKm > 0) {
            TextView totalTv = new TextView(c);
            SpannableStringBuilder totSb = new SpannableStringBuilder();
            Style.appendUnit(totSb, c.getString(R.string.diary_tco_total) + "  ", Style.UNIT_SCALE_ROW);
            totSb.append(Style.moneyWithUnit(tco.totalCost, Style.TEXT, Style.UNIT_SCALE_ROW));
            totalTv.setText(totSb);
            totalTv.setTextSize(26f);
            totalTv.setTypeface(null, Typeface.BOLD);
            totalTv.setSingleLine(true);
            heroRow.addView(totalTv);
        }
        topSummaryCard.addView(heroRow, hlp);

        float eShare = (float) Math.max(0.0, Math.min(1.0, tco.chargeShare));
        float mShare = (float) Math.max(0.0, Math.min(1.0, tco.maintenanceShare));
        float bShare = (float) Math.max(0.0, Math.min(1.0, tco.bureaucracyShare));
        boolean hasE = eShare > 0.005f;
        boolean hasM = mShare > 0.005f;
        boolean hasB = bShare > 0.005f;
        int pE = Math.round(eShare * 100f);
        int pB = hasB ? Math.round(bShare * 100f) : 0;
        int pM = Math.max(0, 100 - pE - pB);

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 14));
        blp.topMargin = Style.dp(c, 10);
        if (hasE) {
            bar.addView(barSeg(c, Style.ACCENT, true, !hasM && !hasB),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, eShare));
        }
        if (hasM) {
            bar.addView(barSeg(c, Style.WARN, !hasE, !hasB),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, mShare));
        }
        if (hasB) {
            bar.addView(barSeg(c, Style.PURPLE, !hasE && !hasM, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, bShare));
        }
        topSummaryCard.addView(bar, blp);

        // One column per cost bucket, under the bar
        LinearLayout figs = new LinearLayout(c);
        figs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams figLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        figs.addView(costFigure(c, Style.ACCENT, c.getString(R.string.diary_tco_energy),
            tco.chargeCost, pE), figLp);
        figs.addView(costFigure(c, Style.WARN, c.getString(R.string.diary_tco_maintenance),
            tco.maintenanceCost, pM), figLp);
        if (tco.bureaucracyCost > 0.005) {
            figs.addView(costFigure(c, Style.PURPLE, c.getString(R.string.diary_tco_bureaucracy),
                tco.bureaucracyCost, pB), figLp);
        }
        LinearLayout.LayoutParams fgLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fgLp.topMargin = Style.dp(c, 12);
        topSummaryCard.addView(figs, fgLp);
    }

    /** One cost bucket: "● Energy" (dim) over "R$ 432,01 73%". No dot when dotColor is 0,
     * no share when pct is negative. */
    private LinearLayout costFigure(Context c, int dotColor, String label, double amount, int pct) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView l = new TextView(c);
        SpannableStringBuilder ls = new SpannableStringBuilder();
        if (dotColor != 0) appendDot(ls, dotColor, label); else ls.append(label);
        l.setText(ls);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(18f);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(l);

        TextView v = new TextView(c);
        SpannableStringBuilder vs = new SpannableStringBuilder(
            Style.moneyWithUnit(amount, Style.TEXT, Style.UNIT_SCALE_ROW));
        if (pct >= 0) {
            vs.append("  ");
            vs.append(Style.valueWithUnit(String.valueOf(pct), Style.TEXT_DIM, null, Style.UNIT_SCALE_ROW));
            Style.appendUnit(vs, "%", Style.UNIT_SCALE_ROW);
        }
        v.setText(vs);
        v.setTextSize(22f);
        v.setTypeface(null, Typeface.BOLD);
        v.setSingleLine(true);
        v.setPadding(0, Style.dp(c, 2), 0, 0);
        col.addView(v);
        return col;
    }

    private View barSeg(Context c, int color, boolean leftR, boolean rightR) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = Style.dp(c, 7);
        g.setCornerRadii(new float[]{
            leftR ? r : 0, leftR ? r : 0,
            rightR ? r : 0, rightR ? r : 0,
            rightR ? r : 0, rightR ? r : 0,
            leftR ? r : 0, leftR ? r : 0
        });
        v.setBackground(g);
        return v;
    }

    private static void appendDot(SpannableStringBuilder sb, int color, CharSequence text) {
        int a = sb.length();
        sb.append("●\u00A0");
        sb.setSpan(new ForegroundColorSpan(color), a, a + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(text);
    }

    // ---- Number + unit helpers (docs/STYLE-GUIDE.md "Numbers and units") ----

    /** Stand-ins handed to getString() where a styled value goes; fill() swaps them out. */
    static final String MARK_1 = "\u0001";
    static final String MARK_2 = "\u0002";

    /** Puts styled values (number + smaller, dimmer unit) into an already
     * formatted string: the first value replaces MARK_1, the second MARK_2. */
    static CharSequence fill(String formatted, CharSequence... values) {
        SpannableStringBuilder sb = new SpannableStringBuilder(formatted);
        for (int i = 0; i < values.length; i++) {
            String mark = String.valueOf((char) (1 + i));
            int at = sb.toString().indexOf(mark);
            if (at >= 0) sb.replace(at, at + 1, values[i]);
        }
        return sb;
    }

    private static CharSequence km(double km, Integer numberColor) {
        return Style.valueWithUnit(String.format(Locale.getDefault(), "%,.0f", km),
            numberColor, "km", Style.UNIT_SCALE_ROW);
    }

    /** "62%" with the sign as a unit. */
    private static CharSequence pct(String number, Integer numberColor) {
        SpannableStringBuilder sb = new SpannableStringBuilder(
            Style.valueWithUnit(number, numberColor, null, Style.UNIT_SCALE_ROW));
        Style.appendUnit(sb, "%", Style.UNIT_SCALE_ROW);
        return sb;
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

        SpannableStringBuilder detail = new SpannableStringBuilder(fill(
            c.getString(R.string.diary_jolt_pending, MARK_1),
            Style.durationWithUnits(Math.max(0L, nowMs - j.tsMs), Style.UNIT_SCALE_ROW)));
        if (j.speedKmh != null) {
            detail.append(" · ").append(Style.valueWithUnit(
                String.format(Locale.getDefault(), "%.0f", j.speedKmh), null, "km/h", Style.UNIT_SCALE_ROW));
        }
        detail.append(" · ").append(Style.valueWithUnit(
            String.format(Locale.getDefault(), "%.1f", j.maxG()), null, "g", Style.UNIT_SCALE_ROW));

        TextView msg = new TextView(c);
        msg.setText(detail);
        msg.setTextColor(Style.TEXT);
        msg.setTextSize(19f);
        msg.setTypeface(null, Typeface.BOLD);
        card.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView logBtn = Style.cardButton(c, c.getString(R.string.diary_jolt_log_btn), true,
            () -> openEntryDialog(CarDiary.Category.INCIDENT, j));
        card.addView(logBtn);

        card.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 12), 1));

        TextView disBtn = Style.cardButton(c, c.getString(R.string.diary_jolt_dismiss_btn), false, () -> {
            com.geely.drivemem.sensors.JoltFeedback.record(c, j, false);
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
        b.setTextSize(18f);
        b.setTextColor(active ? Style.TEXT : Style.TEXT_DIM);
        b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        b.setBackground(active ? Style.card(Style.CARD_HI, c, 20) : null);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinimumHeight(Style.dp(c, TOUCH_DP));
        int h = Style.dp(c, 20);
        b.setPadding(h, 0, h, 0);
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
        List<CarDiary.Entry> shown = CarDiary.filterEntries(
            cachedEntries, activeGroupFilter, activePeriodFilter,
            System.currentTimeMillis(), ZoneId.systemDefault());
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

    // ---- Diary Period Picker & Export Menu (USB + Phone QR) ----

    private String periodLabel(Context c, CarDiary.PeriodFilter f) {
        if (f == null) return c.getString(R.string.diary_period_all);
        switch (f.kind) {
            case DAYS_30: return c.getString(R.string.diary_period_30d);
            case DAYS_90: return c.getString(R.string.diary_period_90d);
            case YEAR: return String.valueOf(f.year);
            default: return c.getString(R.string.diary_period_all);
        }
    }

    private void showPeriodMenu(View anchor) {
        Context c = getContext();
        LinearLayout menu = new LinearLayout(c);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(Style.card(Style.CARD, c));
        int pad = Style.dp(c, 6);
        menu.setPadding(pad, pad, pad, pad);

        PopupWindow popup = new PopupWindow(menu,
            Style.dp(c, 260), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(Style.dp(c, 8));
        trackPopup(popup);

        List<CarDiary.PeriodFilter> options = new ArrayList<>();
        options.add(CarDiary.PeriodFilter.ALL);
        options.add(CarDiary.PeriodFilter.DAYS_30);
        options.add(CarDiary.PeriodFilter.DAYS_90);
        for (int yr : CarDiary.availableYears(cachedEntries, System.currentTimeMillis(), ZoneId.systemDefault())) {
            options.add(CarDiary.PeriodFilter.ofYear(yr));
        }
        for (CarDiary.PeriodFilter opt : options) {
            TextView row = new TextView(c);
            row.setText(periodLabel(c, opt));
            row.setTextSize(18f);
            boolean current = opt.equals(activePeriodFilter);
            row.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
            row.setTextColor(current ? Style.ACCENT : Style.TEXT);
            int rpad = Style.dp(c, 18);
            row.setPadding(rpad, rpad, rpad, rpad);
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (!opt.equals(activePeriodFilter)) {
                    activePeriodFilter = opt;
                    if (periodPillLabel != null) periodPillLabel.setText(periodLabel(c, opt));
                    refresh();
                }
            });
            menu.addView(row);
        }
        popup.showAsDropDown(anchor, 0, Style.dp(c, 8));
    }

    private void showExportMenu(View anchor) {
        Context c = getContext();
        LinearLayout menu = new LinearLayout(c);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(Style.card(Style.CARD, c));
        int pad = Style.dp(c, 6);
        menu.setPadding(pad, pad, pad, pad);

        PopupWindow popup = new PopupWindow(menu,
            Style.dp(c, 320), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(Style.dp(c, 8));
        trackPopup(popup);

        int rpad = Style.dp(c, 18);
        TextView usbRow = new TextView(c);
        usbRow.setText(c.getString(R.string.diary_export_usb));
        usbRow.setTextSize(18f);
        usbRow.setTextColor(Style.TEXT);
        usbRow.setPadding(rpad, rpad, rpad, rpad);
        usbRow.setOnClickListener(v -> {
            popup.dismiss();
            List<CarDiary.Entry> shown = CarDiary.filterEntries(
                cachedEntries, null, activePeriodFilter,
                System.currentTimeMillis(), ZoneId.systemDefault());
            CsvExport.exportDiaryToUsb(c, shown, (ok, drive, copied) -> post(() -> {
                String msg = drive == null ? c.getString(R.string.charge_export_no_drive)
                    : !ok || copied == 0 ? c.getString(R.string.charge_export_nothing)
                    : c.getString(R.string.charge_export_ok, copied);
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
            }));
        });
        menu.addView(usbRow);

        menu.addView(hairline(c));

        TextView qrRow = new TextView(c);
        qrRow.setText(c.getString(R.string.diary_export_qr));
        qrRow.setTextSize(18f);
        qrRow.setTextColor(Style.TEXT);
        qrRow.setPadding(rpad, rpad, rpad, rpad);
        qrRow.setOnClickListener(v -> {
            popup.dismiss();
            Activity act = unwrapActivity(c);
            if (act != null) PhoneAccessBlock.showQr(act);
        });
        menu.addView(qrRow);

        popup.showAsDropDown(anchor, -Style.dp(c, 320 - TOUCH_DP), Style.dp(c, 8));
    }

    /** Remembers the open menu (closing an older one), and forgets it when it closes. */
    private void trackPopup(PopupWindow popup) {
        if (openPopup != null) {
            try { openPopup.dismiss(); } catch (Throwable ignored) {}
        }
        openPopup = popup;
        popup.setOnDismissListener(() -> { if (openPopup == popup) openPopup = null; });
    }

    /** Remembers the open map / delete dialog (closing an older one), and forgets it when it closes. */
    private void trackSideDialog(android.app.Dialog dlg) {
        if (sideDialog != null) {
            try { sideDialog.dismiss(); } catch (Throwable ignored) {}
        }
        sideDialog = dlg;
        dlg.setOnDismissListener(d -> { if (sideDialog == dlg) sideDialog = null; });
    }

    private static Activity unwrapActivity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
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

        // The class icon tells what the entry is; its colour tells the state
        // (amber = still open, as the dot did before).
        ImageView ico = new ImageView(c);
        ico.setImageResource(e.category != null ? e.category.group.iconRes : R.drawable.ic_diary_notes);
        ico.setColorFilter(entryDotColor(e), PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Style.dp(c, 26), Style.dp(c, 26));
        dlp.rightMargin = Style.dp(c, 12);
        l1.addView(ico, dlp);

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
        date.setTextSize(18f);
        l1.addView(date);
        box.addView(l1);

        // Line 2: concise detail + cost + odometer
        LinearLayout l2 = new LinearLayout(c);
        l2.setOrientation(LinearLayout.HORIZONTAL);
        l2.setGravity(Gravity.CENTER_VERTICAL);
        l2.setPadding(Style.dp(c, 38), Style.dp(c, 6), 0, 0);

        TextView detail = new TextView(c);
        detail.setText(buildDetailLine(c, e));
        detail.setTextColor(Style.TEXT_DIM);
        detail.setTextSize(18f);
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
            if (e.lat != null && e.lon != null) {
                act.addView(inlineIconTextBtn(c, R.drawable.ic_map, c.getString(R.string.diary_btn_map),
                    () -> showLocationDialog(c, e)));
            }
            if (e.category != null && e.category.userPickable) {
                act.addView(inlineIconTextBtn(c, R.drawable.ic_edit_outline, c.getString(R.string.diary_btn_edit),
                    () -> trackEntryDialog(DiaryEntryDialog.showEdit(
                        c, e, cachedDue, () -> post(this::refresh)))));
            }
            act.addView(inlineIconOnlyBtn(c, R.drawable.ic_delete_outline, Style.TEXT_DIM, () -> confirmDelete(e)));
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
        int pad = Style.dp(c, 16);
        iv.setPadding(pad, pad, pad, pad);
        int sz = Style.dp(c, TOUCH_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.leftMargin = Style.dp(c, 10);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(v -> onTap.run());
        return iv;
    }

    private void showLocationDialog(Context c, CarDiary.Entry e) {
        if (e.lat == null || e.lon == null) return;
        android.app.Dialog dlg = new android.app.Dialog(c);
        dlg.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 22);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Style.card(Style.cardFillColor(), c));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String label = (e.title != null && !e.title.isEmpty()) ? e.title : c.getString(e.category.labelRes);
        TextView title = Style.title(c, label);
        title.setTextSize(22f);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String coords = String.format(Locale.US, "%.5f, %.5f", e.lat, e.lon);
        TextView sub = new TextView(c);
        sub.setText(new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(e.tsMs)) + "  ·  " + coords);
        sub.setTextColor(Style.TEXT_DIM);
        sub.setTextSize(18f);
        sub.setSingleLine(true);
        sub.setPadding(Style.dp(c, 16), 0, 0, 0);
        head.addView(sub);
        root.addView(head);

        RouteView map = new RouteView(c);
        map.setLocationPin(e.lat, e.lon, "!");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 420));
        mlp.topMargin = Style.dp(c, 14);
        root.addView(map, mlp);

        dlg.setContentView(root);
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            win.setLayout(Style.dp(c, 860), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        trackSideDialog(dlg);
        dlg.show();
    }

    /** Delete confirmation in the app's own dialog look (card, large text,
     * card buttons), like the info and map dialogs. */
    private void confirmDelete(CarDiary.Entry e) {
        Context c = getContext();
        android.app.Dialog dlg = new android.app.Dialog(c);
        dlg.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 32);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Style.card(Style.cardFillColor(), c));

        String label = (e.title != null && !e.title.isEmpty()) ? e.title : c.getString(e.category.labelRes);
        root.addView(Style.title(c, label));

        TextView msg = new TextView(c);
        msg.setText(c.getString(R.string.diary_delete_q));
        msg.setTextColor(Style.TEXT);
        msg.setTextSize(22f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Style.dp(c, 12);
        root.addView(msg, mlp);

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(c, 28);

        TextView cancel = Style.cardButton(c, c.getString(android.R.string.cancel), false, dlg::dismiss);
        cancel.setBackground(Style.tile(c));
        cancel.setMinWidth(Style.dp(c, 160));
        btns.addView(cancel);

        TextView del = Style.cardButton(c, c.getString(R.string.diary_btn_delete), false, () -> {
            dlg.dismiss();
            if (selectedEntryId == e.id) selectedEntryId = -1L;
            CarDiary.delete(c, e.id, e.clipName, () -> post(this::refresh));
        });
        del.setBackground(Style.card(Style.DANGER, c));
        del.setTextColor(Style.TEXT_ON);
        del.setTypeface(null, Typeface.BOLD);
        del.setMinWidth(Style.dp(c, 160));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Style.dp(c, 12);
        btns.addView(del, dlp);
        root.addView(btns, blp);

        dlg.setContentView(root);
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            win.setLayout(Style.dp(c, 640), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        trackSideDialog(dlg);
        dlg.show();
        if (win != null) win.setLayout(Style.dp(c, 640), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private CharSequence buildDetailLine(Context c, CarDiary.Entry e) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (e.category == CarDiary.Category.TIRE_PRESSURE) {
            int[] psi = CarDiary.parsePsi(e.metaJson);
            Boolean cold = CarDiary.parseCold(e.metaJson);
            if (psi != null) {
                boolean allEq = (psi[0] == psi[1] && psi[1] == psi[2] && psi[2] == psi[3]);
                sb.append(allEq
                    ? fill(c.getString(R.string.diary_detail_psi_all, MARK_1),
                        Style.valueWithUnit(String.valueOf(psi[0]), null, "PSI", Style.UNIT_SCALE_ROW))
                    : Style.valueWithUnit(
                        String.format(Locale.US, "%d/%d/%d/%d", psi[0], psi[1], psi[2], psi[3]),
                        null, "PSI", Style.UNIT_SCALE_ROW));
                if (cold != null) {
                    sb.append(", ").append(c.getString(cold ? R.string.diary_psi_cold : R.string.diary_psi_warm));
                }
            }
        }
        if (e.category != null && e.category.coverageDays >= 365) {
            // Insurance, tax and licensing: who, until when, and the number to call.
            String provider = CarDiary.parseMetaText(e.metaJson, CarDiary.META_PROVIDER);
            Long until = CarDiary.parseValidUntilMs(e.metaJson);
            String phone = CarDiary.parseMetaText(e.metaJson, CarDiary.META_ASSIST_PHONE);
            if (provider != null) sb.append(provider);
            if (until != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(c.getString(R.string.diary_cov_until_detail,
                    new SimpleDateFormat("d MMM yy", Locale.getDefault()).format(new Date(until))));
            }
            if (phone != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("\u260E ").append(phone);
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
            // Noise tags are saved as stable keys; show them in the current language.
            sb.append(DiaryEntryDialog.noiseTagsLabel(c, e.tags));
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
        return sb;
    }

    // ---- Maintenance Schedule (Magic Sort + Custom Intervals) ----

    private static final class ScheduleItem {
        final double sortDays;
        final int iconRes;
        final int iconTint;
        final String title;
        final CharSequence subtitle;
        final CharSequence statusText;
        final int statusColor;
        final Runnable onTap;

        ScheduleItem(double sortDays, int iconRes, int iconTint, String title,
                     CharSequence subtitle, CharSequence statusText, int statusColor, Runnable onTap) {
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
            CharSequence sub;
            Double cfgKm = cfg != null ? cfg.kmInterval(d.category) : d.category.kmInterval;
            if (d.lastEntry != null) {
                sub = c.getString(R.string.diary_last_prefix, fmt.format(new Date(d.lastEntry.tsMs)));
            } else if (d.kmRemaining != null && cfgKm != null) {
                sub = fill(c.getString(R.string.diary_at_km_prefix, MARK_1), km(cfgKm, null));
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
                () -> openEntryDialog(d.category, null)));
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
        row.setMinimumHeight(Style.dp(c, TOUCH_DP));

        ImageView ico = new ImageView(c);
        ico.setImageResource(item.iconRes);
        ico.setColorFilter(item.iconTint, PorterDuff.Mode.SRC_IN);
        int sz = Style.dp(c, 24);
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
        name.setSingleLine(true);
        nameCol.addView(name);

        if (item.subtitle != null && item.subtitle.length() > 0) {
            TextView sub = new TextView(c);
            sub.setText(item.subtitle);
            sub.setTextColor(Style.TEXT_DIM);
            sub.setTextSize(18f);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(Style.dp(c, 12), 0, Style.dp(c, 12), 0);
            nameCol.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        row.addView(nameCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(c);
        status.setText(item.statusText);
        status.setTextColor(item.statusColor);
        status.setTextSize(18f);
        status.setTypeface(null, Typeface.BOLD);
        status.setSingleLine(true);
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
        sub.setTextSize(18f);
        sub.setTypeface(null, Typeface.BOLD);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        subRow.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(c);
        badge.setTextSize(18f);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setSingleLine(true);
        if (hv != null && hv.soh != null) {
            badge.setText(fill(c.getString(R.string.diary_hv_soh, MARK_1),
                pct(String.format(Locale.getDefault(), "%.1f", hv.soh), null)));
            badge.setTextColor(Style.GOOD);
        } else if (ch.lowest != null) {
            badge.setText(fill(c.getString(R.string.diary_hv_min_soc, MARK_1),
                pct(String.valueOf(ch.lowest.min), null)));
            badge.setTextColor(ch.lowest.min < 10 ? Style.DANGER : Style.TEXT_DIM);
        } else {
            badge.setText("—");
            badge.setTextColor(Style.TEXT_DIM);
        }
        subRow.addView(badge);
        hvHealthBox.addView(subRow);

        // 2x2 HV Longevity Tiles:
        //   Row 1: Time at 100%             | Peak temperature
        //   Row 2: Low charge (<20% / <10%) | Charging mix (AC vs DC %)
        CharSequence stayVal = h.fullCount == 0 ? "—"
            : withDimWord(hoursVal(h.avgFullMs()), c.getString(R.string.charge_avg_short));

        CharSequence peakVal = t.n > 0 ? degC(t.max) : "—";
        int peakCol = t.n > 0 ? (t.max >= 40 ? Style.DANGER : t.max >= 35 ? Style.WARN : Style.TEXT) : Style.TEXT;

        SpannableStringBuilder lowVal = new SpannableStringBuilder();
        lowVal.append(hoursVal(h.below20Ms));
        Style.appendUnit(lowVal, " / ", Style.UNIT_SCALE_ROW);
        int b10Start = lowVal.length();
        lowVal.append(hoursVal(h.below10Ms));
        if (h.below10Ms > 0) {
            lowVal.setSpan(new ForegroundColorSpan(Style.DANGER), b10Start, lowVal.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

        row1.addView(hvTile(c, c.getString(R.string.diary_hv_time_100), stayVal, Style.TEXT), leftLp);
        row1.addView(hvTile(c, c.getString(R.string.diary_hv_peak_temp), peakVal, peakCol), rightLp);
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
            h.below20Ms == 0 ? Style.GOOD : Style.TEXT), leftLp2);
        row2.addView(hvChargingMixTile(c, ch), rightLp2);
        hvHealthBox.addView(row2, r2Lp);
    }

    private View hvChargingMixTile(Context c, ChargePeriodSummary ch) {
        if (ch == null || ch.count <= 0) {
            return hvTile(c, c.getString(R.string.diary_hv_charging_mix), "—", Style.TEXT);
        }
        double totalKwh = ch.totalKwh();
        float acShare = totalKwh > 0 ? (float) Math.max(0.0, Math.min(1.0, ch.acKwh / totalKwh)) : 1f;
        float dcShare = Math.max(0f, 1f - acShare);
        int acPct = Math.round(acShare * 100f);
        int dcPct = 100 - acPct;

        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Style.tile(c));
        int p = Style.dp(c, 12);
        t.setPadding(p, Style.dp(c, 8), p, Style.dp(c, 8));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(c);
        l.setText(c.getString(R.string.diary_hv_charging_mix));
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(18f);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView countTv = new TextView(c);
        countTv.setText(ch.count == 1
            ? c.getString(R.string.stats_charge_one)
            : c.getString(R.string.stats_charge_many, ch.count));
        countTv.setTextColor(Style.TEXT_DIM);
        countTv.setTextSize(18f);
        countTv.setSingleLine(true);
        countTv.setPadding(Style.dp(c, 8), 0, 0, 0);
        head.addView(countTv);
        t.addView(head);

        // Proportional AC (blue) vs DC (green) bar
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 9));
        blp.topMargin = Style.dp(c, 8);
        blp.bottomMargin = Style.dp(c, 7);
        if (acShare > 0.005f) {
            bar.addView(barSeg(c, Style.ACCENT, true, dcShare <= 0.005f),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, acShare));
        }
        if (dcShare > 0.005f) {
            bar.addView(barSeg(c, Style.GOOD, acShare <= 0.005f, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, dcShare));
        }
        t.addView(bar, blp);

        // "● AC 62%   ● DC 38%": the wording comes from one string, the two
        // dots take the AC and DC colours, each % sign is a unit.
        SpannableStringBuilder mix = new SpannableStringBuilder(fill(
            c.getString(R.string.diary_hv_ac_dc, MARK_1, MARK_2),
            pct(String.valueOf(acPct), null), pct(String.valueOf(dcPct), null)));
        int firstDot = mix.toString().indexOf('●');
        int secondDot = firstDot < 0 ? -1 : mix.toString().indexOf('●', firstDot + 1);
        if (firstDot >= 0) {
            mix.setSpan(new ForegroundColorSpan(Style.ACCENT), firstDot, firstDot + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (secondDot >= 0) {
            mix.setSpan(new ForegroundColorSpan(Style.GOOD), secondDot, secondDot + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView mixTv = new TextView(c);
        mixTv.setText(mix);
        mixTv.setTextColor(Style.TEXT);
        mixTv.setTextSize(20f);
        mixTv.setTypeface(null, Typeface.BOLD);
        mixTv.setSingleLine(true);
        t.addView(mixTv);

        return t;
    }

    private void render12vBadge(Aux12vMonitor.Summary s12) {
        if (aux12vBadge == null) return;
        Context c = getContext();
        Double shownV = s12.latestRestingV != null ? s12.latestRestingV : s12.latestReadyV;
        if (shownV == null) {
            aux12vBadge.setText("—");
            aux12vBadge.setTextColor(Style.TEXT_DIM);
            return;
        }
        SpannableStringBuilder text = new SpannableStringBuilder(Style.valueWithUnit(
            String.format(Locale.getDefault(), "%.1f", shownV), null, "V", Style.UNIT_SCALE_ROW));
        text.append(" · ").append(healthSubLabel(c, s12));
        aux12vBadge.setText(text);
        aux12vBadge.setTextColor(s12.health == Aux12vMonitor.Health.GOOD ? Style.GOOD : healthColor(s12.health));
    }

    private View hvTile(Context c, String label, CharSequence value, int valColor) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Style.tile(c));
        int p = Style.dp(c, 12);
        t.setPadding(p, Style.dp(c, 8), p, Style.dp(c, 8));

        TextView l = new TextView(c);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(18f);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(l);

        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(valColor);
        v.setTextSize(22f);
        v.setTypeface(null, Typeface.BOLD);
        v.setSingleLine(true);
        t.addView(v);
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
                return fill(c.getString(R.string.diary_overdue_km, MARK_1),
                    km(Math.max(1L, Math.round(-d.kmRemaining)), null));
            }
            return c.getString(R.string.diary_due_overdue);
        }
        // Which limit comes first, as a share of its interval. The intervals are
        // the ones set in Config (the same the schedule rows use), not the defaults.
        CarDiary.ScheduleConfig cfg = cachedCfg;
        Double kmInt = cfg != null ? cfg.kmInterval(d.category) : d.category.kmInterval;
        Integer dayInt = cfg != null ? cfg.dayInterval(d.category)
            : (d.category.monthInterval != null
                ? (int) Math.round(d.category.monthInterval * 30.4375) : null);
        if (d.kmRemaining != null && (d.daysRemaining == null
                || (kmInt != null && dayInt != null && kmInt > 0 && dayInt > 0
                    && d.kmRemaining / kmInt < d.daysRemaining / (double) dayInt))) {
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
        return Style.TEXT_DIM;
    }

    private View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Style.tileStrokeColor());
        v.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 1)));
        return v;
    }
}
