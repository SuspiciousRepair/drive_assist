package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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
        title.setTypeface(Typeface.DEFAULT_BOLD);
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
        input.setTypeface(Typeface.DEFAULT_BOLD);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setBackground(Style.card(Style.CARD_HI, context));
        int inPad = Style.dp(context, 14);
        input.setPadding(inPad, inPad, inPad, inPad);
        if (currentCost != null && currentCost > 0) {
            input.setText(String.format(Locale.US, "%.2f", currentCost));
        } else {
            input.setText("0");
        }
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

        TextView btnSave = Style.cardButton(context, context.getString(R.string.charge_cost_btn_save), true, () -> {
            String txt = input.getText().toString().trim().replace(',', '.');
            if (txt.isEmpty()) {
                txt = "0";
            }
            try {
                double cost = Double.parseDouble(txt);
                if (cost < 0) {
                    Toast.makeText(context, context.getString(R.string.charge_cost_invalid), Toast.LENGTH_SHORT).show();
                    return;
                }
                ChargeSession.updateCost(context, sessionId, cost);
                dialog.dismiss();
                if (onUpdated != null) onUpdated.run();
            } catch (NumberFormatException e) {
                Toast.makeText(context, context.getString(R.string.charge_cost_invalid), Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave);
        card.addView(btnRow);

        dialog.show();

        // Focus and request software keyboard after display
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
}
