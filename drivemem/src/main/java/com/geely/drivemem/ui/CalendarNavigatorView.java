package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.StatsCalendar;
import com.geely.drivemem.util.Style;

import java.text.DateFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The stats navigator: a year of days as a GitHub-style grid, one column per
 * week (Monday on top), one square per day, coloured by that day's km on the
 * driver's own scale (StatsCalendar.levelCuts). It replaces the 14-bar chart.
 *
 * The selected period is a light-blue outline traced around its squares: one
 * column for a week, the month's stepped shape for a month. In Day view the
 * week stays outlined and a smaller box marks the day inside it.
 *
 * TAP ONLY, NO DRAG. The page sits inside a ScrollView inside the pager's
 * HorizontalScrollView; a drag here would fight both. Precision comes from
 * the Anterior/Próximo arrows, so a tap only has to land near the right place.
 * A square is ~4 mm on this glass (1 dp = 1 px, see CLAUDE.md), too small to
 * hit exactly, which is why a tap in Week view takes the whole COLUMN — a tall
 * target — and the row only matters in Day view.
 */
public class CalendarNavigatorView extends View {

    public interface OnPick { void onPick(LocalDate date); }

    static final int WEEKS = 52;

    private List<StatsCalendar.Cell> cells = new ArrayList<>();
    private final Map<LocalDate, StatsCalendar.Cell> byDate = new HashMap<>();
    private double[] cuts = new double[0];
    private LocalDate today = LocalDate.now();
    private LocalDate gridStart = StatsCalendar.weekStart(today).minusWeeks(WEEKS - 1);
    private LocalDate firstData;
    private StatsCalendar.Grain grain = StatsCalendar.Grain.DAY;
    private LocalDate selected = today;
    private OnPick onPick;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path outline = new Path();

    private final String[] monthNames = new String[12];
    private final String[] dayLetters = new String[7];

    // layout, set in onSizeChanged
    private float labelW, topH, pitch, cell, gridX, gridY;
    private float downX, downY;
    private final int touchSlop;

