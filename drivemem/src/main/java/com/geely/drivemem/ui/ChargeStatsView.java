package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.BatteryHeat;
import com.geely.drivemem.sensors.ChargePeriodSummary;
import com.geely.drivemem.sensors.SocHistory;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.CsvExport;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Comprehensive charging history & statistics screen with cost tracking and period rollups. */
public class ChargeStatsView extends LinearLayout {

    private final Runnable onBack;
    // Month or year, and one day inside it: the stats page's picker
    // (owner, 2026-11-18). Calendar periods, so "October" can be asked.
    private enum Grain { MONTH, YEAR }
    private Grain grain = Grain.MONTH;
    private java.time.LocalDate anchor = java.time.LocalDate.now();
    private java.time.LocalDate firstData;

    private LinearLayout headerContainer;
    private LinearLayout kpiContainer;
    private TextView rangeLabel;
    private TextView prevBtn, nextBtn, grainLabel;
    private int loadGen;
    private LinearLayout listContainer;
    private ScrollView sessionListScroll;
    // Where the period's charges took place: one pin per place, with its count.
    private RouteView chargeMap;
    private TextView chargeMapNone;
    private List<ChargeSpots.Place> places = new ArrayList<>();
    private long selectedSessionId = -1;
    private LinearLayout overview;
    /** The list's least height; it grows to the bottom of the screen. */
    private static final int MIN_LIST_DP = 340;
    private final Map<Long, ChargeSession.Summary> visibleSessions = new LinkedHashMap<>();
    private final Map<Long, View> sessionRows = new LinkedHashMap<>();

    private final EntityBus.Listener chargeListener = (key, reading) -> {
        post(this::refresh);
    };

    public ChargeStatsView(Context context) {
        this(context, null);
    }

    public ChargeStatsView(Context context, Runnable onBack) {
        super(context);
        this.onBack = onBack;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 16);
        setPadding(pad, pad, pad, Style.dp(context, 32));

        buildSkeleton();
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EntityBus.subscribe("charge.completed", chargeListener);
        EntityBus.subscribe("charge.cost_updated", chargeListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EntityBus.unsubscribe("charge.completed", chargeListener);
        EntityBus.unsubscribe("charge.cost_updated", chargeListener);
    }

