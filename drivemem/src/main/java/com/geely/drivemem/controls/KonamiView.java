package com.geely.drivemem.controls;

import com.geely.drivemem.art.PanelCardView;
import com.geely.drivemem.car.CarAccess;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/** Invisible tap gesture detector for unlocking a hidden theme.
 *
 * Recognizes an eight-tap Konami code: UP, UP, DOWN, DOWN, LEFT, RIGHT, LEFT, RIGHT.
 * The trick: a repeated direction means staying in place (no travel required), while
 * a new direction requires movement. Each tap is measured relative to the previous tap,
 * not from a fixed origin.
 *
 * Layout: positioned as the lowest child of rightPanel so it sits beneath the HA card
 * but above full-bleed art. Touch is dispatched top-first; when HA card is showing,
 * it consumes touches and the gesture detector is inert. Wrong taps restart from that
 * point, allowing correction mid-sequence. Incomplete sequences timeout after RESET_MS.
 */
public class KonamiView extends View {
    private static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    private static final int[] SEQ = { UP, UP, DOWN, DOWN, LEFT, RIGHT, LEFT, RIGHT };
    private static final long RESET_MS = 6000;

    // Tap detection threshold: 12% of the smallest dimension (min 48dp).
    // Each tap is measured relative to the previous tap; movement threshold
    // distinguishes between "tap in the same place" and "clearly moved".
    private static final float MIN_MOVE_FRAC = 0.12f;
    private static final float MIN_MOVE_FLOOR_DP = 48f;
    private static final float DOMINANCE = 1.3f;   // how clearly one axis must win

    // Tap feedback: accepted taps show N faint dots (counting progress), resetting
    // to 1 on error. Provides visual progress indication since logcat is unavailable
    // in the car. Brief and dim enough to avoid accidental discovery.
    private static final long  DOT_MS = 900;
    private static final float DOT_R_DP = 5f;
    private static final float DOT_GAP_DP = 16f;

    private final Runnable onUnlock;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float floorPx, dotR, dotGap;
    private float minMove;
    private int idx = 0;
    private long lastTap = 0;
    private float ax = -1f, ay = -1f;   // the previous tap: the origin of the next move

    private float fx, fy;               // where to draw the feedback
    private int fn = 0;                 // how many dots
    private long fT = 0;

    public KonamiView(Context c, Runnable onUnlock) {
        super(c);
        this.onUnlock = onUnlock;
        float d = c.getResources().getDisplayMetrics().density;
        this.floorPx = MIN_MOVE_FLOOR_DP * d;
        this.dotR    = DOT_R_DP * d;
        this.dotGap  = DOT_GAP_DP * d;
        this.minMove = floorPx;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Threshold based on narrower dimension to keep moves within panel bounds.
        minMove = Math.max(floorPx, MIN_MOVE_FRAC * Math.min(w, h));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;

        // Use timestamp-based reset instead of postDelayed (simpler lifecycle).
        long now = SystemClock.uptimeMillis();
        if (now - lastTap > RESET_MS) idx = 0;
        lastTap = now;

        float x = e.getX(), y = e.getY();
        if (idx == 0) { mark(x, y); return true; }      // tap 1: free, sets the mark

        float dx = x - ax, dy = y - ay;
        float adx = Math.abs(dx), ady = Math.abs(dy);
        boolean travelled = Math.max(adx, ady) >= minMove;

        if (SEQ[idx] == SEQ[idx - 1]) {
            // Repeat: no travel required for same direction.
            if (travelled) { mark(x, y); return true; }
        } else {
            // Direction change: travel is required.
            if (!travelled) { mark(x, y); return true; }
            // Too diagonal: unclear which axis was intended. Restart from here.
            if (Math.max(adx, ady) < DOMINANCE * Math.min(adx, ady)) { mark(x, y); return true; }
            int dir = adx > ady ? (dx > 0 ? RIGHT : LEFT) : (dy > 0 ? DOWN : UP);
            if (dir != SEQ[idx]) { mark(x, y); return true; }
        }
        // Mark moves on every accepted tap, including repeats.
        ax = x; ay = y;
        idx++;

        Log.i(CarAccess.TAG, "konami " + idx + "/" + SEQ.length);
        flash(x, y, idx);

        if (idx == SEQ.length) { idx = 0; onUnlock.run(); }
        return true;
    }

    // Restart from here: this tap becomes tap 1.
    private void mark(float x, float y) {
        ax = x; ay = y; idx = 1;
        Log.i(CarAccess.TAG, "konami 1/" + SEQ.length + " (mark)");
        flash(x, y, 1);
    }

    private void flash(float x, float y, int n) {
        fx = x; fy = y; fn = n; fT = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        if (fn <= 0) return;
        long age = SystemClock.uptimeMillis() - fT;
        if (age >= DOT_MS) { fn = 0; return; }
        float k = 1f - age / (float) DOT_MS;
        dot.setColor(0x00FFFFFF | ((int) (150 * k) << 24));
        float span = (fn - 1) * dotGap;
        for (int i = 0; i < fn; i++)
            cv.drawCircle(fx - span * 0.5f + i * dotGap, fy, dotR, dot);
        postInvalidateOnAnimation();
    }
}
