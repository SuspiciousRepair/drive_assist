package com.geely.drivemem.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.geely.drivemem.BuildConfig;
import com.geely.drivemem.R;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Style;

/**
 * Driver-facing in-app dialog for reviewing software updates and changelog
 * before accepting or declining the update.
 *
 * CRITICAL SAFETY REQUIREMENT:
 * This dialog MUST NEVER be shown while the vehicle is driving or in gear (D/R/N).
 * It will immediately abort if CarState.isParked() is false.
 */
public class UpdateDialog {
    private static final String TAG = "UpdateDialog";

    public static AlertDialog show(final Activity activity, final Updater.UpdateInfo info, final Runnable onAccept) {
        return show(activity, info, onAccept, null);
    }

    public static AlertDialog show(final Activity activity, final Updater.UpdateInfo info, final Runnable onAccept, final Runnable onDecline) {
        if (activity == null || activity.isFinishing()) return null;

        // Vehicle Safety Rule: NEVER show dialog while driving / in gear
        if (!CarState.isParked()) {
            Log.w(TAG, "Update dialog suppressed: vehicle is not parked (safety lock)");
            return null;
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> show(activity, info, onAccept, onDecline));
            return null;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

            // Container Card
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Style.card(Style.CARD, activity));
            int pad = Style.dp(activity, 24);
            card.setPadding(pad, pad, pad, pad);
            card.setLayoutParams(new ViewGroup.LayoutParams(
                Style.dp(activity, 750), ViewGroup.LayoutParams.WRAP_CONTENT));

            // Title
            TextView title = new TextView(activity);
            title.setText(activity.getString(R.string.update_dialog_title));
            title.setTextColor(Style.TEXT);
            title.setTextSize(22f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(title);

            // Version Comparison Card
            LinearLayout verCard = new LinearLayout(activity);
            verCard.setOrientation(LinearLayout.HORIZONTAL);
            verCard.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams vcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vcLp.setMargins(0, Style.dp(activity, 14), 0, Style.dp(activity, 16));
            verCard.setLayoutParams(vcLp);
            verCard.setBackground(Style.card(Style.CARD_HI, activity));
            int vcPad = Style.dp(activity, 14);
            verCard.setPadding(vcPad, vcPad, vcPad, vcPad);

            // Left: Current
            LinearLayout colCur = new LinearLayout(activity);
            colCur.setOrientation(LinearLayout.VERTICAL);
            colCur.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView curLbl = new TextView(activity);
            curLbl.setText(activity.getString(R.string.update_dialog_current_ver, BuildConfig.VERSION_NAME));
            curLbl.setTextColor(Style.TEXT_DIM);
            curLbl.setTextSize(13f);
            curLbl.setTypeface(Typeface.DEFAULT_BOLD);
            colCur.addView(curLbl);
            verCard.addView(colCur);

            // Arrow
            TextView arrow = new TextView(activity);
            arrow.setText("➔");
            arrow.setTextColor(Style.ACCENT);
            arrow.setTextSize(18f);
            arrow.setPadding(Style.dp(activity, 12), 0, Style.dp(activity, 12), 0);
            verCard.addView(arrow);

            // Right: New
            LinearLayout colNew = new LinearLayout(activity);
            colNew.setOrientation(LinearLayout.VERTICAL);
            colNew.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView newLbl = new TextView(activity);
            String displayNew = info.versionName != null ? info.versionName : "build " + info.versionCode;
            newLbl.setText(activity.getString(R.string.update_dialog_new_ver, displayNew));
            newLbl.setTextColor(Color.parseColor("#30D158"));
            newLbl.setTextSize(13f);
            newLbl.setTypeface(Typeface.DEFAULT_BOLD);
            colNew.addView(newLbl);
            verCard.addView(colNew);

            card.addView(verCard);

            // Changelog Header
            TextView clHdr = new TextView(activity);
            clHdr.setText(activity.getString(R.string.update_dialog_changelog_header));
            clHdr.setTextColor(Style.TEXT_DIM);
            clHdr.setTextSize(13f);
            clHdr.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams clHdrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clHdrLp.setMargins(0, 0, 0, Style.dp(activity, 6));
            clHdr.setLayoutParams(clHdrLp);
            card.addView(clHdr);

            // Changelog Scroll Box
            ScrollView sv = new ScrollScrollView(activity);
            LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(activity, 220));
            sv.setLayoutParams(svLp);
            GradientDrawable logBg = new GradientDrawable();
            logBg.setColor(Style.CARD_HI);
            logBg.setCornerRadius(Style.dp(activity, 8));
            logBg.setStroke(1, Style.STROKE_COLOR);
            sv.setBackground(logBg);
            int svPad = Style.dp(activity, 14);
            sv.setPadding(svPad, svPad, svPad, svPad);

            TextView tvLog = new TextView(activity);
            tvLog.setText(info.changelog != null && !info.changelog.isEmpty()
                ? info.changelog
                : "• Performance improvements and bug fixes.");
            tvLog.setTextColor(Style.TEXT);
            tvLog.setTextSize(13f);
            tvLog.setLineSpacing(Style.dp(activity, 4), 1f);
            sv.addView(tvLog);
            card.addView(sv);

            // Action Buttons Row
            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brLp.setMargins(0, Style.dp(activity, 20), 0, 0);
            btnRow.setLayoutParams(brLp);

            final AlertDialog dialog = builder.setView(card).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            // Decline button
            Button btnDecline = new Button(activity);
            btnDecline.setText(activity.getString(R.string.update_dialog_decline));
            btnDecline.setTextColor(Style.TEXT_DIM);
            btnDecline.setTextSize(14f);
            LinearLayout.LayoutParams decLp = new LinearLayout.LayoutParams(
                Style.dp(activity, 140), Style.dp(activity, 48));
            decLp.setMargins(0, 0, Style.dp(activity, 12), 0);
            btnDecline.setLayoutParams(decLp);
            GradientDrawable decBg = new GradientDrawable();
            decBg.setColor(Style.CARD_HI);
            decBg.setCornerRadius(Style.dp(activity, 8));
            decBg.setStroke(1, Style.STROKE_COLOR);
            btnDecline.setBackground(decBg);
            btnDecline.setOnClickListener(v -> {
                dialog.dismiss();
                if (onDecline != null) onDecline.run();
            });
            btnRow.addView(btnDecline);

            // Accept button
            Button btnAccept = new Button(activity);
            btnAccept.setText(activity.getString(R.string.update_dialog_accept));
            btnAccept.setTextColor(Style.TEXT_ON);
            btnAccept.setTextSize(14f);
            btnAccept.setTypeface(Typeface.DEFAULT_BOLD);
            btnAccept.setLayoutParams(new LinearLayout.LayoutParams(
                Style.dp(activity, 200), Style.dp(activity, 48)));
            GradientDrawable accBg = new GradientDrawable();
            accBg.setColor(Style.ACCENT);
            accBg.setCornerRadius(Style.dp(activity, 8));
            btnAccept.setBackground(accBg);
            btnAccept.setOnClickListener(v -> {
                dialog.dismiss();
                if (onAccept != null) onAccept.run();
            });
            btnRow.addView(btnAccept);

            card.addView(btnRow);

            dialog.setOnCancelListener(d -> {
                if (onDecline != null) onDecline.run();
            });

            dialog.show();
            return dialog;
    }

    private static class ScrollScrollView extends ScrollView {
        public ScrollScrollView(android.content.Context context) {
            super(context);
        }
    }
}