    private void buildSkeleton() {
        Context c = getContext();

        // 1. Header row: the period, what it covers, and export. No page
        // title, same as the stats page (owner's mock, 2026-11-18).
        headerContainer = new LinearLayout(c);
        headerContainer.setOrientation(LinearLayout.HORIZONTAL);
        headerContainer.setGravity(Gravity.CENTER_VERTICAL);
        View backBtn = onBack != null ? Style.backButton(c, onBack) : null;

        LinearLayout dateNav = new LinearLayout(c);
        dateNav.setOrientation(LinearLayout.HORIZONTAL);
        dateNav.setGravity(Gravity.CENTER_VERTICAL);
        dateNav.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        prevBtn = Style.cardButton(c, c.getString(R.string.daily_stats_prev_day), false, () -> step(-1));
        setNavIcon(prevBtn, R.drawable.ic_chevron_left, true);
        dateNav.addView(prevBtn);
        rangeLabel = new TextView(c);
        rangeLabel.setTextColor(Style.TEXT);
        rangeLabel.setTextSize(20);
        rangeLabel.setTypeface(null, Typeface.BOLD);
        rangeLabel.setGravity(Gravity.CENTER);
        dateNav.addView(rangeLabel, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nextBtn = Style.cardButton(c, c.getString(R.string.daily_stats_next_day), false, () -> step(+1));
        setNavIcon(nextBtn, R.drawable.ic_chevron_right, false);
        dateNav.addView(nextBtn);

        View grainBtn = grainButton(c);
        TextView exportBtn = Style.cardButton(c, c.getString(R.string.charge_export), false, () ->
            CsvExport.exportToUsb(c, (ok, drive, copied) -> post(() -> {
                String msg = drive == null ? c.getString(R.string.charge_export_no_drive)
                           : !ok || copied == 0 ? c.getString(R.string.charge_export_nothing)
                           : c.getString(R.string.charge_export_ok, copied);
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
            })));
        Style.addMirrored(headerContainer, 14, backBtn, dateNav, grainBtn, exportBtn);
        addView(headerContainer);

        // 2. KPI Summary Container
        kpiContainer = new LinearLayout(c);
        kpiContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams kpiLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kpiLp.topMargin = Style.dp(c, 12);
        kpiContainer.setLayoutParams(kpiLp);
        addView(kpiContainer);

        // 3. The charges (the home screen's charging card, one per charge) and
        // the map of where they happened. The battery-bar chart that sat
        // between them went (2026-11-23): each card already says its battery
        // levels, and the list needed the room.
        overview = new LinearLayout(c);
        overview.setOrientation(LinearLayout.HORIZONTAL);
        overview.setBaselineAligned(false);
        LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        overviewLp.topMargin = Style.dp(c, 12);
        overview.setLayoutParams(overviewLp);

        // No frame and no title around the list: the cards are the list,
        // as on the home screen.
        LinearLayout listCard = new LinearLayout(c);
        listCard.setOrientation(LinearLayout.VERTICAL);

        sessionListScroll = new ScrollView(c);
        sessionListScroll.setVerticalScrollBarEnabled(false);
        sessionListScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listContainer = new LinearLayout(c);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sessionListScroll.addView(listContainer);
        // The list scrolls inside its own box, which reaches the bottom of
        // the screen (fitList); the map beside it takes the same height.
        listCard.addView(sessionListScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, MIN_LIST_DP)));
        listCard.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 40f));   // about the home card's width

        // Where we charged: the period's charge places on the map, the
        // number of charges in each pin. Touch a pin: its newest charge is
        // selected in the list. The map is the card itself:
        // no frame, no title (owner, 2026-11-18). RouteView rounds its own
        // corners like a card, and it takes the height of the cards beside it.
        android.widget.FrameLayout mapFrame = new android.widget.FrameLayout(c);
        chargeMap = new RouteView(c);
        chargeMap.setMinSpanM(3000);
        chargeMap.setOnSpotPick(i -> {
            if (i < 0 || i >= places.size()) return;
            List<Long> ids = places.get(i).ids;
            long id = ids.get(ids.size() - 1);   // the newest at that place
            selectSession(id, true);
        });
        mapFrame.addView(chargeMap, new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // No place saved in the period: a plain card with the note instead.
        chargeMapNone = Style.label(c, c.getString(R.string.charge_map_none));
        chargeMapNone.setTextSize(16);
        chargeMapNone.setTextColor(Style.TEXT_DIM);
        chargeMapNone.setGravity(Gravity.CENTER);
        chargeMapNone.setBackground(Style.card(Style.cardFillColor(), c));
        chargeMapNone.setVisibility(View.GONE);
        mapFrame.addView(chargeMapNone, new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapFrame.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 60f));
        Style.addMirrored(overview, 12, listCard, mapFrame);
        addView(overview);
    }

    // ---- period ----

    private java.time.LocalDate periodStart(java.time.LocalDate d) {
        return grain == Grain.MONTH ? d.withDayOfMonth(1) : d.withDayOfYear(1);
    }

    private java.time.LocalDate periodEnd(java.time.LocalDate d) {   // exclusive
        return grain == Grain.MONTH ? periodStart(d).plusMonths(1) : periodStart(d).plusYears(1);
    }

    private java.time.LocalDate stepped(java.time.LocalDate d, int dir) {
        return grain == Grain.MONTH ? d.plusMonths(dir) : d.plusYears(dir);
    }

    /** The stepped period still holds data: it ends after the first record
     * and starts no later than today. */
    private boolean canStep(int dir) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate d = stepped(anchor, dir);
        java.time.LocalDate min = firstData != null ? firstData : today;
        return periodEnd(d).isAfter(min) && !periodStart(d).isAfter(today);
    }

    private void step(int dir) {
        if (!canStep(dir)) return;
        anchor = stepped(anchor, dir);
        refresh();
    }

    /** "Mês ▾" / "Ano ▾", the stats page's menu. */
    private View grainButton(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Style.card(Style.CARD, c));
        b.setPadding(Style.dp(c, 18), Style.dp(c, 14), Style.dp(c, 18), Style.dp(c, 14));
        grainLabel = new TextView(c);
        grainLabel.setText(grainName(grain));
        grainLabel.setTextColor(Style.onFill(Style.CARD));
        grainLabel.setTextSize(15.5f);
        grainLabel.setTypeface(null, Typeface.BOLD);
        b.addView(grainLabel);
        ImageView chev = new ImageView(c);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(Style.onFill(Style.CARD), android.graphics.PorterDuff.Mode.SRC_IN);
        chev.setRotation(90f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Style.dp(c, 18), Style.dp(c, 18));
        clp.leftMargin = Style.dp(c, 8);
        b.addView(chev, clp);
        b.setOnClickListener(v -> showGrainMenu(b));
        return b;
    }

    private String grainName(Grain g) {
        return getContext().getString(g == Grain.MONTH ? R.string.charge_period_month : R.string.charge_period_year);
    }

    private void showGrainMenu(View anchorView) {
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
        for (Grain g : Grain.values()) {
            TextView row = new TextView(c);
            row.setText(grainName(g));
            row.setTextSize(15.5f);
            row.setTypeface(null, g == grain ? Typeface.BOLD : Typeface.NORMAL);
            row.setTextColor(g == grain ? Style.ACCENT : Style.TEXT);
            int rp = Style.dp(c, 14);
            row.setPadding(rp, rp, rp, rp);
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (grain == g) return;
                grain = g;
                grainLabel.setText(grainName(g));
                refresh();
            });
            menu.addView(row);
        }
        popup.showAsDropDown(anchorView, 0, Style.dp(c, 8));
    }

    private static void setNavIcon(TextView btn, int drawableRes, boolean onLeft) {
        Context c = btn.getContext();
        android.graphics.drawable.Drawable icon = c.getDrawable(drawableRes).mutate();
        int size = Style.dp(c, 18);
        icon.setBounds(0, 0, size, size);
        icon.setTint(btn.getCurrentTextColor());
        btn.setCompoundDrawables(onLeft ? icon : null, null, onLeft ? null : icon, null);
        btn.setCompoundDrawablePadding(Style.dp(c, 8));
    }

    private static void setNavEnabled(TextView b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.35f);
    }

    // ---- loading (off the UI thread: a year of samples for the heat card) ----

    private static final java.util.concurrent.ExecutorService LOADER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "charge-load");
            t.setDaemon(true);
            return t;
        });

    private static final class Loaded {
        java.time.LocalDate firstData;
        List<ChargeSession.Summary> period = new ArrayList<>();
        ChargePeriodSummary sum;
        BatteryHeat.Stats heat;
        Double soh;                               // state of health at the period's end, or null
        double km;
        /** id -> {lat, lon} of every charge that saved its place. */
        final Map<Long, double[]> where = new java.util.HashMap<>();
    }

    /** Coming to the page: back to the current month or year (owner,
     * 2026-11-18). Browsing to October and leaving kept October for good. */
    public void showCurrent() {
        anchor = java.time.LocalDate.now();
        refresh();
    }

    public void refresh() {
        final Context c = getContext().getApplicationContext();
        final int gen = ++loadGen;
        final java.time.LocalDate start = periodStart(anchor), end = periodEnd(anchor);
        LOADER.execute(() -> {
            Loaded l = new Loaded();
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            java.time.LocalDate today = java.time.LocalDate.parse(DailyStatsProvider.todayDateStr());
            long now = System.currentTimeMillis();
            long fromMs = start.atStartOfDay(zone).toInstant().toEpochMilli();
            long toMs = end.atStartOfDay(zone).toInstant().toEpochMilli();
            List<ChargeSession.Summary> all = ChargeSession.readLog(c);
            l.firstData = DailyStatsProvider.firstRecordedDay(c);
            for (ChargeSession.Summary s : all) {
                if (s.startWallMs <= 0) continue;
                java.time.LocalDate d = java.time.Instant.ofEpochMilli(s.startWallMs).atZone(zone).toLocalDate();
                if (l.firstData == null || d.isBefore(l.firstData)) l.firstData = d;
                if (s.startWallMs >= fromMs && s.startWallMs < toMs) l.period.add(s);
            }
            java.time.LocalDate last = end.minusDays(1).isAfter(today) ? today : end.minusDays(1);
            List<SocHistory.DayRange> days = last.isBefore(start)
                ? new ArrayList<>() : SocHistory.dailyRanges(c, start, last);
            l.sum = ChargePeriodSummary.of(all, fromMs, toMs, now, days);
            l.heat = BatteryHeat.read(c, start, end);
            l.soh = com.geely.drivemem.sensors.BatteryHealth.latestBefore(c, Math.min(toMs, now));
            if (!start.isAfter(today)) {
                for (com.geely.drivemem.sensors.StatsCalendar.Cell cell : DailyStatsProvider.getCalendarDays(c, start)) {
                    if (cell.date.isBefore(end)) l.km += cell.km;
                }
            }
            try (android.database.Cursor pc = CarDb.get(c).db().rawQuery(
                    "SELECT id, lat, lon FROM charge_session WHERE lat IS NOT NULL AND lon IS NOT NULL", null)) {
                while (pc.moveToNext()) {
                    l.where.put(pc.getLong(0), new double[]{pc.getDouble(1), pc.getDouble(2)});
                }
            } catch (Exception ignored) { }
            post(() -> { if (gen == loadGen) show(l, start); });
        });
    }

    private void show(Loaded l, java.time.LocalDate start) {
        Context c = getContext();
        firstData = l.firstData;
        setNavEnabled(prevBtn, canStep(-1));
        setNavEnabled(nextBtn, canStep(+1));
        String name = grain == Grain.MONTH
            ? DailyStatsProvider.monthLabel(start.toString())
            : String.valueOf(start.getYear());
        rangeLabel.setText(name + "  ·  " + String.format(Locale.getDefault(), "%,.0f", l.km) + " km");

        // Four data cards (owner's mocks, 2026-11-18): charges with the
        // charge to 100 %, cost, how low the battery went, its temperature.
        kpiContainer.removeAllViews();
        LinearLayout cards = new LinearLayout(c);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setBaselineAligned(false);
        long now = System.currentTimeMillis();
        boolean current = !periodEnd(start).isBefore(java.time.LocalDate.now().plusDays(1));
        View c1 = chargesCard(l.sum); c1.setLayoutParams(cardLp(true));
        View c2 = costCard(l.sum, l.km); c2.setLayoutParams(cardLp(true));
        View c3 = lowCard(l.sum, l.heat, l.soh, current); c3.setLayoutParams(cardLp(true));
        View c4 = heatCard(l.heat, start); c4.setLayoutParams(cardLp(true));
        Style.addMirrored(cards, 13, c1, c2, c3, c4);
        kpiContainer.addView(cards);


        // The list, newest first.
        listContainer.removeAllViews();
        sessionRows.clear();
        visibleSessions.clear();
        for (ChargeSession.Summary s : l.period) visibleSessions.put(s.id, s);

        // The map: the period's charges that saved their place, oldest first,
        // so a place's last id is its newest charge.
        List<ChargeSpots.Placed> placedNow = new ArrayList<>();
        for (ChargeSession.Summary s : l.period) {
            double[] w = l.where.get(s.id);
            if (w != null) placedNow.add(new ChargeSpots.Placed(s.id, w[0], w[1], s.isDcfc()));
        }
        places = ChargeSpots.places(placedNow);
        chargeMap.setChargePlaces(places);
        chargeMapNone.setVisibility(places.isEmpty() ? View.VISIBLE : View.GONE);
        chargeMap.setVisibility(places.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        if (l.period.isEmpty()) {
            listContainer.addView(Style.label(c, c.getString(R.string.charge_none)));
            return;
        }
        if (!visibleSessions.containsKey(selectedSessionId)) {
            selectedSessionId = l.period.get(l.period.size() - 1).id;
        }
        List<ChargeSession.Summary> rev = new ArrayList<>(l.period);
        Collections.reverse(rev);
        for (ChargeSession.Summary s : rev) {
            View row = chargeRow(s);
            sessionRows.put(s.id, row);
            listContainer.addView(row);
        }
        chargeMap.setSelectedSpot(ChargeSpots.placeOf(places, selectedSessionId));
    }

    // ---- the four cards ----

    private LinearLayout.LayoutParams cardLp(boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (!last) lp.rightMargin = Style.dp(getContext(), 13);
        return lp;
    }

    private static String money(double v) {
        return com.geely.drivemem.util.Prefs.formatMoney(v);
    }

    private static String shortDate(long ms) {
        return new SimpleDateFormat("d/M", Locale.getDefault()).format(new java.util.Date(ms));
    }

    private static String kwh(double v) {
        return String.format(Locale.getDefault(), v >= 100 ? "%,.0f" : "%.1f", v);
    }

    private LinearLayout dataCard(String title, CharSequence hero, int heroColor, String caption,
                                  View left, View right) {
        return dataCard(title, hero, heroColor, caption, left, right, null);
    }

    /** Title, a big number, an optional one-line caption, two tiles side by
     * side, then an optional full-width band at the bottom: the stats page's
     * card shape. One fact per line, no notes (owner, 2026-11-23: "so many
     * little phrases"); an explanation goes behind the card's (i). */
    private LinearLayout dataCard(String title, CharSequence hero, int heroColor, String caption,
                                  View left, View right, View band) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 16);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Style.card(Style.cardFillColor(), c));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Style.TEXT);
        t.setTextSize(18);
        t.setTypeface(null, Typeface.BOLD);
        card.addView(t);

        TextView h = new TextView(c);
        h.setText(hero);
        h.setTextColor(heroColor);
        h.setTextSize(36);
        h.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Style.dp(c, 8);
        card.addView(h, hlp);

        if (caption != null) {
            TextView cap = new TextView(c);
            cap.setText(caption);
            cap.setTextColor(Style.TEXT_DIM);
            cap.setTextSize(16);
            card.addView(cap);
        }

        LinearLayout tiles = new LinearLayout(c);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setBaselineAligned(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        tlp.rightMargin = Style.dp(c, 10);
        tiles.addView(left, tlp);
        tiles.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tilesLp.topMargin = Style.dp(c, 14);
        card.addView(new View(c), new LinearLayout.LayoutParams(0, 0, 1f));   // tiles sit low, in line across cards
        card.addView(tiles, tilesLp);

        if (band != null) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Style.dp(c, 10);
            card.addView(band, blp);
        }
        return card;
    }

    private View tile(String label, CharSequence value, int valueColor) {
        Context c = getContext();
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        int p = Style.dp(c, 12);
        t.setPadding(p, Style.dp(c, 8), p, Style.dp(c, 8));
        t.setBackground(Style.tile(c));
        TextView l = new TextView(c);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(17);
        l.setSingleLine(true);
        t.addView(l);
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(valueColor);
        v.setTextSize(22);
        v.setTypeface(null, Typeface.BOLD);
        v.setSingleLine(true);
        t.addView(v);
        return t;
    }

    /** "5× 75,7 kWh": the count small and dim, so it does not read as part
     * of the energy, then the energy with a small unit. */
    private static CharSequence countKwh(int count, double kwh) {
        if (count == 0) return "0";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Style.appendUnit(sb, count + "×  ", Style.UNIT_SCALE_ROW);
        sb.append(Style.valueWithUnit(kwh(kwh), null, "kWh", Style.UNIT_SCALE_ROW));
        return sb;
    }

    private static CharSequence moneyTile(double v) {
        return v >= 0 ? Style.moneyWithUnit(v, null, Style.UNIT_SCALE_ROW) : "—";
    }

    /** Recargas: how many, the energy, AC and DC. */
    private View chargesCard(ChargePeriodSummary m) {
        Context c = getContext();
        View ac = tile("AC", countKwh(m.acCount, m.acKwh), Style.ACCENT);
        View dc = tile("DC", countKwh(m.dcCount, m.dcKwh), Style.GOOD);
        return dataCard(c.getString(R.string.charge_card_charges), String.valueOf(m.count), Style.TEXT,
            c.getString(R.string.charge_card_energy_cap, kwh(m.totalKwh())), ac, dc);
    }

    /** "Last 100%: yesterday (22/11)". The current period says how long ago
     * (a fact about now); a past one names its own last full charge. */
    private String lastFull(ChargePeriodSummary m, boolean current) {
        Context c = getContext();
        String last;
        if (!current) {
            last = m.lastFullInPeriodMs < 0 ? "—" : shortDate(m.lastFullInPeriodMs);
        } else if (m.lastFullMs < 0) {
            last = c.getString(R.string.charge_card_never);
        } else {
            long d = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.Instant.ofEpochMilli(m.lastFullMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                java.time.LocalDate.now());
            String when = d <= 0 ? c.getString(R.string.charge_card_today)
                        : d == 1 ? c.getString(R.string.charge_card_yesterday)
                        : c.getString(R.string.charge_card_days_ago, (int) d);
            last = when + " (" + shortDate(m.lastFullMs) + ")";
        }
        return c.getString(R.string.charge_card_full_last, last);
    }

    private View costCard(ChargePeriodSummary m, double km) {
        Context c = getContext();
        if (!m.anyCost) {
            return dataCard(c.getString(R.string.charge_card_cost), "—", Style.TEXT,
                c.getString(R.string.charge_card_no_cost),
                tile(c.getString(R.string.charge_card_per_kwh, "AC"), "—", Style.TEXT),
                tile(c.getString(R.string.charge_card_per_kwh, "DC"), "—", Style.TEXT));
        }
        // What driving costs, then the price per kWh at each charger type:
        // the two numbers that change how and where to charge.
        String cap = km > 0 ? c.getString(R.string.charge_card_per_100km, money(m.totalCost() / km * 100)) : null;
        double ac = m.acPerKwh(), dc = m.dcPerKwh();
        View acTile = tile(c.getString(R.string.charge_card_per_kwh, "AC"), moneyTile(ac), Style.TEXT);
        // DC in the danger colour when it costs well over AC: that gap is
        // the one fact on this card worth acting on.
        int dcColor = dc >= 0 && ac > 0 && dc > 2 * ac ? Style.DANGER : Style.TEXT;
        View dcTile = tile(c.getString(R.string.charge_card_per_kwh, "DC"), moneyTile(dc), dcColor);
        return dataCard(c.getString(R.string.charge_card_cost),
            Style.moneyWithUnit(m.totalCost(), null, Style.UNIT_SCALE_HERO), Style.TEXT, cap, acTile, dcTile);
    }

    /** Saúde da bateria: the state of health (OBD2 dongle) with the last
     * charge to 100 % under it (LFP re-learns its % only at the top), and the
     * hours spent low. With no state of health read, the lowest charge is the
     * big number and the last 100 % goes to a band at the bottom. */
    private View lowCard(ChargePeriodSummary m, BatteryHeat.Stats heat, Double soh, boolean current) {
        Context c = getContext();
        String lowDate = m.lowest == null ? null
            : m.lowest.date.format(java.time.format.DateTimeFormatter.ofPattern("d/M"));
        CharSequence hero;
        String cap;
        View band = null;
        if (soh != null) {
            hero = Style.valueWithUnit(String.format(Locale.getDefault(), "%.1f", soh), Style.TEXT,
                "%", Style.UNIT_SCALE_HERO);
            cap = lastFull(m, current);
        } else {
            band = textBand(lastFull(m, current));
            hero = m.lowest == null ? "—"
                : Style.valueWithUnit(String.valueOf(m.lowest.min), m.lowest.min < 10 ? Style.DANGER : Style.TEXT,
                    "%", Style.UNIT_SCALE_HERO);
            cap = m.lowest == null ? c.getString(R.string.soc_history_empty)
                : c.getString(R.string.charge_card_low_cap, lowDate);
        }
        return dataCard(c.getString(R.string.charge_card_low), hero, Style.TEXT, cap,
            tile(c.getString(R.string.charge_card_below, BatteryHeat.LOW_PCT), lowHours(heat.below10Ms),
                heat.below10Ms == 0 ? Style.GOOD : Style.DANGER),
            tile(c.getString(R.string.charge_card_below, BatteryHeat.LOW2_PCT), lowHours(heat.below20Ms),
                heat.below20Ms == 0 ? Style.GOOD : Style.TEXT),
            band);
    }

    /** One bold line in a tile, the full width of a card. */
    private View textBand(String text) {
        Context c = getContext();
        LinearLayout band = new LinearLayout(c);
        int p = Style.dp(c, 12);
        band.setPadding(p, Style.dp(c, 10), p, Style.dp(c, 10));
        band.setBackground(Style.tile(c));
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(Style.TEXT);
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        band.addView(t);
        return band;
    }

    private static CharSequence lowHours(long ms) {
        return ms == 0 ? Style.valueWithUnit("0", null, "h", Style.UNIT_SCALE_ROW) : hours(ms);
    }

    /** "3,6 h" with a small h. */
    private static CharSequence hours(long ms) {
        double h = ms / 3_600_000.0;
        return Style.valueWithUnit(String.format(Locale.getDefault(), h >= 10 ? "%.0f" : "%.1f", h),
            null, "h", Style.UNIT_SCALE_ROW);
    }

    /** A value then a dim word after it: "3,2 h avg", "30 °C max". */
    private static CharSequence withWord(CharSequence value, String word) {
        SpannableStringBuilder sb = new SpannableStringBuilder(value);
        Style.appendUnit(sb, " " + word, Style.UNIT_SCALE_ROW);
        return sb;
    }

    private static CharSequence degC(double t) {
        return Style.valueWithUnit(String.format(Locale.getDefault(), "%.0f", t), null, "°C", Style.UNIT_SCALE_ROW);
    }

    /** How warm the pack got, and how long it sat at 100 % and how warm
     * (LFP: heat and a full pack together are what age it). Battery
     * temperature comes from the OBD2 dongle; in a period the dongle never
     * read, the card shows the outside air (the car's own sensor) under its
     * own title. Either way the (i) says where the numbers come from, so the
     * card carries no "data from" line of its own. */
    private View heatCard(BatteryHeat.Stats h, java.time.LocalDate periodStart) {
        Context c = getContext();
        boolean batt = h.showsBattery();
        BatteryHeat.Temps t = h.shown();
        String title = c.getString(batt ? R.string.charge_card_temp : R.string.charge_card_out_temp);
        CharSequence hero;
        String cap;
        if (t.n > 0) {
            int col = t.max >= 40 ? Style.DANGER : t.max >= 35 ? Style.WARN : Style.TEXT;
            hero = Style.valueWithUnit(String.format(Locale.getDefault(), "%.0f", t.max), col, "°C",
                Style.UNIT_SCALE_HERO);
            cap = c.getString(R.string.charge_card_temp_highest, shortDate(t.maxMs));
        } else {
            hero = "—";
            cap = c.getString(R.string.charge_card_no_temp);
        }
        View time = h.fullCount == 0
            ? tile(c.getString(R.string.charge_card_full_time), "—", Style.TEXT)
            : tile(c.getString(R.string.charge_card_full_time),
                withWord(hours(h.avgFullMs()), c.getString(R.string.charge_avg_short)), Style.TEXT);
        View temp = t.fullN == 0
            ? tile(c.getString(R.string.charge_card_full_temp), "—", Style.TEXT)
            : tile(c.getString(R.string.charge_card_full_temp),
                withWord(degC(t.fullMax), c.getString(R.string.charge_max_short)),
                t.fullMax >= 35 ? Style.WARN : Style.TEXT);
        LinearLayout card = dataCard(title, hero, Style.TEXT, cap, time, temp);
        if (t.has()) {
            String info = c.getString(batt ? R.string.charge_temp_info_batt : R.string.charge_temp_info_out)
                + "\n\n" + c.getString(R.string.charge_card_temp_note);
            if (batt && t.firstDay != null && t.firstDay.isAfter(periodStart)) {
                info += "\n\n" + c.getString(R.string.charge_card_temp_since,
                    t.firstDay.format(java.time.format.DateTimeFormatter.ofPattern("d/M")));
            }
            withInfo(card, title, info);
        }
        return card;
    }

    /** An (i) at the end of a card's title, and the whole card opens the
     * explanation: a big target, read from the driver's seat. */
    private void withInfo(LinearLayout card, String title, String info) {
        Context c = getContext();
        TextView t = (TextView) card.getChildAt(0);
        android.graphics.drawable.Drawable icon = c.getDrawable(R.drawable.ic_info_outline).mutate();
        icon.setTint(Style.TEXT_DIM);
        int size = Style.dp(c, 22);
        icon.setBounds(0, 0, size, size);
        t.setCompoundDrawablesRelative(null, null, icon, null);
        t.setCompoundDrawablePadding(Style.dp(c, 8));
        card.setOnClickListener(v -> showInfo(c, title, info));
    }

    /** Title and text on a card; tap outside to close, like the charge
     * details. Also the trip screen's (i). */
    static void showInfo(Context c, String title, CharSequence info) {
        android.app.Dialog dialog = new android.app.Dialog(c);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 32);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Style.card(Style.cardFillColor(), c));
        root.addView(Style.title(c, title));
        TextView body = new TextView(c);
        body.setText(info);
        body.setTextColor(Style.TEXT);
        body.setTextSize(20);
        body.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(c, 12);
        root.addView(body, blp);
        root.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout(Style.dp(c, 760), ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.show();
        if (window != null) window.setLayout(Style.dp(c, 760), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** One charge: the home screen's finished-charge card (ChargeSessionCard),
     * with the date. The card selects the charge (outline + its pin on the
     * map) and opens its page (ChargeDetailActivity); the right tile
     * sets the cost (ChargeCostDialog; typing waits for Park). */
    private View chargeRow(ChargeSession.Summary s) {
        Context c = getContext();
        ChargeSessionCard card = new ChargeSessionCard(c);
        card.bind(s, false, true);
        int pad = Style.dp(c, 22);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (listContainer.getChildCount() > 0) lp.topMargin = Style.dp(c, 14);
        card.setLayoutParams(lp);
        card.setOnOpen(() -> {
            selectSession(s.id, false);
            if (s.id > 0) c.startActivity(ChargeDetailActivity.intent(c, s.id));
        });
        card.setOnCost(() -> ChargeCostDialog.show(c, s.id, s.kwh, s.socStart, s.socEnd,
            s.cost != null && s.cost >= 0 ? s.cost : null, this::refresh));
        card.setBackground(s.id == selectedSessionId
            ? Style.outlinedCard(s.isDcfc() ? Style.GOOD : Style.ACCENT, c)
            : Style.card(Style.cardFillColor(), c));
        return card;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        fitList();
    }

    /** The page sits in a ScrollView: make the list (and the map beside it)
     * end at the bottom of what that ScrollView shows, so no space is left
     * under them and the page itself does not scroll for the list. */
    private void fitList() {
        if (!(getParent() instanceof View) || overview == null || sessionListScroll == null) return;
        View port = (View) getParent();
        int want = port.getHeight() - port.getPaddingBottom() - getPaddingBottom() - overview.getTop()
            - ((ViewGroup.MarginLayoutParams) overview.getLayoutParams()).bottomMargin;
        want = Math.max(Style.dp(getContext(), MIN_LIST_DP), want);
        ViewGroup.LayoutParams lp = sessionListScroll.getLayoutParams();
        if (Math.abs(lp.height - want) > 2) {
            lp.height = want;
            post(() -> sessionListScroll.setLayoutParams(lp));
        }
    }

    private void selectSession(long id, boolean fromMap) {
        selectedSessionId = id;
        for (Map.Entry<Long, View> entry : sessionRows.entrySet()) {
            ChargeSession.Summary s = visibleSessions.get(entry.getKey());
            int color = s != null && s.isDcfc() ? Style.GOOD : Style.ACCENT;
            entry.getValue().setBackground(entry.getKey() == id
                ? Style.outlinedCard(color, getContext())
                : Style.card(Style.cardFillColor(), getContext()));
        }
        chargeMap.setSelectedSpot(ChargeSpots.placeOf(places, id));
        View row = sessionRows.get(id);
        if (fromMap && row != null) sessionListScroll.smoothScrollTo(0, row.getTop());
    }
}
