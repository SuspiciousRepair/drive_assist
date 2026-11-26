package com.geely.drivemem.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.SecurityLock;
import com.geely.drivemem.util.Style;

/** Car-sized numeric PIN dialog (no virtual keyboard needed) used for unlocking
 * Settings or Valet Mode, setting/changing the PIN, or removing it. */
public final class PinDialog {
    private PinDialog() {}

    /** Prompts for the current PIN before running {@code onUnlocked}. */
    public static void requireUnlock(Context ctx, int titleRes, Runnable onUnlocked) {
        requireUnlock(ctx, titleRes, onUnlocked, null);
    }

    /** Same, and runs {@code onCancelled} when the user closes the keypad
     * without unlocking (Cancel, Back, or a tap outside). */
    public static void requireUnlock(Context ctx, int titleRes, Runnable onUnlocked, Runnable onCancelled) {
        showKeypad(ctx, ctx.getString(titleRes), null, true, (dialog, pin, status) -> {
            long now = System.currentTimeMillis();
            int wait = SecurityLock.lockoutRemainingSec(ctx, now);
            if (wait > 0) {
                status.setText(ctx.getString(R.string.sec_pin_locked_wait, wait));
                status.setTextColor(Style.DANGER);
                return false;
            }
            if (SecurityLock.verifyPin(ctx, pin, now)) {
                dialog.dismiss();
                if (onUnlocked != null) onUnlocked.run();
                return true;
            }
            int after = SecurityLock.lockoutRemainingSec(ctx, now);
            status.setText(after > 0
                ? ctx.getString(R.string.sec_pin_locked_wait, after)
                : ctx.getString(R.string.sec_pin_wrong));
            status.setTextColor(Style.DANGER);
            return false;
        }, onUnlocked, onCancelled);
    }

