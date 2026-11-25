package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** The month calendar of the diary: large day cells, month stepping, one tap
 * picks a day. Used by the entry form and by the car's pick-up date. */
final class DiaryCalendar {
    private static final int TOUCH_DP = 60;

    private DiaryCalendar() {}

    /** Opens the calendar on calSelected. Future days can be picked only when
     * allowFuture is set. Returns the open dialog. */
    static AlertDialog open(Context c, LocalDate calSelected, boolean allowFuture,
                            java.util.function.Consumer<LocalDate> onPick) {
        final DateTimeFormatter monthYearFmt = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault());
        final LocalDate today = LocalDate.now();
        final YearMonth currentYm = YearMonth.from(today);
        final YearMonth[] shown = {YearMonth.from(calSelected)};

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(c, Style.CARD, withAlpha(Style.TEXT, 0.16f), 1, 24));
        int p = Style.dp(c, 22);
        card.setPadding(p, p, p, p);

        // Header: [<]  Month YYYY  [>]
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView prevBtn = calNavBtn(c, "‹");
        TextView monthTitle = new TextView(c);
        monthTitle.setTextColor(Style.TEXT);
        monthTitle.setTextSize(22f);
        monthTitle.setTypeface(null, Typeface.BOLD);
        monthTitle.setGravity(Gravity.CENTER);
        TextView nextBtn = calNavBtn(c, "›");

        head.addView(prevBtn);
        head.addView(monthTitle, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(nextBtn);
        card.addView(head);

        // Weekday row (Sun .. Sat)
        LinearLayout wdRow = new LinearLayout(c);
        wdRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Style.dp(c, 16);
        wlp.bottomMargin = Style.dp(c, 6);
        Locale loc = Locale.getDefault();
        DayOfWeek[] order = {
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
        };
        for (DayOfWeek dow : order) {
            TextView w = new TextView(c);
            w.setText(dow.getDisplayName(TextStyle.SHORT, loc));
            w.setTextColor(Style.TEXT_DIM);
            w.setTextSize(18f);
            w.setTypeface(null, Typeface.BOLD);
            w.setGravity(Gravity.CENTER);
            wdRow.addView(w, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(wdRow, wlp);

        // 6x7 day grid container
        LinearLayout gridHost = new LinearLayout(c);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        card.addView(gridHost, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Footer: Today (left) + Cancel (right)
        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Style.dp(c, 14);

        AlertDialog calDlg = new AlertDialog.Builder(c).create();
        calDlg.setView(card, 0, 0, 0, 0);

        TextView todayBtn = Style.cardButton(c, c.getString(R.string.diary_date_today), false, () -> {
            onPick.accept(today);
            calDlg.dismiss();
        });
        foot.addView(todayBtn);
        foot.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView closeBtn = Style.cardButton(c, c.getString(android.R.string.cancel), false, calDlg::dismiss);
        foot.addView(closeBtn);
        card.addView(foot, flp);

        Runnable renderMonth = () -> {
            YearMonth ym = shown[0];
            String raw = monthYearFmt.format(ym.atDay(1));
            String cap = raw.isEmpty() ? raw
                : raw.substring(0, 1).toUpperCase(loc) + raw.substring(1);
            monthTitle.setText(cap);

            boolean canNext = ym.isBefore(currentYm);
            nextBtn.setEnabled(canNext);
            nextBtn.setAlpha(canNext ? 1f : 0.28f);

            gridHost.removeAllViews();
            LocalDate first = ym.atDay(1);
            int leadBlanks = first.getDayOfWeek().getValue() % 7; // Sunday=0..Saturday=6
            int daysInMonth = ym.lengthOfMonth();
            int rows = (leadBlanks + daysInMonth + 6) / 7;

            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < 7; col++) {
                    int dayNum = r * 7 + col - leadBlanks + 1;
                    TextView cell = new TextView(c);
                    cell.setGravity(Gravity.CENTER);
                    cell.setTextSize(18f);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, Style.dp(c, 58), 1f);
                    clp.setMargins(Style.dp(c, 3), Style.dp(c, 3), Style.dp(c, 3), Style.dp(c, 3));

                    if (dayNum < 1 || dayNum > daysInMonth) {
                        cell.setText("");
                    } else {
                        LocalDate d = ym.atDay(dayNum);
                        boolean future = !allowFuture && d.isAfter(today);
                        boolean isSel = d.equals(calSelected);
                        boolean isTod = d.equals(today);
                        cell.setText(String.valueOf(dayNum));
                        if (future) {
                            cell.setTextColor(withAlpha(Style.TEXT_DIM, 0.30f));
                        } else if (isSel) {
                            cell.setTextColor(Style.onFill(Style.ACCENT));
                            cell.setTypeface(null, Typeface.BOLD);
                            cell.setBackground(roundedRect(c, Style.ACCENT, 0, 0, 14));
                        } else if (isTod) {
                            cell.setTextColor(Style.TEXT);
                            cell.setTypeface(null, Typeface.BOLD);
                            cell.setBackground(roundedRect(c, Color.TRANSPARENT, Style.ACCENT, 2, 14));
                        } else {
                            cell.setTextColor(Style.TEXT);
                            cell.setBackground(roundedRect(c, Style.CARD_HI, 0, 0, 14));
                        }
                        if (!future) {
                            cell.setOnClickListener(v -> {
                                onPick.accept(d);
                                calDlg.dismiss();
                            });
                        }
                    }
                    row.addView(cell, clp);
                }
                gridHost.addView(row);
            }
        };

        prevBtn.setOnClickListener(v -> {
            shown[0] = shown[0].minusMonths(1);
            renderMonth.run();
        });
        nextBtn.setOnClickListener(v -> {
            if (allowFuture || shown[0].isBefore(currentYm)) {
                shown[0] = shown[0].plusMonths(1);
                renderMonth.run();
            }
        });
        renderMonth.run();

        Window win = calDlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        calDlg.show();
        if (win != null) {
            win.setLayout(Style.dp(c, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return calDlg;
    }

    private static TextView calNavBtn(Context c, String glyph) {
        TextView b = new TextView(c);
        b.setText(glyph);
        b.setTextColor(Style.TEXT);
        b.setTextSize(26f);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundedRect(c, Style.CARD_HI, withAlpha(Style.TEXT, 0.16f), 1, 99));
        int sz = Style.dp(c, TOUCH_DP);
        b.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        return b;
    }

    private static GradientDrawable roundedRect(Context c, int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Style.dp(c, radiusDp));
        g.setColor(fill);
        if (stroke != 0 && strokeDp > 0) g.setStroke(Style.dp(c, strokeDp), stroke);
        return g;
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255f * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
