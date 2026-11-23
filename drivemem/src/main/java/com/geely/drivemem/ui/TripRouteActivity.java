package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.EnergySource;
import com.geely.drivemem.sensors.EnergySplit;
import com.geely.drivemem.sensors.SpeedBuckets;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.database.Cursor;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** One trip in detail (issue #19): the route drawn as lines on a plain
 * background, the height profile under it, and the trip's numbers on the
 * right. Opened by tapping a finished trip card on the stats page.
 *
 * Energy, battery and climb come from the card (extras), so both screens
 * show the same numbers. Moving time, speeds and height come from the route
 * points; outside temperature from the 30 s samples. Trips without points
 * (older ones, or recorded with routes off) show the numbers and a note. */
public class TripRouteActivity extends Activity {
    public static final String EXTRA_TRIP_ID = "trip_id";
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_END_MS = "end_ms";
    public static final String EXTRA_KM = "km";
    public static final String EXTRA_SOC_START = "soc_start";
    public static final String EXTRA_SOC_END = "soc_end";
    public static final String EXTRA_SPENT = "spent_kwh";
    public static final String EXTRA_REGEN = "regen_kwh";
    public static final String EXTRA_NET = "net_kwh";
    public static final String EXTRA_EFF = "kwh_100km";
    public static final String EXTRA_ASCENT = "ascent_m";
    public static final String EXTRA_DESCENT = "descent_m";
    public static final String EXTRA_ESTIMATED = "estimated";

    /** The intent that opens the route of one finished trip. */
    static android.content.Intent intent(android.content.Context c, DailyStatsProvider.DriveSession t) {
        return new android.content.Intent(c, TripRouteActivity.class)
            .putExtra(EXTRA_TRIP_ID, t.tripId)
            .putExtra(EXTRA_START_MS, t.startMs)
            .putExtra(EXTRA_END_MS, t.endMs)
            .putExtra(EXTRA_KM, t.distanceKm)
            .putExtra(EXTRA_SOC_START, t.socStart)
            .putExtra(EXTRA_SOC_END, t.socEnd)
            .putExtra(EXTRA_SPENT, t.spentKwh)
            .putExtra(EXTRA_REGEN, t.regenKwh)
            .putExtra(EXTRA_NET, t.energyKwh)
            .putExtra(EXTRA_EFF, t.efficiencyKwh100km)
            .putExtra(EXTRA_ASCENT, t.ascentDPlusM)
            .putExtra(EXTRA_DESCENT, t.descentDMinusM)
            .putExtra(EXTRA_ESTIMATED, t.energySource == EnergySource.ESTIMATED
                    || t.energySource == EnergySource.MIXED);
    }

    /** Window (points) of the moving median on the height: 2 each side. */
    static final int HEIGHT_SMOOTH = 5;

    private RouteView route;
    private ElevationView profile;
    private TextView note;
    private LinearLayout stats;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);
        Style.edgeToEdge(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Style.screenBg());
        int pad = Style.dp(this, 22);
        root.setPadding(pad, pad + Style.statusBarHeight(this), pad, pad);

        long startMs = getIntent().getLongExtra(EXTRA_START_MS, 0);
        long endMs = getIntent().getLongExtra(EXTRA_END_MS, 0);
        double km = getIntent().getDoubleExtra(EXTRA_KM, 0);

        String when = android.text.format.DateFormat.getMediumDateFormat(this).format(new java.util.Date(startMs))
            + "  " + SessionText.clock(startMs) + (endMs > 0 ? " – " + SessionText.clock(endMs) : "")
            + String.format(Locale.US, "  ·  %.1f km", km);
        TextView t = Style.title(this, getString(R.string.route_title) + "  ·  " + when);
        t.setTextSize(22);
        root.addView(Style.headerRow(this, Style.backButton(this, this::finish), t));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Map, height profile, speed key (left in LHD, right in RHD).
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        route = new RouteView(this);
        route.setTripScreen(true);
        left.addView(route, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        note = new TextView(this);
        note.setTextColor(Style.TEXT_DIM);
        note.setTextSize(20);
        note.setGravity(Gravity.CENTER);
        note.setVisibility(View.GONE);
        left.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        profile = new ElevationView(this);
        profile.setOnPick(i -> route.setMarker(i));
        left.addView(profile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 230)));
        left.addView(legend());

        // The numbers (right in LHD, left in RHD).
        stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        int sp = Style.dp(this, 20);
        stats.setPadding(sp, sp, sp, sp);
        stats.setBackground(Style.tile(this));
        stats.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(this, 460),
            ViewGroup.LayoutParams.MATCH_PARENT));
        Style.addMirrored(body, 20, left, stats);
        showStats(null);

        setContentView(root);

        final long tripId = getIntent().getLongExtra(EXTRA_TRIP_ID, -1);
        new Thread(() -> load(tripId, startMs, endMs), "trip-route").start();
    }

    // Speed colour key, one chip per speed bucket: the stats page's buckets
    // and colours, same scale for every trip.
    private LinearLayout legend() { return legend(this); }

    static LinearLayout legend(android.content.Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Style.dp(ctx, 10), 0, 0);
        String[] labels = SpeedBuckets.LABELS;
        int[] colors = SpeedBucketColors.get();
        for (int i = 0; i < labels.length; i++) {
            TextView sw = new TextView(ctx);
            sw.setText("━━ " + labels[i] + (i == labels.length - 1 ? " km/h" : ""));
            sw.setTextColor(colors[i]);
            sw.setTextSize(19);
            sw.setPadding(Style.dp(ctx, 16), 0, Style.dp(ctx, 16), 0);
            row.addView(sw);
        }
        return row;
    }

    /** What load() found; null fields when there are no points. */
    static final class Loaded {
        double[] lat = new double[0], lon = new double[0], speed = new double[0], alt = new double[0];
        long[] ts = new long[0];
        Double tempMin, tempMax;
        /** Charges right before or after this trip (ChargeSpots.forTrip). */
        java.util.List<ChargeSpots.Spot> spots = new java.util.ArrayList<>();
        /** The trip's raw split columns (CarDb.TRIP_SPLIT_COLUMNS), or null
         * when CAN did not measure the trip. */
        double[] split;
    }

    private void load(long tripId, long startMs, long endMs) {
        Loaded d = new Loaded();
        try {
            Cursor c = CarDb.get(this).db().rawQuery(
                "SELECT lat, lon, COALESCE(speed_kmh, 0), alt_m, ts_ms FROM trip_point "
              + "WHERE trip_id = ? ORDER BY ts_ms", new String[]{String.valueOf(tripId)});
            try {
                int n = c.getCount();
                d.lat = new double[n]; d.lon = new double[n]; d.speed = new double[n];
                d.alt = new double[n]; d.ts = new long[n];
                for (int i = 0; c.moveToNext(); i++) {
                    d.lat[i] = c.getDouble(0); d.lon[i] = c.getDouble(1); d.speed[i] = c.getDouble(2);
                    d.alt[i] = c.isNull(3) ? Double.NaN : c.getDouble(3);
                    d.ts[i] = c.getLong(4);
                }
            } finally { c.close(); }
            int np = d.lat.length;
            if (np >= 2) {
                try {
                    d.spots = ChargeSpots.forTrip(ChargeSpots.read(this), startMs, endMs,
                        d.lat[0], d.lon[0], d.lat[np - 1], d.lon[np - 1],
                        ChargeSpots.readHops(this, startMs, endMs > 0 ? endMs : startMs));
                } catch (Throwable e) { android.util.Log.w("DriveMem", "trip charges: " + e); }
            }
            Cursor sc = CarDb.get(this).db().rawQuery(
                "SELECT can_net_kwh, drive_spent_kwh, drive_regen_kwh, climate_raw_kwh, climate_on_s, fan_kwh "
              + "FROM trip WHERE id = ? AND can_net_kwh IS NOT NULL", new String[]{String.valueOf(tripId)});
            try {
                if (sc.moveToFirst()) {
                    d.split = new double[6];
                    for (int i = 0; i < 6; i++) d.split[i] = sc.isNull(i) ? 0 : sc.getDouble(i);
                }
            } finally { sc.close(); }
            if (endMs > startMs) {
                Cursor tc = CarDb.get(this).db().rawQuery(
                    "SELECT MIN(outside_temp_c), MAX(outside_temp_c) FROM telemetry_sample "
                  + "WHERE ts_ms BETWEEN ? AND ? AND outside_temp_c IS NOT NULL",
                    new String[]{String.valueOf(startMs), String.valueOf(endMs)});
                try {
                    if (tc.moveToFirst() && !tc.isNull(0)) { d.tempMin = tc.getDouble(0); d.tempMax = tc.getDouble(1); }
                } finally { tc.close(); }
            }
        } catch (Throwable e) {
            android.util.Log.w("DriveMem", "trip route: " + e);
        }
        runOnUiThread(() -> show(d));
    }

    private void show(Loaded d) {
        if (d.lat.length < 2) {
            route.setVisibility(View.GONE);
            profile.setVisibility(View.GONE);
            note.setText(R.string.route_none);
            note.setVisibility(View.VISIBLE);
        } else {
            route.setChargeSpots(d.spots);
            route.setRoute(d.lat, d.lon, d.speed);
            double[] alt = TripRouteStats.fillGaps(d.alt);
            if (alt != null) {
                profile.setProfile(TripRouteStats.cumulativeKm(d.lat, d.lon),
                    TripRouteStats.medianSmooth(alt, HEIGHT_SMOOTH), d.speed);
            } else {
                profile.setVisibility(View.GONE);
            }
        }
        showStats(d);
    }

    private void showStats(Loaded d) {
        stats.removeAllViews();
        android.content.Intent in = getIntent();
        long startMs = in.getLongExtra(EXTRA_START_MS, 0), endMs = in.getLongExtra(EXTRA_END_MS, 0);
        double km = in.getDoubleExtra(EXTRA_KM, 0);
        int socStart = in.getIntExtra(EXTRA_SOC_START, -1), socEnd = in.getIntExtra(EXTRA_SOC_END, -1);
        double spent = in.getDoubleExtra(EXTRA_SPENT, 0), regen = in.getDoubleExtra(EXTRA_REGEN, 0);
        double net = in.getDoubleExtra(EXTRA_NET, 0), eff = in.getDoubleExtra(EXTRA_EFF, 0);
        boolean est = in.getBooleanExtra(EXTRA_ESTIMATED, false);
        boolean hasPoints = d != null && d.lat.length >= 2;

        stat(R.string.route_stat_distance, String.format(Locale.US, "%.1f km", km), null);

        long total = endMs > startMs ? endMs - startMs : 0;
        String timeSub = null;
        if (hasPoints) {
            long moving = Math.min(TripRouteStats.movingMs(d.ts), total);
            timeSub = getString(R.string.route_stat_time_split,
                TripRouteStats.duration(moving), TripRouteStats.duration(total - moving));
            // From the points' own distance, not the odometer km: with
            // points missing (GPS dropout) km / moving time was absurd.
            double avgKmh = TripRouteStats.avgMovingKmh(d.lat, d.lon, d.ts);
            String avg = Double.isNaN(avgKmh) ? "—" : String.format(Locale.US, "%.0f", avgKmh);
            stat(R.string.route_stat_time, TripRouteStats.duration(total), timeSub);
            speedStat(avg, String.format(Locale.US, "%.0f", TripRouteStats.max(d.speed)));
        } else {
            stat(R.string.route_stat_time, TripRouteStats.duration(total), null);
        }

        // With the Energy by use chart, the net energy line is not shown: the
        // chart's Battery is the same number, and its Regen and Gasto say
        // the rest. Trips with no chart (estimated, or older) keep the line.
        boolean chart = !est && d != null && d.split != null;
        if (!chart) {
            String netText = String.format(Locale.US, "%.1f kWh%s", net, est ? " ~" : "");
            String netSub = !est && regen > 0 ? getString(R.string.session_stat_net_breakdown,
                String.format(Locale.US, "%.1f", spent), String.format(Locale.US, "%.1f", regen)) : null;
            stat(R.string.session_stat_net_energy, netText, netSub);
        }
        if (chart) {
            EnergySplit sp = EnergySplit.of(net, d.split[0], d.split[1], d.split[2], d.split[3], d.split[4], d.split[5]);
            TextView l = new TextView(this);
            l.setText(R.string.route_stat_by_use);
            l.setTextColor(Style.TEXT_DIM);
            l.setTextSize(17);
            l.setPadding(0, Style.dp(this, 10), 0, 0);
            stats.addView(l);
            EnergySankeyView sankey = new EnergySankeyView(this);
            sankey.setData(sp.batteryKwh(), sp.motorRegenKwh, sp.motorOutKwh, sp.climateKwh, sp.otherKwh, km);
            stats.addView(sankey, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        stat(R.string.session_stat_efficiency,
            eff > 0 ? com.geely.drivemem.util.EnergyUnits.number(eff) + " " + com.geely.drivemem.util.EnergyUnits.WH_PER_KM : "—", null);
        stat(R.string.session_stat_battery, socStart >= 0 && socEnd >= 0
            ? socStart + "% → " + socEnd + "%" : "—", null);

        String climb = String.format(Locale.US, "↑%.0f  ↓%.0f m",
            in.getDoubleExtra(EXTRA_ASCENT, 0), in.getDoubleExtra(EXTRA_DESCENT, 0));
        String heightSub = null;
        if (hasPoints) {
            double[] alt = TripRouteStats.fillGaps(d.alt);
            if (alt != null) {
                double[] sm = TripRouteStats.medianSmooth(alt, HEIGHT_SMOOTH);
                heightSub = getString(R.string.route_stat_height_range,
                    String.format(Locale.US, "%.0f", TripRouteStats.min(sm)),
                    String.format(Locale.US, "%.0f", TripRouteStats.max(sm)));
            }
        }
        stat(R.string.session_stat_elevation, climb, heightSub);

        if (d != null && d.tempMin != null) {
            stat(R.string.route_stat_outside, Math.round(d.tempMin) == Math.round(d.tempMax)
                ? String.format(Locale.US, "%.0f °C", d.tempMin)
                : String.format(Locale.US, "%.0f – %.0f °C", d.tempMin, d.tempMax), null);
        }
    }

    /** Speed: two numbers side by side, each with its name small and dim
     * under it, so the words do not fight the numbers. The label has an
     * (i), and a tap anywhere on the block says what the numbers are. */
    private void speedStat(String avg, String top) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(R.string.route_stat_speed);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(17);
        l.setPadding(0, Style.dp(this, 10), 0, 0);
        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_info_outline).mutate();
        icon.setTint(Style.TEXT_DIM);
        int size = Style.dp(this, 20);
        icon.setBounds(0, 0, size, size);
        l.setCompoundDrawablesRelative(null, null, icon, null);
        l.setCompoundDrawablePadding(Style.dp(this, 8));
        block.addView(l);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(speedPart(avg, R.string.route_stat_speed_avg));
        View top_ = speedPart(top, R.string.route_stat_speed_top);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Style.dp(this, 48);
        row.addView(top_, lp);
        block.addView(row);
        block.setOnClickListener(v -> ChargeStatsView.showInfo(this,
            getString(R.string.route_stat_speed), getString(R.string.route_stat_speed_info)));
        stats.addView(block);
    }

    /** "15 km/h" (the unit smaller and dim) over "average". */
    private View speedPart(String number, int name) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(this);
        android.text.SpannableString s = new android.text.SpannableString(number + " km/h");
        int from = number.length();
        s.setSpan(new android.text.style.RelativeSizeSpan(0.65f), from, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(Style.TEXT_DIM), from, s.length(), 0);
        v.setText(s);
        v.setTextColor(Style.TEXT);
        v.setTextSize(26);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(v);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Style.TEXT_DIM);
        n.setTextSize(16);
        col.addView(n);
        return col;
    }

    // One number: a small dim label, the value, and an optional dim line under it.
    private void stat(int label, String value, String sub) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(17);
        l.setPadding(0, Style.dp(this, 10), 0, 0);
        stats.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Style.TEXT);
        v.setTextSize(26);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stats.addView(v);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(Style.TEXT_DIM);
            s.setTextSize(16);
            stats.addView(s);
        }
    }
}
