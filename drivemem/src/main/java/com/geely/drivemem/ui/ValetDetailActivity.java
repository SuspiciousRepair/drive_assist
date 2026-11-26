package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.DriveScorer;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One Valet interval in detail, opened from its card on the stats page.
 *
 * Left: route map with amber bump strike pins (tappable to jump into the
 * dashcam video 3 s before the hit), timeline with matching bump ticks, and
 * overlapping dashcam clips. Right: Driver & Road scores at the top (neutral
 * color on Road so bad pavement doesn't look like the valet's fault), four
 * fixed event slots, then speed/power/moves/time/distance/battery/outside. */
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
        Integer roadScore, driverScore, roadBumps, hardBrakes, hardAccels, hardCorners;
        long[] moveStart = new long[0], moveEnd = new long[0];
        double[] lat = new double[0], lon = new double[0], speed = new double[0];
        double[] bumpLat = new double[0], bumpLon = new double[0];
        long[] bumpTs = new long[0];
        double[] bumpPeak = new double[0];
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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Style.backButton(this, this::finish));
        title = Style.title(this, getString(R.string.valet_short));
        title.setTextSize(22);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Style.dp(this, 14);
        head.addView(title, tlp);
        root.addView(head);

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

        ScrollView statsScroll = new ScrollView(this);
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
                + "max_speed_kmh, max_power_kw, road_score, driver_score, road_bumps, hard_brakes, hard_accels, hard_corners "
                + "FROM valet_session WHERE id = ?", new String[]{String.valueOf(id)});
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
                d.roadScore = v.isNull(8) ? null : v.getInt(8);
                d.driverScore = v.isNull(9) ? null : v.getInt(9);
                d.roadBumps = v.isNull(10) ? null : v.getInt(10);
                d.hardBrakes = v.isNull(11) ? null : v.getInt(11);
                d.hardAccels = v.isNull(12) ? null : v.getInt(12);
                d.hardCorners = v.isNull(13) ? null : v.getInt(13);
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
                Cursor p = db.rawQuery(
                    "SELECT lat, lon, COALESCE(speed_kmh, 0), ts_ms, road_peak_z "
                  + "FROM trip_point WHERE trip_id IN (" + in + ") ORDER BY ts_ms", null);
                try {
                    int n = p.getCount();
                    d.lat = new double[n]; d.lon = new double[n]; d.speed = new double[n];
                    List<Double> bLat = new ArrayList<>(), bLon = new ArrayList<>(), bPeak = new ArrayList<>();
                    List<Long> bTs = new ArrayList<>();
                    for (int i = 0; p.moveToNext(); i++) {
                        double la = p.getDouble(0), lo = p.getDouble(1);
                        d.lat[i] = la; d.lon[i] = lo; d.speed[i] = p.getDouble(2);
                        long ts = p.getLong(3);
                        if (!p.isNull(4)) {
                            double peak = Math.abs(p.getDouble(4));
                            if (peak > DriveScorer.BUMP_PEAK_Z) {
                                bLat.add(la); bLon.add(lo); bTs.add(ts); bPeak.add(peak);
                            }
                        }
                    }
                    int bn = bTs.size();
                    d.bumpLat = new double[bn]; d.bumpLon = new double[bn];
                    d.bumpTs = new long[bn]; d.bumpPeak = new double[bn];
                    for (int i = 0; i < bn; i++) {
                        d.bumpLat[i] = bLat.get(i); d.bumpLon[i] = bLon.get(i);
                        d.bumpTs[i] = bTs.get(i); d.bumpPeak[i] = bPeak.get(i);
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
            if (d.bumpLat.length > 0) {
                route.setBumpMarkers(d.bumpLat, d.bumpLon);
                route.setOnBumpPick(idx -> openBumpVideo(d, idx));
            }
        } else {
            route.setVisibility(View.GONE);
            note.setText(R.string.valet_route_none);
            note.setVisibility(View.VISIBLE);
        }
        timeline.setData(d.vs, d.ve, d.moveStart, d.moveEnd);
        if (d.bumpTs.length > 0) {
            timeline.setTicks(d.bumpTs);
            timeline.setOnTickPick(idx -> openBumpVideo(d, idx));
        }
        showClips(d);
        showStats(d, km);
    }

    private void openBumpVideo(Loaded d, int bumpIdx) {
        if (bumpIdx < 0 || bumpIdx >= d.bumpTs.length) return;
        int[] match = findClipForBump(d, d.bumpTs[bumpIdx]);
        if (match == null) {
            Toast.makeText(this, R.string.valet_bump_no_clip, Toast.LENGTH_SHORT).show();
            return;
        }
        Clips.Clip c = d.clips.get(match[0]);
        startActivity(new Intent(this, ClipPlayerActivity.class)
            .putExtra(ClipPlayerActivity.EXTRA_PATH, c.mp4.getAbsolutePath())
            .putExtra(ClipPlayerActivity.EXTRA_SEEK_MS, match[1]));
    }

    private int[] findClipForBump(Loaded d, long eventMs) {
        if (d.clips.isEmpty()) return null;
        int n = d.clips.size();
        long[] starts = new long[n];
        int[] secs = new int[n];
        for (int i = 0; i < n; i++) {
            starts[i] = d.clips.get(i).whenMs;
            secs[i] = d.clips.get(i).seconds;
        }
        return ValetDetail.findClipForTime(eventMs, starts, secs);
    }

    /** Returns the index of the strongest bump that has a covering dashcam clip, or -1. */
    private int bestVideoBumpIndex(Loaded d) {
        int best = -1;
        double bestPeak = -1;
        for (int i = 0; i < d.bumpTs.length; i++) {
            if (d.bumpPeak[i] > bestPeak && findClipForBump(d, d.bumpTs[i]) != null) {
                bestPeak = d.bumpPeak[i];
                best = i;
            }
        }
        return best;
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
        // 1. Scores lead the right column (owner's primary question after Valet).
        stats.addView(scoreBlock(d));
        addDivider(stats, 16, 6);

        // 2. Speed & power immediately under scores.
        if (d.maxSpeed != null) {
            int col = ValetDetail.fast(d.maxSpeed) ? Style.DANGER : Style.TEXT;
            statSpan(R.string.session_stat_max_speed, Style.valueWithUnit(
                String.format(Locale.US, "%.0f", d.maxSpeed), col, "km/h", Style.UNIT_SCALE_ROW), null, col);
        }
        if (d.maxPower != null) {
            statSpan(R.string.session_stat_max_power, Style.valueWithUnit(
                String.format(Locale.US, "%.0f", d.maxPower), Style.TEXT, "kW", Style.UNIT_SCALE_ROW), null, Style.TEXT);
        }

        // 3. Context: moves, time, distance, battery, outside.
        statSpan(R.string.valet_stat_moves, String.valueOf(d.moveStart.length), null, Style.TEXT);
        long total = d.ve - d.vs;
        long moving = ValetDetail.movingMs(d.moveStart, d.moveEnd, d.vs, d.ve);
        statSpan(R.string.route_stat_time, TripRouteStats.duration(total),
            getString(R.string.route_stat_time_split, TripRouteStats.duration(moving),
                TripRouteStats.duration(total - moving)), Style.TEXT);
        statSpan(R.string.route_stat_distance, Style.valueWithUnit(
            String.format(Locale.US, "%.1f", km), Style.TEXT, "km", Style.UNIT_SCALE_ROW), null, Style.TEXT);
        if (d.startSoc != null) {
            CharSequence soc = d.endSoc != null
                ? Style.percentRange(d.startSoc, d.endSoc, Style.UNIT_SCALE_ROW)
                : Style.valueWithUnit(String.valueOf(d.startSoc), Style.TEXT, "%", Style.UNIT_SCALE_ROW);
            statSpan(R.string.session_stat_battery, soc, null, Style.TEXT);
        }
        if (d.tempMin != null) {
            String tNum = Math.round(d.tempMin) == Math.round(d.tempMax)
                ? String.format(Locale.US, "%.0f", d.tempMin)
                : String.format(Locale.US, "%.0f – %.0f", d.tempMin, d.tempMax);
            statSpan(R.string.route_stat_outside,
                Style.valueWithUnit(tNum, Style.TEXT, "°C", Style.UNIT_SCALE_ROW), null, Style.TEXT);
        }
    }

    /** Hero Driver & Road scores (44sp + 6dp bar) and four fixed event slots. */
    private View scoreBlock(Loaded d) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(R.string.route_stat_scores);
        lbl.setTextColor(Style.TEXT_DIM);
        lbl.setTextSize(17);
        Drawable info = getDrawable(R.drawable.ic_info_outline).mutate();
        info.setTint(Style.TEXT_DIM);
        int isz = Style.dp(this, 20);
        info.setBounds(0, 0, isz, isz);
        lbl.setCompoundDrawablesRelative(null, null, info, null);
        lbl.setCompoundDrawablePadding(Style.dp(this, 8));
        lbl.setOnClickListener(v -> ChargeStatsView.showInfo(this,
            getString(R.string.route_stat_scores), getString(R.string.valet_scores_info)));
        block.addView(lbl);

        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams drvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pair.addView(heroScore(d.driverScore, R.string.route_stat_driver_score, true), drvLp);
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rdLp.leftMargin = Style.dp(this, 36);
        pair.addView(heroScore(d.roadScore, R.string.route_stat_road_score, false), rdLp);
        block.addView(pair);

        if (d.driverScore == null && d.roadScore == null) return block;

        addDivider(block, 14, 12);

        int bumps = d.roadBumps != null ? d.roadBumps : 0;
        int brakes = d.hardBrakes != null ? d.hardBrakes : 0;
        int accels = d.hardAccels != null ? d.hardAccels : 0;
        int corners = d.hardCorners != null ? d.hardCorners : 0;

        if (bumps == 0 && brakes == 0 && accels == 0 && corners == 0) {
            LinearLayout calm = new LinearLayout(this);
            calm.setOrientation(LinearLayout.HORIZONTAL);
            calm.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check_circle);
            ck.setColorFilter(Style.GOOD);
            calm.addView(ck, new LinearLayout.LayoutParams(Style.dp(this, 22), Style.dp(this, 22)));
            TextView tv = new TextView(this);
            tv.setText(R.string.route_score_smooth);
            tv.setTextColor(Style.GOOD);
            tv.setTextSize(18);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setPadding(Style.dp(this, 8), 0, 0, 0);
            calm.addView(tv);
            block.addView(calm);
        } else {
            int videoBump = bestVideoBumpIndex(d);
            LinearLayout evRow = new LinearLayout(this);
            evRow.setOrientation(LinearLayout.HORIZONTAL);
            View bumpCell = eventCell(R.drawable.ic_score_bump, bumps, R.string.score_word_bumps,
                bumps > 0 ? Style.TEXT : Style.TEXT_DIM, videoBump >= 0);
            if (videoBump >= 0) {
                final int hit = videoBump;
                bumpCell.setOnClickListener(v -> openBumpVideo(d, hit));
            }
            evRow.addView(bumpCell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            evRow.addView(eventCell(R.drawable.ic_score_brake, brakes, R.string.score_word_brakes,
                brakes > 0 ? Style.WARN : Style.TEXT_DIM, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            evRow.addView(eventCell(R.drawable.ic_score_launch, accels, R.string.score_word_launches,
                accels > 0 ? Style.WARN : Style.TEXT_DIM, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            evRow.addView(eventCell(R.drawable.ic_score_turn, corners, R.string.score_word_turns,
                corners > 0 ? Style.WARN : Style.TEXT_DIM, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            block.addView(evRow);
        }
        return block;
    }

    private View heroScore(Integer score, int labelRes, boolean semanticColor) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        int numColor = score == null ? Style.TEXT_DIM
            : (semanticColor ? TripRouteActivity.scoreColor(score) : Style.TEXT);
        int barColor = score == null ? Style.CARD_HI
            : (semanticColor ? TripRouteActivity.scoreColor(score) : Style.TEXT_DIM);

        TextView num = new TextView(this);
        num.setText(score == null ? "—"
            : Style.valueWithUnit(String.valueOf(score), numColor, "/100", Style.UNIT_SCALE_HERO));
        num.setTextColor(numColor);
        num.setTextSize(42);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(num);

        // 6dp progress bar (ClipDrawable over Style.CARD_HI track).
        View bar = new View(this);
        float rad = Style.dp(this, 3);
        GradientDrawable track = new GradientDrawable();
        track.setColor(Style.CARD_HI);
        track.setCornerRadius(rad);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(barColor);
        fill.setCornerRadius(rad);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        clip.setLevel(score == null ? 0 : Math.max(0, Math.min(100, score)) * 100);
        bar.setBackground(new LayerDrawable(new Drawable[]{track, clip}));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 6));
        blp.topMargin = Style.dp(this, 4);
        blp.bottomMargin = Style.dp(this, 5);
        col.addView(bar, blp);

        TextView lbl = new TextView(this);
        lbl.setText(labelRes);
        lbl.setTextColor(Style.TEXT_DIM);
        lbl.setTextSize(16);
        col.addView(lbl);
        return col;
    }

    private View eventCell(int iconRes, int count, int wordRes, int color, boolean hasVideo) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(color);
        top.addView(ic, new LinearLayout.LayoutParams(Style.dp(this, 22), Style.dp(this, 22)));
        TextView n = new TextView(this);
        n.setText(hasVideo ? count + " ▶" : String.valueOf(count));
        n.setTextColor(hasVideo ? Style.WARN : color);
        n.setTextSize(22);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setPadding(Style.dp(this, 6), 0, 0, 0);
        top.addView(n);
        col.addView(top);

        TextView w = new TextView(this);
        w.setText(wordRes);
        w.setTextColor(hasVideo ? Style.WARN : Style.TEXT_DIM);
        w.setTextSize(14);
        w.setPadding(0, Style.dp(this, 2), 0, 0);
        col.addView(w);
        return col;
    }

    private void addDivider(LinearLayout parent, int topDp, int bottomDp) {
        View line = new View(this);
        line.setBackgroundColor(Style.CARD_HI);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 1));
        lp.topMargin = Style.dp(this, topDp);
        lp.bottomMargin = Style.dp(this, bottomDp);
        parent.addView(line, lp);
    }

    private void statSpan(int label, CharSequence value, String sub, int color) {
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
        v.setTypeface(Typeface.DEFAULT_BOLD);
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
