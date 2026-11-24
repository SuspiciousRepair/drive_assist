package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.ChargePeriodSummary;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.EnergySource;
import com.geely.drivemem.sensors.StatsCalendar;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.CsvExport;
import com.geely.drivemem.util.EnergyUnits;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import android.content.Context;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unified Driving & Charging Statistics view (Tab 2 of the 3-icon dock):
 * 1. Fixed header: Prev / Period label / Next + Day/Week/Month/Year picker + USB CSV Export.
 * 2. Year-of-days calendar heatmap navigator (CalendarNavigatorView).
 * 3. Three calm KPI cards: Driving, Efficiency & Energy, Charging & Energy Cost.
 * 4. Bottom 2-column area:
 *    - Left (52%): Unified Session Timeline with All | Trips | Charges filter pills.
 *    - Right (48%): Contextual Map (trip routes on All/Trips; charging spots map on Charges).
 */
public class DailyStatsView extends LinearLayout {

    private final Runnable onBack;

    private enum Period { DAY, WEEK, MONTH, YEAR }
    private enum SessionFilter { ALL, TRIPS, CHARGES, VALET }

    private Period period = Period.DAY;
    private SessionFilter sessionFilter = SessionFilter.ALL;
    private LocalDate selected;
    private LocalDate firstData;
    private TextView periodDropdownLabel;

    private CalendarNavigatorView navigator;
    private TextView navPrevBtn;
    private TextView navNextBtn;
    private TextView navDateLabel;

    private LinearLayout tripCard;
    private LinearLayout consumptionCard;
    private LinearLayout chargingCard;
    private LinearLayout sessionsFilterBar;
    private LinearLayout sessionsContainer;
    private TextView sessionsSummary;
    private TextView mapHeaderTitle;
    private TextView mapActionPill;
    private RouteView contextMap;
    private TextView mapEmptyState;
    private View dashboardRowView, bottomRowView;
    private ScrollView pageScroll;

    private Loaded currentLoaded;
    private final Map<Long, View> chargeRowViews = new LinkedHashMap<>();
    private final Map<Long, Boolean> chargeRowIsDc = new HashMap<>();
    private long selectedChargeId = -1L;

    private final EntityBus.Listener tickListener = (key, reading) -> post(this::onLiveTick);

    /** A charge ended or its cost was edited: reload calendar and current period. */
    private final EntityBus.Listener chargeListener = (key, reading) -> {
        post(() -> {
            synchronized (PERIOD_CACHE) { PERIOD_CACHE.clear(); }
            if (isShown() && selected != null) refresh();
        });
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EntityBus.subscribe("telemetry.tick", tickListener);
        EntityBus.subscribe("charge.cost_updated", chargeListener);
        EntityBus.subscribe("charge.completed", chargeListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EntityBus.unsubscribe("telemetry.tick", tickListener);
        EntityBus.unsubscribe("charge.cost_updated", chargeListener);
        EntityBus.unsubscribe("charge.completed", chargeListener);
    }

    public DailyStatsView(Context context) {
        this(context, null);
    }

    public DailyStatsView(Context context, Runnable onBack) {
        super(context);
        this.onBack = onBack;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 16);
        setPadding(pad, pad, pad, 0);

        buildSkeleton();
        refresh();
    }

    private void buildSkeleton() {
        Context c = getContext();

        // 1. Fixed header row: Prev / Label / Next + Period picker + USB CSV Export
        LinearLayout navRow = new LinearLayout(c);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        if (onBack != null) {
            navRow.addView(Style.backButton(c, onBack));
            Style.gap(navRow, c, 16);
        }

        LinearLayout dateNav = new LinearLayout(c);
        dateNav.setOrientation(LinearLayout.HORIZONTAL);
        dateNav.setGravity(Gravity.CENTER_VERTICAL);
        dateNav.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        navPrevBtn = Style.cardButton(c, c.getString(R.string.daily_stats_prev_day), false, this::onNavPrev);
        setNavIcon(navPrevBtn, R.drawable.ic_chevron_left, true);

        navDateLabel = new TextView(c);
        navDateLabel.setTextColor(Style.TEXT);
        navDateLabel.setTextSize(18f);
        navDateLabel.setTypeface(null, Typeface.BOLD);
        navDateLabel.setGravity(Gravity.CENTER);
        navDateLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        navNextBtn = Style.cardButton(c, c.getString(R.string.daily_stats_next_day), false, this::onNavNext);
        setNavIcon(navNextBtn, R.drawable.ic_chevron_right, false);

        dateNav.addView(navPrevBtn);
        dateNav.addView(navDateLabel);
        dateNav.addView(navNextBtn);
        navRow.addView(dateNav);

        Style.gap(navRow, c, 14);

        LinearLayout periodDropdownBtn = new LinearLayout(c);
        periodDropdownBtn.setOrientation(LinearLayout.HORIZONTAL);
        periodDropdownBtn.setGravity(Gravity.CENTER_VERTICAL);
        periodDropdownBtn.setBackground(Style.card(Style.CARD, c));
        int ddPad = Style.dp(c, 18);
        periodDropdownBtn.setPadding(ddPad, ddPad, ddPad, ddPad);

        periodDropdownLabel = new TextView(c);
        periodDropdownLabel.setText(periodDisplayName(period));
        periodDropdownLabel.setTextColor(Style.onFill(Style.CARD));
        periodDropdownLabel.setTextSize(18f);
        periodDropdownBtn.addView(periodDropdownLabel);

        ImageView periodChevron = new ImageView(c);
        periodChevron.setImageResource(R.drawable.ic_chevron_right);
        periodChevron.setColorFilter(Style.onFill(Style.CARD), PorterDuff.Mode.SRC_IN);
        periodChevron.setRotation(90f);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(Style.dp(c, 18), Style.dp(c, 18));
        chevLp.leftMargin = Style.dp(c, 8);
        periodChevron.setLayoutParams(chevLp);
        periodDropdownBtn.addView(periodChevron);

        periodDropdownBtn.setOnClickListener(v -> showPeriodMenu(periodDropdownBtn));
        navRow.addView(periodDropdownBtn);

        Style.gap(navRow, c, 12);

        TextView exportBtn = Style.cardButton(c, c.getString(R.string.charge_export), false, () ->
            CsvExport.exportToUsb(c, (ok, drive, copied) -> post(() -> {
                String msg = drive == null ? c.getString(R.string.charge_export_no_drive)
                           : !ok || copied == 0 ? c.getString(R.string.charge_export_nothing)
                           : c.getString(R.string.charge_export_ok, copied);
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
            })));
        navRow.addView(exportBtn);

        addView(navRow);
        Style.gap(this, c, 14);

        // Scrollable content area below the fixed header
        pageScroll = new ScrollView(c);
        pageScroll.setVerticalScrollBarEnabled(false);
        pageScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, Style.dp(c, 32));
        pageScroll.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(pageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 2. Year-of-days calendar heatmap navigator
        navigator = new CalendarNavigatorView(c);
        navigator.setOnPick(this::onPick);
        FrameLayout navCard = new FrameLayout(c);
        navCard.setBackground(Style.card(Style.cardFillColor(), c));
        int navPad = Style.dp(c, 12);
        navCard.setPadding(navPad, navPad, navPad + Style.dp(c, 4), Style.dp(c, 4));
        navCard.addView(navigator, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(navCard, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Style.gap(content, c, 16);

        // 3. Simplified 3-Card KPI Row: Driving | Efficiency & Energy | Charging & Energy Cost
        LinearLayout dashboardRow = new LinearLayout(c);
        dashboardRow.setOrientation(LinearLayout.HORIZONTAL);
        dashboardRow.setBaselineAligned(false);
        dashboardRow.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int cardPad = Style.dp(c, 16);
        int cardGap = Style.dp(c, 14);

        tripCard = newDashboardCard(c, cardPad, cardGap);
        dashboardRow.addView(tripCard);

        consumptionCard = newDashboardCard(c, cardPad, cardGap);
        dashboardRow.addView(consumptionCard);

        chargingCard = newDashboardCard(c, cardPad, 0);
        dashboardRow.addView(chargingCard);

        content.addView(dashboardRow);
        dashboardRowView = dashboardRow;
        Style.gap(content, c, 18);

        // 4. Bottom 2-column area: Unified Session Timeline (52%) | Contextual Map (48%)
        LinearLayout bottomRow = new LinearLayout(c);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.TOP);
        bottomRow.setBaselineAligned(false);
        bottomRow.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Left: Unified Session Timeline card
        LinearLayout sessionsCard = new LinearLayout(c);
        sessionsCard.setOrientation(LinearLayout.VERTICAL);
        sessionsCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        sessionsCard.setBackground(Style.card(Style.cardFillColor(), c));
        sessionsCard.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 52f));

