package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
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

import java.util.ArrayList;
import java.util.List;

/** The Integrations page of the Config screen: one tile per entry of
 * Integrations.ALL, four to a row. A tile shows the icon, the name, one line
 * on what it does, and its live state. Tapping it opens that integration's
 * own settings page (onOpen). */
final class TelemetryIntegrationsSection extends LinearLayout {
    // 4 x 380 + 3 x 24 = 1592: fits the ~1636dp Config panel with no scroll
    // sideways. Big targets on purpose -- this is tapped from the driver's seat.
    private static final int PER_ROW = 4;
    private static final int TILE_W_DP = 370;   // 4 x 370 + 3 x 24 = 1552, fits the ~1566dp panel
    private static final int TILE_H_DP = 230;

    interface OnOpen { void open(int section); }

    // How often the tiles look again while the page is on screen. The states
    // come from four places (MQTT service, OBD2 hub key, ABRP uploader,
    // Spotify), most with no listener to hook, so one light tick asks them
    // all. It runs only between attach and detach.
    static final long REFRESH_MS = 2000;

    private final List<Runnable> refreshers = new ArrayList<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            postDelayed(this, REFRESH_MS);
        }
    };

    TelemetryIntegrationsSection(Activity a, OnOpen onOpen) {
        super(a);
        setOrientation(VERTICAL);

        TextView intro = ConfigCards.hint(a, a.getString(R.string.int_intro));
        intro.setTextSize(16);
        intro.setPadding(0, 0, 0, Style.dp(a, 14));
        addView(intro);

        LinearLayout row = null;
        for (int i = 0; i < Integrations.ALL.size(); i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(a);
                row.setOrientation(HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Style.dp(a, Style.CARD_GAP_DP);
                addView(row, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Style.dp(a, TILE_W_DP), Style.dp(a, TILE_H_DP));
            if (i % PER_ROW > 0) lp.leftMargin = Style.dp(a, Style.COLUMN_GAP_DP);
            row.addView(tile(a, Integrations.ALL.get(i), onOpen), lp);
        }
    }

    /** Re-reads every tile's state. Cheap: a few pref reads per tile, and a
     * tile is redrawn only when its state changed. No view is rebuilt. */
    void refresh() { for (Runnable r : refreshers) r.run(); }

    // A tile opened just after wake said "Connecting..." for good, though the
    // integration connected seconds later: only onResume refreshed it.
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private View tile(Activity a, Integrations.Integration in, OnOpen onOpen) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(VERTICAL);
        int p = Style.dp(a, 22);
        t.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Style.dp(a, Style.RADIUS_DP));
        bg.setColor((Style.blend(Style.BG_TOP, Style.CARD, 0.45f) & 0x00FFFFFF) | 0xF2000000);
        bg.setStroke(Style.dp(a, 1), Style.tileStrokeColor());
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(v -> onOpen.open(in.section));

        // Top line: the icon badge, and the state on the right.
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        t.addView(top);

        ImageView icon = new ImageView(a);
        GradientDrawable badge = new GradientDrawable();
        badge.setCornerRadius(Style.dp(a, 18));
        badge.setColor(in.color);
        icon.setBackground(badge);
        int ip = Style.dp(a, 14);
        icon.setPadding(ip, ip, ip, ip);
        icon.setImageResource(in.iconRes);
        icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        top.addView(icon, new LinearLayout.LayoutParams(Style.dp(a, 72), Style.dp(a, 72)));

        View spacer = new View(a);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        View dot = new View(a);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Style.dp(a, 14), Style.dp(a, 14));
        dlp.rightMargin = Style.dp(a, 8);
        top.addView(dot, dlp);

        TextView state = new TextView(a);
        state.setTextSize(16);
        state.setTypeface(null, Typeface.BOLD);
        top.addView(state);

        TextView name = new TextView(a);
        name.setText(a.getString(in.nameRes));
        name.setTextColor(Style.TEXT);
        name.setTextSize(24);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(0, Style.dp(a, 16), 0, Style.dp(a, 4));
        name.setSingleLine(true);
        t.addView(name);

        TextView desc = new TextView(a);
        desc.setText(a.getString(in.descRes));
        desc.setTextColor(Style.TEXT_DIM);
        desc.setTextSize(16);
        desc.setMaxLines(2);
        t.addView(desc);

        final Integrations.Status[] shown = new Integrations.Status[1];
        Runnable r = () -> {
            Integrations.Status s = in.status.of(a);
            if (s.sameAs(shown[0])) return;
            shown[0] = s;
            int c = colorOf(s.state);
            dotBg.setColor(c);
            state.setTextColor(s.state == Integrations.State.OFF ? Style.TEXT_DIM : c);
            state.setText(s.text);
        };
        refreshers.add(r);
        r.run();
        return t;
    }

    private static int colorOf(Integrations.State s) {
        switch (s) {
            case OK:    return Style.GOOD;
            case BUSY:  return Style.WARN;
            case ERROR: return Style.DANGER;
            default:    return Style.blend(Style.TEXT_DIM, Style.CARD, 0.4f);
        }
    }
}
