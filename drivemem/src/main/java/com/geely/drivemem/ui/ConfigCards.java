package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;

/** The one layout every Config section uses: settings grouped into CARDS, and
 * cards stacked in fixed-width COLUMNS (one or two of them).
 *
 * Why fixed width and not a weight: the Config panel is ~1566dp wide on this
 * 1920dp screen. Every row that was MATCH_PARENT -- a text field, a Save
 * button, a one-line hint -- ran the whole way across it, so a 3-digit number
 * sat in a 1600dp bar and a hint read as one 1600dp line. A column of COL_DP
 * is about the width of the drive-mode cards on the Drive page, which is the
 * widest a single setting ever needs. Two of them, plus the gap, still fit
 * the panel (2 x 760 + 24 = 1544; the panel is ~1566dp since the sidebar
 * grew to 310dp for OEM-sized text).
 *
 * Which page gets how many columns: a page with only a few settings uses ONE
 * column and leaves the rest of the screen empty (the Drive, Doors and
 * Elements pages carry the car render there on purpose). A page with two
 * kinds of thing -- settings and live status, settings and a list -- gets
 * TWO, one kind per column. */
final class ConfigCards {
    private ConfigCards() {}

    static final int COL_DP = 760;
    private static final int PAD_DP = 22;

    /** An empty vertical column. Fill it with add(). */
    static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    /** Puts columns side by side, each COL_DP wide, with the standard
     * column gap between them, mirrored and aligned to the driver's edge in RHD. */
    static LinearLayout columns(Context c, LinearLayout... cols) {
        return columns(c, COL_DP, cols);
    }

    /** Same, with an explicit column width in dp (used by pages like Drive
     * whose wider mode-card rows predate COL_DP). */
    static LinearLayout columns(Context c, int colWidthDp, LinearLayout... cols) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Style.driverGravity());
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int widthPx = Style.dp(c, colWidthDp);
        for (int i = 0; i < cols.length; i++) {
            cols[i].setLayoutParams(new LinearLayout.LayoutParams(
                widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Style.addMirrored(row, Style.COLUMN_GAP_DP, cols);
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

    // ---- Stepper row: the one control for "pick a value from a list" ----
    // name ............ ‹ [ value ] ›     (docs/STYLE-GUIDE.md, "Stepper rows")
    // First used on Config > Maintenance; Config > Doors uses it too.

    static final int STEP_ROW_DP = 60, VALUE_W_DP = 220, VALUE_H_DP = 52, CHEVRON_DP = 60;

    /** One stepper row: the name on the left, then ‹ value ›. value comes
     * from valueBox(); a null down/up draws that chevron dim, with no action
     * (the end of the list). */
    static LinearLayout stepRow(Context c, CharSequence label, TextView value, Runnable down, Runnable up) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(c, STEP_ROW_DP));
        wlp.topMargin = Style.dp(c, 6);
        row.setLayoutParams(wlp);
        row.addView(rowLabel(c, label), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(chevron(c, true, down));
        row.addView(value, new LinearLayout.LayoutParams(Style.dp(c, VALUE_W_DP), Style.dp(c, VALUE_H_DP)));
        row.addView(chevron(c, false, up));
        return row;
    }

    /** The name at the start of a stepper row. */
    static TextView rowLabel(Context c, CharSequence label) {
        TextView lbl = new TextView(c);
        lbl.setText(label);
        lbl.setTextColor(Style.TEXT);
        lbl.setTextSize(20f);
        lbl.setSingleLine(true);
        return lbl;
    }

    /** The value box of a stepper row: bold on CARD_HI. off (the value means
     * "nothing", such as Off or 0) shows it dim and not bold. */
    static TextView valueBox(Context c, CharSequence text, boolean off) {
        TextView val = new TextView(c);
        styleValue(val, text, off);
        val.setTextSize(20f);
        val.setGravity(Gravity.CENTER);
        val.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Style.dp(c, 14));
        bg.setColor(Style.CARD_HI);
        bg.setStroke(Style.dp(c, 1), Style.tileStrokeColor());
        val.setBackground(bg);
        return val;
    }

    /** New text for a value box, after a step. */
    static void styleValue(TextView val, CharSequence text, boolean off) {
        val.setText(text);
        val.setTextColor(off ? Style.TEXT_DIM : Style.TEXT);
        val.setTypeface(null, off ? Typeface.NORMAL : Typeface.BOLD);
    }

    /** A plain chevron, no box, in a 60 dp touch target. A null onTap draws
     * it dim, with no action. */
    static ImageView chevron(Context c, boolean left, Runnable onTap) {
        ImageView a = new ImageView(c);
        a.setImageResource(R.drawable.ic_chevron_right);
        if (left) a.setRotation(180f);
        int p = Style.dp(c, 16);
        a.setPadding(p, p, p, p);
        setChevron(a, onTap);
        a.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(c, CHEVRON_DP), Style.dp(c, CHEVRON_DP)));
        return a;
    }

    /** Turns a chevron on (with this action) or off (null: dim, no action). */
    static void setChevron(ImageView a, Runnable onTap) {
        if (onTap != null) {
            a.setColorFilter(Style.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
            a.setOnClickListener(v -> onTap.run());
            a.setClickable(true);
        } else {
            a.setColorFilter((Style.TEXT_DIM & 0x00FFFFFF) | 0x40000000, android.graphics.PorterDuff.Mode.SRC_IN);
            a.setOnClickListener(null);
            a.setClickable(false);
        }
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
