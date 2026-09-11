package com.geely.drivemem.controls;

import com.geely.drivemem.util.Style;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.widget.Switch;

/** Custom Switch with Geely theme colors. Uses Android's native Switch for
 * animation, dragging, and accessibility; applies custom styling only. */
public class GeelySwitch extends Switch {
    public interface OnToggle { void onToggle(boolean on); }

    public static final int DEFAULT_LOCK_S = 5;

    // Theme colors read at construction time (screen recreates on theme change).
    private final int off  = Style.CARD_HI;                                  // track when off
    private final int knob = Style.LIGHT ? 0xFFFFFFFF : 0xFFF2F4F7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int lockSeconds = DEFAULT_LOCK_S;
    private OnToggle listener;
    private boolean suppress = false;

    public GeelySwitch(Context c) {
        super(c);
        // Track and thumb colors for checked/unchecked states.
        int[][] states = new int[][]{ new int[]{ android.R.attr.state_checked }, new int[]{} };
        setTrackTintList(new ColorStateList(states, new int[]{ Style.ACCENT, off }));
        setThumbTintList(new ColorStateList(states, new int[]{ knob, knob }));
        // Extended track makes thumb movement clearly visible.
        setSwitchMinWidth(dp(c, 64));
        setSwitchPadding(dp(c, 8));
        setScaleX(1.6f); setScaleY(1.6f);   // Scaled for car screen interaction.
        setPadding(dp(c, 18), 0, dp(c, 12), 0);
        setShowText(false);

        super.setOnCheckedChangeListener((btn, isChecked) -> {
            if (suppress) return;
            if (listener != null) listener.onToggle(isChecked);
            lock();
        });
    }

    public void setLockSeconds(int s) { lockSeconds = s; }
    public void setOnToggle(OnToggle l) { listener = l; }

    /** Updates the ON track color to follow cabin ambient light. */
    public void setAccent(int rgb) {
        int on = 0xFF000000 | (rgb & 0xFFFFFF);
        int[][] states = new int[][]{ new int[]{ android.R.attr.state_checked }, new int[]{} };
        setTrackTintList(new ColorStateList(states, new int[]{ on, off }));
    }

    /** Sets the checked state without notifying listeners. */
    public void setCheckedSilently(boolean v) {
        suppress = true; setChecked(v); suppress = false;
    }

    private void lock() {
        setEnabled(false); setAlpha(0.5f);
        ui.postDelayed(() -> { setEnabled(true); setAlpha(1f); }, lockSeconds * 1000L);
    }

    private static int dp(Context c, int v) {
        return (int)(v * c.getResources().getDisplayMetrics().density);
    }
}
