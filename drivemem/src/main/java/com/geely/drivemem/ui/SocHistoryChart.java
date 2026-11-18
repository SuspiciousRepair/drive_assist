package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.DailyStatsProvider;
import com.geely.drivemem.sensors.SocHistory;
import com.geely.drivemem.util.Style;

import java.text.DateFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The charge page's battery chart: state of charge over the selected period,
 * with the zone under SocHistory.LOW_PCT shaded, so how often the pack goes
 * low reads at a glance. Replaces the Energy Balance bars (owner, 2026-11-18).
 *
 * 30 days: the real curve (one min/max per 30 minutes, from the samples).
 * 12 months: one min..max bar per day (from daily_stat; the samples are only
 * kept ~90 days). Whatever falls under the floor is drawn in the danger
 * colour. Reads in the background; the card shows the last data meanwhile.
 */
public final class SocHistoryChart extends LinearLayout {

    private static final long BUCKET_MS = 30 * 60 * 1000L;
    private static final java.util.concurrent.ExecutorService LOADER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "soc-history");
            t.setDaemon(true);
            return t;
        });

    private final TextView summary;
    private final Plot plot;
    private int loadGen;

    public SocHistoryChart(Context c) {
        super(c);
        setOrientation(VERTICAL);
        int pad = Style.dp(c, 16);
        setPadding(pad, pad, pad, Style.dp(c, 10));
        setBackground(Style.card(Style.cardFillColor(), c));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        TextView title = Style.label(c, c.getString(R.string.soc_history_title));
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        summary = Style.label(c, "");
        summary.setTextSize(14);
        head.addView(summary);
        addView(head);

        plot = new Plot(c);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, Style.dp(c, 230));
        lp.topMargin = Style.dp(c, 10);
        addView(plot, lp);
    }

    /** Loads and shows the last `days` days (30 = the curve, more = daily bars). */
    public void show(int days) {
        final Context c = getContext().getApplicationContext();
        final int gen = ++loadGen;
        LOADER.execute(() -> {
            LocalDate today = LocalDate.parse(DailyStatsProvider.todayDateStr());
            LocalDate from = today.minusDays(days - 1);
            ZoneId zone = ZoneId.systemDefault();
            long fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli();
            long toMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            List<SocHistory.DayRange> ranges = SocHistory.dailyRanges(c, from, today);
            List<SocHistory.Bucket> trace = days <= 31
                ? SocHistory.trace(c, fromMs, toMs, BUCKET_MS) : null;
            post(() -> {
                if (gen != loadGen) return;
                plot.set(from, today, fromMs, toMs, ranges, trace);
                summary.setText(summaryText(ranges));
            });
        });
    }

    private String summaryText(List<SocHistory.DayRange> ranges) {
        Context c = getContext();
        SocHistory.DayRange low = SocHistory.lowest(ranges);
        if (low == null) return c.getString(R.string.soc_history_empty);
        int n = SocHistory.daysBelow(ranges, SocHistory.LOW_PCT);
        String when = low.date.format(DateTimeFormatter.ofPattern("d/M"));
        return n == 0
            ? c.getString(R.string.soc_history_none, SocHistory.LOW_PCT, low.min, when)
            : c.getResources().getQuantityString(R.plurals.soc_history_below, n,
                n, SocHistory.LOW_PCT, low.min, when);
    }

    private static final class Plot extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String[] months = new String[12];

        private LocalDate from, today;
        private long fromMs, toMs;
        private List<SocHistory.DayRange> ranges = new ArrayList<>();
        private List<SocHistory.Bucket> trace;

        // layout
        private float left, right, top, bottom;

        Plot(Context c) {
            super(c);
            String[] m = DateFormatSymbols.getInstance(Locale.getDefault()).getShortMonths();
            for (int i = 0; i < 12; i++) months[i] = m[i].replace(".", "").toLowerCase(Locale.getDefault());
            text.setTextSize(Style.dp(c, 13));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(LocalDate from, LocalDate today, long fromMs, long toMs,
                 List<SocHistory.DayRange> ranges, List<SocHistory.Bucket> trace) {
            this.from = from; this.today = today; this.fromMs = fromMs; this.toMs = toMs;
            this.ranges = ranges; this.trace = trace;
            invalidate();
        }

        private float y(double pct) { return bottom - (float) (pct / 100.0) * (bottom - top); }
        private float xMs(long ms) { return left + (float) (ms - fromMs) / (toMs - fromMs) * (right - left); }

        @Override
        protected void onDraw(Canvas cv) {
            if (from == null) return;
            Context c = getContext();
            left = Style.dp(c, 40);
            right = getWidth() - Style.dp(c, 4);
            top = Style.dp(c, 8);
            bottom = getHeight() - Style.dp(c, 24);
            float yLow = y(SocHistory.LOW_PCT);

            // the low zone and the grid
            fill.setColor((Style.DANGER & 0x00FFFFFF) | 0x1C000000);
            cv.drawRect(left, yLow, right, bottom, fill);
            line.setPathEffect(null);
            line.setStrokeWidth(Style.dp(c, 1));
            line.setColor(Style.blend(Style.CARD, Style.TEXT_DIM, 0.25f));
            for (int pct : new int[]{50, 100}) cv.drawLine(left, y(pct), right, y(pct), line);
            cv.drawLine(left, bottom, right, bottom, line);
            line.setColor((Style.DANGER & 0x00FFFFFF) | 0xB0000000);
            line.setPathEffect(new DashPathEffect(new float[]{Style.dp(c, 6), Style.dp(c, 5)}, 0));
            cv.drawLine(left, yLow, right, yLow, line);
            line.setPathEffect(null);

            text.setTextAlign(Paint.Align.RIGHT);
            for (int pct : new int[]{0, SocHistory.LOW_PCT, 50, 100}) {
                text.setColor(pct == SocHistory.LOW_PCT ? Style.DANGER : Style.TEXT_DIM);
                cv.drawText(pct + "%", left - Style.dp(c, 6), y(pct) + Style.dp(c, 4), text);
            }
            drawXLabels(cv, c);

            // the data, twice: normal above the floor, danger below it
            for (int pass = 0; pass < 2; pass++) {
                cv.save();
                if (pass == 0) cv.clipRect(left, 0, right, yLow);
                else cv.clipRect(left, yLow, right, getHeight());
                int col = pass == 0 ? Style.ACCENT : Style.DANGER;
                if (trace != null) drawTrace(cv, c, col);
                else drawDays(cv, c, col);
                cv.restore();
            }
        }

        /** The 30-day curve: a min..max stroke per bucket, joined by the
         * bucket averages (a parked car keeps its charge, so a gap is a flat
         * join, not a hole). */
        private void drawTrace(Canvas cv, Context c, int col) {
            line.setColor(col);
            line.setStrokeWidth(Style.dp(c, 2));
            path.reset();
            boolean first = true;
            for (SocHistory.Bucket b : trace) {
                float x = xMs(b.startMs + BUCKET_MS / 2);
                if (first) { path.moveTo(x, y(b.avg)); first = false; }
                else path.lineTo(x, y(b.avg));
            }
            cv.drawPath(path, line);
            for (SocHistory.Bucket b : trace) {
                if (b.max - b.min < 1) continue;
                float x = xMs(b.startMs + BUCKET_MS / 2);
                cv.drawLine(x, y(b.max), x, y(b.min), line);
            }
        }

        /** The year: one min..max bar per day. */
        private void drawDays(Canvas cv, Context c, int col) {
            long span = java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1;
            float slot = (right - left) / span;
            fill.setColor(col);
            float w = Math.max(Style.dp(c, 2), slot * 0.7f);
            for (SocHistory.DayRange d : ranges) {
                long i = java.time.temporal.ChronoUnit.DAYS.between(from, d.date);
                float cx = left + (i + 0.5f) * slot;
                float y0 = y(d.max), y1 = Math.max(y(d.min), y0 + Style.dp(c, 2));
                cv.drawRoundRect(cx - w / 2, y0, cx + w / 2, y1, w / 2, w / 2, fill);
            }
        }

        private void drawXLabels(Canvas cv, Context c) {
            text.setColor(Style.TEXT_DIM);
            text.setTextAlign(Paint.Align.CENTER);
            float ty = getHeight() - Style.dp(c, 6);
            long span = java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1;
            float slot = (right - left) / span;
            if (trace != null) {
                // a date every week, counted back from today
                DateTimeFormatter f = DateTimeFormatter.ofPattern("d/M");
                for (LocalDate d = today; !d.isBefore(from); d = d.minusDays(7)) {
                    long i = java.time.temporal.ChronoUnit.DAYS.between(from, d);
                    cv.drawText(d.format(f), left + (i + 0.5f) * slot, ty, text);
                }
            } else {
                // a month name at each month's first day
                for (LocalDate d = from.withDayOfMonth(1).plusMonths(1); !d.isAfter(today); d = d.plusMonths(1)) {
                    long i = java.time.temporal.ChronoUnit.DAYS.between(from, d);
                    float x = left + i * slot;
                    text.setTextAlign(Paint.Align.LEFT);
                    cv.drawText(months[d.getMonthValue() - 1], x + Style.dp(c, 2), ty, text);
                }
                text.setTextAlign(Paint.Align.CENTER);
            }
        }
    }
}
