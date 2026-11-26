package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.TollMatcher;
import com.geely.drivemem.state.TollTracker;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/** Dialog to set/edit a detected toll passage's price (with a zoomed-in local
 * map centered on that gantry/plaza so you can confirm the exact crossing),
 * optionally remember it on the booth for future drives, or dismiss a false positive. */
public final class TollPassageDialog {
    private static final double LOCAL_RADIUS_M = 900.0;
    private static final long LOCAL_TIME_WINDOW_MS = 180_000L;

    private TollPassageDialog() {}

    public static void show(Context context, TollTracker.Passage p, Runnable onUpdated) {
        show(context, p, null, null, null, null, onUpdated);
    }

    public static void show(Context context, TollTracker.Passage p,
                            long[] trackTs, double[] trackLat, double[] trackLon, double[] trackSpeed,
                            Runnable onUpdated) {
        final boolean[] remember = {true};

        LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackground(Style.card(Style.CARD, context));
        int pad = Style.dp(context, 24);
        outer.setPadding(pad, pad, pad, pad);
        outer.setLayoutParams(new ViewGroup.LayoutParams(
            Style.dp(context, 960), ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Left: zoomed-in map focused right on this gantry/plaza ----
        RouteView miniMap = new RouteView(context);
        miniMap.setBackground(Style.card(Style.BG_BOTTOM, context, 14));
        miniMap.setClipToOutline(true);
        miniMap.setMinSpanM(800);
        String pinLabel = p.cost != null
            ? Prefs.currencySymbol() + String.format(Locale.getDefault(), "%.2f", p.cost)
            : p.boothName;
        double[][] slice = extractLocalSlice(p.tsMs, p.lat, p.lon, trackTs, trackLat, trackLon, trackSpeed);
        if (slice != null && slice[0].length >= 2) {
            miniMap.setTollPins(new double[]{p.lat}, new double[]{p.lon}, new String[]{pinLabel});
            miniMap.setRoute(slice[0], slice[1], slice[2]);
        } else {
            miniMap.setLocationPin(p.lat, p.lon, pinLabel);
        }
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
            Style.dp(context, 480), Style.dp(context, 380));
        mapLp.rightMargin = Style.dp(context, 24);
        outer.addView(miniMap, mapLp);

        // ---- Right: gantry info, price editor, and actions ----
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        outer.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setText(p.boothName);
        title.setTextColor(Style.TEXT);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView sub = new TextView(context);
        String kind = TollMatcher.KIND_FREE_FLOW.equals(p.boothKind)
            ? context.getString(R.string.toll_kind_free_flow)
            : context.getString(R.string.toll_kind_plaza);
        sub.setText(SessionText.clock(p.tsMs) + "  ·  " + kind);
        sub.setTextColor(Style.TEXT_DIM);
        sub.setTextSize(15f);
        sub.setPadding(0, Style.dp(context, 4), 0, Style.dp(context, 14));
        card.addView(sub);

        EditText input = new EditText(context);
        input.setHint(context.getString(R.string.charge_cost_input_hint, Prefs.getCurrency(context)));
        input.setTextColor(Style.TEXT);
        input.setHintTextColor(Style.TEXT_DIM);
        input.setTextSize(22f);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[0]);
        input.setBackground(Style.card(Style.CARD_HI, context));
        int inPad = Style.dp(context, 14);
        input.setPadding(inPad, inPad, inPad, inPad);
        input.setText(ChargeCostDialog.formatCents(p.cost == null ? 0 : Math.round(p.cost * 100)));
        input.addTextChangedListener(new TextWatcher() {
            private boolean formatting;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (formatting) return;
                formatting = true;
                String f = ChargeCostDialog.formatCents(ChargeCostDialog.centsFromText(value.toString()));
                input.setText(f);
                input.setSelection(f.length());
                formatting = false;
            }
        });
        input.selectAll();
        card.addView(input);

