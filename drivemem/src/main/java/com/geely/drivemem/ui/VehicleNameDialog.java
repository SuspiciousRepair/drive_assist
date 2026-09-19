package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.VehicleProfile;

/** Edits the model and nickname without restarting the activity or touching car settings. */
final class VehicleNameDialog {
    private VehicleNameDialog() {}

    static AlertDialog show(Context context, SharedPreferences prefs) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Style.dp(context, 24);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Style.card(Style.CARD, context, 24));
        card.addView(Style.title(context, context.getString(R.string.ui_vehicle_name_title)));
        TextView hint = Style.label(context, context.getString(R.string.ui_vehicle_name_hint));
        hint.setTextSize(20);
        hint.setTextColor(Style.TEXT_DIM);
        hint.setPadding(0, Style.dp(context, 8), 0, Style.dp(context, 16));
        card.addView(hint);

        String[] selectedModel = {VehicleProfile.getModel(prefs)};
        TextView modelLabel = Style.label(context, context.getString(R.string.ui_vehicle_model_label));
        modelLabel.setTextSize(20);
        modelLabel.setPadding(0, 0, 0, Style.dp(context, 8));
        card.addView(modelLabel);
        LinearLayout models = new LinearLayout(context);
        models.setOrientation(LinearLayout.HORIZONTAL);
        String[] modelIds = {VehicleProfile.MODEL_EX2, VehicleProfile.MODEL_EX2_MAX};
        Button[] modelButtons = new Button[modelIds.length];
        Runnable updateModels = () -> {
            for (int i = 0; i < modelButtons.length; i++) {
                Button button = modelButtons[i];
                boolean selected = modelIds[i].equals(selectedModel[0]);
                int fill = selected ? Style.CARD_ON : Style.CARD_HI;
                button.setSelected(selected);
                button.setTextColor(Style.onFill(fill));
                android.graphics.drawable.GradientDrawable background = Style.card(fill, context, 16);
                background.setStroke(Style.dp(context, selected ? 2 : 1),
                    selected ? Style.CARD_ON : Style.blend(Style.CARD_HI, Style.TEXT_DIM, 0.25f));
                button.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Style.blend(fill, Style.TEXT, 0.15f)),
                    background, null));
            }
        };
        for (int i = 0; i < modelIds.length; i++) {
            String id = modelIds[i];
            Button button = new Button(context);
            modelButtons[i] = button;
            button.setAllCaps(false);
            button.setText(VehicleProfile.modelName(id));
            button.setTextSize(24);
            button.setTypeface(Style.font(context));
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(Style.dp(context, 72));
            button.setPadding(Style.dp(context, 12), Style.dp(context, 12),
                Style.dp(context, 12), Style.dp(context, 12));
            button.setStateListAnimator(null);
            button.setContentDescription(context.getString(R.string.ui_vehicle_model_label)
                + ": " + VehicleProfile.modelName(id));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i == 0) size.rightMargin = Style.dp(context, 12);
            models.addView(button, size);
            button.setOnClickListener(view -> {
                selectedModel[0] = id;
                updateModels.run();
            });
        }
        updateModels.run();
        card.addView(models);

        TextView nameLabel = Style.label(context, context.getString(R.string.ui_vehicle_name_label));
        nameLabel.setTextSize(20);
        nameLabel.setPadding(0, Style.dp(context, 16), 0, Style.dp(context, 8));
        card.addView(nameLabel);
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(VehicleProfile.NAME_LIMIT)});
        input.setTextSize(26);
        input.setTypeface(Style.font(context));
        input.setTextColor(Style.TEXT);
        input.setHintTextColor(Style.TEXT_DIM);
        input.setHint(R.string.ui_vehicle_name_add);
        input.setContentDescription(context.getString(R.string.ui_vehicle_name_label));
        input.setBackground(Style.card(Style.CARD_HI, context));
        input.setPadding(Style.dp(context, 16), Style.dp(context, 12),
            Style.dp(context, 16), Style.dp(context, 12));
        input.setMinHeight(Style.dp(context, 72));
        input.setText(VehicleProfile.customName(prefs));
        input.selectAll();
        card.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(card);
        AlertDialog dialog = new AlertDialog.Builder(context).setView(scroll).create();
        Runnable save = () -> {
            VehicleProfile.saveProfile(prefs, selectedModel[0], input.getText().toString());
            dialog.dismiss();
        };
        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(0, Style.dp(context, 20), 0, 0);
        TextView cancel = Style.cardButton(context, context.getString(android.R.string.cancel), false, dialog::dismiss);
        TextView confirm = Style.cardButton(context, context.getString(R.string.ui_vehicle_name_save), true, save);
        int saveGreen = 0xFF009B46;
        confirm.setBackground(Style.card(saveGreen, context));
        confirm.setTextColor(android.graphics.Color.WHITE);
        for (TextView button : new TextView[] {cancel, confirm}) {
            button.setTextSize(22);
            button.setMinHeight(Style.dp(context, 64));
        }
        confirm.setTextSize(24);
        confirm.setTypeface(Style.font(context), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.rightMargin = Style.dp(context, 12);
        actions.addView(cancel, cancelLp);
        actions.addView(confirm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(actions);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save.run();
            return true;
        });
        dialog.setOnDismissListener(d -> {
            InputMethodManager keyboard = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int width = Math.min(Style.dp(context, 680),
                context.getResources().getDisplayMetrics().widthPixels - Style.dp(context, 48));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        modelButtons[0].requestFocus();
        return dialog;
    }
}
