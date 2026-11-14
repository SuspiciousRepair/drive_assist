package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.util.Style;

/** The one layout every Config section uses: settings grouped into CARDS, and
 * cards stacked in fixed-width COLUMNS (one or two of them).
 *
 * Why fixed width and not a weight: the Config panel is ~1636dp wide on this
 * 1920dp screen. Every row that was MATCH_PARENT -- a text field, a Save
 * button, a one-line hint -- ran the whole way across it, so a 3-digit number
 * sat in a 1600dp bar and a hint read as one 1600dp line. A column of COL_DP
 * is about the width of the drive-mode cards on the Drive page, which is the
 * widest a single setting ever needs. Two of them, plus the gap, still fit
 * the panel (2 x 780 + 24 = 1584).
 *
 * Which page gets how many columns: a page with only a few settings uses ONE
 * column and leaves the rest of the screen empty (the Drive, Doors and
 * Elements pages carry the car render there on purpose). A page with two
 * kinds of thing -- settings and live status, settings and a list -- gets
 * TWO, one kind per column. */
final class ConfigCards {
    private ConfigCards() {}

    static final int COL_DP = 780;
    private static final int PAD_DP = 22;

    /** An empty vertical column. Fill it with add(). */
    static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    /** Puts columns side by side, each COL_DP wide, with the standard
     * column gap between them. */
    static LinearLayout columns(Context c, LinearLayout... cols) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < cols.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Style.dp(c, COL_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Style.dp(c, Style.COLUMN_GAP_DP);
            row.addView(cols[i], lp);
        }
        return row;
    }

    /** A card: a framed group of related settings with its title inside the
     * frame. title may be null for a card that needs none. */
    static LinearLayout card(Context c, String title) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(background(c));
        int p = Style.dp(c, PAD_DP);
        card.setPadding(p, Style.dp(c, 14), p, p);
        if (title != null) {
            TextView h = Style.header(c, title);
            h.setPadding(0, Style.dp(c, 4), 0, Style.dp(c, 8));
            card.addView(h);
        }
        return card;
    }

    /** Adds a card (or any view) to a column, full column width, with the
     * standard gap above it when it is not the first. */
    static void add(LinearLayout col, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (col.getChildCount() > 0) lp.topMargin = Style.dp(col.getContext(), Style.CARD_GAP_DP);
        col.addView(v, lp);
    }

    /** Dim explanatory line under a setting. 14sp: 13 and below is hard to
     * read from the driver's seat on this panel. */
    static TextView hint(Context c, String text) {
        TextView t = new TextView(c);
        t.setTextColor(Style.TEXT_DIM);
        t.setTextSize(14);
        t.setPadding(0, 0, 0, Style.dp(c, 6));
        if (text != null) t.setText(text);
        return t;
    }

    // Between the page background and CARD, so a text field (CARD fill)
    // still stands out inside it, and nearly opaque, so the car render behind
    // the Drive/Doors/Elements pages does not show through the text.
    private static GradientDrawable background(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Style.dp(c, Style.RADIUS_DP));
        int fill = Style.blend(Style.BG_TOP, Style.CARD, 0.45f);
        g.setColor((fill & 0x00FFFFFF) | 0xF2000000);
        g.setStroke(Style.dp(c, 1), Style.tileStrokeColor());
        return g;
    }
}
