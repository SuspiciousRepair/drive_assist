package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.database.Cursor;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** One trip's route, drawn as lines on a plain background (issue #19).
 * Opened by tapping a finished trip card on the stats page. Trips saved
 * before routes were recorded (or with "Record trip routes" off) have no
 * points and show a short note instead. */
public class TripRouteActivity extends Activity {
    public static final String EXTRA_TRIP_ID = "trip_id";
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_END_MS = "end_ms";
    public static final String EXTRA_KM = "km";

    private RouteView route;
    private TextView note;

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
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Style.dp(this, 14);
        head.addView(t, tlp);
        root.addView(head);

        route = new RouteView(this);
        root.addView(route, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        note = new TextView(this);
        note.setTextColor(Style.TEXT_DIM);
        note.setTextSize(20);
        note.setGravity(Gravity.CENTER);
        note.setVisibility(android.view.View.GONE);
        root.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(legend());
        setContentView(root);

        final long tripId = getIntent().getLongExtra(EXTRA_TRIP_ID, -1);
        new Thread(() -> load(tripId), "trip-route").start();
    }

    // Speed colour key, one chip per band, same scale for every trip.
    private LinearLayout legend() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Style.dp(this, 12), 0, 0);
        double[] s = RouteGeometry.SPEED_STEPS;
        String[] labels = {
            String.format(Locale.US, "≤%.0f", s[0]),
            String.format(Locale.US, "%.0f–%.0f", s[0], s[1]),
            String.format(Locale.US, "%.0f–%.0f", s[1], s[2]),
            String.format(Locale.US, "%.0f–%.0f", s[2], s[3]),
            String.format(Locale.US, ">%.0f km/h", s[3])};
        for (int i = 0; i < labels.length; i++) {
            TextView sw = new TextView(this);
            sw.setText("━━ " + labels[i]);
            sw.setTextColor(RouteGeometry.SPEED_COLORS[i]);
            sw.setTextSize(20);
            sw.setPadding(Style.dp(this, 18), 0, Style.dp(this, 18), 0);
            row.addView(sw);
        }
        return row;
    }

    private void load(long tripId) {
        double[] lat = new double[0], lon = new double[0], spd = new double[0];
        try {
            Cursor c = CarDb.get(this).db().rawQuery(
                "SELECT lat, lon, COALESCE(speed_kmh, 0) FROM trip_point WHERE trip_id = ? ORDER BY ts_ms",
                new String[]{String.valueOf(tripId)});
            try {
                int n = c.getCount();
                lat = new double[n]; lon = new double[n]; spd = new double[n];
                for (int i = 0; c.moveToNext(); i++) {
                    lat[i] = c.getDouble(0); lon[i] = c.getDouble(1); spd[i] = c.getDouble(2);
                }
            } finally { c.close(); }
        } catch (Throwable t) {
            android.util.Log.w("DriveMem", "trip route: " + t);
        }
        final double[] la = lat, lo = lon, sp = spd;
        runOnUiThread(() -> {
            if (la.length < 2) {
                route.setVisibility(android.view.View.GONE);
                note.setText(R.string.route_none);
                note.setVisibility(android.view.View.VISIBLE);
            } else {
                route.setRoute(la, lo, sp);
            }
        });
    }
}
