package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One Valet interval in detail, opened from its card on the stats page.
 *
 * Left: where the car went (every move on one map, a green dot where the
 * first move started and a checkered one where the last ended, so many
 * moves do not crowd the map with marks), a timeline of the whole interval
 * with each move where it happened, and the dashcam clips recorded in that
 * time. Right: the numbers.
 *
 * The moves are the trip rows inside the interval (the same rule as
 * DailyStatsProvider.TRIP_IN_VALET_SQL). Their route points are drawn as
 * one line: the car does not jump, each move starts where the last ended.
 * Gear changes are not shown: the samples only see the gear now and then,
 * so a count of reverses would be a guess. */
public class ValetDetailActivity extends Activity {
    public static final String EXTRA_VALET_ID = "valet_id";

    static Intent intent(android.content.Context c, DailyStatsProvider.ValetSessionItem v) {
        return new Intent(c, ValetDetailActivity.class).putExtra(EXTRA_VALET_ID, v.valetId);
    }

    private RouteView route;
    private TextView note;
    private ValetTimelineView timeline;
    private LinearLayout clipRow;
    private HorizontalScrollView clipScroll;
    private TextView clipTitle;
    private LinearLayout stats;
    private TextView title;

    /** What load() found. */
    static final class Loaded {
        long vs, ve;
        boolean open;
        Double startOdo, endOdo, maxSpeed, maxPower, tempMin, tempMax;
        Integer startSoc, endSoc;
        long[] moveStart = new long[0], moveEnd = new long[0];
        double[] lat = new double[0], lon = new double[0], speed = new double[0];
        List<Clips.Clip> clips = new ArrayList<>();
        List<Bitmap> thumbs = new ArrayList<>();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);
        Style.edgeToEdge(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Style.screenBg());
        int pad = Style.dp(this, 22);
        root.setPadding(pad, pad + Style.statusBarHeight(this), pad, pad);