        sessionsSummary = sectionSummary(c);
        sessionsSummary.setTextColor(Style.TEXT_DIM);
        sessionsSummary.setTextSize(15f);
        sessionsSummary.setTypeface(null, Typeface.BOLD);
        sessionsCard.addView(sectionHeading(c, c.getString(R.string.stats_sessions_title), sessionsSummary));

        sessionsFilterBar = new LinearLayout(c);
        sessionsFilterBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = Style.dp(c, 12);
        sessionsCard.addView(sessionsFilterBar, fLp);

        Style.gap(sessionsCard, c, 12);
        sessionsContainer = new LinearLayout(c);
        sessionsContainer.setOrientation(LinearLayout.VERTICAL);
        sessionsCard.addView(sessionsContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Right: Contextual Map card (routes on All/Trips, charging spots on Charges)
        LinearLayout mapCard = new LinearLayout(c);
        mapCard.setOrientation(LinearLayout.VERTICAL);
        mapCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        mapCard.setBackground(Style.card(Style.cardFillColor(), c));
        mapCard.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 48f));

        LinearLayout mapHead = new LinearLayout(c);
        mapHead.setOrientation(LinearLayout.HORIZONTAL);
        mapHead.setGravity(Gravity.CENTER_VERTICAL);
        mapHeaderTitle = new TextView(c);
        mapHeaderTitle.setText(c.getString(R.string.stats_map_routes_title));
        mapHeaderTitle.setTextColor(Style.TEXT);
        mapHeaderTitle.setTextSize(17f);
        mapHeaderTitle.setTypeface(null, Typeface.BOLD);
        mapHead.addView(mapHeaderTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mapActionPill = new TextView(c);
        mapActionPill.setText(c.getString(R.string.routes_all_title));
        mapActionPill.setTextColor(Style.onFill(Style.CARD));
        mapActionPill.setTextSize(14.5f);
        mapActionPill.setTypeface(null, Typeface.BOLD);
        mapActionPill.setBackground(Style.card(Style.CARD, c));
        android.graphics.drawable.Drawable mapIcon = c.getDrawable(R.drawable.ic_map).mutate();
        int iconPx = Style.dp(c, 18);
        mapIcon.setBounds(0, 0, iconPx, iconPx);
        mapIcon.setTint(Style.onFill(Style.CARD));
        mapActionPill.setCompoundDrawables(mapIcon, null, null, null);
        mapActionPill.setCompoundDrawablePadding(Style.dp(c, 8));
        mapActionPill.setGravity(Gravity.CENTER_VERTICAL);
        mapActionPill.setPadding(Style.dp(c, 14), Style.dp(c, 8), Style.dp(c, 14), Style.dp(c, 8));
        mapActionPill.setOnClickListener(v -> openAllRoutes());
        mapHead.addView(mapActionPill);
        mapCard.addView(mapHead);
        Style.gap(mapCard, c, 12);

        FrameLayout mapFrame = new FrameLayout(c);
        contextMap = new RouteView(c);
        contextMap.setOnClickListener(v -> {
            if (sessionFilter != SessionFilter.CHARGES) openAllRoutes();
        });
        mapFrame.addView(contextMap, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mapEmptyState = Style.label(c, c.getString(R.string.stats_map_routes_none));
        mapEmptyState.setTextSize(16f);
        mapEmptyState.setTextColor(Style.TEXT_DIM);
        mapEmptyState.setGravity(Gravity.CENTER);
        mapEmptyState.setBackground(Style.tile(c));
        mapEmptyState.setVisibility(View.GONE);
        mapFrame.addView(mapEmptyState, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mapCard.addView(mapFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 480)));

        Style.addMirrored(bottomRow, 14, sessionsCard, mapCard);
        content.addView(bottomRow);
        bottomRowView = bottomRow;
    }

    /** Reloads the year of days and renders the current selection. */
    public void refresh() {
        final Context c = getContext().getApplicationContext();
        final LocalDate today = LocalDate.parse(DailyStatsProvider.todayDateStr());
        final int gen = ++yearGen;
        LOADER.execute(() -> {
            LocalDate first = DailyStatsProvider.firstRecordedDay(c);
            List<StatsCalendar.Cell> days =
                DailyStatsProvider.getCalendarDays(c, CalendarNavigatorView.gridStartFor(today));
            post(() -> {
                if (gen != yearGen) return;
                firstData = first == null || first.isAfter(today) ? today : first;
                navigator.setData(days, firstData);
                selected = selected == null ? today
                    : StatsCalendar.clamp(selected, navMin(today), today);
                render();
            });
        });
    }

    // ---- loading off the UI thread ----

    private static final String TAG = "DailyStats";
    private static final java.util.concurrent.ExecutorService LOADER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stats-load");
            t.setDaemon(true);
            return t;
        });
    private int loadGen, yearGen;
    private boolean loading;

    private static final class Loaded {
        String date;
        DailyStatsProvider.DayOverview ov;          // Day view
        DailyStatsProvider.PeriodOverview po;       // Week / Month / Year view
        ChargePeriodSummary chargeSummary;
        RouteSet routeSet;                          // period's trip routes for contextual map
        List<ChargeSpots.Spot> routeChargeSpots = Collections.emptyList();
        List<ChargeSpots.Place> chargePlaces = Collections.emptyList();

        DailyStatsProvider.DayOverview totals() { return po != null ? po.totals : ov; }
    }

    private static final Map<String, Loaded> PERIOD_CACHE =
        new LinkedHashMap<String, Loaded>(24, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Loaded> e) {
                return size() > 24;
            }
        };

    private static StatsCalendar.Grain toGrain(Period p) {
        switch (p) {
            case WEEK: return StatsCalendar.Grain.WEEK;
            case MONTH: return StatsCalendar.Grain.MONTH;
            case YEAR: return StatsCalendar.Grain.YEAR;
            default: return StatsCalendar.Grain.DAY;
        }
    }

    private static Loaded loadCached(Context c, Period p, String date) {
        LocalDate d = LocalDate.parse(date);
        LocalDate[] span = StatsCalendar.period(d, toGrain(p));
        boolean past = StatsCalendar.isFrozen(span[1], LocalDate.parse(DailyStatsProvider.todayDateStr()));
        String key = p + ":" + span[0];
        if (past) synchronized (PERIOD_CACHE) {
            Loaded hit = PERIOD_CACHE.get(key);
            if (hit != null) return hit;
        }
        Loaded l = load(c, p, date, span);
        if (past) synchronized (PERIOD_CACHE) { PERIOD_CACHE.put(key, l); }
        return l;
    }

    private void dimCards(boolean dim) {
        float a = dim ? 0.45f : 1f;
        if (dashboardRowView != null) dashboardRowView.setAlpha(a);
        if (bottomRowView != null) bottomRowView.setAlpha(a);
    }

    private static Loaded load(Context c, Period p, String date, LocalDate[] span) {
        Loaded l = new Loaded();
        l.date = date;
        if (p == Period.DAY) {
            l.ov = DailyStatsProvider.getDayOverview(c, date);
        } else if (p == Period.WEEK) {
            l.po = DailyStatsProvider.recentWeeks(c, date, 1).get(0);
        } else if (p == Period.MONTH) {
            l.po = DailyStatsProvider.recentMonths(c, date, 1).get(0);
        } else {
            l.po = DailyStatsProvider.yearOverview(c, date);
        }

        ZoneId zone = ZoneId.systemDefault();
        long fromMs = span[0].atStartOfDay(zone).toInstant().toEpochMilli();
        long toMs = span[1].plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long now = System.currentTimeMillis();

        // Charging period summary + charging spot pins for this period
        List<ChargeSession.Summary> allCharges = ChargeSession.readLog(c);
        l.chargeSummary = ChargePeriodSummary.of(allCharges, fromMs, toMs, now, Collections.emptyList());

        Map<Long, double[]> chargeWhere = new HashMap<>();
        try (Cursor pc = CarDb.get(c).db().rawQuery(
                "SELECT id, lat, lon FROM charge_session WHERE start_ms >= ? AND start_ms < ? "
                + "AND lat IS NOT NULL AND lon IS NOT NULL",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)})) {
            while (pc.moveToNext()) {
                chargeWhere.put(pc.getLong(0), new double[]{pc.getDouble(1), pc.getDouble(2)});
            }
        } catch (Throwable ignored) {}

        List<ChargeSpots.Placed> placed = new ArrayList<>();
        List<ChargeSpots.Charge> rawSpots = new ArrayList<>();
        for (ChargeSession.Summary s : allCharges) {
            if (s.startWallMs < fromMs || s.startWallMs >= toMs) continue;
            double[] w = chargeWhere.get(s.id);
            if (w != null) {
                placed.add(new ChargeSpots.Placed(s.id, w[0], w[1], s.isDcfc()));
                rawSpots.add(new ChargeSpots.Charge(w[0], w[1], s.startWallMs, s.endWallMs, s.isDcfc()));
            }
        }
        l.chargePlaces = ChargeSpots.places(placed);
        l.routeChargeSpots = ChargeSpots.cluster(rawSpots);

        // Trip routes in this period (bounded by period window, capped to recent 80 trips for fast Year draw)
        l.routeSet = loadPeriodRoutes(c, fromMs, toMs);
        return l;
    }

    private static RouteSet loadPeriodRoutes(Context c, long fromMs, long toMs) {
        List<RouteSet.Trip> trips = new ArrayList<>();
        try (Cursor cur = CarDb.get(c).db().rawQuery(
                "SELECT tp.trip_id, tp.lat, tp.lon, COALESCE(tp.speed_kmh, 0) "
                + "FROM trip_point tp JOIN trip t ON tp.trip_id = t.id "
                + "WHERE t.start_ms >= ? AND t.start_ms < ? AND tp.trip_id IS NOT NULL "
                + "ORDER BY tp.trip_id DESC, tp.ts_ms ASC",
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)})) {
            long curId = -1;
            List<double[]> pts = new ArrayList<>();
            while (true) {
                boolean more = cur.moveToNext();
                long id = more ? cur.getLong(0) : -2;
                if (id != curId) {
                    if (pts.size() >= 2) {
                        int n = pts.size();
                        double[] lat = new double[n], lon = new double[n], spd = new double[n], alt = new double[n];
                        for (int i = 0; i < n; i++) {
                            double[] pt = pts.get(i);
                            lat[i] = pt[0]; lon[i] = pt[1]; spd[i] = pt[2]; alt[i] = Double.NaN;
                        }
                        trips.add(new RouteSet.Trip(lat, lon, spd, alt));
                        if (trips.size() >= 80) break;
                    }
                    pts.clear();
                    curId = id;
                }
                if (!more) break;
                pts.add(new double[]{cur.getDouble(1), cur.getDouble(2), cur.getDouble(3)});
            }
        } catch (Throwable ignored) {}
        if (trips.isEmpty()) return null;
        Collections.reverse(trips);
        return RouteSet.of(trips, TripRouteActivity.HEIGHT_SMOOTH);
    }

    private void loadAndShow() {
        final int gen = ++loadGen;
        final Period p = period;
        final String date = selected.toString();
        final Context c = getContext().getApplicationContext();
        loading = true;
        postDelayed(() -> { if (gen == loadGen && loading) dimCards(true); }, 150);
        LOADER.execute(() -> {
            long t0 = android.os.SystemClock.uptimeMillis();
            Loaded l = loadCached(c, p, date);
            long ms = android.os.SystemClock.uptimeMillis() - t0;
            post(() -> {
                if (gen != loadGen) return;
                loading = false;
                dimCards(false);
                currentLoaded = l;
                if (l.po == null && l.date.equals(DailyStatsProvider.todayDateStr())) {
                    navigator.updateToday(l.ov.distanceKm);
                }
                renderLoaded(l);
                android.util.Log.d(TAG, p + " " + date + ": load " + ms + " ms");
            });
        });
    }

    private LocalDate navMin(LocalDate today) {
        LocalDate min = firstData != null ? firstData : today;
        LocalDate gridStart = CalendarNavigatorView.gridStartFor(today);
        return min.isBefore(gridStart) ? gridStart : min;
    }

    private void onLiveTick() {
        if (!isShown() || selected == null) return;
        String todayStr = DailyStatsProvider.todayDateStr();
        LocalDate today = LocalDate.parse(todayStr);
        if (lastLoadedToday != null && !lastLoadedToday.equals(today)) {
            lastLoadedToday = today;
            refresh();
            return;
        }
        lastLoadedToday = today;
        if (period != Period.DAY || !selected.equals(today) || loading) return;
        loadAndShow();
    }

    private LocalDate lastLoadedToday;

    private StatsCalendar.Grain grain() { return toGrain(period); }

    private void render() {
        if (selected == null) return;
        LocalDate today = LocalDate.parse(DailyStatsProvider.todayDateStr());
        lastLoadedToday = today;
        LocalDate min = navMin(today);
        StatsCalendar.Grain g = grain();

        navigator.setSelection(g, selected);
        setNavEnabled(navPrevBtn, StatsCalendar.canStep(selected, g, -1, min, today));
        setNavEnabled(navNextBtn, StatsCalendar.canStep(selected, g, +1, min, today));

        loadAndShow();
    }

    private static void setNavEnabled(TextView b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1.0f : 0.35f);
    }

    private void onNavPrev() { stepBy(-1); }
    private void onNavNext() { stepBy(+1); }

    private void stepBy(int dir) {
        if (selected == null) return;
        LocalDate today = LocalDate.parse(DailyStatsProvider.todayDateStr());
        selected = StatsCalendar.arrowStep(selected, grain(), dir, navMin(today), today);
        render();
    }

    private void onPick(LocalDate d) {
        if (d.equals(selected)) return;
        selected = d;
        render();
    }

    private String periodDisplayName(Period p) {
        Context c = getContext();
        switch (p) {
            case WEEK: return c.getString(R.string.stats_period_week);
            case MONTH: return c.getString(R.string.charge_period_month);
            case YEAR: return c.getString(R.string.charge_period_year);
            default: return c.getString(R.string.stats_period_day);
        }
    }

    private void showPeriodMenu(View anchor) {
        Context c = getContext();
        LinearLayout menu = new LinearLayout(c);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(Style.card(Style.CARD, c));
        int pad = Style.dp(c, 6);
        menu.setPadding(pad, pad, pad, pad);

        android.widget.PopupWindow popup = new android.widget.PopupWindow(menu,
            Style.dp(c, 200), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(Style.dp(c, 8));

        for (Period p : Period.values()) {
            TextView row = new TextView(c);
            row.setText(periodDisplayName(p));
            row.setTextSize(15.5f);
            boolean current = p == period;
            row.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
            row.setTextColor(current ? Style.ACCENT : Style.TEXT);
            int rpad = Style.dp(c, 14);
            row.setPadding(rpad, rpad, rpad, rpad);
            row.setOnClickListener(v -> {
                popup.dismiss();
                setPeriod(p);
            });
            menu.addView(row);
        }

        popup.showAsDropDown(anchor, 0, Style.dp(c, 8));
    }

    private void setPeriod(Period p) {
        if (period == p) return;
        period = p;
        periodDropdownLabel.setText(periodDisplayName(p));
        render();
    }

    // ---- Rendering Loaded Period ----

    private void renderLoaded(Loaded l) {
        DailyStatsProvider.DayOverview totals = l.totals();
        if (l.po != null) {
            navDateLabel.setText(l.po.periodLabel);
        } else if (totals.displayDate != null && !totals.displayDate.isEmpty()) {
            navDateLabel.setText(
                Character.toUpperCase(totals.displayDate.charAt(0)) + totals.displayDate.substring(1));
        } else {
            navDateLabel.setText("");
        }

        renderTripCard(totals);
        renderConsumptionCard(totals);
        renderChargingCard(l.chargeSummary, totals);

        rebuildSessionFilterBar(totals);
        renderFilteredSessions(l);
        renderContextualMap(l);
    }

    // ---- Card 1: Driving ----

    private void renderTripCard(DailyStatsProvider.DayOverview ov) {
        Context c = getContext();
        tripCard.removeAllViews();
        cardTitle(tripCard, c.getString(R.string.stats_card_trip));

        long dMin = Math.round(ov.drivingMinutes);
        tripCard.addView(createHeroView(c, valueWithUnit(kmNumber(ov.distanceKm), null, "km", HERO_UNIT_SCALE),
            c.getString(R.string.stats_distance)));
        Style.gap(tripCard, c, 14);

        double moving = ov.movingSpeedKmh();
        CharSequence velVal = moving > 0
            ? valueWithUnit(String.format(Locale.US, "%.0f", moving), null, "km/h", ROW_UNIT_SCALE)
            : "—";

        int trips = 0;
        for (DailyStatsProvider.DaySession s : ov.sessions) if (s.isTrip() && !s.isValet()) trips++;
        CharSequence tripsVal = trips > 0 ? String.valueOf(trips) : "—";
        CharSequence perTrip = trips > 0
            ? c.getString(R.string.stats_km_per_trip, kmNumber(ov.distanceKm / trips)) : null;

        double net = ov.netElevationM;
        int netColor = net > 0.5 ? Style.HEAT : net < -0.5 ? Style.COOL : Style.TEXT;
        CharSequence netVal = valueWithUnit(String.format(Locale.US, "%s%.0f", net > 0.5 ? "+" : "", net),
            netColor, "m", ROW_UNIT_SCALE);
        SpannableStringBuilder upDown = new SpannableStringBuilder();
        int a = upDown.length();
        upDown.append(String.format(Locale.US, "↑%.0f", ov.ascentDPlusM));
        upDown.setSpan(new ForegroundColorSpan(Style.HEAT), a, upDown.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        upDown.append("  ");
        int d = upDown.length();
        upDown.append(String.format(Locale.US, "↓%.0f", ov.descentDMinusM));
        upDown.setSpan(new ForegroundColorSpan(Style.COOL), d, upDown.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        upDown.append(" m");

        List<View> chips = new ArrayList<>();
        chips.add(metricChip(c, c.getString(R.string.stats_driving_time),
            String.format(Locale.US, "%dh %02dm", dMin / 60, dMin % 60)));
        chips.add(metricChip(c, c.getString(R.string.stats_moving_speed), velVal));
        chips.add(metricChip(c, c.getString(R.string.stats_trips), tripsVal, perTrip));
        chips.add(metricChip(c, c.getString(R.string.stats_relief), netVal, upDown));
        distributeInPairs(c, tripCard, chips, 10);
    }

    // ---- Card 2: Efficiency & Energy (Hero consumption + EnergySankeyView) ----

    private void renderConsumptionCard(DailyStatsProvider.DayOverview ov) {
        Context c = getContext();
        consumptionCard.removeAllViews();
        cardTitle(consumptionCard, c.getString(R.string.stats_card_efficiency));

        CharSequence heroVal = ov.efficiencyKwh100km > 0
            ? withEnergySourceMark(valueWithUnit(EnergyUnits.number(ov.efficiencyKwh100km),
                null, EnergyUnits.WH_PER_KM, HERO_UNIT_SCALE), ov.energySource)
            : "—";
        CharSequence consumptionCaption = withEnergySourceMark(
            c.getString(R.string.route_stat_by_use), ov.energySource);
        if (ov.efficiencyFromCharges) {
            heroVal = valueWithUnit(EnergyUnits.number(ov.chargeBasedKwh100km),
                null, EnergyUnits.WH_PER_KM, HERO_UNIT_SCALE);
            consumptionCaption = c.getString(R.string.stats_eff_from_charges);
        }
        consumptionCard.addView(createHeroView(c, heroVal, consumptionCaption));
        Style.gap(consumptionCard, c, 10);

        // Energy flow Sankey (battery + regen -> used -> drive, climate, other)
        double[] split = DailyStatsProvider.splitTotals(ov.sessions);
        if (split != null) {
            EnergySankeyView sankey = new EnergySankeyView(c);
            sankey.setData(split[4], split[5], split[0], split[1], split[2], split[3]);
            consumptionCard.addView(sankey, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (ov.dischargeKwh > 0 || ov.regenKwh > 0) {
            List<View> chips = new ArrayList<>();
            chips.add(metricChip(c, c.getString(R.string.stats_energy_net),
                valueWithUnit(String.format(Locale.US, "%.1f", ov.netKwh), null, "kWh", ROW_UNIT_SCALE)));
            chips.add(metricChip(c, c.getString(R.string.stats_energy_regen),
                valueWithUnit(String.format(Locale.US, "%.1f", ov.regenKwh), Style.GOOD, "kWh", ROW_UNIT_SCALE)));
            distributeInPairs(c, consumptionCard, chips, 10);
        } else {
            TextView none = Style.label(c, c.getString(R.string.stats_by_use_none));
            none.setTextSize(14f);
            consumptionCard.addView(none);
        }
    }

    // ---- Card 3: Charging & Energy Cost ----

    private void renderChargingCard(ChargePeriodSummary m, DailyStatsProvider.DayOverview ov) {
        Context c = getContext();
        chargingCard.removeAllViews();
        cardTitle(chargingCard, c.getString(R.string.stats_card_charging));

        if (m == null) m = new ChargePeriodSummary();

        CharSequence hero;
        CharSequence cap;
        if (m.anyCost && m.totalCost() > 0) {
            hero = Style.moneyWithUnit(m.totalCost(), null, HERO_UNIT_SCALE);
            if (ov.distanceKm > 0.5) {
                String per100 = c.getString(R.string.charge_card_per_100km,
                    Prefs.formatMoney(m.totalCost() / ov.distanceKm * 100.0));
                cap = per100 + "  ·  " + kwhNum(m.totalKwh()) + " kWh";
            } else {
                cap = c.getString(R.string.charge_card_energy_cap, kwhNum(m.totalKwh()));
            }
        } else if (m.count > 0) {
            hero = valueWithUnit("+" + kwhNum(m.totalKwh()), null, "kWh", HERO_UNIT_SCALE);
            String countStr = m.count == 1 ? c.getString(R.string.stats_charge_one)
                : c.getString(R.string.stats_charge_many, m.count);
            cap = countStr + "  ·  " + c.getString(R.string.charge_card_no_cost);
        } else {
            hero = "—";
            cap = c.getString(R.string.stats_charges_none_period);
        }
        chargingCard.addView(createHeroView(c, hero, cap));
        Style.gap(chargingCard, c, 14);

        double acUnit = m.acPerKwh(), dcUnit = m.dcPerKwh();
        int dcUnitCol = dcUnit >= 0 && acUnit > 0 && dcUnit > 2 * acUnit ? Style.DANGER : Style.TEXT;

        List<View> chips = new ArrayList<>();
        chips.add(metricChip(c, c.getString(R.string.stats_chip_ac_charges), countKwh(m.acCount, m.acKwh, Style.ACCENT)));
        chips.add(metricChip(c, c.getString(R.string.stats_chip_dc_charges), countKwh(m.dcCount, m.dcKwh, Style.GOOD)));
        chips.add(metricChip(c, c.getString(R.string.stats_chip_ac_cost_kwh),
            acUnit >= 0 ? Style.moneyWithUnit(acUnit, null, ROW_UNIT_SCALE) : "—"));
        chips.add(metricChip(c, c.getString(R.string.stats_chip_dc_cost_kwh),
            dcUnit >= 0 ? Style.moneyWithUnit(dcUnit, dcUnitCol, ROW_UNIT_SCALE) : "—"));
        distributeInPairs(c, chargingCard, chips, 10);
    }

    private static String kwhNum(double v) {
        return String.format(Locale.getDefault(), v >= 100 ? "%,.0f" : "%.1f", v);
    }

    private static CharSequence countKwh(int count, double kwh, int color) {
        if (count == 0) return "0";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Style.appendUnit(sb, count + "×  ", ROW_UNIT_SCALE);
        sb.append(Style.valueWithUnit(kwhNum(kwh), color, "kWh", ROW_UNIT_SCALE));
        return sb;
    }

    // ---- Session Filter Pills & Unified Timeline ----

    private void rebuildSessionFilterBar(DailyStatsProvider.DayOverview totals) {
        Context c = getContext();
        int trips = 0, charges = 0, valets = 0;
        for (DailyStatsProvider.DaySession s : totals.sessions) {
            if (s.isValet()) valets++;
            else if (s.isTrip()) trips++;
            else charges++;
        }
        if (sessionFilter == SessionFilter.VALET && valets == 0) {
            sessionFilter = SessionFilter.ALL;
        }
        sessionsSummary.setText(trips + " " + c.getString(trips == 1 ? R.string.stats_unit_trip : R.string.stats_unit_trips)
            + " · " + charges + " " + c.getString(charges == 1 ? R.string.stats_unit_charge : R.string.stats_unit_charges));

        sessionsFilterBar.removeAllViews();
        addSessionFilterPill(c.getString(R.string.stats_filter_all), SessionFilter.ALL);
        addSessionFilterPill(c.getString(R.string.stats_filter_trips) + (trips > 0 ? " (" + trips + ")" : ""), SessionFilter.TRIPS);
        addSessionFilterPill(c.getString(R.string.stats_filter_charges) + (charges > 0 ? " (" + charges + ")" : ""), SessionFilter.CHARGES);
        if (valets > 0) {
            addSessionFilterPill(c.getString(R.string.stats_filter_valet) + " (" + valets + ")", SessionFilter.VALET);
        }
    }

    private void addSessionFilterPill(String label, SessionFilter f) {
        Context c = getContext();
        boolean active = (f == sessionFilter);
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(15.5f);
        b.setTextColor(active ? Style.onFill(Style.ACCENT) : Style.TEXT_DIM);
        b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        b.setBackground(active ? Style.card(Style.ACCENT, c, 20) : Style.tile(c));
        int h = Style.dp(c, 16), v = Style.dp(c, 8);
        b.setPadding(h, v, h, v);
        b.setOnClickListener(view -> {
            if (sessionFilter == f) return;
            sessionFilter = f;
            if (currentLoaded != null) {
                rebuildSessionFilterBar(currentLoaded.totals());
                renderFilteredSessions(currentLoaded);
                renderContextualMap(currentLoaded);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (sessionsFilterBar.getChildCount() > 0) lp.leftMargin = Style.dp(c, 8);
        sessionsFilterBar.addView(b, lp);
    }

    private boolean matchesFilter(DailyStatsProvider.DaySession s) {
        switch (sessionFilter) {
            case TRIPS: return s.isTrip() && !s.isValet();
            case CHARGES: return !s.isTrip() && !s.isValet();
            case VALET: return s.isValet();
            default: return true;
        }
    }

    private void renderFilteredSessions(Loaded l) {
        Context c = getContext();
        sessionsContainer.removeAllViews();
        chargeRowViews.clear();
        chargeRowIsDc.clear();
        sessionsGen++;

        if (l.po == null) {
            List<DailyStatsProvider.DaySession> filtered = new ArrayList<>();
            for (DailyStatsProvider.DaySession s : l.ov.sessions) {
                if (matchesFilter(s)) filtered.add(s);
            }
            if (filtered.isEmpty()) {
                TextView empty = Style.label(c, emptyFilterMessage(c));
                empty.setPadding(0, Style.dp(c, 20), 0, Style.dp(c, 20));
                sessionsContainer.addView(empty);
                return;
            }
            Collections.reverse(filtered);
            long dayStartMs = parseDayMs(l.ov.date);
            for (int i = 0; i < filtered.size(); i++) {
                DailyStatsProvider.DaySession s = filtered.get(i);
                View card = buildSessionCard(s, dayStartMs);
                card.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                sessionsContainer.addView(card);
                if (i < filtered.size() - 1) {
                    Style.gap(sessionsContainer, c, 4);
                    if (sessionFilter == SessionFilter.ALL) {
                        long gapMs = s.startMs - filtered.get(i + 1).endMs;
                        View gapRow = buildGapRow(gapMs);
                        if (gapRow != null) sessionsContainer.addView(gapRow);
                    }
                    Style.gap(sessionsContainer, c, 4);
                }
            }
            return;
        }

        // Multi-day period (Week / Month / Year)
        List<DailyStatsProvider.DayOverview> orderedDays = new ArrayList<>(l.po.days);
        Collections.reverse(orderedDays);
        List<java.util.function.Supplier<View>> items = new ArrayList<>();
        boolean anyRendered = false;

        for (DailyStatsProvider.DayOverview day : orderedDays) {
            List<DailyStatsProvider.DaySession> dayFiltered = new ArrayList<>();
            for (DailyStatsProvider.DaySession s : day.sessions) {
                if (matchesFilter(s)) dayFiltered.add(s);
            }
            if (dayFiltered.isEmpty()) continue;

            anyRendered = true;
            items.add(() -> periodDayHeader(day));
            Collections.reverse(dayFiltered);
            long dayStartMs = parseDayMs(day.date);
            for (DailyStatsProvider.DaySession s : dayFiltered) {
                items.add(() -> {
                    View card = buildSessionCard(s, dayStartMs);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = Style.dp(c, 6);
                    card.setLayoutParams(lp);
                    return card;
                });
            }
            items.add(() -> {
                View gap = new View(c);
                gap.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 10)));
                return gap;
            });
        }
        addChunked(items);

        if (!anyRendered) {
            TextView empty = Style.label(c, emptyFilterMessage(c));
            empty.setPadding(0, Style.dp(c, 20), 0, Style.dp(c, 20));
            sessionsContainer.addView(empty);
        }
    }

    private String emptyFilterMessage(Context c) {
        if (sessionFilter == SessionFilter.CHARGES) return c.getString(R.string.charge_none);
        if (sessionFilter == SessionFilter.TRIPS) return c.getString(R.string.stats_trips_none);
        return c.getString(R.string.stats_sessions_none);
    }

    // ---- Contextual Map (Routes on All/Trips, Charging Spots on Charges) ----

    private void renderContextualMap(Loaded l) {
        Context c = getContext();
        if (sessionFilter == SessionFilter.CHARGES) {
            mapHeaderTitle.setText(c.getString(R.string.stats_map_charges_title));
            mapActionPill.setVisibility(View.GONE);
            contextMap.clearRoutes();
            contextMap.setMinSpanM(3000);
            contextMap.setOnSpotPick(idx -> {
                if (idx < 0 || idx >= l.chargePlaces.size()) return;
                List<Long> ids = l.chargePlaces.get(idx).ids;
                if (!ids.isEmpty()) {
                    long id = ids.get(ids.size() - 1);
                    selectChargeRow(id, l.chargePlaces);
                }
            });
            contextMap.setChargePlaces(l.chargePlaces);
            if (l.chargePlaces.isEmpty()) {
                contextMap.setVisibility(View.INVISIBLE);
                mapEmptyState.setText(c.getString(R.string.charge_map_none));
                mapEmptyState.setVisibility(View.VISIBLE);
            } else {
                mapEmptyState.setVisibility(View.GONE);
                contextMap.setVisibility(View.VISIBLE);
                if (selectedChargeId > 0) {
                    contextMap.setSelectedSpot(ChargeSpots.placeOf(l.chargePlaces, selectedChargeId));
                }
            }
        } else {
            mapHeaderTitle.setText(c.getString(R.string.stats_map_routes_title));
            mapActionPill.setVisibility(View.VISIBLE);
            contextMap.setOnSpotPick(null);
            contextMap.setMinSpanM(200);
            if (l.routeSet == null || l.routeSet.lat.length == 0) {
                contextMap.clearRoutes();
                contextMap.clearSpots();
                contextMap.setVisibility(View.INVISIBLE);
                mapEmptyState.setText(c.getString(R.string.stats_map_routes_none));
                mapEmptyState.setVisibility(View.VISIBLE);
            } else {
                mapEmptyState.setVisibility(View.GONE);
                contextMap.setVisibility(View.VISIBLE);
                contextMap.setChargeSpots(l.routeChargeSpots);
                contextMap.setRoutes(l.routeSet.lat, l.routeSet.lon, l.routeSet.speed, l.routeSet.starts);
            }
        }
    }

    private void selectChargeRow(long id, List<ChargeSpots.Place> places) {
        selectedChargeId = id;
        Context c = getContext();
        for (Map.Entry<Long, View> e : chargeRowViews.entrySet()) {
            boolean isDc = Boolean.TRUE.equals(chargeRowIsDc.get(e.getKey()));
            GradientDrawable bg = Style.tile(c);
            if (e.getKey() == id) {
                bg.setStroke(Style.dp(c, 2), isDc ? Style.GOOD : Style.ACCENT);
            }
            e.getValue().setBackground(bg);
        }
        contextMap.setSelectedSpot(ChargeSpots.placeOf(places, id));
        View row = chargeRowViews.get(id);
        if (row != null && pageScroll != null) {
            int[] loc = new int[2];
            row.getLocationInWindow(loc);
            int[] scLoc = new int[2];
            pageScroll.getLocationInWindow(scLoc);
            int targetY = pageScroll.getScrollY() + loc[1] - scLoc[1] - Style.dp(c, 120);
            pageScroll.smoothScrollTo(0, Math.max(0, targetY));
        }
    }

    private int sessionsGen;

    private void addChunked(List<java.util.function.Supplier<View>> items) {
        addChunk(items, 0, ++sessionsGen);
    }

    private void addChunk(List<java.util.function.Supplier<View>> items, int from, int gen) {
        if (gen != sessionsGen) return;
        int end = Math.min(items.size(), from + 12);
        for (int i = from; i < end; i++) sessionsContainer.addView(items.get(i).get());
        if (end < items.size()) post(() -> addChunk(items, end, gen));
    }

    private View periodDayHeader(DailyStatsProvider.DayOverview day) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Style.dp(c, 10);
        row.setLayoutParams(rlp);

        TextView label = new TextView(c);
        String d = day.displayDate != null && !day.displayDate.isEmpty()
            ? Character.toUpperCase(day.displayDate.charAt(0)) + day.displayDate.substring(1)
            : day.date;
        label.setText(d);
        label.setTextColor(Style.TEXT_DIM);
        label.setTextSize(14f);
        label.setTypeface(null, Typeface.BOLD);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (day.distanceKm > 0.05) {
            TextView km = new TextView(c);
            km.setText(String.format(Locale.getDefault(), "%.1f km", day.distanceKm));
            km.setTextColor(Style.TEXT_DIM);
            km.setTextSize(14f);
            row.addView(km);
        }

        View divider = sessionDivider(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 1));
        dlp.topMargin = Style.dp(c, 6);

        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(rlp);
        wrap.addView(row);
        wrap.addView(divider, dlp);
        return wrap;
    }

    private static long parseDayMs(String isoDate) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate).getTime();
        } catch (Exception e) { return -1; }
    }

    private static String kmNumber(double km) {
        return km >= 100 ? String.format(Locale.getDefault(), "%,.0f", km)
                         : String.format(Locale.getDefault(), "%.1f", km);
    }

    private void openAllRoutes() {
        android.content.Intent i = new android.content.Intent(getContext(), AllRoutesActivity.class);
        if (period == Period.DAY && selected != null) {
            i.putExtra(AllRoutesActivity.EXTRA_DAY, selected.toString());
        }
        getContext().startActivity(i);
    }

    private TextView sectionSummary(Context c) {
        TextView item = new TextView(c);
        item.setTextColor(Style.TEXT_DIM);
        item.setTextSize(13.5f);
        return item;
    }

    private View sectionHeading(Context c, String title, TextView summary) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(c);
        heading.setText(title);
        heading.setTextColor(Style.TEXT);
        heading.setTextSize(17f);
        heading.setTypeface(null, Typeface.BOLD);
        row.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (summary != null) {
            summary.setPadding(Style.dp(c, 12), 0, 0, 0);
            row.addView(summary);
        }
        return row;
    }

    private static LinearLayout newDashboardCard(Context c, int pad, int rightGap) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Style.card(Style.cardFillColor(), c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.rightMargin = rightGap;
        card.setLayoutParams(lp);
        return card;
    }

    private void cardTitle(LinearLayout card, CharSequence text) {
        Context c = getContext();
        TextView title = new TextView(c);
        title.setText(text);
        title.setTextColor(Style.TEXT_DIM);
        title.setTextSize(15.5f);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        Style.gap(card, c, 8);
    }

    private static final float HERO_UNIT_SCALE = Style.UNIT_SCALE_HERO;
    private static final float ROW_UNIT_SCALE = Style.UNIT_SCALE_ROW;

    private View createHeroView(Context c, CharSequence value, CharSequence subCaption) {
        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(LinearLayout.VERTICAL);

        TextView valTv = new TextView(c);
        valTv.setText(value);
        valTv.setTextColor(Style.TEXT);
        valTv.setTextSize(36);
        valTv.setTypeface(null, Typeface.BOLD);
        hero.addView(valTv);

        TextView capTv = new TextView(c);
        capTv.setText(subCaption);
        capTv.setTextColor(Style.TEXT_DIM);
        capTv.setTextSize(14f);
        capTv.setPadding(0, Style.dp(c, 2), 0, 0);
        hero.addView(capTv);

        return hero;
    }

    private View metricChip(Context c, String label, CharSequence value) {
        return metricChip(c, label, value, null);
    }

    private View metricChip(Context c, String label, CharSequence value, CharSequence sub) {
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.VERTICAL);
        int padH = Style.dp(c, 10), padV = Style.dp(c, 8);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackground(Style.tile(c));

        TextView labelTv = new TextView(c);
        labelTv.setText(label);
        labelTv.setTextColor(Style.TEXT_DIM);
        labelTv.setTextSize(14f);
        chip.addView(labelTv);

        TextView valueTv = new TextView(c);
        valueTv.setTextColor(Style.TEXT);
        valueTv.setTextSize(19);
        valueTv.setTypeface(null, Typeface.BOLD);
        valueTv.setText(value);
        valueTv.setPadding(0, Style.dp(c, 2), 0, 0);
        chip.addView(valueTv);

        if (sub != null) {
            TextView subTv = new TextView(c);
            subTv.setText(sub);
            subTv.setTextColor(Style.TEXT_DIM);
            subTv.setTextSize(14f);
            chip.addView(subTv);
        }
        return chip;
    }

    private void setNavIcon(TextView btn, int drawableRes, boolean onLeft) {
        Context c = btn.getContext();
        android.graphics.drawable.Drawable icon = c.getDrawable(drawableRes).mutate();
        int size = Style.dp(c, 18);
        icon.setBounds(0, 0, size, size);
        icon.setTint(btn.getCurrentTextColor());
        btn.setCompoundDrawables(onLeft ? icon : null, null, onLeft ? null : icon, null);
        btn.setCompoundDrawablePadding(Style.dp(c, 8));
    }

    private static void distributeInPairs(Context c, LinearLayout container, List<View> items, int gapDp) {
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Style.dp(c, gapDp);
                row.setLayoutParams(rlp);
                container.addView(row);
            }
            View v = items.get(i);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % 2 == 0) vlp.rightMargin = Style.dp(c, gapDp);
            v.setLayoutParams(vlp);
            row.addView(v);

            boolean rowEnd = (i % 2 == 1) || (i == items.size() - 1);
            if (rowEnd && row.getChildCount() == 1) {
                View spacer = new View(c);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
                row.addView(spacer);
            }
        }
    }

    static CharSequence withEnergySourceMark(CharSequence base, EnergySource source) {
        if (source != EnergySource.ESTIMATED && source != EnergySource.MIXED) return base;
        SpannableStringBuilder sb = new SpannableStringBuilder(base);
        sb.append(' ');
        Style.appendUnit(sb, "~", 0.85f);
        return sb;
    }

    private static CharSequence valueWithUnit(String number, Integer numberColor, String unit, float unitScale) {
        return Style.valueWithUnit(number, numberColor, unit, unitScale);
    }

    private static CharSequence percentRange(int from, int to, float unitScale) {
        return Style.percentRange(from, to, unitScale);
    }

    private static void appendUnit(SpannableStringBuilder sb, String text, float scale) {
        Style.appendUnit(sb, text, scale);
    }

    private View buildGapRow(long gapMs) {
        if (gapMs <= 0) return null;
        Context c = getContext();

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = Style.dp(c, 12);
        row.setPadding(pad, Style.dp(c, 2), pad, Style.dp(c, 2));

        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_parking);
        icon.setColorFilter(Style.TEXT_DIM, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Style.dp(c, 16), Style.dp(c, 16));
        ilp.rightMargin = Style.dp(c, 8);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        TextView tv = new TextView(c);
        tv.setTextColor(Style.TEXT_DIM);
        tv.setTextSize(16f);
        tv.setText(c.getString(R.string.session_parked, SessionText.duration(gapMs)));
        row.addView(tv);
        return row;
    }

    private View sessionDivider(Context c) {
        View divider = new View(c);
        divider.setBackgroundColor(Style.blend(Style.cardFillColor(), Style.TEXT_DIM, 0.18f));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 1)));
        return divider;
    }

    // ---- Session cards ----

    private static final int STAT_COLUMNS = 4;

    private View buildSessionCard(DailyStatsProvider.DaySession session, long dayStartMs) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 12);
        card.setPadding(pad, pad, pad, pad);

        boolean live = session.endMs <= 0;
        int color;
        if (session.isValet()) {
            color = Style.PURPLE;
            buildValetSessionCard(card, (DailyStatsProvider.ValetSessionItem) session, dayStartMs);
        } else if (session.isTrip()) {
            color = Style.TEXT_DIM;
            buildDriveSessionCard(card, (DailyStatsProvider.DriveSession) session, dayStartMs);
        } else {
            DailyStatsProvider.ChargeSessionItem ch = (DailyStatsProvider.ChargeSessionItem) session;
            color = ch.isDcfc ? Style.GOOD : Style.ACCENT;
            buildChargeSessionCard(card, ch, dayStartMs);
            if (ch.chargeId > 0) {
                chargeRowViews.put(ch.chargeId, card);
                chargeRowIsDc.put(ch.chargeId, ch.isDcfc);
            }
        }

        GradientDrawable bg = Style.tile(c);
        if (live) {
            bg.setStroke(Style.dp(c, 1), color);
        } else if (!session.isTrip() && !session.isValet()) {
            DailyStatsProvider.ChargeSessionItem ch = (DailyStatsProvider.ChargeSessionItem) session;
            if (ch.chargeId > 0 && ch.chargeId == selectedChargeId) {
                bg.setStroke(Style.dp(c, 2), color);
            }
        }
        card.setBackground(bg);
        return card;
    }

    private LinearLayout titleRow(int iconRes, int iconColor, DailyStatsProvider.DaySession s,
                                  long dayStartMs, CharSequence hero) {
        Context c = getContext();
        LinearLayout line1 = new LinearLayout(c);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        ImageView badge = new ImageView(c);
        badge.setImageResource(iconRes);
        badge.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        line1.addView(badge, new LinearLayout.LayoutParams(Style.dp(c, 20), Style.dp(c, 20)));

        boolean live = s.endMs <= 0;
        String yesterday = c.getString(R.string.session_yesterday);
        String range = live
            ? c.getString(R.string.session_since, SessionText.startLabel(s.startMs, dayStartMs, yesterday))
            : SessionText.range(s.startMs, s.endMs, dayStartMs, yesterday);
        long durMs = (live ? System.currentTimeMillis() : s.endMs) - s.startMs;
        SpannableStringBuilder time = new SpannableStringBuilder(range);
        appendUnit(time, "  ·  " + SessionText.duration(durMs), 0.92f);

        TextView timeView = new TextView(c);
        timeView.setTextColor(Style.TEXT);
        timeView.setTextSize(17f);
        timeView.setTypeface(null, Typeface.BOLD);
        timeView.setText(time);
        timeView.setPadding(Style.dp(c, 10), 0, 0, 0);
        line1.addView(timeView);

        line1.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView heroView = new TextView(c);
        heroView.setTextColor(Style.TEXT);
        heroView.setTextSize(18.5f);
        heroView.setTypeface(null, Typeface.BOLD);
        heroView.setText(hero);
        line1.addView(heroView);
        return line1;
    }

    private LinearLayout statsRow(View... stats) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setPadding(Style.dp(c, 30), Style.dp(c, 10), 0, 0);
        for (int i = 0; i < STAT_COLUMNS; i++) {
            View v = i < stats.length && stats[i] != null ? stats[i] : new View(c);
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private View stat(int labelRes, CharSequence value, CharSequence note) {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, Style.dp(c, 12), 0);

        TextView label = new TextView(c);
        label.setText(labelRes);
        label.setTextColor(Style.TEXT_DIM);
        label.setTextSize(15f);
        col.addView(label);

        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(Style.TEXT);
        v.setTextSize(18f);
        v.setTypeface(null, Typeface.BOLD);
        col.addView(v);

        if (note != null) {
            TextView n = new TextView(c);
            n.setText(note);
            n.setTextColor(Style.TEXT_DIM);
            n.setTextSize(15f);
            col.addView(n);
        }
        return col;
    }

    private static CharSequence socValue(int from, int to) {
        if (from >= 0 && to >= 0) return percentRange(from, to, ROW_UNIT_SCALE);
        if (from >= 0) return valueWithUnit(String.valueOf(from), null, "%", ROW_UNIT_SCALE);
        return "—";
    }

    private static CharSequence number(double v, String unit) {
        return valueWithUnit(String.format(Locale.US, "%.1f", v), null, unit, ROW_UNIT_SCALE);
    }

    private void buildValetSessionCard(LinearLayout card, DailyStatsProvider.ValetSessionItem v, long dayStartMs) {
        card.addView(titleRow(R.drawable.ic_shield_car, Style.PURPLE, v, dayStartMs, number(v.distanceKm, "km")));
        card.addView(statsRow(
            stat(R.string.session_stat_battery, socValue(v.startSoc, v.endSoc), null),
            stat(R.string.session_stat_max_speed, valueWithUnit(
                String.format(Locale.US, "%.0f", v.maxSpeedKmh), null, "km/h", ROW_UNIT_SCALE), null),
            stat(R.string.session_stat_max_power, v.maxPowerKw == null ? "—" : number(v.maxPowerKw, "kW"), null)));
        card.setOnClickListener(x -> getContext().startActivity(ValetDetailActivity.intent(getContext(), v)));
    }

    private void buildDriveSessionCard(LinearLayout card, DailyStatsProvider.DriveSession t, long dayStartMs) {
        Context c = getContext();
        card.addView(titleRow(R.drawable.ic_car, Style.TEXT_DIM, t, dayStartMs, number(t.distanceKm, "km")));

        CharSequence eff = t.efficiencyKwh100km > 0
            ? valueWithUnit(EnergyUnits.number(t.efficiencyKwh100km), null, EnergyUnits.WH_PER_KM, ROW_UNIT_SCALE)
            : "—";
        CharSequence splitNote = null;
        if (t.split != null && t.distanceKm > 0.2) {
            splitNote = c.getString(R.string.session_stat_split_note,
                EnergyUnits.wholeWh(EnergyUnits.whPerKm(t.split.driveKwh, t.distanceKm)),
                EnergyUnits.wholeWh(EnergyUnits.whPerKm(t.split.climateKwh, t.distanceKm)),
                EnergyUnits.wholeWh(EnergyUnits.whPerKm(t.split.otherKwh, t.distanceKm)));
        }

        boolean hasEnergy = t.spentKwh > 0 || t.regenKwh > 0 || t.energySource == EnergySource.ESTIMATED;
        CharSequence net;
        CharSequence breakdown = null;
        if (hasEnergy) {
            SpannableStringBuilder sb = new SpannableStringBuilder(number(t.energyKwh, "kWh"));
            if (t.energySource == EnergySource.ESTIMATED || t.energySource == EnergySource.MIXED) {
                sb.append(' ');
                appendUnit(sb, "~", 0.85f);
            }
            net = sb;
            if (t.energySource != EnergySource.ESTIMATED && t.regenKwh > 0) {
                breakdown = c.getString(R.string.session_stat_net_breakdown,
                    String.format(Locale.US, "%.1f", t.spentKwh),
                    String.format(Locale.US, "%.1f", t.regenKwh));
            }
        } else {
            net = t.distanceKm > 0 ? number(0, "kWh") : "—";
        }

        SpannableStringBuilder alt = new SpannableStringBuilder();
        int a = alt.length();
        alt.append(String.format(Locale.US, "↑%.0f", t.ascentDPlusM));
        alt.setSpan(new ForegroundColorSpan(Style.HEAT), a, alt.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        alt.append("  ");
        int d = alt.length();
        alt.append(String.format(Locale.US, "↓%.0f", t.descentDMinusM));
        alt.setSpan(new ForegroundColorSpan(Style.COOL), d, alt.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        appendUnit(alt, " m", ROW_UNIT_SCALE);

        card.addView(statsRow(
            stat(R.string.session_stat_battery, socValue(t.socStart, t.socEnd), null),
            stat(R.string.session_stat_net_energy, net, breakdown),
            stat(R.string.session_stat_efficiency, eff, splitNote),
            stat(R.string.session_stat_elevation, alt, null)));

        if (t.endMs > 0 && t.tripId > 0) {
            card.setOnClickListener(v -> getContext().startActivity(TripRouteActivity.intent(getContext(), t)));
        }
    }

    private void buildChargeSessionCard(LinearLayout card, DailyStatsProvider.ChargeSessionItem ch, long dayStartMs) {
        Context c = getContext();
        boolean live = ch.endMs <= 0;
        CharSequence eta = live ? chargeEta(ch) : null;
        Runnable onOpen = live
            ? () -> c.startActivity(ChargeDetailActivity.live(c))
            : ch.chargeId > 0
                ? () -> {
                    if (currentLoaded != null && sessionFilter == SessionFilter.CHARGES) {
                        selectChargeRow(ch.chargeId, currentLoaded.chargePlaces);
                    }
                    c.startActivity(ChargeDetailActivity.intent(c, ch.chargeId));
                }
                : null;
        Runnable onCost = (!live && ch.chargeId > 0)
            ? () -> ChargeCostDialog.show(c, ch.chargeId, ch.kwh, ch.socStart, ch.socEnd,
                ch.cost != null && ch.cost >= 0 ? ch.cost : null, this::refresh)
            : null;
        ChargeEventCard.populateDaily(card, ch, dayStartMs, eta, onOpen, onCost);
    }

    private CharSequence chargeEta(DailyStatsProvider.ChargeSessionItem ch) {
        Context c = getContext();
        if (ch.socEnd >= 100) return c.getString(R.string.charge_card_ready);
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(0, now - ch.startMs);
        Long remainingMs = ChargeSession.estimateRemainingMs(ch.socEnd, elapsedMs);
        if (remainingMs == null) return c.getString(R.string.charge_card_calculating);
        return SessionText.clock(now + remainingMs);
    }
}
