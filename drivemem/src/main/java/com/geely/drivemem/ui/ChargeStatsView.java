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
import com.geely.drivemem.sensors.ChargePeriodSummary;
import com.geely.drivemem.sensors.SocHistory;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.OdoStats;
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
    private int periodDays = 30;

    private LinearLayout headerContainer;
    private LinearLayout kpiContainer;
    private TextView rangeLabel;
    private TextView btn30, btn365;
    private final java.util.Set<Long> placedIds = new java.util.HashSet<>();
    private LinearLayout listContainer;
    private ScrollView sessionListScroll;
    private ChargeSocChart chargeSocChart;
    private long selectedSessionId = -1;
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
        if (onBack != null) {
            headerContainer.addView(Style.backButton(c, onBack));
            Style.gap(headerContainer, c, 16);
        }
        btn30 = periodButton(30, c.getString(R.string.charge_period_30d));
        btn365 = periodButton(365, c.getString(R.string.charge_ltm_title));
        headerContainer.addView(btn30);
        Style.gap(headerContainer, c, 12);
        headerContainer.addView(btn365);
        rangeLabel = new TextView(c);
        rangeLabel.setTextColor(Style.TEXT_DIM);
        rangeLabel.setTextSize(17);
        rangeLabel.setPadding(Style.dp(c, 24), 0, Style.dp(c, 12), 0);
        headerContainer.addView(rangeLabel, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView exportBtn = Style.cardButton(c, c.getString(R.string.charge_export), false, () ->
            CsvExport.exportToUsb(c, (ok, drive, copied) -> post(() -> {
                String msg = drive == null ? c.getString(R.string.charge_export_no_drive)
                           : !ok || copied == 0 ? c.getString(R.string.charge_export_nothing)
                           : c.getString(R.string.charge_export_ok, copied);
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
            })));
        headerContainer.addView(exportBtn);
        addView(headerContainer);

        // 2. KPI Summary Container
        kpiContainer = new LinearLayout(c);
        kpiContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams kpiLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kpiLp.topMargin = Style.dp(c, 12);
        kpiContainer.setLayoutParams(kpiLp);
        addView(kpiContainer);

        // 3. Compact session navigator and chronological SoC chart.
        // These are two independent cards, not a matched pair -- neither's
        // height should derive from the other's. Previously both were forced
        // into one shared fixed-height row (390dp), so a short chart reserved
        // the same box as a long session list and vice versa; whichever had
        // more content than that box could hold got clipped instead of
        // scrolling on its own. Each card now sizes to (or caps) its own
        // content independently.
        LinearLayout overview = new LinearLayout(c);
        overview.setOrientation(LinearLayout.HORIZONTAL);
        overview.setBaselineAligned(false);
        LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        overviewLp.topMargin = Style.dp(c, 12);
        overview.setLayoutParams(overviewLp);

        LinearLayout listCard = new LinearLayout(c);
        listCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = Style.dp(c, 14);
        listCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        listCard.setBackground(Style.card(Style.cardFillColor(), c));
        TextView listTitle = Style.label(c, c.getString(R.string.charge_sessions_title));
        listTitle.setTextSize(14);
        listTitle.setTypeface(null, Typeface.BOLD);
        listCard.addView(listTitle);

        sessionListScroll = new ScrollView(c);
        sessionListScroll.setVerticalScrollBarEnabled(false);
        sessionListScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listContainer = new LinearLayout(c);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sessionListScroll.addView(listContainer);
        // Own fixed cap (not tied to the chart card): enough room for a
        // couple of session cards before this list scrolls internally,
        // regardless of how tall the chart beside it ends up being.
        listCard.addView(sessionListScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 340)));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 46f);
        listLp.rightMargin = Style.dp(c, 12);
        overview.addView(listCard, listLp);

        LinearLayout chartCard = new LinearLayout(c);
        chartCard.setOrientation(LinearLayout.VERTICAL);
        chartCard.setPadding(cardPad, cardPad, cardPad, Style.dp(c, 8));
        chartCard.setBackground(Style.card(Style.cardFillColor(), c));
        LinearLayout chartHeading = new LinearLayout(c);
        chartHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView chartTitle = Style.label(c, c.getString(R.string.charge_soc_chart_title));
        chartTitle.setTextSize(14);
        chartTitle.setTypeface(null, Typeface.BOLD);
        chartHeading.addView(chartTitle, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chartHeading.addView(legend(c, "AC", Style.ACCENT));
        chartHeading.addView(legend(c, "DC", Style.GOOD));
        chartCard.addView(chartHeading);
        chargeSocChart = new ChargeSocChart(c);
        chargeSocChart.setListener(id -> selectSession(id, true));
        // A fixed height, the same as the list beside it: the bars scroll
        // inside the card. Sized to its bars, a year (18 charges) made the
        // card grow off the screen (2026-11-18).
        chartCard.addView(chargeSocChart, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, 340)));

        LinearLayout rightColumn = new LinearLayout(c);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.addView(chartCard, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        overview.addView(rightColumn, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 54f));
        addView(overview);
    }

    public void refresh() {
        Context c = getContext();
        List<ChargeSession.Summary> allSessions = ChargeSession.readLog(c);

        long now = System.currentTimeMillis();
        long cutoff = now - periodDays * 24L * 3600 * 1000;
        double kmDriven = OdoStats.kmSince(c, periodDays);
        java.time.LocalDate today = java.time.LocalDate.parse(DailyStatsProvider.todayDateStr());
        java.time.LocalDate from = java.time.Instant.ofEpochMilli(cutoff)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        List<SocHistory.DayRange> days = SocHistory.dailyRanges(c, from, today);
        ChargePeriodSummary sum = ChargePeriodSummary.of(allSessions, cutoff, now, days);

        btn30.setBackground(Style.card(periodDays == 30 ? Style.CARD_ON : Style.CARD, c));
        btn30.setTextColor(Style.onFill(periodDays == 30 ? Style.CARD_ON : Style.CARD));
        btn365.setBackground(Style.card(periodDays == 365 ? Style.CARD_ON : Style.CARD, c));
        btn365.setTextColor(Style.onFill(periodDays == 365 ? Style.CARD_ON : Style.CARD));
        rangeLabel.setText(c.getString(R.string.charge_range_label,
            shortDate(cutoff), shortDate(now), String.format(Locale.getDefault(), "%,.0f", kmDriven)));

        // Four data cards (owner's mock, 2026-11-18): charges, cost, how low
        // the battery went, and the last charge to 100 %. They replaced the
        // single metrics card and the Energy Balance chart.
        kpiContainer.removeAllViews();
        LinearLayout cards = new LinearLayout(c);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setBaselineAligned(false);
        cards.addView(chargesCard(sum), cardLp(false));
        cards.addView(costCard(sum, kmDriven), cardLp(false));
        cards.addView(lowCard(sum), cardLp(false));
        cards.addView(fullCard(sum, now), cardLp(true));
        kpiContainer.addView(cards);

        placedIds.clear();
        try (android.database.Cursor pc = CarDb.get(c).db().rawQuery(
                "SELECT id FROM charge_session WHERE lat IS NOT NULL AND lon IS NOT NULL", null)) {
            while (pc.moveToNext()) placedIds.add(pc.getLong(0));
        } catch (Exception ignored) { }

        // Rebuild session navigator and chart in the same order (newest
        // first) -- they used to disagree (list newest-first, chart
        // chronological), which read as two different sort orders for the
        // same data sitting side by side.
        listContainer.removeAllViews();
        sessionRows.clear();
        visibleSessions.clear();
        List<ChargeSession.Summary> periodSessions = new ArrayList<>();
        for (ChargeSession.Summary s : allSessions) {
            if (s.startWallMs >= cutoff) {
                periodSessions.add(s);
                visibleSessions.put(s.id, s);
            }
        }
        if (periodSessions.isEmpty()) {
            listContainer.addView(Style.label(c, c.getString(R.string.charge_none)));
            chargeSocChart.setSessions(periodSessions, -1);
            return;
        }
        if (!visibleSessions.containsKey(selectedSessionId)) {
            selectedSessionId = periodSessions.get(periodSessions.size() - 1).id;
        }
        List<ChargeSession.Summary> rev = new ArrayList<>(periodSessions);
        Collections.reverse(rev);
        for (ChargeSession.Summary s : rev) {
            View row = chargeRow(s);
            sessionRows.put(s.id, row);
            listContainer.addView(row);
        }
        chargeSocChart.setSessions(rev, selectedSessionId);
        chargeSocChart.select(selectedSessionId, true);
    }

    /** A period pill in the header row; refresh() paints the selected one. */
    private TextView periodButton(int days, String label) {
        return Style.cardButton(getContext(), label, periodDays == days, () -> {
            periodDays = days;
            refresh();
        });
    }

    // ---- the four cards ----

    private LinearLayout.LayoutParams cardLp(boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (!last) lp.rightMargin = Style.dp(getContext(), 13);
        return lp;
    }

    private static String money(double v) {
        return String.format(Locale.getDefault(), "R$ %,.2f", v);
    }

    private static String shortDate(long ms) {
        return new SimpleDateFormat("d/M", Locale.getDefault()).format(new java.util.Date(ms));
    }

    private static String kwh(double v) {
        return String.format(Locale.getDefault(), v >= 100 ? "%,.0f" : "%.1f", v);
    }

    /** Title, a big number, a caption, two tiles side by side, an optional
     * note at the bottom: the stats page's card shape. */
    private LinearLayout dataCard(String title, CharSequence hero, int heroColor, String caption,
                                  View left, View right, String note) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(c, 16);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Style.card(Style.cardFillColor(), c));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Style.TEXT);
        t.setTextSize(16);
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

        TextView cap = new TextView(c);
        cap.setText(caption);
        cap.setTextColor(Style.TEXT_DIM);
        cap.setTextSize(14);
        card.addView(cap);

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
        card.addView(tiles, tilesLp);

        if (note != null) {
            View spacer = new View(c);
            card.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
            TextView n = new TextView(c);
            n.setText(note);
            n.setTextColor(Style.TEXT_DIM);
            n.setTextSize(13);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = Style.dp(c, 12);
            card.addView(n, nlp);
        }
        return card;
    }

    private View tile(String label, CharSequence value, int valueColor, String sub) {
        Context c = getContext();
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        int p = Style.dp(c, 12);
        t.setPadding(p, Style.dp(c, 8), p, Style.dp(c, 8));
        t.setBackground(Style.tile(c));
        TextView l = new TextView(c);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(13);
        t.addView(l);
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(valueColor);
        v.setTextSize(19);
        v.setTypeface(null, Typeface.BOLD);
        t.addView(v);
        if (sub != null) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextColor(Style.TEXT_DIM);
            s.setTextSize(12);
            t.addView(s);
        }
        return t;
    }

    private View chargesCard(ChargePeriodSummary m) {
        Context c = getContext();
        String note = null;
        if (m.acCount > 0 && m.dcCount > 0) {
            note = c.getString(R.string.charge_card_power_note, String.format(Locale.getDefault(),
                "AC %.1f kW · DC %.0f kW", m.acPowerW / 1000, m.dcPowerW / 1000));
        } else if (m.count > 0) {
            note = c.getString(R.string.charge_card_power_note, String.format(Locale.getDefault(),
                "%s %.1f kW", m.dcCount > 0 ? "DC" : "AC", (m.dcCount > 0 ? m.dcPowerW : m.acPowerW) / 1000));
        }
        return dataCard(c.getString(R.string.charge_card_charges), String.valueOf(m.count), Style.TEXT,
            c.getString(R.string.charge_card_energy_cap, kwh(m.totalKwh())),
            tile("AC", m.acCount + " · " + kwh(m.acKwh) + " kWh", Style.ACCENT, null),
            tile("DC", m.dcCount == 0 ? "0" : m.dcCount + " · " + kwh(m.dcKwh) + " kWh", Style.GOOD, null),
            note);
    }

    private View costCard(ChargePeriodSummary m, double km) {
        Context c = getContext();
        if (!m.anyCost) {
            return dataCard(c.getString(R.string.charge_card_cost), "—", Style.TEXT,
                c.getString(R.string.charge_card_no_cost),
                tile(c.getString(R.string.charge_card_per_kwh, "AC"), "—", Style.TEXT, null),
                tile(c.getString(R.string.charge_card_per_kwh, "DC"), "—", Style.TEXT, null), null);
        }
        String per100 = km > 0 ? money(m.totalCost() / km * 100) : "—";
        String cap = c.getString(R.string.charge_card_cost_cap, per100, money(m.perKwh()));
        double ac = m.acPerKwh(), dc = m.dcPerKwh();
        View acTile = tile(c.getString(R.string.charge_card_per_kwh, "AC"), ac >= 0 ? money(ac) : "—", Style.TEXT,
            ac >= 0 ? c.getString(R.string.charge_card_cost_total, money(m.acCost)) : null);
        // DC in the danger colour when it costs well over AC: that gap is
        // the one fact on this card worth acting on.
        int dcColor = dc >= 0 && ac > 0 && dc > 2 * ac ? Style.DANGER : Style.TEXT;
        View dcTile = tile(c.getString(R.string.charge_card_per_kwh, "DC"), dc >= 0 ? money(dc) : "—", dcColor,
            dc >= 0 ? c.getString(R.string.charge_card_cost_share, money(m.dcCost),
                          (int) Math.round(m.dcCostShare() * 100))
                    : c.getString(R.string.charge_card_no_dc));
        return dataCard(c.getString(R.string.charge_card_cost), money(m.totalCost()), Style.TEXT, cap,
            acTile, dcTile, null);
    }

    private View lowCard(ChargePeriodSummary m) {
        Context c = getContext();
        CharSequence hero = m.lowest == null ? "—"
            : Style.valueWithUnit(String.valueOf(m.lowest.min), m.lowest.min < 10 ? Style.DANGER : Style.TEXT,
                "%", Style.UNIT_SCALE_HERO);
        String cap = m.lowest == null ? c.getString(R.string.soc_history_empty)
            : c.getString(R.string.charge_card_low_cap,
                m.lowest.date.format(java.time.format.DateTimeFormatter.ofPattern("d/M")));
        String note = m.avgPlugPct >= 0
            ? c.getString(R.string.charge_card_plug_note, (int) Math.round(m.avgPlugPct)) : null;
        return dataCard(c.getString(R.string.charge_card_low), hero, Style.TEXT, cap,
            tile(c.getString(R.string.charge_card_below, 10), String.valueOf(m.daysBelow10),
                m.daysBelow10 == 0 ? Style.GOOD : Style.DANGER, null),
            tile(c.getString(R.string.charge_card_below, 20), String.valueOf(m.daysBelow20),
                m.daysBelow20 == 0 ? Style.GOOD : Style.TEXT, null),
            note);
    }

    private View fullCard(ChargePeriodSummary m, long now) {
        Context c = getContext();
        String hero, cap;
        if (m.lastFullMs < 0) {
            hero = c.getString(R.string.charge_card_never);
            cap = "";
        } else {
            long d = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.Instant.ofEpochMilli(m.lastFullMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                java.time.LocalDate.now());
            hero = d <= 0 ? c.getString(R.string.charge_card_today)
                 : d == 1 ? c.getString(R.string.charge_card_yesterday)
                 : c.getString(R.string.charge_card_days_ago, (int) d);
            cap = c.getString(R.string.charge_card_full_cap, shortDate(m.lastFullMs));
        }
        View gap;
        if (m.longestGapFromMs < 0) {
            gap = tile(c.getString(R.string.charge_card_gap), "—", Style.TEXT, null);
        } else {
            long to = m.longestGapToMs < 0 ? now : m.longestGapToMs;
            int days = (int) ((to - m.longestGapFromMs) / (24L * 3600 * 1000));
            String span = m.longestGapToMs < 0
                ? c.getString(R.string.charge_card_gap_open, shortDate(m.longestGapFromMs))
                : shortDate(m.longestGapFromMs) + " → " + shortDate(m.longestGapToMs);
            // LFP wants a full charge about weekly; past that, amber.
            gap = tile(c.getString(R.string.charge_card_gap), c.getString(R.string.charge_card_gap_days, days),
                days > 7 ? Style.WARN : Style.TEXT, span);
        }
        return dataCard(c.getString(R.string.charge_card_full), hero, Style.TEXT, cap,
            tile(c.getString(R.string.charge_card_full_count),
                c.getString(R.string.charge_card_full_of, m.fullInPeriod, m.count), Style.TEXT, null),
            gap, c.getString(R.string.charge_card_full_note));
    }

    private View chargeRow(ChargeSession.Summary s) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        // More horizontal padding than vertical: the icon and the SoC/Cost
        // corner text both used to sit flush against the card's left/right
        // edge. Vertical stays tight (each corner is one line now). Right
        // gets extra: the right corners are bold, variable-width numbers
        // (energy, cost) that came closer to the border than the left side
        // did at the same padding value.
        int pL = Style.dp(c, 16);
        int pR = Style.dp(c, 24);
        int pV = Style.dp(c, 10);
        card.setPadding(pL, pV, pR, pV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(c, 16);
        card.setLayoutParams(lp);

        boolean isDcfc = s.isDcfc();
        int typeColor = !s.hasChargeVoltage() ? Style.TEXT_DIM : isDcfc ? Style.GOOD : Style.ACCENT;

        // Four corners, one per dimension (2026-09-13 v2, replacing the
        // previous four-stacked-row layout -- that version was too tall for
        // what it showed, with too much vertical gap between rows and hero
        // numbers sized the same as secondary text). Top-left Time,
        // top-right SoC/Energy, bottom-left Power (+ chart link),
        // bottom-right Cost -- each quadrant anchored to its own corner
        // instead of stacked full-width lines.
        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.TOP);

        // Top-left: Time.
        LinearLayout timeCell = new LinearLayout(c);
        timeCell.setOrientation(LinearLayout.HORIZONTAL);
        timeCell.setGravity(Gravity.CENTER_VERTICAL);
        timeCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // App-wide charging style guide (2026-09-13): AC = blue + plug icon,
        // DC = green + ev-plug-ccs2 icon, color/icon alone carry the meaning
        // -- no "AC"/"DC" text on a session card, that's reserved for the
        // chart legend (the one place two colors side by side need naming).
        ImageView typeIcon = new ImageView(c);
        typeIcon.setImageResource(isDcfc ? R.drawable.ic_ev_plug_ccs2 : R.drawable.ic_power_plug);
        typeIcon.setColorFilter(typeColor, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Style.dp(c, 20), Style.dp(c, 20));
        iconLp.rightMargin = Style.dp(c, 8);
        typeIcon.setLayoutParams(iconLp);
        timeCell.addView(typeIcon);

        // Time and duration share one line -- "11 set 13:40 -> 14:39  0:58"
        // -- rather than duration sitting on its own line below (2026-09-13:
        // that made the cell taller than it needed to be for one related fact).
        LinearLayout timeText = new LinearLayout(c);
        timeText.setOrientation(LinearLayout.HORIZONTAL);
        timeText.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        title.setText(s.title()); // "11 set  13:40 → 14:39"
        title.setTextColor(Style.TEXT);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        timeText.addView(title);
        TextView durationText = new TextView(c);
        durationText.setText(s.durationLabel());
        durationText.setTextColor(Style.TEXT_DIM);
        durationText.setTextSize(13);
        LinearLayout.LayoutParams durationLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        durationLp.leftMargin = Style.dp(c, 8);
        durationText.setLayoutParams(durationLp);
        timeText.addView(durationText);
        timeCell.addView(timeText);
        topRow.addView(timeCell);

        // Top-right: SoC and the energy it added share one line -- these are
        // two views of the same fact (how much the battery gained), not two
        // separate ones, so they read better side by side than stacked.
        LinearLayout socCell = new LinearLayout(c);
        socCell.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        socCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView socEnergyText = new TextView(c);
        SpannableStringBuilder socEnergy = new SpannableStringBuilder();
        socEnergy.append(Style.percentRange(s.socStart, s.socEnd, Style.UNIT_SCALE_ROW));
        socEnergy.append("  ");
        // Normal weight, not bold: the SoC range is the primary fact on this
        // corner, the energy added is secondary -- two bold numbers side by
        // side on one line fought for attention instead of reading as
        // primary/secondary (2026-09-13).
        socEnergy.append(Style.valueWithUnit(String.format(Locale.getDefault(), "+%.1f", s.kwh),
            null, "kWh", Style.UNIT_SCALE_ROW, false));
        socEnergyText.setText(socEnergy);
        socEnergyText.setTextColor(Style.TEXT);
        socEnergyText.setTextSize(19);
        socEnergyText.setTypeface(null, Typeface.BOLD);
        socCell.addView(socEnergyText);
        topRow.addView(socCell);
        card.addView(topRow);

        LinearLayout bottomRow = new LinearLayout(c);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = Style.dp(c, 4);
        bottomRow.setLayoutParams(bottomLp);

        // Bottom-left: Power, with the link to its detail chart right next to
        // it (the chart it opens *is* the power curve, so it belongs with
        // this number rather than floating between two unrelated ones).
        // A spacer matching the icon's own width (20dp) + its margin (8dp)
        // comes first, so Power's text starts at the same x as Time's text
        // above it -- the icon stays where it is (top-left only), but the
        // two corners' text still lines up into one visual column.
        LinearLayout powerCell = new LinearLayout(c);
        powerCell.setOrientation(LinearLayout.HORIZONTAL);
        powerCell.setGravity(Gravity.CENTER_VERTICAL);
        powerCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View iconAlignSpacer = new View(c);
        iconAlignSpacer.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 28), 0));
        powerCell.addView(iconAlignSpacer);

        TextView powerText = new TextView(c);
        SpannableStringBuilder power = new SpannableStringBuilder();
        power.append(Style.valueWithUnit(String.format(Locale.getDefault(), "%.1f", s.avgPowerW / 1000.0),
            Style.TEXT, "kW", Style.UNIT_SCALE_ROW));
        Style.appendUnit(power, " méd.", Style.UNIT_SCALE_ROW);
        powerText.setText(power);
        powerText.setTextSize(19);
        powerText.setTypeface(null, Typeface.BOLD);
        powerCell.addView(powerText);

        // A small icon, not a full button -- a labeled button sitting in the
        // middle of a data row read as an odd, heavy element.
        ImageView curveBtn = new ImageView(c);
        curveBtn.setImageResource(R.drawable.ic_chart_line);
        curveBtn.setColorFilter(Style.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
        curveBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int curvePad = Style.dp(c, 6);
        curveBtn.setPadding(curvePad, curvePad, curvePad, curvePad);
        // 44dp touch target around a 24dp glyph -- a hand-sized tap area, not
        // just a visually-sized one (see CLAUDE.md's note on touch targets).
        curveBtn.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 44), Style.dp(c, 44)));
        curveBtn.setOnClickListener(v -> {
            if (!com.geely.drivemem.state.CarState.isParked()) {
                Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                return;
            }
            ChargeCurrentCurveDialog.show(c, s, this::refresh);
        });
        powerCell.addView(curveBtn);
        // Where it charged: only charges that saved their place (from
        // 2026-11-16 on). Opens the routes map on that day, where the charge
        // is drawn as its icon (ChargeSpots).
        if (placedIds.contains(s.id)) {
            ImageView mapBtn = new ImageView(c);
            mapBtn.setImageResource(R.drawable.ic_map);
            mapBtn.setColorFilter(Style.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
            mapBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            mapBtn.setPadding(curvePad, curvePad, curvePad, curvePad);
            mapBtn.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, 44), Style.dp(c, 44)));
            mapBtn.setOnClickListener(v -> {
                if (!com.geely.drivemem.state.CarState.isParked()) {
                    Toast.makeText(c, R.string.route_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                android.content.Intent i = new android.content.Intent(c, AllRoutesActivity.class);
                i.putExtra(AllRoutesActivity.EXTRA_DAY,
                    new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(s.startWallMs)));
                c.startActivity(i);
            });
            powerCell.addView(mapBtn);
        }
        bottomRow.addView(powerCell);

        // Bottom-right: Cost, anchored to that corner. Total and its per-kWh
        // rate share one line, same as SoC/Energy above -- the rate is
        // de-emphasized inline (smaller/dimmer) rather than stacked on its
        // own line, per the unit style guide's "value then smaller/dimmer
        // detail" idea applied to a rate instead of a suffix unit.
        LinearLayout costCell = new LinearLayout(c);
        costCell.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        costCell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (s.cost != null && s.cost >= 0) {
            TextView costText = new TextView(c);
            SpannableStringBuilder costSb = new SpannableStringBuilder();
            costSb.append(s.costLabel());
            if (s.costPerKwhLabel() != null) {
                Style.appendUnit(costSb, "  " + s.costPerKwhLabel(), Style.UNIT_SCALE_ROW);
            }
            costText.setText(costSb);
            costText.setTextColor(Style.ACCENT);
            costText.setTextSize(19);
            costText.setTypeface(null, Typeface.BOLD);
            costText.setOnClickListener(v -> {
                if (!com.geely.drivemem.state.CarState.isParked()) {
                    Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeCostDialog.show(c, s.id, s.kwh, s.socStart, s.socEnd, s.cost, this::refresh);
            });
            costCell.addView(costText);
        } else {
            TextView costBtn = Style.cardButton(c, "+ " + c.getString(R.string.charge_cost_label), false, () -> {
                if (!com.geely.drivemem.state.CarState.isParked()) {
                    Toast.makeText(c, R.string.charge_cost_parked_only, Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeCostDialog.show(c, s.id, s.kwh, s.socStart, s.socEnd, null, this::refresh);
            });
            costCell.addView(costBtn);
        }
        bottomRow.addView(costCell);
        card.addView(bottomRow);
        // Tapping the card selects it (outline + its bar in the chart above)
        // and opens its details, the same dialog as the chart icon. The
        // details are Park only; on the move the tap only selects.
        card.setOnClickListener(v -> {
            selectSession(s.id, false);
            if (com.geely.drivemem.state.CarState.isParked()) ChargeCurrentCurveDialog.show(c, s, this::refresh);
        });
        card.setBackground(s.id == selectedSessionId
            ? Style.outlinedCard(isDcfc ? Style.GOOD : Style.ACCENT, c)
            : Style.card(Style.CARD, c));
        return card;
    }

    private void selectSession(long id, boolean fromChart) {
        selectedSessionId = id;
        for (Map.Entry<Long, View> entry : sessionRows.entrySet()) {
            ChargeSession.Summary s = visibleSessions.get(entry.getKey());
            int color = s != null && s.isDcfc() ? Style.GOOD : Style.ACCENT;
            entry.getValue().setBackground(entry.getKey() == id
                ? Style.outlinedCard(color, getContext())
                : Style.card(Style.CARD, getContext()));
        }
        chargeSocChart.select(id, !fromChart);
        View row = sessionRows.get(id);
        if (fromChart && row != null) sessionListScroll.smoothScrollTo(0, row.getTop());
    }

    private View legend(Context c, String text, int color) {
        TextView v = new TextView(c);
        v.setText("● " + text);
        v.setTextColor(color);
        v.setTextSize(13);
        v.setPadding(Style.dp(c, 12), 0, 0, 0);
        return v;
    }
}