        title = Style.title(this, getString(R.string.valet_short));
        title.setTextSize(22);
        root.addView(Style.headerRow(this, Style.backButton(this, this::finish), title));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        route = new RouteView(this);
        left.addView(route, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        note = new TextView(this);
        note.setTextColor(Style.TEXT_DIM);
        note.setTextSize(20);
        note.setGravity(Gravity.CENTER);
        note.setVisibility(View.GONE);
        left.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        timeline = new ValetTimelineView(this);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 64));
        tl.topMargin = Style.dp(this, 14);
        left.addView(timeline, tl);

        clipTitle = new TextView(this);
        clipTitle.setText(R.string.clips_title);
        clipTitle.setTextColor(Style.TEXT_DIM);
        clipTitle.setTextSize(17);
        clipTitle.setVisibility(View.GONE);
        left.addView(clipTitle);
        clipScroll = new HorizontalScrollView(this);
        clipScroll.setHorizontalScrollBarEnabled(false);
        clipScroll.setVisibility(View.GONE);
        clipRow = new LinearLayout(this);
        clipRow.setOrientation(LinearLayout.HORIZONTAL);
        clipScroll.addView(clipRow);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Style.dp(this, 6);
        left.addView(clipScroll, cl);

        stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        int sp = Style.dp(this, 20);
        stats.setPadding(sp, sp, sp, sp);
        stats.setBackground(Style.tile(this));
        stats.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(this, 460),
            ViewGroup.LayoutParams.MATCH_PARENT));
        Style.addMirrored(body, 20, left, stats);

        setContentView(root);

        final long id = getIntent().getLongExtra(EXTRA_VALET_ID, -1);
        new Thread(() -> {
            Loaded d = load(id);
            runOnUiThread(() -> show(d));
        }, "valet-detail").start();
    }

    private Loaded load(long id) {
        Loaded d = new Loaded();
        try {
            android.database.sqlite.SQLiteDatabase db = CarDb.get(this).db();
            Cursor v = db.rawQuery("SELECT start_ms, end_ms, start_odo_km, end_odo_km, start_soc, end_soc, "
                + "max_speed_kmh, max_power_kw FROM valet_session WHERE id = ?", new String[]{String.valueOf(id)});
            try {
                if (!v.moveToFirst()) return d;
                d.vs = v.getLong(0);
                d.open = v.isNull(1);
                d.ve = d.open ? System.currentTimeMillis() : v.getLong(1);
                d.startOdo = v.isNull(2) ? null : v.getDouble(2);
                d.endOdo = v.isNull(3) ? null : v.getDouble(3);
                d.startSoc = v.isNull(4) ? null : v.getInt(4);
                d.endSoc = v.isNull(5) ? null : v.getInt(5);
                d.maxSpeed = v.isNull(6) ? null : v.getDouble(6);
                d.maxPower = v.isNull(7) ? null : v.getDouble(7);
            } finally { v.close(); }

            String[] win = {String.valueOf(d.vs), String.valueOf(d.ve)};
            List<Long> ids = new ArrayList<>();
            Cursor t = db.rawQuery("SELECT id, start_ms, COALESCE(end_ms, start_ms) FROM trip "
                + "WHERE start_ms >= ? AND start_ms < ? ORDER BY start_ms", win);
            try {
                int n = t.getCount();
                d.moveStart = new long[n]; d.moveEnd = new long[n];
                for (int i = 0; t.moveToNext(); i++) {
                    ids.add(t.getLong(0));
                    d.moveStart[i] = t.getLong(1);
                    d.moveEnd[i] = t.getLong(2);
                }
            } finally { t.close(); }

            if (!ids.isEmpty()) {
                StringBuilder in = new StringBuilder();
                for (Long x : ids) in.append(in.length() == 0 ? "" : ",").append(x);
                Cursor p = db.rawQuery("SELECT lat, lon, COALESCE(speed_kmh, 0) FROM trip_point WHERE trip_id IN ("
                    + in + ") ORDER BY ts_ms", null);
                try {
                    int n = p.getCount();
                    d.lat = new double[n]; d.lon = new double[n]; d.speed = new double[n];
                    for (int i = 0; p.moveToNext(); i++) {
                        d.lat[i] = p.getDouble(0); d.lon[i] = p.getDouble(1); d.speed[i] = p.getDouble(2);
                    }
                } finally { p.close(); }
            }

            Cursor tc = db.rawQuery("SELECT MIN(outside_temp_c), MAX(outside_temp_c) FROM telemetry_sample "
                + "WHERE ts_ms BETWEEN ? AND ? AND outside_temp_c IS NOT NULL", win);
            try {
                if (tc.moveToFirst() && !tc.isNull(0)) { d.tempMin = tc.getDouble(0); d.tempMax = tc.getDouble(1); }
            } finally { tc.close(); }
        } catch (Throwable e) {
            android.util.Log.w("DriveMem", "valet detail: " + e);
        }

        // Dashcam clips that overlap the interval, oldest first.
        try {
            for (Clips.Clip c : Clips.list(this)) {
                if (!c.playable() || !ValetDetail.clipInWindow(c.whenMs, c.seconds, d.vs, d.ve)) continue;
                d.clips.add(c);
            }
            d.clips.sort((a, b) -> Long.compare(a.whenMs, b.whenMs));
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            for (Clips.Clip c : d.clips) {
                d.thumbs.add(c.thumb.exists() ? android.graphics.BitmapFactory.decodeFile(c.thumb.getAbsolutePath(), o) : null);
            }
        } catch (Throwable e) {
            android.util.Log.w("DriveMem", "valet clips: " + e);
        }
        return d;
    }

    private void show(Loaded d) {
        if (d.ve <= 0) { finish(); return; }
        double km = d.startOdo != null && d.endOdo != null && d.endOdo >= d.startOdo ? d.endOdo - d.startOdo : 0;
        String when = android.text.format.DateFormat.getMediumDateFormat(this).format(new java.util.Date(d.vs))
            + "  " + SessionText.clock(d.vs) + (d.open ? "" : " – " + SessionText.clock(d.ve))
            + String.format(Locale.US, "  ·  %.1f km", km);
        title.setText(getString(R.string.valet_short) + "  ·  " + when);

        if (d.lat.length >= 2) {
            route.setRoute(d.lat, d.lon, d.speed);
        } else {
            route.setVisibility(View.GONE);
            note.setText(R.string.valet_route_none);
            note.setVisibility(View.VISIBLE);
        }
        timeline.setData(d.vs, d.ve, d.moveStart, d.moveEnd);
        showClips(d);
        showStats(d, km);
    }

    private void showClips(Loaded d) {
        if (d.clips.isEmpty()) return;
        clipTitle.setVisibility(View.VISIBLE);
        clipScroll.setVisibility(View.VISIBLE);
        int w = Style.dp(this, 240), h = Style.dp(this, 135), gap = Style.dp(this, 14);
        for (int i = 0; i < d.clips.size(); i++) {
            final Clips.Clip c = d.clips.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            ImageView shot = new ImageView(this);
            shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
            shot.setBackgroundColor(Style.CARD_HI);
            Bitmap bm = d.thumbs.get(i);
            if (bm != null) shot.setImageBitmap(bm);
            shot.setClipToOutline(true);
            final float radius = Style.dp(this, Style.RADIUS_DP);
            shot.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            cell.addView(shot, new LinearLayout.LayoutParams(w, h));
            TextView t = new TextView(this);
            String len = c.seconds > 0 ? String.format(Locale.US, "  ·  %d:%02d", c.seconds / 60, c.seconds % 60) : "";
            t.setText(SessionText.clock(c.whenMs) + len);
            t.setTextColor(Style.TEXT);
            t.setTextSize(18);
            t.setPadding(0, Style.dp(this, 6), 0, 0);
            cell.addView(t);
            cell.setOnClickListener(v -> startActivity(new Intent(this, ClipPlayerActivity.class)
                .putExtra(ClipPlayerActivity.EXTRA_PATH, c.mp4.getAbsolutePath())));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = gap;
            clipRow.addView(cell, lp);
        }
    }

    private void showStats(Loaded d, double km) {
        stats.removeAllViews();
        stat(R.string.valet_stat_moves, String.valueOf(d.moveStart.length), null, Style.TEXT);
        long total = d.ve - d.vs;
        long moving = ValetDetail.movingMs(d.moveStart, d.moveEnd, d.vs, d.ve);
        stat(R.string.route_stat_time, TripRouteStats.duration(total),
            getString(R.string.route_stat_time_split, TripRouteStats.duration(moving),
                TripRouteStats.duration(total - moving)), Style.TEXT);
        stat(R.string.route_stat_distance, String.format(Locale.US, "%.1f km", km), null, Style.TEXT);
        if (d.maxSpeed != null) {
            stat(R.string.session_stat_max_speed, String.format(Locale.US, "%.0f km/h", d.maxSpeed), null,
                ValetDetail.fast(d.maxSpeed) ? Style.DANGER : Style.TEXT);
        }
        if (d.maxPower != null) {
            stat(R.string.session_stat_max_power, String.format(Locale.US, "%.0f kW", d.maxPower), null, Style.TEXT);
        }
        if (d.startSoc != null) {
            stat(R.string.session_stat_battery, d.startSoc + "%"
                + (d.endSoc != null ? " → " + d.endSoc + "%" : ""), null, Style.TEXT);
        }
        if (d.tempMin != null) {
            stat(R.string.route_stat_outside, Math.round(d.tempMin) == Math.round(d.tempMax)
                ? String.format(Locale.US, "%.0f °C", d.tempMin)
                : String.format(Locale.US, "%.0f – %.0f °C", d.tempMin, d.tempMax), null, Style.TEXT);
        }
    }

    // One number: a small dim label, the value, and an optional dim line under it.
    private void stat(int label, String value, String sub, int color) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Style.TEXT_DIM);
        l.setTextSize(17);
        l.setPadding(0, Style.dp(this, 10), 0, 0);
        stats.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
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
