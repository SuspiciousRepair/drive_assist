package com.geely.drivemem.ui;

import com.geely.drivemem.R;
import com.geely.drivemem.state.GateState;
import com.geely.drivemem.util.Style;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Gate control card: shows gate status and toggles gate on click with border glow feedback. */
public class GateCard extends LinearLayout {

    public interface OnHintListener {
        void onHint(String text);
    }

    private final TextView gateStatusView;
    private final GradientDrawable gateCardBg;
    private final GradientDrawable gateLampBg;
    private ValueAnimator gateGlowAnim;
    private boolean gateDebounced = false;
    private int currentAccent = Style.ACCENT;
    private OnHintListener onHintListener;

    public GateCard(Context context) {
        this(context, null);
    }

    public GateCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = Style.dp(context, 26);
        setPadding(pad, pad, pad, pad);
        setMinimumHeight(Style.dp(context, 108));

        gateCardBg = Style.card(Style.cardFillColor(), context);
        setBackground(gateCardBg);

        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> handleCardClick());

        View lamp = new View(context);
        gateLampBg = new GradientDrawable();
        gateLampBg.setShape(GradientDrawable.OVAL);
        gateLampBg.setColor(currentAccent);
        lamp.setBackground(gateLampBg);
        LinearLayout.LayoutParams lampLp =
            new LinearLayout.LayoutParams(Style.dp(context, 14), Style.dp(context, 14));
        lampLp.rightMargin = Style.dp(context, 22);
        addView(lamp, lampLp);

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.gate_title));
        title.setTextColor(Style.TEXT);
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        gateStatusView = new TextView(context);
        gateStatusView.setTextSize(22);
        gateStatusView.setTypeface(null, Typeface.BOLD);
        gateStatusView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addView(gateStatusView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshStatus();
    }

    public void setOnHintListener(OnHintListener listener) {
        this.onHintListener = listener;
    }

    public void setAccent(int accent) {
        this.currentAccent = accent;
        if (gateLampBg != null) gateLampBg.setColor(accent);
        refreshStatus();
    }

    public void refreshStatus() {
        if (gateStatusView == null) return;
        Context c = getContext();
        if (gateDebounced) {
            gateStatusView.setText(c.getString(R.string.gate_button_sent));
            gateStatusView.setTextColor(currentAccent);
            return;
        }
        String s = GateState.state();
        if ("opening".equals(s)) {
            gateStatusView.setText(c.getString(R.string.gate_opening));
            gateStatusView.setTextColor(currentAccent);
        } else if ("closing".equals(s)) {
            gateStatusView.setText(c.getString(R.string.gate_closing));
            gateStatusView.setTextColor(currentAccent);
        } else if ("open".equals(s)) {
            gateStatusView.setText(c.getString(R.string.gate_button_close));
            gateStatusView.setTextColor(Style.TEXT_DIM);
        } else {
            gateStatusView.setText(c.getString(R.string.gate_button_open));
            gateStatusView.setTextColor(Style.TEXT_DIM);
        }
    }

    private void handleCardClick() {
        android.util.Log.i("DriveMem", "GateCard: clicked, connected=" + GateState.connected() + ", debounced=" + gateDebounced);
        if (gateDebounced || !GateState.connected()) return;
        boolean sent = GateState.press();
        Context c = getContext();
        if (!sent) {
            android.util.Log.w("DriveMem", "GateCard: GateState.press() returned false (no sender)");
            if (onHintListener != null) onHintListener.onHint(c.getString(R.string.gate_no_link));
            return;
        }
        gateDebounced = true;
        if (onHintListener != null) onHintListener.onHint(c.getString(R.string.gate_sent));
        triggerGateGlow();
        refreshStatus();
        postDelayed(() -> {
            gateDebounced = false;
            refreshStatus();
        }, 3000);
    }

    public void triggerGateGlow() {
        if (gateCardBg == null) return;
        if (gateGlowAnim != null) {
            gateGlowAnim.cancel();
        }
        Context c = getContext();
        int glowColor = Style.mixWhite(0xFF000000 | (currentAccent & 0xFFFFFF), 0.40f);
        int restingColor = Style.STROKE_COLOR;
        int restingW = Style.dp(c, Style.STROKE_DP);
        int glowW = Math.max(restingW + Style.dp(c, 2), Style.dp(c, 3));

        gateGlowAnim = ValueAnimator.ofFloat(1f, 0f);
        gateGlowAnim.setDuration(1600);
        gateGlowAnim.setInterpolator(new DecelerateInterpolator());
        gateGlowAnim.addUpdateListener(anim -> {
            float f = (Float) anim.getAnimatedValue();
            if (gateCardBg != null) {
                int w = Math.round(restingW + (glowW - restingW) * f);
                int color = blendArgb(restingColor, glowColor, f);
                gateCardBg.setStroke(w, color);
                invalidate();
            }
        });
        gateGlowAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (gateCardBg != null) {
                    gateCardBg.setStroke(restingW, restingColor);
                    invalidate();
                }
                gateGlowAnim = null;
            }
        });
        gateGlowAnim.start();
    }

    public void onPause() {
        if (gateGlowAnim != null) {
            gateGlowAnim.cancel();
            gateGlowAnim = null;
        }
        if (gateCardBg != null) {
            gateCardBg.setStroke(Style.dp(getContext(), Style.STROKE_DP), Style.STROKE_COLOR);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        onPause();
    }

    private static int blendArgb(int c1, int c2, float t) {
        int a = (int)(Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int)(Color.red(c1)   + (Color.red(c2)   - Color.red(c1))   * t);
        int g = (int)(Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int)(Color.blue(c1)  + (Color.blue(c2)  - Color.blue(c1))  * t);
        return Color.argb(a, r, g, b);
    }
}