    /** Two-step flow to set a new 4-6 digit PIN (enter -> confirm). */
    public static void showSetPin(Context ctx, Runnable onChanged) {
        showKeypad(ctx, ctx.getString(R.string.sec_pin_enter_new),
            ctx.getString(R.string.sec_pin_length_hint), false, (d1, firstPin, st1) -> {
                if (!SecurityLock.isValidPin(firstPin)) {
                    st1.setText(ctx.getString(R.string.sec_pin_length_hint));
                    st1.setTextColor(Style.WARN);
                    return false;
                }
                d1.dismiss();
                showKeypad(ctx, ctx.getString(R.string.sec_pin_confirm), null, false, (d2, secondPin, st2) -> {
                    if (!firstPin.equals(secondPin)) {
                        st2.setText(ctx.getString(R.string.sec_pin_mismatch));
                        st2.setTextColor(Style.DANGER);
                        return false;
                    }
                    SecurityLock.setPin(ctx, firstPin);
                    d2.dismiss();
                    Toast.makeText(ctx, R.string.sec_pin_saved, Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                    return true;
                }, null, null);
                return true;
            }, null, null);
    }

    /** Verifies the current PIN and clears it. */
    public static void showRemovePin(Context ctx, Runnable onChanged) {
        requireUnlock(ctx, R.string.sec_pin_remove_title, () -> {
            SecurityLock.clearPin(ctx);
            Toast.makeText(ctx, R.string.sec_pin_removed, Toast.LENGTH_SHORT).show();
            if (onChanged != null) onChanged.run();
        });
    }

    private interface OnSubmit {
        boolean submit(AlertDialog dialog, String pin, TextView status);
    }

    private static void showKeypad(Context ctx, String titleText, String initialHint,
                                   boolean allowForgot, OnSubmit onSubmit, Runnable onHomeReset,
                                   Runnable onCancelled) {
        final StringBuilder buf = new StringBuilder();

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Style.card(Style.CARD, ctx));
        int pad = Style.dp(ctx, 24);
        card.setPadding(pad, pad, pad, pad);
        card.setLayoutParams(new ViewGroup.LayoutParams(
            Style.dp(ctx, 440), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(ctx);
        title.setText(titleText);
        title.setTextColor(Style.TEXT);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView dots = new TextView(ctx);
        dots.setTextColor(Style.TEXT);
        dots.setTextSize(34f);
        dots.setTypeface(Typeface.DEFAULT_BOLD);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(0.28f);
        dots.setPadding(0, Style.dp(ctx, 14), 0, Style.dp(ctx, 6));
        dots.setText("—");
        card.addView(dots);

        TextView status = new TextView(ctx);
        status.setTextColor(Style.TEXT_DIM);
        status.setTextSize(15f);
        status.setGravity(Gravity.CENTER);
        status.setMinHeight(Style.dp(ctx, 24));
        if (initialHint != null) status.setText(initialHint);
        card.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(ctx).setView(card).create();
        // cancel() (Cancel, Back, tap outside) is the only way out that is not
        // a success: success paths call dismiss(), which never fires this.
        if (onCancelled != null) dialog.setOnCancelListener(d -> onCancelled.run());
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        Runnable refreshDots = () -> {
            if (buf.length() == 0) {
                dots.setText("—");
                dots.setTextColor(Style.TEXT_DIM);
            } else {
                StringBuilder d = new StringBuilder();
                for (int i = 0; i < buf.length(); i++) d.append('•');
                dots.setText(d.toString());
                dots.setTextColor(Style.TEXT);
            }
        };

        String[][] rows = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {"⌫", "0", "OK"}
        };
        int gap = Style.dp(ctx, 10);
        for (String[] rowKeys : rows) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = gap;
            for (int c = 0; c < rowKeys.length; c++) {
                final String key = rowKeys[c];
                boolean isOk = "OK".equals(key);
                boolean isDel = "⌫".equals(key);
                TextView btn = new TextView(ctx);
                btn.setText(key);
                btn.setGravity(Gravity.CENTER);
                btn.setTextSize(isOk ? 20f : 26f);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setTextColor(isOk ? Style.onFill(Style.ACCENT) : Style.TEXT);
                btn.setBackground(Style.card(isOk ? Style.ACCENT : Style.CARD_HI, ctx));
                btn.setOnClickListener(v -> {
                    if (isDel) {
                        if (buf.length() > 0) buf.deleteCharAt(buf.length() - 1);
                        refreshDots.run();
                    } else if (isOk) {
                        if (!onSubmit.submit(dialog, buf.toString(), status)) {
                            buf.setLength(0);
                            refreshDots.run();
                        }
                    } else if (buf.length() < SecurityLock.MAX_PIN_LEN) {
                        buf.append(key);
                        refreshDots.run();
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Style.dp(ctx, 64), 1f);
                if (c > 0) blp.leftMargin = gap;
                row.addView(btn, blp);
            }
            card.addView(row, rlp);
        }

        // Bottom row: optional Forgot PIN (home GPS reset) + Cancel
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Style.dp(ctx, 16);

        if (allowForgot && SecurityLock.canOfferForgotPin(ctx)) {
            TextView forgot = new TextView(ctx);
            forgot.setText(R.string.sec_pin_forgot);
            forgot.setTextColor(Style.TEXT_DIM);
            forgot.setTextSize(16f);
            forgot.setPadding(Style.dp(ctx, 8), Style.dp(ctx, 8), Style.dp(ctx, 8), Style.dp(ctx, 8));
            forgot.setOnClickListener(v -> {
                if (SecurityLock.tryHomeReset(ctx)) {
                    dialog.dismiss();
                    Toast.makeText(ctx, R.string.sec_pin_reset_ok, Toast.LENGTH_SHORT).show();
                    if (onHomeReset != null) onHomeReset.run();
                } else {
                    // Intentionally generic so strangers cannot infer the GPS mechanism.
                    status.setText(R.string.sec_pin_reset_unavailable);
                    status.setTextColor(Style.WARN);
                }
            });
            foot.addView(forgot);
        }

        foot.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView cancel = Style.cardButton(ctx, ctx.getString(android.R.string.cancel), false, dialog::cancel);
        foot.addView(cancel);
        card.addView(foot, flp);

        dialog.show();
    }
}