    public CalendarNavigatorView(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        DateFormatSymbols sym = DateFormatSymbols.getInstance(Locale.getDefault());
        String[] m = sym.getShortMonths();
        for (int i = 0; i < 12; i++) monthNames[i] = m[i].replace(".", "").toLowerCase(Locale.getDefault());
        String[] w = sym.getShortWeekdays();   // index Calendar.SUNDAY..SATURDAY
        int[] order = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
                       Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        for (int i = 0; i < 7; i++) {
            String s = w[order[i]];
            dayLetters[i] = s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.getDefault());
        }
        text.setTextSize(Style.dp(c, 15));
        stroke.setStyle(Paint.Style.STROKE);
    }

    public void setOnPick(OnPick l) { onPick = l; }

    /** Every day from gridStart() to today, oldest first. */
    public void setData(List<StatsCalendar.Cell> cells, LocalDate firstData) {
        this.cells = cells;
        byDate.clear();
        for (StatsCalendar.Cell c : cells) byDate.put(c.date, c);
        this.cuts = StatsCalendar.levelCuts(cells);
        this.today = cells.isEmpty() ? LocalDate.now() : cells.get(cells.size() - 1).date;
        this.gridStart = gridStartFor(today);
        this.firstData = firstData;
        invalidate();
    }

    /** Live km for today, without reloading the year. */
    public void updateToday(double km) {
        StatsCalendar.Cell old = byDate.get(today);
        if (old == null || Math.abs(old.km - km) < 0.05) return;
        StatsCalendar.Cell now = new StatsCalendar.Cell(today, km, old.charges);
        byDate.put(today, now);
        cells.set(cells.size() - 1, now);
        invalidate();
    }

    public void setSelection(StatsCalendar.Grain grain, LocalDate selected) {
        this.grain = grain;
        this.selected = selected;
        invalidate();
    }

    public static LocalDate gridStartFor(LocalDate today) {
        return StatsCalendar.weekStart(today).minusWeeks(WEEKS - 1);
    }

    // ---- layout ----

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float lw = Style.dp(getContext(), 34), th = Style.dp(getContext(), 30), legend = Style.dp(getContext(), 34);
        float p = (w - lw) / WEEKS;
        setMeasuredDimension(w, (int) Math.ceil(th + 7 * p + legend));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        labelW = Style.dp(getContext(), 34);
        topH = Style.dp(getContext(), 30);
        pitch = (w - labelW) / WEEKS;
        cell = pitch * 0.84f;
        gridX = labelW;
        gridY = topH;
    }

    private float colX(int col) { return gridX + col * pitch; }
    private float rowY(int row) { return gridY + row * pitch; }

    // ---- colours, from the active theme ----

    private int levelColor(int lv) {
        switch (lv) {
            case 0: return Style.blend(Style.CARD, Style.CARD_HI, 0.5f);
            case 1: return Style.blend(Style.CARD_HI, Style.ACCENT, 0.30f);
            case 2: return Style.blend(Style.CARD_HI, Style.ACCENT, 0.50f);
            case 3: return Style.blend(Style.CARD_HI, Style.ACCENT, 0.72f);
            case 4: return Style.ACCENT;
            default: return Style.blend(Style.ACCENT, 0xFFFFFFFF, 0.45f);
        }
    }

    private int selColor() { return Style.blend(Style.ACCENT, 0xFFFFFFFF, 0.55f); }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float rad = cell * 0.2f;

        // weekday letters
        text.setColor(Style.TEXT_DIM);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(Style.dp(getContext(), 14));
        for (int rw = 0; rw < 7; rw++) {
            cv.drawText(dayLetters[rw], gridX - Style.dp(getContext(), 10), rowY(rw) + cell * 0.72f, text);
        }

        // month names over the first week that starts in that month
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(Style.dp(getContext(), 15));
        int lastMonth = -1;
        for (int col = 0; col < WEEKS; col++) {
            LocalDate mon = gridStart.plusWeeks(col);
            int m = mon.getMonthValue() - 1;
            if (m != lastMonth && (col > 0 || mon.getDayOfMonth() <= 7)) {
                cv.drawText(monthNames[m], colX(col), gridY - Style.dp(getContext(), 10), text);
            }
            lastMonth = m;
        }

        // the squares
        stroke.setStrokeWidth(Style.dp(getContext(), 2));
        stroke.setColor(Style.blend(Style.CARD, Style.CARD_HI, 0.5f));
        for (int col = 0; col < WEEKS; col++) {
            for (int rw = 0; rw < 7; rw++) {
                LocalDate d = gridStart.plusDays(col * 7L + rw);
                if (d.isAfter(today)) continue;
                r.set(colX(col), rowY(rw), colX(col) + cell, rowY(rw) + cell);
                boolean before = firstData == null || d.isBefore(firstData);
                if (before) { cv.drawRoundRect(r, rad, rad, stroke); continue; }
                StatsCalendar.Cell c = byDate.get(d);
                double km = c == null ? 0 : c.km;
                fill.setColor(levelColor(StatsCalendar.level(km, cuts)));
                cv.drawRoundRect(r, rad, rad, fill);
                if (c != null && c.charges > 0) {
                    fill.setColor(Style.GOOD);
                    float dr = cell * 0.13f;
                    cv.drawCircle(r.right - dr * 1.8f, r.top + dr * 1.8f, dr, fill);
                }
            }
        }

        // the period outline (a week stays outlined in Day view)
        StatsCalendar.Grain shape = grain == StatsCalendar.Grain.DAY ? StatsCalendar.Grain.WEEK : grain;
        LocalDate[] p = StatsCalendar.period(selected, shape);
        drawOutline(cv, p[0], p[1]);

        // Day view: a smaller box on the day itself
        if (grain == StatsCalendar.Grain.DAY) {
            long idx = java.time.temporal.ChronoUnit.DAYS.between(gridStart, selected);
            if (idx >= 0) {
                int col = (int) (idx / 7), rw = (int) (idx % 7);
                float in = Style.dp(getContext(), 1);
                r.set(colX(col) - in, rowY(rw) - in, colX(col) + cell + in, rowY(rw) + cell + in);
                stroke.setColor(Style.TEXT);
                stroke.setStrokeWidth(Style.dp(getContext(), 3));
                cv.drawRoundRect(r, rad + in, rad + in, stroke);
            }
        }

        drawLegend(cv);
    }

    /** Traces the outer edge of the squares first..last (clipped to the grid
     * and to today) along the gap midlines: one edge per side of a square whose
     * neighbour on that side is outside the period. */
    private void drawOutline(Canvas cv, LocalDate first, LocalDate last) {
        if (last.isAfter(today)) last = today;
        if (first.isBefore(gridStart)) first = gridStart;
        if (first.isAfter(last)) return;
        long a = java.time.temporal.ChronoUnit.DAYS.between(gridStart, first);
        long b = java.time.temporal.ChronoUnit.DAYS.between(gridStart, last);
        float half = (pitch - cell) / 2f;
        outline.reset();
        for (long i = a; i <= b; i++) {
            int col = (int) (i / 7), rw = (int) (i % 7);
            float x0 = colX(col) - half, x1 = colX(col) + cell + half;
            float y0 = rowY(rw) - half, y1 = rowY(rw) + cell + half;
            if (!(rw > 0 && i - 1 >= a)) { outline.moveTo(x0, y0); outline.lineTo(x1, y0); }   // top
            if (!(rw < 6 && i + 1 <= b)) { outline.moveTo(x0, y1); outline.lineTo(x1, y1); }   // bottom
            if (!(i - 7 >= a))           { outline.moveTo(x0, y0); outline.lineTo(x0, y1); }   // left
            if (!(i + 7 <= b))           { outline.moveTo(x1, y0); outline.lineTo(x1, y1); }   // right
        }
        stroke.setColor(selColor());
        stroke.setStrokeWidth(Style.dp(getContext(), 4));
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        cv.drawPath(outline, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawLegend(Canvas cv) {
        Context c = getContext();
        float y = rowY(7) + Style.dp(c, 4), sq = Style.dp(c, 16), gap = Style.dp(c, 5);
        text.setTextSize(Style.dp(c, 14));
        text.setColor(Style.TEXT_DIM);
        String less = c.getString(R.string.stats_nav_less), more = c.getString(R.string.stats_nav_more),
               charge = c.getString(R.string.stats_nav_charge);
        float dotGap = Style.dp(c, 28);
        float total = text.measureText(less) + gap + 6 * (sq + gap) + text.measureText(more)
                    + dotGap + sq + text.measureText(charge);
        float x = getWidth() - total;
        float base = y + sq * 0.8f;
        text.setTextAlign(Paint.Align.LEFT);
        cv.drawText(less, x, base, text);
        x += text.measureText(less) + gap;
        float rad = sq * 0.2f;
        for (int lv = 0; lv <= 5; lv++) {
            fill.setColor(levelColor(lv));
            r.set(x, y, x + sq, y + sq);
            cv.drawRoundRect(r, rad, rad, fill);
            x += sq + gap;
        }
        cv.drawText(more, x, base, text);
        x += text.measureText(more) + dotGap;
        fill.setColor(Style.GOOD);
        cv.drawCircle(x + sq * 0.3f, y + sq / 2f, sq * 0.25f, fill);
        cv.drawText(charge, x + sq * 0.8f, base, text);
    }

    // ---- touch: tap only ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(e.getX() - downX) > touchSlop || Math.abs(e.getY() - downY) > touchSlop) return true;
                LocalDate d = dateAt(e.getX(), e.getY());
                if (d != null && onPick != null) {
                    performClick();
                    onPick.onPick(d);
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    /** The day under (x, y). Off the grid's edges it clamps to the nearest
     * column/row; after today it is today; before the data it is the first
     * recorded day. Null only when there is nothing to pick. */
    private LocalDate dateAt(float x, float y) {
        if (pitch <= 0) return null;
        int col = Math.max(0, Math.min(WEEKS - 1, (int) Math.floor((x - gridX) / pitch)));
        int rw = Math.max(0, Math.min(6, (int) Math.floor((y - gridY) / pitch)));
        LocalDate d = gridStart.plusDays(col * 7L + rw);
        LocalDate min = firstData == null ? today : firstData;
        if (min.isBefore(gridStart)) min = gridStart;
        return StatsCalendar.clamp(d, min, today);
    }
}
