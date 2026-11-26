package com.geely.drivemem.ui;

import android.app.Activity;
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
import com.geely.drivemem.state.ParkingCost;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.util.List;

/** Sets what one stop cost: a parked gap (a car park or paid street
 * parking) or one Valet stay. The place field and the price chips come from
 * {@link ParkingCost#suggest}: the spot this stop joins, and the last prices
 * paid there. The owner is the judge: keeping the name joins the spot,
 * another name starts or joins another spot.
 *
 * Same cent keyboard as {@link ChargeCostDialog}, and like it no Park lock. */
public final class ParkingCostDialog {

    private ParkingCostDialog() {}

    /** Reads the place memory off the main thread, then shows the dialog.
     * existing: the row to edit, or null for a new one. valetId: the Valet
     * stay, or null for a parked gap. endMs null: the stop is still going. */
    public static void open(Context context, long startMs, Long endMs, Long valetId,
                            ParkingCost.Row existing, Runnable onUpdated) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            List<ParkingCost.Row> history = ParkingCost.placed(app);
            if (valetId != null) history.removeIf(r -> r.valetId == null);
            else history.removeIf(r -> r.valetId != null);
            double[] here = existing != null && existing.hasPlace()
                ? new double[]{existing.lat, existing.lon}
                : ParkingCost.placeFor(app, startMs, endMs);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (context instanceof Activity && ((Activity) context).isFinishing()) return;
                show(context, startMs, endMs, valetId, existing, history, here, onUpdated);
            });
        }, "parking-dialog").start();
    }

    /** Right after Valet ends in Park: when an earlier Valet stay here had a
     * price, opens the cost dialog with those prices as one-tap chips. With
     * no earlier price, nothing shows (the stats card still takes one). */
    public static void offerAfterValet(Context context, long valetId) {
        if (valetId <= 0) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            long[] span = ParkingCost.valetSpan(app, valetId);
            if (span == null || ParkingCost.forValet(app, valetId) != null) return;
            List<ParkingCost.Row> history = ParkingCost.placed(app);
            history.removeIf(r -> r.valetId == null);
            double[] here = ParkingCost.placeFor(app, span[0], span[1]);
            ParkingCost.Suggestion s = ParkingCost.suggest(history,
                here != null ? here[0] : null, here != null ? here[1] : null, ParkingCost.KIND_LOT, -1);
            if (s.prices.isEmpty()) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (context instanceof Activity && ((Activity) context).isFinishing()) return;
                show(context, span[0], span[1], valetId, null, history, here, null);
            });
        }, "parking-valet").start();
    }

    /** A 12 dp space in a row (Style.gap is a vertical space). */
    static void hGap(LinearLayout row, Context c) {
        row.addView(new View(c), new LinearLayout.LayoutParams(Style.dp(c, 12), 1));
    }

    private static void show(Context context, long startMs, Long endMs, Long valetId,
                             ParkingCost.Row existing, List<ParkingCost.Row> history,
                             double[] here, Runnable onUpdated) {
        final Double lat = here != null ? here[0] : null, lon = here != null ? here[1] : null;
        final long excludeId = existing != null ? existing.id : -1;
        final boolean valet = valetId != null;
        ParkingCost.Suggestion first = ParkingCost.suggest(history, lat, lon,
            existing != null ? existing.kind : null, excludeId);
        final String[] kind = {first.kind};
        final boolean[] nameTouched = {existing != null};

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 24);
        // Where the car stood, when that is known and OpenStreetMap maps are
        // on: a map on the left, the form on the right (like a toll's dialog).
        final boolean withMap = lat != null && lon != null && Prefs.getOsmMapsEnabled(context);
        View root = card;
        if (withMap) {
            LinearLayout outer = new LinearLayout(context);
            outer.setOrientation(LinearLayout.HORIZONTAL);
            outer.setBackground(Style.card(Style.CARD, context));
            outer.setPadding(pad, pad, pad, pad);
            outer.setLayoutParams(new ViewGroup.LayoutParams(
                Style.dp(context, 1040), ViewGroup.LayoutParams.WRAP_CONTENT));
            RouteView map = new RouteView(context);
            map.setBackground(Style.card(Style.BG_BOTTOM, context, 14));
            map.setClipToOutline(true);
            map.setLocationPin(lat, lon, "P");
            // 340 tall: the dialog sits above the on-screen keyboard (top ~460 px).
            LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                Style.dp(context, 400), Style.dp(context, 340));
            mapLp.rightMargin = Style.dp(context, 24);
            outer.addView(map, mapLp);
            outer.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root = outer;
        } else {
            card.setBackground(Style.card(Style.CARD, context));
            card.setPadding(pad, pad, pad, pad);
            card.setLayoutParams(new ViewGroup.LayoutParams(
                Style.dp(context, 620), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView title = new TextView(context);
        title.setText(valet ? R.string.parking_valet_title : R.string.parking_title);
        title.setTextColor(Style.TEXT);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        String when = SessionText.clock(startMs);
        if (endMs != null && endMs > startMs) {
            when += " – " + SessionText.clock(endMs) + "  ·  " + SessionText.duration(endMs - startMs);
        }
        TextView sub = new TextView(context);
        sub.setText(when);
        sub.setTextColor(Style.TEXT_DIM);
        sub.setTextSize(16f);
        sub.setPadding(0, Style.dp(context, 4), 0, Style.dp(context, 14));
        card.addView(sub);

        EditText name = new EditText(context);
        name.setHint(R.string.parking_place_hint);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setTextColor(Style.TEXT);
        name.setHintTextColor(Style.TEXT_DIM);
        name.setTextSize(20f);
        name.setBackground(Style.card(Style.CARD_HI, context));
        int inPad = Style.dp(context, 14);
        name.setPadding(inPad, inPad, inPad, inPad);
        String startName = existing != null ? existing.placeName : first.placeName;
        if (startName != null) name.setText(startName);
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { if (name.hasFocus()) nameTouched[0] = true; }
        });

        EditText input = new EditText(context);
        input.setHint(context.getString(R.string.charge_cost_input_hint, Prefs.getCurrency(context)));
        input.setTextColor(Style.TEXT);
        input.setHintTextColor(Style.TEXT_DIM);
        input.setTextSize(22f);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        // Keep the digits keyboard, let the formatter insert the comma.
        input.setFilters(new InputFilter[0]);
        input.setBackground(Style.card(Style.CARD_HI, context));
        input.setPadding(inPad, inPad, inPad, inPad);
        input.setText(ChargeCostDialog.formatCents(existing == null ? 0 : Math.round(existing.cost * 100)));
        input.addTextChangedListener(new TextWatcher() {
            private boolean formatting;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable value) {
                if (formatting) return;
                formatting = true;
                String f = ChargeCostDialog.formatCents(ChargeCostDialog.centsFromText(value.toString()));
                input.setText(f);
                input.setSelection(f.length());
                formatting = false;
            }
        });

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        Runnable[] fillChips = new Runnable[1];
        fillChips[0] = () -> {
            chips.removeAllViews();
            ParkingCost.Suggestion s = ParkingCost.suggest(history, lat, lon, kind[0], excludeId);
            for (Double price : s.prices) {
                if (chips.getChildCount() > 0) hGap(chips, context);
                TextView chip = Style.cardButton(context, "", false, () -> {
                    input.setText(ChargeCostDialog.formatCents(Math.round(price * 100)));
                    input.setSelection(input.length());
                });
                chip.setText(Style.moneyWithUnit(price, Style.TEXT, Style.UNIT_SCALE_ROW));
                chips.addView(chip);
            }
            chips.setVisibility(chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
            if (!nameTouched[0]) name.setText(s.placeName != null ? s.placeName : "");
        };

        if (!valet) {
            LinearLayout kinds = new LinearLayout(context);
            kinds.setOrientation(LinearLayout.HORIZONTAL);
            TextView[] kindBtn = new TextView[2];
            String[] kindKey = {ParkingCost.KIND_LOT, ParkingCost.KIND_STREET};
            int[] kindLabel = {R.string.parking_kind_lot, R.string.parking_kind_street};
            Runnable paintKinds = () -> {
                for (int i = 0; i < 2; i++) {
                    boolean on = kindKey[i].equals(kind[0]);
                    int fill = on ? Style.CARD_ON : Style.CARD_HI;
                    kindBtn[i].setBackground(Style.card(fill, context));
                    kindBtn[i].setTextColor(Style.onFill(fill));
                }
            };
            for (int i = 0; i < 2; i++) {
                final String k = kindKey[i];
                kindBtn[i] = Style.cardButton(context, context.getString(kindLabel[i]), false, null);
                kindBtn[i].setOnClickListener(v -> {
                    if (k.equals(kind[0])) return;
                    kind[0] = k;
                    paintKinds.run();
                    fillChips[0].run();
                });
                if (i > 0) hGap(kinds, context);
                kinds.addView(kindBtn[i]);
            }
            paintKinds.run();
            card.addView(kinds);
            Style.gap(card, context, 12);
        }

        card.addView(name);
        Style.gap(card, context, 12);
        card.addView(input);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Style.dp(context, 12);
        card.addView(chips, clp);
        fillChips[0].run();

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = Style.dp(context, 20);
        btnRow.setLayoutParams(brLp);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(root).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        if (existing != null) {
            btnRow.addView(Style.cardButton(context, context.getString(R.string.parking_btn_clear), false, () -> {
                dialog.dismiss();
                ParkingCost.delete(context, existing.id, onUpdated);
            }));
            btnRow.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        btnRow.addView(Style.cardButton(context, context.getString(android.R.string.cancel), false, dialog::dismiss));
        Style.gap(btnRow, context, 10);
        btnRow.addView(Style.cardButton(context, context.getString(R.string.charge_cost_btn_save), true, () -> {
            double cost = ChargeCostDialog.centsFromText(input.getText().toString()) / 100.0;
            dialog.dismiss();
            if (cost <= 0) {
                // A free stop is no stop to record: zero clears.
                if (existing != null) ParkingCost.delete(context, existing.id, onUpdated);
                return;
            }
            ParkingCost.Row r = existing != null ? existing : new ParkingCost.Row();
            if (existing == null) {
                r.startMs = startMs;
                r.endMs = endMs;
                r.valetId = valetId;
                r.lat = lat;
                r.lon = lon;
            }
            r.cost = cost;
            r.kind = kind[0];
            r.placeName = name.getText().toString().trim();
            ParkingCost.save(context, r, onUpdated);
        }));
        card.addView(btnRow);

        dialog.show();
        if (withMap && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Style.dp(context, 1060), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        input.requestFocus();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            input.selectAll();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }
}
