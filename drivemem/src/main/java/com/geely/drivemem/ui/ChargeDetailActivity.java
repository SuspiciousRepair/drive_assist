package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** One charge in detail, opened from the home screen's charging card, live
 * while the car charges.
 *
 * Left: the charts (ChargeDetailChartView): power with the battery and
 * outside temperature on top, battery % below, one time axis, and a line a
 * finger moves through both. Right: the numbers, the cost (tap to set it)
 * and the map of where the car charged.
 *
 * "Time at 100 %" is the time from the end of a charge that reached 100 %
 * to the next drive (the next trip row), or until now ("so far") when the
 * car has not moved since. It needs no plug sensor. */
public class ChargeDetailActivity extends Activity {
    static final String EXTRA_CHARGE_ID = "charge_id";
    private static final long LIVE_REFRESH_MS = 15_000L;
    // A live page opened after its charge ended takes only a charge that
    // ended this recently (plus the grace period).
    private static final long RECENT_END_MS = 10 * 60_000L;

    /** The charge with this row id. */
    static Intent intent(Context c, long chargeId) {
        return new Intent(c, ChargeDetailActivity.class).putExtra(EXTRA_CHARGE_ID, chargeId);
    }

    /** The charge running now. */
    static Intent live(Context c) {
        return new Intent(c, ChargeDetailActivity.class).putExtra(EXTRA_CHARGE_ID, -1L);
    }

    private TextView title;
    private ChargeDetailChartView chart;
    private TextView empty;
    private LinearLayout stats;
    private RouteView map;
    private long chargeId;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private long liveStartMs = -1;   // start of the open charge this page followed

