package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.DailyStatsProvider.DriveSession;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every recorded trip route in one picture, with a height profile under
 * it and the list of those trips. The day switcher shows all days or one
 * day at a time; one day fits that day's trips to the picture.
 *
 * Touch a route or a row to select one trip: it is drawn thick over the
 * others, the profile shows that trip only, and a button opens its own
 * route screen (TripRouteActivity). Touch the profile to mark that place
 * on the picture.
 *
 * Trips without points (driven with route recording off) and Valet trips
 * are not here: the list comes from DailyStatsProvider.readAllTrips, the
 * same trips the stats page shows. */
public class AllRoutesActivity extends Activity {
    /** Optional "yyyy-MM-dd": open on that day instead of all days. */
    public static final String EXTRA_DAY = "day";

    /** One finished trip with its route. */
    private static final class Item {
        final DriveSession s;
        final RouteSet.Trip pts;
        final String day;
        Item(DriveSession s, RouteSet.Trip pts, String day) { this.s = s; this.pts = pts; this.day = day; }
    }

    private RouteView route;
    private ElevationView profile;
    private TextView note, title, dayLabel, open, prevBtn, nextBtn, allBtn;
    private ListView list;

    /** Every trip with a route, oldest first. */
    private final List<Item> all = new ArrayList<>();
    /** The days that have a route, newest first ("yyyy-MM-dd"). */
    private final List<String> days = new ArrayList<>();
    /** Index into days, or -1 for all days. */
    private int dayIdx = -1;
    /** The trips on screen, oldest first: trip t of the picture is shown.get(t).
     * The list shows them newest first, so row r is trip size - 1 - r. */
    private final List<Item> shown = new ArrayList<>();
    private RouteSet set;
    /** Every charge spot; one day shows only those near its routes. */
    private List<ChargeSpots.Spot> allSpots = new ArrayList<>();
    /** How far from a day's routes a charge spot still shows. */
    private static final double DAY_SPOT_MARGIN_M = 1000;
    private int selected = -1;
    /** Profile point -> point of the picture, for the profile on screen. */
    private int[] profPoint = new int[0];
    private final Rows rows = new Rows();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);
        Style.edgeToEdge(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Style.screenBg());
        int pad = Style.dp(this, 22);
        root.setPadding(pad, pad + Style.statusBarHeight(this), pad, pad);

        // Head: back, title, then the day switcher on the right.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Style.backButton(this, this::finish));
        title = Style.title(this, getString(R.string.routes_all_title));
        title.setTextSize(22);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Style.dp(this, 14);
        head.addView(title, tlp);

        prevBtn = navButton(getString(R.string.daily_stats_prev_day), this::olderDay);
        head.addView(prevBtn);
        dayLabel = new TextView(this);
        dayLabel.setTextColor(Style.TEXT);
        dayLabel.setTextSize(20);
        dayLabel.setTypeface(null, Typeface.BOLD);
        dayLabel.setGravity(Gravity.CENTER);
        dayLabel.setMinWidth(Style.dp(this, 240));
        int lp = Style.dp(this, 16);
        dayLabel.setPadding(lp, 0, lp, 0);
        head.addView(dayLabel);
        nextBtn = navButton(getString(R.string.daily_stats_next_day), this::newerDay);
        head.addView(nextBtn);
        allBtn = navButton(getString(R.string.routes_all_show), () -> setDay(-1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = Style.dp(this, 14);
        head.addView(allBtn, alp);
        root.addView(head);
        Style.gap(root, this, 12);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Left: the picture, the height profile and the speed key.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        body.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        route = new RouteView(this);
        route.setOnTripPick(trip -> select(trip < shown.size() ? trip : -1, true));
        left.addView(route, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        note = new TextView(this);
        note.setTextColor(Style.TEXT_DIM);
        note.setTextSize(20);
        note.setGravity(Gravity.CENTER);
        note.setVisibility(View.GONE);
        left.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        profile = new ElevationView(this);
        profile.setOnPick(i -> route.setMarker(i >= 0 && i < profPoint.length ? profPoint[i] : -1));
        left.addView(profile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 200)));
        left.addView(TripRouteActivity.legend(this));

        // Right: the trips.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        int sp = Style.dp(this, 20);
        right.setPadding(sp, sp, sp, sp);
        right.setBackground(Style.tile(this));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Style.dp(this, 460),
            ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = Style.dp(this, 20);
        body.addView(right, rlp);

        TextView hint = new TextView(this);
        hint.setText(R.string.routes_all_hint);
        hint.setTextColor(Style.TEXT_DIM);
        hint.setTextSize(18);
        right.addView(hint);
        Style.gap(right, this, 12);

        list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(rows);
        list.setOnItemClickListener((p, v, pos, id) -> {
            int trip = shown.size() - 1 - pos;
            select(trip == selected ? -1 : trip, false);
        });
        right.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Style.gap(right, this, 12);
        open = Style.cardButton(this, getString(R.string.routes_all_open), true, () -> {
            if (selected >= 0 && selected < shown.size()) {
                startActivity(TripRouteActivity.intent(this, shown.get(selected).s));
            }
        });
        open.setVisibility(View.GONE);
        right.addView(open, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateDayBar();
        new Thread(this::load, "all-routes").start();
    }

    private TextView navButton(String text, Runnable onClick) {
        TextView t = Style.cardButton(this, text, false, onClick);
        t.setTextSize(17);
        int ph = Style.dp(this, 18), pv = Style.dp(this, 12);
        t.setPadding(ph, pv, ph, pv);
        return t;
    }

    private void load() {
        List<Item> found = new ArrayList<>();
        List<ChargeSpots.Spot> spots = new ArrayList<>();
        try { spots = ChargeSpots.cluster(ChargeSpots.read(this)); }
        catch (Throwable e) { android.util.Log.w("DriveMem", "all routes charges: " + e); }
        final List<ChargeSpots.Spot> spotsF = spots;
        try {
            // Finished trips by id. readAllTrips leaves Valet trips out.
            Map<Long, DriveSession> byId = new HashMap<>();
            for (DriveSession t : DailyStatsProvider.readAllTrips(this)) {
                if (t.endMs > 0 && t.tripId > 0) byId.put(t.tripId, t);
            }
            SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Cursor c = CarDb.get(this).db().rawQuery(
                "SELECT trip_id, lat, lon, COALESCE(speed_kmh, 0), alt_m FROM trip_point "
              + "WHERE trip_id IS NOT NULL ORDER BY trip_id, ts_ms", null);
            try {
                long cur = -1;
                List<double[]> pts = new ArrayList<>();
                while (true) {
                    boolean more = c.moveToNext();
                    long id = more ? c.getLong(0) : -2;
                    if (id != cur) {
                        DriveSession s = byId.get(cur);
                        // A trip of one point is no line.
                        if (s != null && pts.size() >= 2) {
                            int n = pts.size();
                            double[] lat = new double[n], lon = new double[n], speed = new double[n], alt = new double[n];
                            for (int i = 0; i < n; i++) {
                                double[] p = pts.get(i);
                                lat[i] = p[0]; lon[i] = p[1]; speed[i] = p[2]; alt[i] = p[3];
                            }
                            found.add(new Item(s, new RouteSet.Trip(lat, lon, speed, alt),
                                dayFmt.format(new Date(s.startMs))));
                        }
                        pts.clear();
                        cur = id;
                    }
                    if (!more) break;
                    pts.add(new double[]{c.getDouble(1), c.getDouble(2), c.getDouble(3),
                        c.isNull(4) ? Double.NaN : c.getDouble(4)});
                }
            } finally { c.close(); }
            java.util.Collections.sort(found, (x, y) -> Long.compare(x.s.startMs, y.s.startMs));
        } catch (Throwable e) {
            android.util.Log.w("DriveMem", "all routes: " + e);
        }
        runOnUiThread(() -> { allSpots = spotsF; loaded(found); });
    }

    private void loaded(List<Item> found) {
        if (isFinishing() || isDestroyed()) return;
        all.clear();
        all.addAll(found);
        days.clear();
        for (int i = all.size() - 1; i >= 0; i--) {
            String d = all.get(i).day;
            if (days.isEmpty() || !days.get(days.size() - 1).equals(d)) days.add(d);
        }
        // -1 (all days) when no day was asked for, or that day has no route.
        setDay(days.indexOf(getIntent().getStringExtra(EXTRA_DAY)));
    }

    private void olderDay() { if (dayIdx < days.size() - 1) setDay(dayIdx + 1); }
    private void newerDay() { if (dayIdx > 0) setDay(dayIdx - 1); }

    /** Shows one day (index into days) or every day (-1). */
    private void setDay(int idx) {
        dayIdx = idx < 0 || idx >= days.size() ? -1 : idx;
        shown.clear();
        for (Item it : all) if (dayIdx < 0 || it.day.equals(days.get(dayIdx))) shown.add(it);
        List<RouteSet.Trip> trips = new ArrayList<>();
        for (Item it : shown) trips.add(it.pts);
        set = RouteSet.of(trips, TripRouteActivity.HEIGHT_SMOOTH);

        title.setText(getString(R.string.routes_all_title) + "  ·  "
            + getString(R.string.routes_all_count, shown.size()));
        updateDayBar();
        if (shown.isEmpty()) {
            route.setVisibility(View.GONE);
            profile.setVisibility(View.GONE);
            note.setText(R.string.routes_all_none);
            note.setVisibility(View.VISIBLE);
        } else {
            note.setVisibility(View.GONE);
            route.setVisibility(View.VISIBLE);
            route.setChargeSpots(dayIdx < 0 ? allSpots
                : ChargeSpots.inBox(allSpots, set.lat, set.lon, DAY_SPOT_MARGIN_M));
            route.setRoutes(set.lat, set.lon, set.speed, set.starts);
        }
        selected = -2;   // force select() to redraw
        select(-1, false);
        list.setSelection(0);
    }

    private void updateDayBar() {
        // One day: its date. Every day: the first and the last date.
        if (days.isEmpty()) {
            dayLabel.setText("");
        } else if (dayIdx >= 0) {
            dayLabel.setText(dayText(days.get(dayIdx)));
        } else {
            String first = days.get(days.size() - 1), last = days.get(0);
            dayLabel.setText(first.equals(last) ? dayText(last) : dayText(first) + " – " + dayText(last));
        }
        boolean any = !days.isEmpty();
        enable(prevBtn, any && dayIdx < days.size() - 1);
        enable(nextBtn, dayIdx > 0);
        // Only useful on one day: it goes back to every trip.
        allBtn.setVisibility(dayIdx >= 0 ? View.VISIBLE : View.GONE);
    }

    /** "yyyy-MM-dd" as the car's medium date, "16 de nov de 2026". */
    private String dayText(String day) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day);
            return android.text.format.DateFormat.getMediumDateFormat(this).format(d);
        } catch (java.text.ParseException e) {
            return day;
        }
    }

    private static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    /** Selects a trip of the picture (-1 for none) on the picture, the
     * profile and the list. */
    private void select(int trip, boolean scrollTo) {
        if (trip == selected) return;
        selected = trip;
        route.setSelected(trip);
        route.setMarker(-1);
        open.setVisibility(trip < 0 ? View.GONE : View.VISIBLE);
        showProfile();
        rows.notifyDataSetChanged();
        if (scrollTo && trip >= 0) list.smoothScrollToPosition(shown.size() - 1 - trip);
    }

    /** The profile of the selected trip, or of every trip on screen one
     * after the other. */
    private void showProfile() {
        if (set == null) return;
        int from = 0, to = set.point.length;   // profile points [from, to)
        if (selected >= 0) {
            int a = set.starts[selected];
            int b = selected + 1 < set.starts.length ? set.starts[selected + 1] : set.lat.length;
            from = to = 0;
            while (from < set.point.length && set.point[from] < a) from++;
            to = from;
            while (to < set.point.length && set.point[to] < b) to++;
        }
        if (to - from < 2) {
            profPoint = new int[0];
            profile.setVisibility(View.GONE);
            return;
        }
        double[] km = java.util.Arrays.copyOfRange(set.profKm, from, to);
        double base = km[0];
        for (int i = 0; i < km.length; i++) km[i] -= base;
        profPoint = java.util.Arrays.copyOfRange(set.point, from, to);
        profile.setVisibility(View.VISIBLE);
        profile.setProfile(km, java.util.Arrays.copyOfRange(set.profAlt, from, to),
            java.util.Arrays.copyOfRange(set.profSpeed, from, to));
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int r) { return shown.get(shown.size() - 1 - r); }
        @Override public long getItemId(int r) { return shown.get(shown.size() - 1 - r).s.tripId; }

        @Override public View getView(int r, View old, ViewGroup parent) {
            TextView t = old instanceof TextView ? (TextView) old : new TextView(AllRoutesActivity.this);
            if (old == null) {
                int p = Style.dp(AllRoutesActivity.this, 14);
                t.setPadding(p, p, p, p);
                t.setTextSize(20);
            }
            int trip = shown.size() - 1 - r;
            DriveSession s = shown.get(trip).s;
            String day = android.text.format.DateFormat.getMediumDateFormat(AllRoutesActivity.this)
                .format(new Date(s.startMs));
            t.setText(day + "  " + SessionText.clock(s.startMs) + " – " + SessionText.clock(s.endMs)
                + String.format(Locale.US, "\n%.1f km", s.distanceKm));
            boolean on = trip == selected;
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            t.setTextColor(on ? Style.onFill(Style.CARD_ON) : Style.TEXT);
            t.setBackground(on ? Style.card(Style.CARD_ON, AllRoutesActivity.this) : null);
            return t;
        }
    }
}
