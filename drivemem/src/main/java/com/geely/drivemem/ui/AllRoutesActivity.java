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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every recorded trip route in one picture, plus the list of those trips.
 * Touch a route or a row to select one trip: it is drawn thick over the
 * others, and a button opens its own route screen (TripRouteActivity).
 *
 * Trips without points (driven with route recording off) and Valet trips
 * are not here: the list comes from DailyStatsProvider.readAllTrips, the
 * same trips the stats page shows. */
public class AllRoutesActivity extends Activity {
    private RouteView route;
    private TextView note, title, open;
    private ListView list;
    /** Trips with a route, newest first. The picture holds them in the
     * same order, so a list row and a trip of the picture have one index. */
    private final List<DriveSession> trips = new ArrayList<>();
    private int selectedRow = -1;
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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Style.backButton(this, this::finish));
        title = Style.title(this, getString(R.string.routes_all_title));
        title.setTextSize(22);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Style.dp(this, 14);
        head.addView(title, tlp);
        root.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Left: the picture and the speed key.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        body.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        route = new RouteView(this);
        route.setOnTripPick(trip -> select(trip < trips.size() ? trip : -1, true));
        left.addView(route, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        note = new TextView(this);
        note.setTextColor(Style.TEXT_DIM);
        note.setTextSize(20);
        note.setGravity(Gravity.CENTER);
        note.setVisibility(View.GONE);
        left.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        list.setOnItemClickListener((p, v, pos, id) -> select(pos == selectedRow ? -1 : pos, false));
        right.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Style.gap(right, this, 12);
        open = Style.cardButton(this, getString(R.string.routes_all_open), true, () -> {
            if (selectedRow >= 0 && selectedRow < trips.size()) {
                startActivity(TripRouteActivity.intent(this, trips.get(selectedRow)));
            }
        });
        open.setVisibility(View.GONE);
        right.addView(open, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        new Thread(this::load, "all-routes").start();
    }

    /** What load() found. The point arrays hold one trip after the other,
     * in the order of `trips` (newest first). */
    private static final class Loaded {
        final List<DriveSession> trips = new ArrayList<>();
        double[] lat = new double[0], lon = new double[0], speed = new double[0];
        int[] starts = new int[0];
    }

    private void load() {
        Loaded d = new Loaded();
        try {
            // Finished trips by id. readAllTrips leaves Valet trips out.
            Map<Long, DriveSession> byId = new HashMap<>();
            for (DriveSession t : DailyStatsProvider.readAllTrips(this)) {
                if (t.endMs > 0 && t.tripId > 0) byId.put(t.tripId, t);
            }
            // Newest trip first; the index (trip_id, ts_ms) serves the order.
            Cursor c = CarDb.get(this).db().rawQuery(
                "SELECT trip_id, lat, lon, COALESCE(speed_kmh, 0) FROM trip_point "
              + "WHERE trip_id IS NOT NULL ORDER BY trip_id DESC, ts_ms", null);
            try {
                int n = c.getCount(), k = 0, tripCount = 0;
                double[] lat = new double[n], lon = new double[n], speed = new double[n];
                int[] starts = new int[n];
                long cur = -1;
                boolean keep = false;
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    if (id != cur) {
                        // A trip of one point is no line: take it back out.
                        if (keep && k - starts[tripCount - 1] < 2) {
                            k = starts[--tripCount];
                            d.trips.remove(d.trips.size() - 1);
                        }
                        cur = id;
                        DriveSession t = byId.get(id);
                        keep = t != null;
                        if (keep) { starts[tripCount++] = k; d.trips.add(t); }
                    }
                    if (!keep) continue;
                    lat[k] = c.getDouble(1); lon[k] = c.getDouble(2); speed[k] = c.getDouble(3);
                    k++;
                }
                if (keep && k - starts[tripCount - 1] < 2) {
                    k = starts[--tripCount];
                    d.trips.remove(d.trips.size() - 1);
                }
                d.lat = java.util.Arrays.copyOf(lat, k);
                d.lon = java.util.Arrays.copyOf(lon, k);
                d.speed = java.util.Arrays.copyOf(speed, k);
                d.starts = java.util.Arrays.copyOf(starts, tripCount);
            } finally { c.close(); }
        } catch (Throwable e) {
            android.util.Log.w("DriveMem", "all routes: " + e);
        }
        runOnUiThread(() -> show(d));
    }

    private void show(Loaded d) {
        if (isFinishing() || isDestroyed()) return;
        trips.clear();
        trips.addAll(d.trips);
        selectedRow = -1;
        rows.notifyDataSetChanged();
        if (trips.isEmpty()) {
            route.setVisibility(View.GONE);
            note.setText(R.string.routes_all_none);
            note.setVisibility(View.VISIBLE);
            return;
        }
        title.setText(getString(R.string.routes_all_title) + "  ·  "
            + getString(R.string.routes_all_count, trips.size()));
        route.setRoutes(d.lat, d.lon, d.speed, d.starts);
    }

    /** Selects a list row (-1 for none) on the list and on the picture. */
    private void select(int row, boolean scrollTo) {
        selectedRow = row;
        route.setSelected(row);
        open.setVisibility(row < 0 ? View.GONE : View.VISIBLE);
        rows.notifyDataSetChanged();
        if (scrollTo && row >= 0) list.smoothScrollToPosition(row);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return trips.size(); }
        @Override public Object getItem(int i) { return trips.get(i); }
        @Override public long getItemId(int i) { return trips.get(i).tripId; }

        @Override public View getView(int i, View old, ViewGroup parent) {
            TextView t = old instanceof TextView ? (TextView) old : new TextView(AllRoutesActivity.this);
            if (old == null) {
                int p = Style.dp(AllRoutesActivity.this, 14);
                t.setPadding(p, p, p, p);
                t.setTextSize(20);
            }
            DriveSession s = trips.get(i);
            String day = android.text.format.DateFormat.getMediumDateFormat(AllRoutesActivity.this)
                .format(new java.util.Date(s.startMs));
            t.setText(day + "  " + SessionText.clock(s.startMs) + " – " + SessionText.clock(s.endMs)
                + String.format(Locale.US, "\n%.1f km", s.distanceKm));
            boolean on = i == selectedRow;
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            t.setTextColor(on ? Style.onFill(Style.CARD_ON) : Style.TEXT);
            t.setBackground(on ? Style.card(Style.CARD_ON, AllRoutesActivity.this) : null);
            return t;
        }
    }
}
