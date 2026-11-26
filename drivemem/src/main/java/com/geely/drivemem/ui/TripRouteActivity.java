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
    public static final String EXTRA_ODO_START = "odo_start_km";
    public static final String EXTRA_ODO_END = "odo_end_km";
    /** A joined trip (issue #210): its trips' ids, starts, ends and km, and
     * the trip_edit row (Undo join). */
    public static final String EXTRA_MEMBER_IDS = "member_ids";
    public static final String EXTRA_MEMBER_STARTS = "member_starts";
    public static final String EXTRA_MEMBER_ENDS = "member_ends";
    public static final String EXTRA_MEMBER_KM = "member_km";
    public static final String EXTRA_JOIN_ID = "join_id";
    /** A part of a split trip: the recorded trip (Undo split). */
    public static final String EXTRA_CUT_TRIP = "cut_trip_id";
    /** A joined trip or a part: its energy split (EnergySplit.toArray),
     * since no one trip row holds it. */
    public static final String EXTRA_SPLIT = "split";

    /** A stop inside a trip this long gets a line on the height chart, and
     * the split picker snaps to it. */
    static final long STOP_MIN_MS = 2 * 60_000L;
    /** The split picker snaps to a stop this close to the finger. */
    static final double SNAP_KM = 0.5;

    /** The intent that opens the route of one finished trip. */
    static android.content.Intent intent(android.content.Context c, DailyStatsProvider.DriveSession t) {
        android.content.Intent in = new android.content.Intent(c, TripRouteActivity.class)
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
            .putExtra(EXTRA_ODO_START, t.odoStartKm)
            .putExtra(EXTRA_ODO_END, t.odoEndKm)
            .putExtra(EXTRA_ESTIMATED, t.energySource == EnergySource.ESTIMATED
                    || t.energySource == EnergySource.MIXED);
        if (t.isJoined()) {
            int n = t.members.size();
            long[] ids = new long[n], st = new long[n], en = new long[n];
            double[] km = new double[n];
            for (int i = 0; i < n; i++) {
                DailyStatsProvider.DriveSession m = t.members.get(i);
                ids[i] = m.tripId; st[i] = m.startMs; en[i] = m.endMs; km[i] = m.distanceKm;
            }
            in.putExtra(EXTRA_MEMBER_IDS, ids).putExtra(EXTRA_MEMBER_STARTS, st)
              .putExtra(EXTRA_MEMBER_ENDS, en).putExtra(EXTRA_MEMBER_KM, km)
              .putExtra(EXTRA_JOIN_ID, t.joinId);
        }
        if (t.cutTripId > 0) in.putExtra(EXTRA_CUT_TRIP, t.cutTripId);
        if ((t.isJoined() || t.cutTripId > 0) && t.split != null) in.putExtra(EXTRA_SPLIT, t.split.toArray());
        return in;
    }

    /** Window (points) of the moving median on the height: 2 each side. */
    static final int HEIGHT_SMOOTH = 5;

    private RouteView route;
    private ElevationView profile;
    private TextView note;
    private LinearLayout stats;
    private TextView tollsToggle;
    private boolean showTollsOnMap = true;
    private int tollPinCount = 0;
    /** The route point last picked on the height chart or a toll row: where
     * "+ Add toll" pins a missed toll. */
    private int markedIndex = -1;

    private TextView titleView;
    private CharSequence titleText;
    /** The head's buttons: Split, Undo join, Undo split; Cancel and Split
     * here in split mode. */
    private LinearLayout headActions;
    private Loaded loaded;
    /** km from the start, per point (the height chart's x). */
    private double[] pointKm = new double[0];
    private boolean splitMode;
    /** The picked cut: point indexes {a, b}, or null. */
    private int[] cutAt;
    /** The chart had no heights and was shown flat for split mode only. */
    private boolean flatForSplit;

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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Style.backButton(this, this::finish));
        String when = android.text.format.DateFormat.getMediumDateFormat(this).format(new java.util.Date(startMs))
            + "  " + SessionText.clock(startMs) + (endMs > 0 ? " – " + SessionText.clock(endMs) : "")
            + String.format(Locale.US, "  ·  %.1f km", km);
        TextView t = Style.title(this, getString(R.string.route_title) + "  ·  " + when);
        t.setTextSize(22);
        titleView = t;
        titleText = t.getText();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Style.dp(this, 14);
        head.addView(t, tlp);

        tollsToggle = new TextView(this);
        tollsToggle.setTextSize(17);
        tollsToggle.setTypeface(null, android.graphics.Typeface.BOLD);
        int th = Style.dp(this, 16), tv = Style.dp(this, 8);
        tollsToggle.setPadding(th, tv, th, tv);
        tollsToggle.setVisibility(View.GONE);
        tollsToggle.setOnClickListener(v -> {
            showTollsOnMap = !showTollsOnMap;
            route.setShowTolls(showTollsOnMap);
            refreshTollsToggle();
        });
        head.addView(tollsToggle);
        headActions = new LinearLayout(this);
        headActions.setOrientation(LinearLayout.HORIZONTAL);
        headActions.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(headActions);
        root.addView(head);

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
        profile.setOnPick(this::onPick);
        left.addView(profile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 230)));
        left.addView(legend());

        // The numbers (right in LHD, left in RHD), scrollable so Sankey + Scores never clip.
        android.widget.ScrollView statsScroll = new android.widget.ScrollView(this);
        statsScroll.setVerticalScrollBarEnabled(false);
        statsScroll.setBackground(Style.tile(this));
        statsScroll.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(this, 460),
            ViewGroup.LayoutParams.MATCH_PARENT));
        stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        int sp = Style.dp(this, 20);
        stats.setPadding(sp, sp, sp, sp);
        statsScroll.addView(stats, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Style.addMirrored(body, 20, left, statsScroll);
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
        Integer roadScore, driverScore, roadBumps, hardBrakes, hardAccels, hardCorners;
        java.util.List<com.geely.drivemem.state.TollTracker.Passage> tolls = new java.util.ArrayList<>();
        /** Parking paid right after this trip, or null. */
        com.geely.drivemem.state.ParkingCost.Row parking;
        /** Start of the next trip (the end of the stop after this one), or 0. */
        long nextTripStartMs;
        /** The recorded trip each point belongs to (a joined trip has several). */
        long[] pointTrip = new long[0];
        /** A joined trip or a part: the split from the card, not a trip row. */
        EnergySplit fixedSplit;
        /** The lines on the height chart: point index, kind, label. */
        int[] evIdx = new int[0], evKind = new int[0];
        String[] evLabel = new String[0];
        /** Stops inside the trip, for the split picker. */
        java.util.List<com.geely.drivemem.sensors.TripEdits.Stop> stops = new java.util.ArrayList<>();
        /** A joined trip: the time parked between its trips. */
        long parkedMs;
    }

    private void load(long tripId, long startMs, long endMs) {
        Loaded d = new Loaded();
        android.content.Intent in = getIntent();
        long[] memberIds = in.getLongArrayExtra(EXTRA_MEMBER_IDS);
        long[] memberStarts = in.getLongArrayExtra(EXTRA_MEMBER_STARTS);
        long[] memberEnds = in.getLongArrayExtra(EXTRA_MEMBER_ENDS);
        double[] memberKm = in.getDoubleArrayExtra(EXTRA_MEMBER_KM);
        boolean joined = memberIds != null && memberIds.length > 1;
        boolean part = in.getLongExtra(EXTRA_CUT_TRIP, 0) > 0;
        long[] ids = joined ? memberIds : new long[]{tripId};
        StringBuilder inIds = new StringBuilder();
        for (long id : ids) { if (inIds.length() > 0) inIds.append(','); inIds.append(id); }
        d.fixedSplit = EnergySplit.fromArray(in.getDoubleArrayExtra(EXTRA_SPLIT));
        try {
            // A part of a split trip shows only its own stretch of points.
            String window = part ? " AND ts_ms BETWEEN " + startMs + " AND " + endMs : "";
            Cursor c = CarDb.get(this).db().rawQuery(
                "SELECT lat, lon, COALESCE(speed_kmh, 0), alt_m, ts_ms, accuracy_m, trip_id FROM trip_point "
              + "WHERE trip_id IN (" + inIds + ")" + window + " ORDER BY ts_ms", null);
            try {
                int n = c.getCount();
                d.lat = new double[n]; d.lon = new double[n]; d.speed = new double[n];
                d.alt = new double[n]; d.ts = new long[n]; d.pointTrip = new long[n];
                double[] acc = new double[n];
                for (int i = 0; c.moveToNext(); i++) {
                    d.lat[i] = c.getDouble(0); d.lon[i] = c.getDouble(1); d.speed[i] = c.getDouble(2);
                    d.alt[i] = c.isNull(3) ? Double.NaN : c.getDouble(3);
                    d.ts[i] = c.getLong(4);
                    acc[i] = c.isNull(5) ? Double.NaN : c.getDouble(5);
                    d.pointTrip[i] = c.getLong(6);
                }
                // Heights not trusted yet (trip start, a vague fix) and
                // heights the car could not have driven to (a GPS jump) are
                // left out of the chart and the height range.
                d.alt = com.geely.drivemem.sensors.AltitudeGate.dropJumps(
                    com.geely.drivemem.sensors.AltitudeGate.dropUnsure(d.alt, d.ts, acc),
                    TripRouteStats.cumulativeKm(d.lat, d.lon));
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
                "SELECT can_net_kwh, drive_spent_kwh, drive_regen_kwh, climate_raw_kwh, climate_on_s, fan_kwh, "
              + "road_score, driver_score, road_bumps, hard_brakes, hard_accels, hard_corners, id "
              + "FROM trip WHERE id IN (" + inIds + ")", null);
            try {
                if (!joined && !part && sc.moveToFirst()) {
                    if (!sc.isNull(0)) {
                        d.split = new double[6];
                        for (int i = 0; i < 6; i++) d.split[i] = sc.isNull(i) ? 0 : sc.getDouble(i);
                    }
                    if (!sc.isNull(6)) d.roadScore = sc.getInt(6);
                    if (!sc.isNull(7)) d.driverScore = sc.getInt(7);
                    if (!sc.isNull(8)) d.roadBumps = sc.getInt(8);
                    if (!sc.isNull(9)) d.hardBrakes = sc.getInt(9);
                    if (!sc.isNull(10)) d.hardAccels = sc.getInt(10);
                    if (!sc.isNull(11)) d.hardCorners = sc.getInt(11);
                } else if (joined) {
                    joinScores(d, sc, memberIds, memberKm);
                }
                // A part: the scores are the whole trip's, so none is shown.
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
            for (long id : ids) {
                for (com.geely.drivemem.state.TollTracker.Passage p
                        : com.geely.drivemem.state.TollTracker.forTrip(this, id)) {
                    if (!part || (p.tsMs >= startMs && p.tsMs <= endMs)) d.tolls.add(p);
                }
            }
            if (joined) d.tolls.sort((x, y) -> Long.compare(x.tsMs, y.tsMs));
            if (endMs > 0) {
                d.parking = com.geely.drivemem.state.ParkingCost.afterTrip(this, endMs);
                d.nextTripStartMs = com.geely.drivemem.state.ParkingCost.nextTripStart(this, endMs);
            }
            events(d, joined ? memberStarts : null, joined ? memberEnds : null, startMs, endMs);
        } catch (Throwable e) {
            android.util.Log.w("DriveMem", "trip route: " + e);
        }
        runOnUiThread(() -> show(d));
    }

    /** A joined trip's scores: each trip's score weighted by its km, and the
     * events added up. Trips without a score are left out. */
    private static void joinScores(Loaded d, Cursor sc, long[] ids, double[] km) {
        double road = 0, roadW = 0, driver = 0, driverW = 0;
        int bumps = 0, brakes = 0, accels = 0, corners = 0;
        boolean anyEvent = false;
        while (sc.moveToNext()) {
            long id = sc.getLong(12);
            double w = 1;
            for (int i = 0; i < ids.length && km != null && i < km.length; i++) if (ids[i] == id) w = Math.max(0.1, km[i]);
            if (!sc.isNull(6)) { road += sc.getInt(6) * w; roadW += w; }
            if (!sc.isNull(7)) { driver += sc.getInt(7) * w; driverW += w; }
            if (!sc.isNull(8)) { bumps += sc.getInt(8); anyEvent = true; }
            if (!sc.isNull(9)) { brakes += sc.getInt(9); anyEvent = true; }
            if (!sc.isNull(10)) { accels += sc.getInt(10); anyEvent = true; }
            if (!sc.isNull(11)) { corners += sc.getInt(11); anyEvent = true; }
        }
        if (roadW > 0) d.roadScore = (int) Math.round(road / roadW);
        if (driverW > 0) d.driverScore = (int) Math.round(driver / driverW);
        if (anyEvent) {
            d.roadBumps = bumps; d.hardBrakes = brakes; d.hardAccels = accels; d.hardCorners = corners;
        }
    }

    /** The lines on the height chart: tolls, stops inside the trip, and for
     * a joined trip the stop between each two trips (a charge when one
     * started in it, else parked). */
    private void events(Loaded d, long[] starts, long[] ends, long startMs, long endMs) {
        java.util.List<int[]> ev = new java.util.ArrayList<>();   // {index, kind}
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (com.geely.drivemem.state.TollTracker.Passage p : d.tolls) {
            ev.add(new int[]{nearestPointIndex(d.ts, p.tsMs), ElevationView.EVENT_TOLL});
            labels.add(null);
        }
        d.stops = com.geely.drivemem.sensors.TripEdits.stops(d.speed, d.ts, STOP_MIN_MS);
        for (com.geely.drivemem.sensors.TripEdits.Stop st : d.stops) {
            ev.add(new int[]{st.from, ElevationView.EVENT_STOP});
            labels.add(SessionText.duration(d.ts[st.to] - d.ts[st.from]));
        }
        if (starts != null && ends != null) {
            java.util.List<long[]> charges = new java.util.ArrayList<>();
            try (Cursor c = CarDb.get(this).db().rawQuery(
                    "SELECT start_ms, end_ms FROM charge_session WHERE start_ms BETWEEN ? AND ? ORDER BY start_ms",
                    new String[]{String.valueOf(startMs), String.valueOf(endMs)})) {
                while (c.moveToNext()) charges.add(new long[]{c.getLong(0), c.isNull(1) ? 0 : c.getLong(1)});
            } catch (Throwable ignored) {}
            for (int i = 0; i + 1 < starts.length && i + 1 < ends.length; i++) {
                long gs = ends[i], ge = starts[i + 1];
                if (ge <= gs) continue;
                d.parkedMs += ge - gs;
                int at = nearestPointIndex(d.ts, gs);
                long[] ch = null;
                for (long[] x : charges) if (x[0] >= gs && x[0] <= ge) { ch = x; break; }
                if (ch != null && ch[1] > ch[0]) {
                    ev.add(new int[]{at, ElevationView.EVENT_CHARGE});
                    labels.add(SessionText.duration(ch[1] - ch[0]));
                } else {
                    ev.add(new int[]{at, ElevationView.EVENT_PARK});
                    labels.add(SessionText.duration(ge - gs));
                }
            }
        }
        int n = ev.size();
        d.evIdx = new int[n]; d.evKind = new int[n]; d.evLabel = new String[n];
        for (int i = 0; i < n; i++) {
            d.evIdx[i] = ev.get(i)[0]; d.evKind[i] = ev.get(i)[1]; d.evLabel[i] = labels.get(i);
        }
    }

    private void show(Loaded d) {
        loaded = d;
        pointKm = TripRouteStats.cumulativeKm(d.lat, d.lon);
        flatForSplit = false;
        if (d.lat.length < 2) {
            route.setVisibility(View.GONE);
            profile.setVisibility(View.GONE);
            note.setText(R.string.route_none);
            note.setVisibility(View.VISIBLE);
        } else {
            route.setChargeSpots(d.spots);
            tollPinCount = d.tolls.size();
            if (!d.tolls.isEmpty()) {
                int tn = d.tolls.size();
                double[] tLat = new double[tn], tLon = new double[tn];
                String[] tLbl = new String[tn];
                for (int i = 0; i < tn; i++) {
                    com.geely.drivemem.state.TollTracker.Passage p = d.tolls.get(i);
                    tLat[i] = p.lat; tLon[i] = p.lon;
                    tLbl[i] = p.cost != null
                        ? com.geely.drivemem.util.Prefs.currencySymbol() + String.format(Locale.getDefault(), "%.2f", p.cost)
                        : "?";
                }
                route.setTollPins(tLat, tLon, tLbl);
                route.setShowTolls(showTollsOnMap);
            } else {
                route.setTollPins(null, null, null);
            }
            refreshTollsToggle();
            route.setRoute(d.lat, d.lon, d.speed);
            double[] alt = TripRouteStats.fillGaps(d.alt);
            if (alt != null) {
                profile.setProfile(pointKm, TripRouteStats.medianSmooth(alt, HEIGHT_SMOOTH), d.speed);
                profile.setEvents(d.evIdx, d.evKind, d.evLabel);
            } else {
                profile.setVisibility(View.GONE);
            }
        }
        showStats(d);
        refreshHead();
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
        // Start → end, as the car's panel shows it (issue #210).
        String odo = SessionText.odometerSpan(in.getDoubleExtra(EXTRA_ODO_START, -1),
            in.getDoubleExtra(EXTRA_ODO_END, -1));
        if (odo != null) {
            stat(R.string.stat_odometer, Style.valueWithUnit(odo, null, "km", Style.UNIT_SCALE_ROW), null);
        }

        long total = endMs > startMs ? endMs - startMs : 0;
        String timeSub = null;
        if (hasPoints) {
            long moving = Math.min(TripRouteStats.movingMs(d.ts), total);
            long parked = Math.min(d.parkedMs, total - moving);
            timeSub = parked > 0
                ? getString(R.string.route_stat_time_split_parked, TripRouteStats.duration(moving),
                    TripRouteStats.duration(total - moving - parked), TripRouteStats.duration(parked))
                : getString(R.string.route_stat_time_split,
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
        boolean chart = !est && d != null && (d.split != null || d.fixedSplit != null);
        if (!chart) {
            String netText = String.format(Locale.US, "%.1f kWh%s", net, est ? " ~" : "");
            String netSub = !est && regen > 0 ? getString(R.string.session_stat_net_breakdown,
                String.format(Locale.US, "%.1f", spent), String.format(Locale.US, "%.1f", regen)) : null;
            stat(R.string.session_stat_net_energy, netText, netSub);
        }
        if (chart) {
            EnergySplit sp = d.fixedSplit != null ? d.fixedSplit
                : EnergySplit.of(net, d.split[0], d.split[1], d.split[2], d.split[3], d.split[4], d.split[5]);
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
        if (d != null && (d.driverScore != null || d.roadScore != null)) {
            scoreStat(d);
        }
        if (d != null && !d.tolls.isEmpty()) {
            tollsStat(d);
        }
        if (d != null && endMs > 0) roadCostsStat(d, hasPoints);
    }

    // ---- Split and undo (issue #210) ----

    private void onPick(int i) {
        route.setMarker(i);
        markedIndex = i;
        if (!splitMode || loaded == null) return;
        cutAt = com.geely.drivemem.sensors.TripEdits.snap(i, pointKm, loaded.stops, SNAP_KM);
        if (cutAt != null) {
            profile.setMarked(cutAt[0]);
            profile.setCut(cutAt[0], cutAt[1]);
            route.setMarker(cutAt[0]);
        } else {
            profile.setCut(-1, -1);
        }
        showSplitPreview();
        refreshHead();
    }

    /** The head's buttons for what this trip can do: a joined trip can be
     * undone, a part can be put back whole or split again, a recorded trip
     * with a route can be split. Split mode: Cancel and Split here. */
    private void refreshHead() {
        headActions.removeAllViews();
        android.content.Intent in = getIntent();
        long endMs = in.getLongExtra(EXTRA_END_MS, 0);
        long joinId = in.getLongExtra(EXTRA_JOIN_ID, 0);
        long cutTrip = in.getLongExtra(EXTRA_CUT_TRIP, 0);
        boolean canSplit = endMs > 0 && joinId <= 0 && loaded != null && loaded.lat.length >= 3;
        if (splitMode) {
            titleView.setText(R.string.trip_split_pick);
            headButton(0, R.string.trip_split_cancel, false, true, this::exitSplit);
            headButton(R.drawable.ic_scissors, R.string.trip_split_here, true, cutAt != null, this::commitSplit);
            return;
        }
        titleView.setText(titleText);
        if (joinId > 0) {
            headButton(R.drawable.ic_undo, R.string.trip_undo_join, false, true, () ->
                com.geely.drivemem.state.TripEditStore.undoJoin(this, joinId, this::finish));
        }
        if (cutTrip > 0) {
            headButton(R.drawable.ic_undo, R.string.trip_undo_split, false, true, () ->
                com.geely.drivemem.state.TripEditStore.undoCuts(this, cutTrip, this::finish));
        }
        if (canSplit) headButton(R.drawable.ic_scissors, R.string.trip_split, false, true, this::enterSplit);
    }

    private void headButton(int icon, int text, boolean accent, boolean enabled, Runnable onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(20);
        int fill = accent ? Style.ACCENT : Style.CARD;
        b.setTextColor(Style.onFill(fill));
        b.setTypeface(null, accent ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        b.setBackground(Style.card(fill, this, 14));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(Style.dp(this, 60));
        int ph = Style.dp(this, 24);
        b.setPadding(ph, 0, ph, 0);
        if (icon != 0) {
            android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
            int sz = Style.dp(this, 24);
            d.setBounds(0, 0, sz, sz);
            d.setTint(Style.onFill(fill));
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(Style.dp(this, 10));
        }
        b.setAlpha(enabled ? 1f : 0.4f);
        b.setEnabled(enabled);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Style.dp(this, 12);
        headActions.addView(b, lp);
    }

    private void enterSplit() {
        if (loaded == null || loaded.lat.length < 3) return;
        splitMode = true;
        cutAt = null;
        // No heights: the chart is shown flat, as the place to pick on.
        if (profile.getVisibility() != View.VISIBLE) {
            profile.setProfile(pointKm, new double[pointKm.length], loaded.speed);
            profile.setEvents(loaded.evIdx, loaded.evKind, loaded.evLabel);
            profile.setVisibility(View.VISIBLE);
            flatForSplit = true;
        }
        profile.setCut(-1, -1);
        showSplitPreview();
        refreshHead();
    }

    private void exitSplit() {
        splitMode = false;
        cutAt = null;
        profile.setCut(-1, -1);
        profile.setMarked(-1);
        if (flatForSplit) { profile.setVisibility(View.GONE); flatForSplit = false; }
        showStats(loaded);
        refreshHead();
    }

    private void commitSplit() {
        if (cutAt == null || loaded == null) return;
        long cutTrip = getIntent().getLongExtra(EXTRA_CUT_TRIP, 0);
        long trip = cutTrip > 0 ? cutTrip : getIntent().getLongExtra(EXTRA_TRIP_ID, -1);
        if (trip <= 0) return;
        com.geely.drivemem.state.TripEditStore.cut(this, trip,
            loaded.ts[cutAt[0]], loaded.ts[cutAt[1]], this::finish);
    }

    /** The right column in split mode: a hint, then the two trips the cut
     * makes (times, km, length), before anything is saved. */
    private void showSplitPreview() {
        stats.removeAllViews();
        TextView hint = new TextView(this);
        hint.setText(R.string.trip_split_hint);
        hint.setTextColor(Style.TEXT_DIM);
        hint.setTextSize(18);
        hint.setPadding(0, 0, 0, Style.dp(this, 14));
        stats.addView(hint);
        if (cutAt == null || loaded == null) return;
        android.content.Intent in = getIntent();
        long startMs = in.getLongExtra(EXTRA_START_MS, 0), endMs = in.getLongExtra(EXTRA_END_MS, 0);
        double total = pointKm.length > 0 ? pointKm[pointKm.length - 1] : 0;
        long a = loaded.ts[cutAt[0]], b = loaded.ts[cutAt[1]];
        splitPart(1, Style.ACCENT, startMs, a, pointKm[cutAt[0]]);
        splitPart(2, Style.HEAT, b, endMs, Math.max(0, total - pointKm[cutAt[1]]));
        if (b > a) {
            TextView stop = new TextView(this);
            stop.setText(getString(R.string.trip_split_stop_note, SessionText.duration(b - a)));
            stop.setTextColor(Style.TEXT_DIM);
            stop.setTextSize(17);
            stats.addView(stop);
        }
    }

    private void splitPart(int number, int color, long fromMs, long toMs, double km) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Style.dp(this, 16));
        bg.setColor((color & 0x00FFFFFF) | 0x2E000000);
        bg.setStroke(Style.dp(this, 2), color);
        box.setBackground(bg);
        int p = Style.dp(this, 16);
        box.setPadding(p, p, p, p);
        TextView name = new TextView(this);
        name.setText(getString(R.string.trip_split_part, number));
        name.setTextColor(color);
        name.setTextSize(18);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(name);
        TextView when = new TextView(this);
        when.setText(SessionText.clock(fromMs) + " – " + SessionText.clock(toMs));
        when.setTextColor(Style.TEXT);
        when.setTextSize(26);
        when.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(when);
        TextView sub = new TextView(this);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(
            Style.valueWithUnit(String.format(Locale.US, "%.1f", km), null, "km", Style.UNIT_SCALE_ROW));
        sb.append("   ");
        sb.append(SessionText.duration(toMs - fromMs));
        sub.setText(sb);
        sub.setTextColor(Style.TEXT);
        sub.setTextSize(22);
        box.addView(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Style.dp(this, 14);
        stats.addView(box, lp);
    }

    private void reload() {
        final long tripId = getIntent().getLongExtra(EXTRA_TRIP_ID, -1);
        final long startMs = getIntent().getLongExtra(EXTRA_START_MS, 0);
        final long endMs = getIntent().getLongExtra(EXTRA_END_MS, 0);
        new Thread(() -> load(tripId, startMs, endMs), "trip-route-reload").start();
    }

    /** A quiet action line ("+ Add toll"), a full-width 48 dp touch target. */
    private TextView actionRow(int text, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Style.ACCENT);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(Style.dp(this, 48));
        t.setPadding(0, Style.dp(this, 4), 0, Style.dp(this, 4));
        t.setOnClickListener(v -> onClick.run());
        stats.addView(t, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** Under the tolls: "+ Add toll" (at the place picked on the height
     * chart), the parking paid on arrival (or "+ Add parking"), and the trip's
     * road costs, tolls + parking, when either is above zero. */
    private void roadCostsStat(Loaded d, boolean hasPoints) {
        final long tripId = getIntent().getLongExtra(EXTRA_TRIP_ID, -1);
        final long endMs = getIntent().getLongExtra(EXTRA_END_MS, 0);
        // The place is picked on the height chart: no chart, no action.
        if (hasPoints && tripId > 0 && TripRouteStats.fillGaps(d.alt) != null) {
            actionRow(R.string.toll_add, () -> {
                if (markedIndex < 0 || markedIndex >= d.lat.length) {
                    android.widget.Toast.makeText(this, R.string.toll_add_pick_first,
                        android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                // A joined trip: the toll goes to the trip the picked point is on.
                long onTrip = markedIndex < d.pointTrip.length ? d.pointTrip[markedIndex] : tripId;
                TollPassageDialog.showAdd(this, onTrip, markedIndex, d.ts, d.lat, d.lon, d.speed, this::reload);
            });
        }

        final Long stopEnd = d.nextTripStartMs > endMs ? d.nextTripStartMs : null;
        if (d.parking != null) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Style.card(Style.CARD_HI, this, 12));
            int rp = Style.dp(this, 12);
            row.setPadding(rp, rp, rp, rp);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(R.string.trip_dest_parking);
            name.setTextColor(Style.TEXT);
            name.setTextSize(18);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            col.addView(name);
            // The place, else "Street" for paid street parking; a car park
            // with no name needs no second line.
            String place = d.parking.placeName != null && !d.parking.placeName.isEmpty()
                ? d.parking.placeName
                : d.parking.isStreet() ? getString(R.string.parking_kind_street) : null;
            if (place != null) {
                TextView meta = new TextView(this);
                meta.setText(place);
                meta.setTextColor(Style.TEXT_DIM);
                meta.setTextSize(15);
                col.addView(meta);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView price = new TextView(this);
            price.setText(Style.moneyWithUnit(d.parking.cost, Style.TEXT, Style.UNIT_SCALE_ROW));
            price.setTextSize(20);
            price.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(price);
            final com.geely.drivemem.state.ParkingCost.Row existing = d.parking;
            row.setOnClickListener(v -> ParkingCostDialog.open(this, existing.startMs, existing.endMs,
                null, existing, this::reload));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Style.dp(this, 12);
            stats.addView(row, rlp);
        } else {
            actionRow(R.string.trip_add_parking, () ->
                ParkingCostDialog.open(this, endMs, stopEnd, null, null, this::reload));
        }

        double tolls = 0;
        for (com.geely.drivemem.state.TollTracker.Passage p : d.tolls) if (p.cost != null && p.cost > 0) tolls += p.cost;
        double parking = d.parking != null ? d.parking.cost : 0;
        if (tolls + parking <= 0) return;
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, Style.dp(this, 14), 0, 0);
        TextView lbl = new TextView(this);
        lbl.setText(R.string.trip_road_costs);
        lbl.setTextColor(Style.TEXT_DIM);
        lbl.setTextSize(17);
        head.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tot = new TextView(this);
        tot.setText(Style.moneyWithUnit(tolls + parking, Style.TEXT, Style.UNIT_SCALE_ROW));
        tot.setTextSize(21);
        tot.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(tot);
        stats.addView(head);
    }

    /** Chronological list of toll crossings on this trip (tappable in Park to edit/dismiss). */
    private void tollsStat(Loaded d) {
        double sum = 0;
        int unpriced = 0;
        for (com.geely.drivemem.state.TollTracker.Passage p : d.tolls) {
            if (p.cost != null) sum += p.cost;
            else unpriced++;
        }
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, Style.dp(this, 14), 0, Style.dp(this, 4));
        TextView lbl = new TextView(this);
        lbl.setText(getString(R.string.toll_section_header, d.tolls.size()));
        lbl.setTextColor(Style.TEXT_DIM);
        lbl.setTextSize(17);
        head.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tot = new TextView(this);
        if (sum > 0) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(
                Style.moneyWithUnit(sum, Style.TEXT, Style.UNIT_SCALE_ROW));
            if (unpriced > 0) sb.append(" + ?");
            tot.setText(sb);
        } else {
            tot.setText("?");
        }
        tot.setTextColor(Style.TEXT);
        tot.setTextSize(21);
        tot.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(tot);
        stats.addView(head);

        for (com.geely.drivemem.state.TollTracker.Passage p : d.tolls) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Style.card(Style.CARD_HI, this, 12));
            int rp = Style.dp(this, 12);
            row.setPadding(rp, rp, rp, rp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(p.boothName);
            name.setTextColor(Style.TEXT);
            name.setTextSize(18);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            col.addView(name);
            String kind = com.geely.drivemem.sensors.TollMatcher.KIND_FREE_FLOW.equals(p.boothKind)
                ? getString(R.string.toll_kind_free_flow) : getString(R.string.toll_kind_plaza);
            TextView meta = new TextView(this);
            meta.setText(SessionText.clock(p.tsMs) + "  ·  " + kind);
            meta.setTextColor(Style.TEXT_DIM);
            meta.setTextSize(15);
            col.addView(meta);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView price = new TextView(this);
            if (p.cost != null) {
                price.setText(Style.moneyWithUnit(p.cost, Style.TEXT, Style.UNIT_SCALE_ROW));
                price.setTextColor(Style.TEXT);
            } else {
                price.setText(R.string.toll_set_price);
                price.setTextColor(Style.ACCENT);
            }
            price.setTextSize(20);
            price.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(price);

            row.setOnClickListener(v -> {
                int nearest = nearestPointIndex(d.ts, p.tsMs);
                if (nearest >= 0) { route.setMarker(nearest); markedIndex = nearest; }
                TollPassageDialog.show(this, p, d.ts, d.lat, d.lon, d.speed, this::reload);
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Style.dp(this, 8);
            stats.addView(row, rlp);
        }
    }

    private void refreshTollsToggle() {
        if (tollsToggle == null) return;
        if (tollPinCount <= 0) {
            tollsToggle.setVisibility(View.GONE);
            return;
        }
        tollsToggle.setVisibility(View.VISIBLE);
        tollsToggle.setText(getString(
            showTollsOnMap ? R.string.toll_map_show : R.string.toll_map_hide, tollPinCount));
        tollsToggle.setBackground(Style.card(showTollsOnMap ? Style.ACCENT : Style.CARD_HI, this, 16));
        tollsToggle.setTextColor(showTollsOnMap ? Style.onFill(Style.ACCENT) : Style.TEXT);
    }

    private static int nearestPointIndex(long[] ts, long targetMs) {
        if (ts == null || ts.length == 0) return -1;
        int best = 0;
        long bestDiff = Math.abs(ts[0] - targetMs);
        for (int i = 1; i < ts.length; i++) {
            long d = Math.abs(ts[i] - targetMs);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    /** Driver & Road scores side by side, color-coded, with event summary & (i) info. */
    private void scoreStat(Loaded d) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(R.string.route_stat_scores);
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
        if (d.driverScore != null) {
            row.addView(scorePart(d.driverScore, R.string.route_stat_driver_score));
        }
        if (d.roadScore != null) {
            View roadView = scorePart(d.roadScore, R.string.route_stat_road_score);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (d.driverScore != null) lp.leftMargin = Style.dp(this, 48);
            row.addView(roadView, lp);
        }
        block.addView(row);

        String events = formatScoreEvents(this, d.roadBumps, d.hardBrakes, d.hardAccels, d.hardCorners);
        if (events != null) {
            TextView sub = new TextView(this);
            sub.setText(events);
            sub.setTextColor(Style.TEXT_DIM);
            sub.setTextSize(16);
            sub.setPadding(0, Style.dp(this, 4), 0, 0);
            block.addView(sub);
        }
        block.setOnClickListener(v -> ChargeStatsView.showInfo(this,
            getString(R.string.route_stat_scores), getString(R.string.route_stat_scores_info)));
        stats.addView(block);
    }

    private View scorePart(int score, int name) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(this);
        v.setText(scoreSpan(score));
        v.setTextColor(scoreColor(score));
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

    static android.text.SpannableString scoreSpan(int score) {
        String num = String.valueOf(score);
        android.text.SpannableString s = new android.text.SpannableString(num + " /100");
        s.setSpan(new android.text.style.RelativeSizeSpan(0.60f), num.length(), s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(Style.TEXT_DIM), num.length(), s.length(), 0);
        return s;
    }

    static int scoreColor(int score) {
        if (score >= 80) return Style.GOOD;
        if (score >= 60) return Style.WARN;
        return Style.DANGER;
    }

    static String formatScoreEvents(android.content.Context ctx,
                                    Integer bumps, Integer brakes, Integer accels, Integer corners) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (bumps != null && bumps > 0) parts.add(ctx.getString(R.string.route_score_bumps, bumps));
        if (brakes != null && brakes > 0) parts.add(ctx.getString(R.string.route_score_brakes, brakes));
        if (accels != null && accels > 0) parts.add(ctx.getString(R.string.route_score_accels, accels));
        if (corners != null && corners > 0) parts.add(ctx.getString(R.string.route_score_corners, corners));
        if (parts.isEmpty()) return ctx.getString(R.string.route_score_smooth);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
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
    private void stat(int label, CharSequence value, String sub) {
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
