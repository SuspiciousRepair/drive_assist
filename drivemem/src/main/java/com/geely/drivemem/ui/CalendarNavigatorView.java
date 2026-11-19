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
 * The selected period is a light-blue outline traced around its squares: the
 * day's own square, one column for a week, the month's stepped shape for a
 * month. One box only (owner, 2026-11-18: a week box around the day box read
 * as two selections).
 *
 * TAP ONLY, NO DRAG. The page sits inside a ScrollView inside the pager's
 * HorizontalScrollView. Every square is its own target (~4.5 mm on this
 * glass, 1 dp = 1 px, see CLAUDE.md), so a tap must not be lost to a finger
 * that rolls a few px: the parents are told to keep out until the finger has
 * moved more than a third of a square, and a tap goes to the NEAREST square,
 * gaps included. A real drag (past that) is handed back to the parents so the
 * page still scrolls from here.
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
    /** A square's corner radius, as a share of its side. */
    private static final float CORNER = 0.2f;
    private LocalDate selected = today;
    private OnPick onPick;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        StatsCalendar.Cell now = new StatsCalendar.Cell(today, km, old.charges, old.dc);
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
            // the top level moves AWAY from the background: brighter on a
            // dark theme, deeper on a light one (toward white there read as
            // paler than level 4 -- seen on the car 2026-11-18)
            default: return Style.LIGHT ? Style.blend(Style.ACCENT, 0xFF000000, 0.35f)
                                        : Style.blend(Style.ACCENT, 0xFFFFFFFF, 0.45f);
        }
    }

    /** A day before the first record: a flat square, fainter than a 0 km day,
     * so the grid reads as one surface instead of a field of outlines. */
    private int beforeColor() { return Style.blend(Style.CARD, levelColor(0), 0.45f); }

    /** Light blue, never white. On a light theme it has to be a stronger blue
     * to show at all against the white card. */
    private int selColor() {
        return Style.LIGHT ? Style.blend(Style.ACCENT, 0xFF7FD4FF, 0.45f)
                           : Style.blend(Style.ACCENT, 0xFFFFFFFF, 0.55f);
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float rad = cell * CORNER;

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
        for (int col = 0; col < WEEKS; col++) {
            for (int rw = 0; rw < 7; rw++) {
                LocalDate d = gridStart.plusDays(col * 7L + rw);
                if (d.isAfter(today)) continue;
                r.set(colX(col), rowY(rw), colX(col) + cell, rowY(rw) + cell);
                boolean before = firstData == null || d.isBefore(firstData);
                if (before) { fill.setColor(beforeColor()); cv.drawRoundRect(r, rad, rad, fill); continue; }
                StatsCalendar.Cell c = byDate.get(d);
                double km = c == null ? 0 : c.km;
                fill.setColor(levelColor(StatsCalendar.level(km, cuts)));
                cv.drawRoundRect(r, rad, rad, fill);
                if (c != null && c.charges > 0) {
                    float dr = cell * 0.15f;
                    drawChargeDot(cv, r.right - dr * 1.6f, r.top + dr * 1.6f, dr, c.dc);
                }
            }
        }

        // the period outline: the day, the week or the month
        LocalDate[] p = StatsCalendar.period(selected, grain);
        drawOutline(cv, p[0], p[1]);

        drawLegend(cv);
    }

    /** A charge: green for DC, blue for AC (the charge screens' colours).
     * On a blue square a blue dot vanishes, so a soft halo of the card colour
     * lifts it off the square, and a faint glow in its own colour sits on
     * top. Both fade to nothing: no hard ring (a hard ring read as a sticker,
     * owner 2026-11-18). */
    private void drawChargeDot(Canvas cv, float cx, float cy, float dr, boolean dc) {
        int col = dc ? Style.GOOD : chargeAcColor();
        float gr = dr * 1.9f, edge = dr / gr;
        int card = Style.cardFillColor() & 0x00FFFFFF, rgb = col & 0x00FFFFFF;
        glow.setShader(new android.graphics.RadialGradient(cx, cy, gr,
            new int[]{card | 0x99000000, card | 0x99000000, card | 0x33000000, card},
            new float[]{0f, edge, 0.6f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, gr, glow);
        glow.setShader(new android.graphics.RadialGradient(cx, cy, gr,
            new int[]{rgb | 0x40000000, rgb | 0x40000000, rgb | 0x10000000, rgb},
            new float[]{0f, edge, 0.6f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, gr, glow);
        fill.setColor(col);
        cv.drawCircle(cx, cy, dr, fill);
    }

    /** AC blue: the accent, pulled toward cyan so it does not sit at the
     * same hue as the km squares under it. */
    private int chargeAcColor() { return Style.blend(Style.ACCENT, 0xFF00C8FF, 0.35f); }

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
        // The union of the squares grown by half a gap: one closed shape,
        // whose corners are then rounded. A hair of overlap so neighbours
        // merge despite float rounding.
        float eps = 0.01f;
        outline.reset();
        Path one = new Path();
        for (long i = a; i <= b; i++) {
            int col = (int) (i / 7), rw = (int) (i % 7);
            one.reset();
            one.addRect(colX(col) - half - eps, rowY(rw) - half - eps,
                colX(col) + cell + half + eps, rowY(rw) + cell + half + eps, Path.Direction.CW);
            outline.op(one, Path.Op.UNION);
        }
        stroke.setColor(selColor());
        stroke.setStrokeWidth(Style.dp(getContext(), 4));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        // Same centre as the squares' own rounded corners, so the curves
        // match: the squares' radius plus the half gap the outline sits out.
        stroke.setPathEffect(new android.graphics.CornerPathEffect(cell * CORNER + half));
        cv.drawPath(outline, stroke);
        stroke.setPathEffect(null);
        stroke.setStrokeJoin(Paint.Join.MITER);
    }

    /** Lean: "0 ■■■■■■ 52+ km   Recarga ● AC ● DC". Only the two ends of
     * the scale get a number; the top one is where this driver's darkest
     * square starts (StatsCalendar.levelCuts). */
    private void drawLegend(Canvas cv) {
        Context c = getContext();
        float y = rowY(7) + Style.dp(c, 4), sq = Style.dp(c, 16), gap = Style.dp(c, 5),
              textGap = Style.dp(c, 8), groupGap = Style.dp(c, 32);
        text.setTextSize(Style.dp(c, 14));
        text.setColor(Style.TEXT_DIM);
        text.setTextAlign(Paint.Align.LEFT);
        String lo = "0", hi = cuts.length == 4 ? Math.round(cuts[3]) + "+ km" : "km";
        String charge = c.getString(R.string.stats_nav_charge);
        float total = text.measureText(lo) + textGap + 6 * sq + 5 * gap + textGap + text.measureText(hi)
                    + groupGap + text.measureText(charge)
                    + 2 * (textGap + sq * 0.6f + gap + text.measureText("AC"));
        float x = getWidth() - total;
        float base = y + sq * 0.8f, mid = y + sq / 2f;
        float rad = sq * 0.2f;
        cv.drawText(lo, x, base, text);
        x += text.measureText(lo) + textGap;
        for (int lv = 0; lv <= 5; lv++) {
            fill.setColor(levelColor(lv));
            r.set(x, y, x + sq, y + sq);
            cv.drawRoundRect(r, rad, rad, fill);
            x += sq + (lv < 5 ? gap : 0);
        }
        x += textGap;
        cv.drawText(hi, x, base, text);
        x += text.measureText(hi) + groupGap;
        cv.drawText(charge, x, base, text);
        x += text.measureText(charge);
        for (boolean dc : new boolean[]{false, true}) {
            x += textGap;
            fill.setColor(dc ? Style.GOOD : chargeAcColor());
            cv.drawCircle(x + sq * 0.3f, mid, sq * 0.3f, fill);
            x += sq * 0.6f + gap;
            cv.drawText(dc ? "DC" : "AC", x, base, text);
            x += text.measureText("AC");
        }
    }

    // ---- touch: tap only ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (moved(e) && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_UP:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (moved(e)) return true;
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

    /** A drag, not a tap: past a third of a square (and never under the
     * system slop). */
    private boolean moved(MotionEvent e) {
        float lim = Math.max(touchSlop, pitch / 3f);
        return Math.abs(e.getX() - downX) > lim || Math.abs(e.getY() - downY) > lim;
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    /** The day under (x, y). Off the grid's edges it clamps to the nearest
     * column/row; after today it is today; before the data it is the first
     * recorded day. Null only when there is nothing to pick. */
    private LocalDate dateAt(float x, float y) {
        if (pitch <= 0) return null;
        // nearest square: measured from the square's centre, so a tap in a
        // gap goes to whichever square it is closer to
        int col = Math.max(0, Math.min(WEEKS - 1, Math.round((x - gridX - cell / 2f) / pitch)));
        int rw = Math.max(0, Math.min(6, Math.round((y - gridY - cell / 2f) / pitch)));
        LocalDate d = gridStart.plusDays(col * 7L + rw);
        LocalDate min = firstData == null ? today : firstData;
        if (min.isBefore(gridStart)) min = gridStart;
        return StatsCalendar.clamp(d, min, today);
    }
}