        if (p.boothId != null && p.boothId > 0) {
            View remRow = Style.toggleRow(context, context.getString(R.string.toll_remember_booth),
                true, on -> remember[0] = on);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Style.dp(context, 12);
            card.addView(remRow, rlp);
        }

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = Style.dp(context, 18);
        btnRow.setLayoutParams(brLp);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(outer).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView btnDismiss = Style.cardButton(context, context.getString(R.string.toll_btn_dismiss), false, () -> {
            dialog.dismiss();
            TollTracker.dismissPassage(context, p.id, onUpdated);
        });
        btnRow.addView(btnDismiss);
        btnRow.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        btnRow.addView(Style.cardButton(context, context.getString(android.R.string.cancel), false, dialog::dismiss));
        Style.gap(btnRow, context, 10);

        btnRow.addView(Style.cardButton(context, context.getString(R.string.charge_cost_btn_save), true, () -> {
            double cost = ChargeCostDialog.centsFromText(input.getText().toString()) / 100.0;
            dialog.dismiss();
            // An empty or zero price is never taught to the booth: saving an
            // untouched passage would make every later crossing cost 0.00.
            TollTracker.updatePassageCost(context, p.id, p.boothId, cost, remember[0] && cost > 0, onUpdated);
        }));
        card.addView(btnRow);

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Style.dp(context, 980), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        input.requestFocus();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            input.selectAll();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    /** Travel direction at one route point (degrees), from the points two
     * before and two after it; null when the car did not move there. */
    static Double bearingAt(double[] lat, double[] lon, int i) {
        if (lat == null || lon == null || lat.length < 2 || i < 0 || i >= lat.length) return null;
        int a = Math.max(0, i - 2), b = Math.min(lat.length - 1, i + 2);
        if (a == b || TollMatcher.distanceM(lat[a], lon[a], lat[b], lon[b]) < 5) return null;
        return TollMatcher.bearingDeg(lat[a], lon[a], lat[b], lon[b]);
    }

    /** Pins a toll the matcher missed at route point {@code i} of a finished
     * trip (the place picked on the height chart): kind, optional name and
     * price. The new booth matches later trips in this direction too. */
    public static void showAdd(Context context, long tripId, int i,
                               long[] ts, double[] lat, double[] lon, double[] speed, Runnable onDone) {
        if (ts == null || lat == null || lon == null || i < 0 || i >= lat.length || i >= ts.length) return;
        final String[] kind = {TollMatcher.KIND_PLAZA};

        LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackground(Style.card(Style.CARD, context));
        int pad = Style.dp(context, 24);
        outer.setPadding(pad, pad, pad, pad);

        RouteView miniMap = new RouteView(context);
        miniMap.setBackground(Style.card(Style.BG_BOTTOM, context, 14));
        miniMap.setClipToOutline(true);
        miniMap.setMinSpanM(800);
        double[] spd = speed != null ? speed : new double[lat.length];
        double[][] slice = extractLocalSlice(ts[i], lat[i], lon[i], ts, lat, lon, spd);
        if (slice != null && slice[0].length >= 2) {
            miniMap.setTollPins(new double[]{lat[i]}, new double[]{lon[i]}, new String[]{"+"});
            miniMap.setRoute(slice[0], slice[1], slice[2]);
        } else {
            miniMap.setLocationPin(lat[i], lon[i], "+");
        }
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
            Style.dp(context, 480), Style.dp(context, 380));
        mapLp.rightMargin = Style.dp(context, 24);
        outer.addView(miniMap, mapLp);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        outer.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setText(R.string.toll_add_title);
        title.setTextColor(Style.TEXT);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView sub = new TextView(context);
        sub.setText(SessionText.clock(ts[i]));
        sub.setTextColor(Style.TEXT_DIM);
        sub.setTextSize(16f);
        sub.setPadding(0, Style.dp(context, 4), 0, Style.dp(context, 14));
        card.addView(sub);

