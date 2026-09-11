package com.geely.drivemem.art;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.ui.ComfortActivity;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

// Base of the panel arts (a skyline in Geely/Claro/Neon, the vaporwave scene
// in Noturno). Handles what they all need and none should reimplement:
//
// 1. ACCELERATION derived from speed — the car exposes no accelerometer, so the
//    change in the speed we read IS the signal. Positive = accelerating,
//    negative = braking (on an electric, regenerating).
//
// 2. FIXED TIME STEP. Integrating "once per frame" ties the animation to the
//    frame rate: on a head unit that throttles on temperature and drops to
//    30 fps the scene starts moving at half speed. Here the clock is in charge
//    — steps() says how many 1/60 s steps fit since the last frame.
public abstract class ArtView extends View {
    protected static final float STEP_S = 1f / 60f;   // fixed physics step
    private static final float STEP_MS  = 1000f / 60f;
    private static final float MAX_ACC_MS = 250f;     // coming back from a pause must not "teleport" the scene

    private static final float ACCEL_REF = 6f;        // km/h per s = full deflection

    protected float speedKmh = 0f;
    protected float accel = 0f;      // -1..1 (negative = braking / regen)

    private long lastSpeedT = 0L;
    private long lastStepT = 0L;
    private float accMs = 0f;

    public ArtView(Context c) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
    }

    protected final float dens;

    // speed in km/h (comes from ComfortActivity's 1 s poll)
    public void setSpeed(float kmh) {
        if (kmh < 0) kmh = 0;
        long now = SystemClock.uptimeMillis();
        if (lastSpeedT != 0) {
            float dt = (now - lastSpeedT) / 1000f;
            if (dt > 0.1f) {
                float a = (kmh - speedKmh) / dt;                  // km/h per second
                accel = Math.max(-1f, Math.min(1f, a / ACCEL_REF));
            }
        }
        lastSpeedT = now;
        speedKmh = kmh;
        // THE POLL MUST NOT WAKE THE SCENE. It arrives at 1 Hz, INCLUDING WITH
        // THE CAR STOPPED, and an unconditional invalidate here used to redraw
        // everything — sky, stars, sun, city plate, ground, ~240 grid lines,
        // scrim, window — once per second in the garage. That cancelled out the
        // rule from section 6 of ARTE.md from the outside: the scene would
        // freeze and the poll would unfreeze it right afterwards.
        //
        // Here we only KICK OFF the chain of frames; what keeps it alive is
        // onDraw itself, for as long as there is something to animate.
        // `wasMoving` delivers the DECELERATION: without it, the last poll
        // (already at zero) would wake nobody and the ground would stay frozen
        // still running.
        boolean moving = kmh > 0.5f || Math.abs(accel) > 0.04f;
        if (moving || wasMoving) postInvalidateOnAnimation();
        wasMoving = moving;
    }

    private boolean wasMoving = false;

    // cabin ambient light colour; each art decides whether to use it
    public abstract void setAmbient(int rgb);

    // Cabin light BRIGHTNESS, raw from the car: 0..CarAccess.AMBIENT_BRIGHT_MAX
    // (20). Same contract as setAmbient — the art gets the raw value and decides.
    // Noturno ignores it; the skyline themes turn it into transparency, so a
    // driver who keeps the cabin light low gets a city that sinks into the
    // background instead of a decoration they did not ask for.
    public void setAmbientBrightness(int level) {}

    // Does the art occupy the WHOLE SCREEN (behind the controls) or only the
    // right-hand third? The art itself answers, not the Activity: a scene that
    // full-bleeds needs to know this in order to paint its own scrim on the
    // left side, and one that does not full-bleed would have no way to. Default:
    // confined.
    //
    // Confined (false) is a no-op today. The panel is now a flowing card, not
    // a fixed column, so only fullBleed=true art is displayed. The hook is left
    // in place for potential future use.
    public boolean fullBleed() { return false; }

    // The HA panel is now an ordinary card that can be overlaid. onHidden() still
    // serves as a generic "this view stopped being visible" hook for settling
    // animators (e.g., Skyline.settle()).
    @Override protected void onWindowVisibilityChanged(int vis) {
        super.onWindowVisibilityChanged(vis);
        if (vis == VISIBLE) return;
        onHidden();
    }

    protected void onHidden() {}

    // how many STEP_S steps have passed since the previous frame
    protected int steps() {
        long now = SystemClock.uptimeMillis();
        if (lastStepT == 0) { lastStepT = now; return 1; }
        accMs += now - lastStepT;
        lastStepT = now;
        if (accMs > MAX_ACC_MS) accMs = MAX_ACC_MS;
        int n = (int) (accMs / STEP_MS);
        accMs -= n * STEP_MS;
        return n;
    }
}
