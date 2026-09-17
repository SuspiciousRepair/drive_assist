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
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Style;

import java.util.Locale;

/** Dialog for inputting and editing the monetary cost of a charging session. */
public final class ChargeCostDialog {

    private ChargeCostDialog() {}

    public static AlertDialog show(Context context, long sessionId, double kwh, int socStart, int socEnd,
                                   Double currentCost, Runnable onUpdated) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Style.card(Style.CARD, context));
        int pad = Style.dp(context, 24);
        card.setPadding(pad, pad, pad, pad);
        card.setLayoutParams(new ViewGroup.LayoutParams(
            Style.dp(context, 520), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.charge_cost_input_title));
        title.setTextColor(Style.TEXT);
        title.setTextSize(22f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(title);

        String socRange = (socStart >= 0 && socEnd >= 0) ? (socStart + "% ➔ " + socEnd + "%") : "";
        TextView subtitle = new TextView(context);
        subtitle.setText(String.format(Locale.US, "+%.1f kWh  •  %s", kwh, socRange));
        subtitle.setTextColor(Style.TEXT_DIM);
        subtitle.setTextSize(15f);
        subtitle.setPadding(0, Style.dp(context, 4), 0, Style.dp(context, 14));
        card.addView(subtitle);

        EditText input = new EditText(context);
        input.setHint(context.getString(R.string.charge_cost_input_hint));
        input.setTextColor(Style.TEXT);
        input.setHintTextColor(Style.TEXT_DIM);
        input.setTextSize(22f);
        input.setTypeface(input.getTypeface(), android.graphics.Typeface.BOLD);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        // TYPE_CLASS_NUMBER installs a digits-only InputFilter as well as a
        // numeric KeyListener. Keep the KeyListener/keyboard, but allow the
        // formatter to insert the visual comma into the Editable.
        input.setFilters(new InputFilter[0]);
        input.setBackground(Style.card(Style.CARD_HI, context));
        int inPad = Style.dp(context, 14);
        input.setPadding(inPad, inPad, inPad, inPad);
        input.setText(formatCents(currentCost == null ? 0 : Math.round(currentCost * 100)));
        input.addTextChangedListener(new TextWatcher() {
            private boolean formatting;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override public void afterTextChanged(Editable value) {
                if (formatting) return;
                formatting = true;
                String formatted = formatCents(centsFromText(value.toString()));
                input.setText(formatted);
                input.setSelection(formatted.length());
                formatting = false;
            }
        });
        input.selectAll();
        card.addView(input);

        // Action row
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = Style.dp(context, 20);
        btnRow.setLayoutParams(brLp);

        AlertDialog dialog = builder.setView(card).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView btnCancel = Style.cardButton(context, context.getString(android.R.string.cancel), false, dialog::dismiss);
        btnRow.addView(btnCancel);
        Style.gap(btnRow, context, 10);

        TextView btnPerKwh = Style.cardButton(context,
            context.getString(R.string.charge_cost_btn_per_kwh), false, () -> {
                double cost = totalCostFromRateCents(centsFromText(input.getText().toString()), kwh);
                input.setText(formatCents(Math.round(cost * 100)));
                input.setSelection(input.length());
            });
        btnRow.addView(btnPerKwh);
        Style.gap(btnRow, context, 10);

        TextView btnSave = Style.cardButton(context, context.getString(R.string.charge_cost_btn_save), true, () -> {
            try {
                double cost = centsFromText(input.getText().toString()) / 100.0;
                saveCost(context, dialog, sessionId, cost, onUpdated);
            } catch (NumberFormatException e) {
                Toast.makeText(context, context.getString(R.string.charge_cost_invalid), Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave);
        card.addView(btnRow);

        dialog.show();

        // Focus and request the compact digits keyboard after display.
        input.requestFocus();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            input.selectAll();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);

        return dialog;
    }

    private static void saveCost(Context context, AlertDialog dialog, long sessionId,
                                 double cost, Runnable onUpdated) {
        ChargeSession.updateCost(context, sessionId, cost);
        dialog.dismiss();
        if (onUpdated != null) onUpdated.run();
    }

    static double totalCostFromRateCents(long rateCents, double kwh) {
        return Math.round(rateCents * kwh) / 100.0;
    }

    static long centsFromText(String text) {
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    static String formatCents(long cents) {
        return String.format(Locale.getDefault(), "%d,%02d", cents / 100, cents % 100);
    }
}