    /** What load() found. */
    static final class Loaded {
        boolean found, dc;
        boolean active;      // current flows now: "Charging now"
        boolean following;   // the open charge (also in its 3 min grace): keep refreshing
        long id = -1, startMs, endMs, nextTripMs = -1;
        int socStart, socEnd;
        double kwh, avgPowerW = Double.NaN;
        Double cost;
        Double lat, lon;
        long[] ts = new long[0];
        float[] kw = new float[0], batT = new float[0], outT = new float[0], soc = new float[0];
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);
        Style.edgeToEdge(this);
        chargeId = getIntent().getLongExtra(EXTRA_CHARGE_ID, -1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Style.screenBg());
        int pad = Style.dp(this, 22);
        root.setPadding(pad, pad + Style.statusBarHeight(this), pad, pad);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Style.backButton(this, this::finish));
        title = Style.title(this, getString(R.string.charge_detail_title));
        title.setTextSize(22);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Style.dp(this, 14);
        head.addView(title, tlp);
        root.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = Style.dp(this, 14);
        root.addView(body, blp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        int lp = Style.dp(this, 20);
        left.setPadding(lp, lp, lp, lp);
        left.setBackground(Style.tile(this));
        body.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        chart = new ChargeDetailChartView(this);
        left.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        empty = new TextView(this);
        empty.setText(R.string.charge_curve_unavailable);
        empty.setTextColor(Style.TEXT_DIM);
        empty.setTextSize(20);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        left.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Style.dp(this, 460),
            ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = Style.dp(this, 20);
        body.addView(right, rlp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Style.tile(this));
        stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        int sp = Style.dp(this, 20);
        stats.setPadding(sp, Style.dp(this, 6), sp, sp);
        scroll.addView(stats);
        right.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        map = new RouteView(this);
        map.setMinSpanM(600);
        map.setVisibility(View.GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Style.dp(this, 300));
        mlp.topMargin = Style.dp(this, 16);
        right.addView(map, mlp);

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        reload();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacksAndMessages(null);
    }

    private void reload() {
        final long id = chargeId;
        new Thread(() -> {
            Loaded d = load(getApplicationContext(), id, liveStartMs);
            runOnUiThread(() -> {
                if (!resumed || isFinishing()) return;
                show(d);
                if (d.following) {
                    ui.removeCallbacksAndMessages(null);
                    ui.postDelayed(this::reload, LIVE_REFRESH_MS);
                }
            });
        }, "charge-detail").start();
    }

    /** liveStartMs: start of the open charge this page showed before, or -1. */
    static Loaded load(Context ctx, long id, long liveStartMs) {
        Loaded d = new Loaded();
        try {
            android.database.sqlite.SQLiteDatabase db = CarDb.get(ctx).db();
            long now = System.currentTimeMillis();
            if (id < 0 && !ChargeSession.isSessionActive()) {
                // The live charge ended while the page was open (or before
                // it opened): show the charge it became.
                // Only a charge that ended just now (or the one this page
                // followed): never an older, unrelated one. A charge too
                // short to be kept has no row, and the page closes.
                long since = liveStartMs > 0 ? liveStartMs
                    : now - ChargeSession.CHARGE_GRACE_PERIOD_MS - RECENT_END_MS;
                Cursor last = db.rawQuery("SELECT id FROM charge_session WHERE end_ms >= ? "
                    + "ORDER BY end_ms DESC LIMIT 1", new String[]{String.valueOf(since)});
                try { if (last.moveToFirst()) id = last.getLong(0); }
                finally { last.close(); }
                if (id < 0) return d;
            }
            if (id < 0) {
                d.found = true;
                d.following = true;
                // In the 3 min after the current stops, the charge is still
                // open (it may go on), but it is not "Charging now".
                d.active = ChargeSession.isCharging();
                long paused = ChargeSession.currentPauseWallMs();
                d.id = ChargeSession.activeRowId();
                d.startMs = ChargeSession.currentStartWallMs();
                d.endMs = d.active || paused <= 0 ? now : paused;
                d.socStart = ChargeSession.currentSocStart();
                d.socEnd = ChargeSession.currentSocEnd();
                d.kwh = ChargeSession.currentKwh();
                d.dc = ChargeSession.currentIsDcfc();
                long ms = d.endMs - d.startMs;
                if (ms > 0) d.avgPowerW = d.kwh * 1000.0 / (ms / 3_600_000.0);
                if (!d.active && d.id > 0) {   // the cost button shows in the grace too
                    Cursor cc = db.rawQuery("SELECT cost FROM charge_session WHERE id = ?",
                        new String[]{String.valueOf(d.id)});
                    try { if (cc.moveToFirst() && !cc.isNull(0)) d.cost = cc.getDouble(0); }
                    finally { cc.close(); }
                }
                if (Prefs.getRecordRoutes(ctx)) {
                    Cursor pc = db.rawQuery(CarDb.CHARGE_PLACE_SQL,
                        new String[]{String.valueOf(d.startMs), String.valueOf(d.startMs)});
                    try { if (pc.moveToFirst()) { d.lat = pc.getDouble(0); d.lon = pc.getDouble(1); } }
                    finally { pc.close(); }
                }
            } else {
                Cursor c = db.rawQuery("SELECT start_ms, end_ms, soc_start, soc_end, kwh, avg_power_w, "
                    + "max_charge_v, cost, lat, lon FROM charge_session WHERE id = ?",
                    new String[]{String.valueOf(id)});
                try {
                    if (!c.moveToFirst()) return d;
                    d.found = true;
                    d.id = id;
                    d.startMs = c.getLong(0);
                    d.endMs = c.getLong(1);
                    d.socStart = c.getInt(2);
                    d.socEnd = c.getInt(3);
                    d.kwh = c.getDouble(4);
                    d.avgPowerW = c.isNull(5) ? Double.NaN : c.getDouble(5);
                    d.dc = !c.isNull(6) && ChargeSession.isDcfc(c.getDouble(6));
                    d.cost = c.isNull(7) ? null : c.getDouble(7);
                    if (!c.isNull(8) && !c.isNull(9)) { d.lat = c.getDouble(8); d.lon = c.getDouble(9); }
                } finally { c.close(); }
                Cursor t = db.rawQuery("SELECT MIN(start_ms) FROM trip WHERE start_ms >= ?",
                    new String[]{String.valueOf(d.endMs)});
                try { if (t.moveToFirst() && !t.isNull(0)) d.nextTripMs = t.getLong(0); }
                finally { t.close(); }
            }

            Cursor s = db.rawQuery("SELECT ts_ms, charge_a, charge_v, outside_temp_c, battery_temp_c, battery_pct "
                + "FROM telemetry_sample WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms",
                new String[]{String.valueOf(d.startMs), String.valueOf(d.endMs)});
            try {
                int n = s.getCount();
                d.ts = new long[n]; d.kw = new float[n]; d.batT = new float[n]; d.outT = new float[n];
                d.soc = new float[n];
                for (int i = 0; s.moveToNext(); i++) {
                    d.ts[i] = s.getLong(0);
                    d.kw[i] = s.isNull(1) || s.isNull(2) || s.getFloat(1) < 0 ? Float.NaN
                        : s.getFloat(1) * s.getFloat(2) / 1000f;
                    d.outT[i] = s.isNull(3) ? Float.NaN : s.getFloat(3);
                    d.batT[i] = s.isNull(4) ? Float.NaN : s.getFloat(4);
                    d.soc[i] = s.isNull(5) ? Float.NaN : s.getFloat(5);
                }
            } finally { s.close(); }
        } catch (Throwable e) {
            android.util.Log.w("DriveMem", "charge detail: " + e);
        }
        return d;
    }

    private void show(Loaded d) {
        if (!d.found) { finish(); return; }
        if (d.following) liveStartMs = d.startMs;
        else if (d.id > 0) chargeId = d.id;   // a live page whose charge ended
        String when = android.text.format.DateFormat.getMediumDateFormat(this).format(new java.util.Date(d.startMs))
            + "  " + SessionText.clock(d.startMs) + (d.active ? "" : " – " + SessionText.clock(d.endMs));
        title.setText(getString(d.active ? R.string.charge_detail_live : R.string.charge_detail_title)
            + "  ·  " + when);

        boolean any = ChargeDetail.range(d.kw) != null || ChargeDetail.range(d.soc) != null;
        chart.setVisibility(any ? View.VISIBLE : View.GONE);
        empty.setVisibility(any ? View.GONE : View.VISIBLE);
        if (any) chart.setData(d.ts, d.kw, d.batT, d.outT, d.soc, d.startMs, d.endMs, d.dc);

        if (d.lat != null && d.lon != null) {
            java.util.List<ChargeSpots.Spot> spot = new java.util.ArrayList<>();
            spot.add(new ChargeSpots.Spot(d.lat, d.lon, d.dc));
            map.setChargeSpots(spot);
            map.setVisibility(View.VISIBLE);
        } else {
            map.setVisibility(View.GONE);
        }
        showStats(d);
    }

    // The numbers, two per row, so all of them fit above the map.
    private final java.util.List<View> blocks = new java.util.ArrayList<>();

    private void showStats(Loaded d) {
        stats.removeAllViews();
        blocks.clear();
        int typeColor = d.dc ? Style.GOOD : Style.ACCENT;
        // Every unit smaller and dimmer than its number (docs/STYLE-GUIDE.md).
        // A level unknown at plug-in is saved as -1: never show "-1%".
        CharSequence battery = d.socStart >= 0 && d.socEnd >= 0
            ? Style.percentRange(d.socStart, d.socEnd, Style.UNIT_SCALE_ROW)
            : d.socEnd >= 0 ? Style.valueWithUnit(String.valueOf(d.socEnd), null, "%", Style.UNIT_SCALE_ROW)
            : getString(R.string.value_unavailable);
        stat(R.string.session_stat_battery, battery, null, Style.TEXT, null);
        stat(R.string.session_stat_charged_energy, Style.valueWithUnit(
            String.format(Locale.getDefault(), "+%.1f", d.kwh), typeColor, "kWh", Style.UNIT_SCALE_ROW), null, Style.TEXT, null);
        stat(R.string.charge_detail_charging, Style.durationWithUnits(d.endMs - d.startMs, Style.UNIT_SCALE_ROW), null, Style.TEXT, null);

        long idle = ChargeDetail.idleAtFullMs(d.socEnd, d.endMs, d.nextTripMs, System.currentTimeMillis(), d.active);
        if (idle >= 0) {
            stat(R.string.charge_detail_idle, Style.durationWithUnits(idle, Style.UNIT_SCALE_ROW),
                d.nextTripMs > 0 ? null : getString(R.string.charge_detail_idle_now),
                Style.TEXT, null);
        }

        float[] kwr = ChargeDetail.range(d.kw);
        if (!Double.isNaN(d.avgPowerW)) {
            stat(R.string.session_stat_avg_power, Style.valueWithUnit(
                String.format(Locale.getDefault(), "%.1f", d.avgPowerW / 1000.0), null, "kW", Style.UNIT_SCALE_ROW), null, Style.TEXT, null);
        }
        if (kwr != null) {
            stat(R.string.session_stat_max_power, Style.valueWithUnit(
                String.format(Locale.getDefault(), "%.1f", kwr[1]), null, "kW", Style.UNIT_SCALE_ROW), null, Style.TEXT, null);
        }
        float[] bt = ChargeDetail.firstLast(d.batT);
        if (bt != null) {
            stat(R.string.charge_detail_bat_temp_label, Style.rangeWithUnit(String.valueOf(Math.round(bt[0])),
                String.valueOf(Math.round(bt[1])), "°", Style.UNIT_SCALE_ROW), null, Style.TEXT, null);
        }
        float[] ot = ChargeDetail.firstLast(d.outT);
        if (ot != null) {
            stat(R.string.route_stat_outside, Style.rangeWithUnit(String.valueOf(Math.round(ot[0])),
                String.valueOf(Math.round(ot[1])), "°", Style.UNIT_SCALE_ROW), null, Style.TEXT, null);
        }
        if (!d.active && d.id > 0) {
            boolean hasCost = d.cost != null && d.cost >= 0;
            CharSequence value = hasCost ? Style.moneyWithUnit(d.cost, null, Style.UNIT_SCALE_ROW)
                : "+ " + getString(R.string.charge_cost_label);
            String sub = hasCost && d.kwh > 0 ? Prefs.formatMoneyPerKwh(d.cost / d.kwh) : null;
            stat(R.string.session_stat_cost, value, sub, hasCost ? Style.TEXT : Style.ACCENT, () ->
                ChargeCostDialog.show(this, d.id, d.kwh, d.socStart, d.socEnd, d.cost, this::reload));
        }
        for (int i = 0; i < blocks.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            for (int k = i; k < i + 2; k++) {
                View b = k < blocks.size() ? blocks.get(k) : new View(this);
                row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            stats.addView(row);
        }
    }

    // One number: a small dim label, the value, and an optional dim line
    // under it. With onTap, the whole block is a button.
    private void stat(int label, CharSequence value, String sub, int color, Runnable onTap) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, Style.dp(this, 12), 0, 0);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(17);
        block.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(26);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        // Only as wide as the text, so an icon after it sits next to it.
        block.addView(v, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(Style.TEXT_DIM);
            s.setTextSize(17);
            block.addView(s);
        }
        if (onTap != null) {
            // A pencil after the value says it can be tapped to edit.
            android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_edit_outline).mutate();
            icon.setTint(Style.ACCENT);
            int size = Style.dp(this, 26);
            icon.setBounds(0, 0, size, size);
            v.setCompoundDrawablesRelative(null, null, icon, null);
            v.setCompoundDrawablePadding(Style.dp(this, 12));
            block.setOnClickListener(x -> onTap.run());
        }
        blocks.add(block);
    }
}