        LinearLayout kinds = new LinearLayout(context);
        kinds.setOrientation(LinearLayout.HORIZONTAL);
        String[] keys = {TollMatcher.KIND_PLAZA, TollMatcher.KIND_FREE_FLOW};
        int[] labels = {R.string.toll_kind_plaza, R.string.toll_kind_free_flow};
        TextView[] btn = new TextView[2];
        Runnable paint = () -> {
            for (int k = 0; k < 2; k++) {
                int fill = keys[k].equals(kind[0]) ? Style.CARD_ON : Style.CARD_HI;
                btn[k].setBackground(Style.card(fill, context));
                btn[k].setTextColor(Style.onFill(fill));
            }
        };
        for (int k = 0; k < 2; k++) {
            final String key = keys[k];
            btn[k] = Style.cardButton(context, context.getString(labels[k]), false, null);
            btn[k].setOnClickListener(v -> { kind[0] = key; paint.run(); });
            if (k > 0) ParkingCostDialog.hGap(kinds, context);
            kinds.addView(btn[k]);
        }
        paint.run();
        card.addView(kinds);
        Style.gap(card, context, 12);

        int inPad = Style.dp(context, 14);
        EditText name = new EditText(context);
        name.setHint(R.string.toll_add_name_hint);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setTextColor(Style.TEXT);
        name.setHintTextColor(Style.TEXT_DIM);
        name.setTextSize(20f);
        name.setBackground(Style.card(Style.CARD_HI, context));
        name.setPadding(inPad, inPad, inPad, inPad);
        card.addView(name);
        Style.gap(card, context, 12);

        EditText input = new EditText(context);
        input.setHint(context.getString(R.string.charge_cost_input_hint, Prefs.getCurrency(context)));
        input.setTextColor(Style.TEXT);
        input.setHintTextColor(Style.TEXT_DIM);
        input.setTextSize(22f);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[0]);
        input.setBackground(Style.card(Style.CARD_HI, context));
        input.setPadding(inPad, inPad, inPad, inPad);
        input.setText(ChargeCostDialog.formatCents(0));
        input.addTextChangedListener(new TextWatcher() {
            private boolean formatting;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (formatting) return;
                formatting = true;
                String f = ChargeCostDialog.formatCents(ChargeCostDialog.centsFromText(value.toString()));
                input.setText(f);
                input.setSelection(f.length());
                formatting = false;
            }
        });
        card.addView(input);

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = Style.dp(context, 18);
        btnRow.setLayoutParams(brLp);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(outer).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        btnRow.addView(Style.cardButton(context, context.getString(android.R.string.cancel), false, dialog::dismiss));
        Style.gap(btnRow, context, 10);
        btnRow.addView(Style.cardButton(context, context.getString(R.string.charge_cost_btn_save), true, () -> {
            double cost = ChargeCostDialog.centsFromText(input.getText().toString()) / 100.0;
            dialog.dismiss();
            Double sp = speed != null && i < speed.length ? speed[i] : null;
            TollTracker.addUserToll(context, tripId, ts[i], lat[i], lon[i], sp, bearingAt(lat, lon, i),
                name.getText().toString(), kind[0], cost, onDone);
        }));
        card.addView(btnRow);

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Style.dp(context, 980), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** Extracts the local GPS track slice around a toll crossing (within 900 m & 3 min)
     * so the mini-map shows street/interchange detail and travel direction without
     * including a return trip passing the same highway hours later. */
    static double[][] extractLocalSlice(long tsMs, double lat, double lon,
                                        long[] trackTs, double[] trackLat, double[] trackLon, double[] trackSpeed) {
        if (trackTs == null || trackLat == null || trackLon == null || trackLat.length < 2) return null;
        int n = Math.min(trackTs.length, Math.min(trackLat.length, trackLon.length));
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (Math.abs(trackTs[i] - tsMs) > LOCAL_TIME_WINDOW_MS) continue;
            if (TollMatcher.distanceM(lat, lon, trackLat[i], trackLon[i]) <= LOCAL_RADIUS_M) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0 || last <= first) return null;
        int len = last - first + 1;
        double[] sLat = new double[len], sLon = new double[len], sSpd = new double[len];
        for (int i = 0; i < len; i++) {
            int idx = first + i;
            sLat[i] = trackLat[idx];
            sLon[i] = trackLon[idx];
            sSpd[i] = (trackSpeed != null && idx < trackSpeed.length) ? trackSpeed[idx] : 0;
        }
        return new double[][]{sLat, sLon, sSpd};
    }
}
