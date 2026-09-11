package com.geely.drivemem.hvac;

import com.geely.drivemem.R;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.net.MqttReporter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

/**
 * Renders the HVAC effort scale (C5..0..W5), mapping car state to effort level
 * based on an absolute measurement (how hard the car is working), not a delta.
 * Lever taps are pure state updates on the ruler's own thread; car I/O
 * (apply, verify, maintenance) runs on CarActor's thread to avoid conflicts.
 * See COMFORT-TABLE.md for design rationale.
 */
// The effort number is absolute (C5..0..W5), readable instantly without prior
// interaction. The scale is fixed (±5 always); nothing decays or requires
// commitment. Taps are UI-only (no car I/O), so bursts never wait on the car.
// Lever positions are reconciled in the background (doMaintenance) and after
// each apply(). Outside temperature drift (2°C sustained for 10min) triggers a
// re-slide, preserving the stated effort level while the recipe adapts.
public class ComfortRuler {
    static final String TAG = CarAccess.TAG;

    // Delay for a single late re-check of apply(). One attempt only, not a loop.
    static final long VERIFY_MS = 800;

    // Re-sliding the table. Both conditions, not either.
    static final float DRIFT_C  = 2f;
    static final long  DRIFT_MS = 10 * 60 * 1000L;
    static final long  QUIET_AFTER_TAP_MS = 60 * 1000L;

    private final Context ctx;
    private final Handler h;    // the reducer: tap() only, no car I/O
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable onChange;

    private volatile int level = 0;            // +5 = C5 ... 0 ... -5 = W5
    private volatile boolean armed = false;
    private volatile String status = "";
    // True if a lever is still out of line (not exact match to column). See
    // "C4ish" in COMFORT-TABLE.md. Written only from CarActor's thread.
    private volatile boolean approx = false;
    // True if the physical defrost button is active. The scale freezes while
    // defrost is held.
    private volatile boolean defrosting = false;
    private volatile int direction = 0;

    private EffortTable.Column[] table;
    private float tableOutC = Float.NaN;
    private long lastTapMs = 0;
    // Timestamp when outside temp first drifted beyond deadband; 0 if not drifted
    private long driftSinceMs = 0;

    // Single-slot mailbox for coalescing a burst of taps into one write.
    // applyStale = true means level changed while a write was in flight.
    private volatile boolean applyStale = false;
    private volatile boolean applying = false;

    // Direct access to CarAccess. MUST be called from CarActor's thread only
    // (via runOnCarThread()), not from the reducer thread h. The reducer uses
    // cachedOutsideTempC() instead.
    private CarAccess car() { return CarActor.get(ctx).rawAccess(); }

    private Float cachedOutsideTempC() {
        CarActor.Reading r = CarActor.get(ctx).get("telemetry.outside_temp");
        return (r.status == CarActor.Reading.Status.OK && r.value instanceof Float) ? (Float) r.value : null;
    }

    public ComfortRuler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        HandlerThread t = new HandlerThread("ruler");
        t.start();
        this.h = new Handler(t.getLooper());
    }

    /** Sets a callback to run on the main thread whenever the ruler state changes. */
    public void setOnChange(Runnable r) { this.onChange = r; }

    /** Returns the current effort level (-5..5). */
    public int pointer()  { return level; }

    /** Returns the current status message. */
    public String status(){ return status; }

    /** Returns true if a lever is slightly out of line. See "C4ish" in COMFORT-TABLE.md. */
    public boolean approx(){ return approx; }

    /** Returns true if the physical defrost button is active. */
    public boolean defrosting(){ return defrosting; }

    /** Returns the current airflow direction bitmask, or 0 if power is off. */
    public int direction() {
        if (defrosting) return EffortTable.DIR_GLASS;
        return direction;
    }

    /** Returns the maximum cooling level (always 5). */
    public int coolMax()  { return EffortTable.MAX_LEVEL; }

    /** Returns the maximum heating level (always 5). */
    public int heatMax()  { return EffortTable.MAX_LEVEL; }

    /** Adjusts the effort level by the given delta (+1 cooler, -1 warmer). */
    public void tap(int dir) {
        h.post(() -> {
            if (!armed && !arm()) { notifyUi(); return; }

            // A press always rebuilds the table for today's weather. The table is
            // built once at arm() but outside temp may have drifted significantly
            // (this unit suspends rather than shutting down). Read-only operation.
            // The 2°C/10min drift guard in doMaintenance() prevents unexpected
            // changes; a press is user-initiated so it uses current weather.
            // Uses cachedOutsideTempC() (not direct car access) since this runs
            // on the reducer thread, not CarActor's thread.
            Float outNow = cachedOutsideTempC();
            if (outNow != null && Math.abs(outNow - tableOutC) >= DRIFT_C) {
                Log.i(TAG, "ruler: table was built for " + tableOutC + "C, now "
                         + outNow + "C — rebuilding before the press");
                table = EffortTable.build(outNow);
                tableOutC = outNow;
                driftSinceMs = 0;
            }

            // Do not refit before this tap. Level is moved purely by user taps;
            // the car catches up asynchronously via drainApply(). If a tap is
            // issued, clear any defrosting flag immediately for instant UI feedback.
            defrosting = false;

            long quietS = lastTapMs == 0 ? -1 : (System.currentTimeMillis() - lastTapMs) / 1000;
            ComfortEvents.event(ctx, "tap", ComfortEvents.lite(level),
                             "dir=" + dir + " quiet=" + quietS + (approx ? " approx" : ""));
            lastTapMs = System.currentTimeMillis();

            // Settling an approximate position: pressing TOWARDS zero does not
            // move the column, it makes the one we are already at exact. From
            // "C4ish", Warm gives a full C4 and Cool gives a full C5 — both are
            // one press, and neither is a surprise.
            boolean towardZero = (level > 0 && dir < 0) || (level < 0 && dir > 0);
            int want = (approx && towardZero) ? level : level + dir;

            if (want >  EffortTable.MAX_LEVEL) want =  EffortTable.MAX_LEVEL;
            if (want < -EffortTable.MAX_LEVEL) want = -EffortTable.MAX_LEVEL;

            if (want == level && !approx) {
                status = ctx.getString(R.string.ruler_at_limit, describe(level));
                notifyUi();
                return;
            }
            level = want;
            if (table != null) {
                EffortTable.Column c = EffortTable.at(table, level);
                direction = c.power ? c.direction : 0;
            }
            status = describe(level);
            notifyUi();          // instant: the dot moves now, not after the car answers
            scheduleApply();     // the car catches up in the background
        });
    }

    /** Posts a drainApply task to CarActor's thread. Called only from tap(). */
    private void scheduleApply() {
        applyStale = true;
        CarActor.get(ctx).runOnCarThread(this::drainApply);
    }

    /** Applies current level to the car. Runs on CarActor's thread, coalescing
     * a burst of taps into a single write. */
    private void drainApply() {
        if (applying) return;
        applying = true;
        try {
            while (applyStale) {
                applyStale = false;
                apply(level);
            }
        } finally {
            applying = false;
        }
    }

    /** Triggers background maintenance (refit from car state, re-slide if weather drifted). */
    public void maintenance() { CarActor.get(ctx).runOnCarThread(this::doMaintenance); }

    /** Ensures the ruler is initialized and ready for display (read-only). */
    public void ensureArmedForDisplay() {
        h.post(() -> { if (!armed && arm()) notifyUi(); });
    }

    /** Shuts down the ruler's threads and cancels pending work. */
    public void shutdown() {
        h.removeCallbacksAndMessages(null);
        h.getLooper().quitSafely();
        CarActor.get(ctx).cancelOnCarThread(verify);
    }

    // ---- internal ----

    /** Initializes the ruler by reading car state. Bridges to CarActor's thread. */
    private boolean arm() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        boolean[] result = {false};
        CarActor.get(ctx).runOnCarThread(() -> {
            result[0] = armOnCarThread();
            latch.countDown();
        });
        try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    /** Runs on CarActor's thread. Builds the table and reads car state (no writes). */
    private boolean armOnCarThread() {
        // Try to connect/reconnect now. Unit suspends frequently, so an earlier
        // connection failure should not disable the ruler for the process lifetime.
        if (!car().isReady() && !car().connect(ctx)) {
            status = ctx.getString(R.string.ruler_no_car);
            return false;
        }
        Float outC = car().readOutsideTempC();
        if (outC == null) {
            status = ctx.getString(R.string.ruler_no_outside_temp);
            return false;
        }
        table = EffortTable.build(outC);
        tableOutC = outC;
        armed = true;
        refit();
        // Initialize silence timer. Needed for drift detection in maintenance().
        lastTapMs = System.currentTimeMillis();
        driftSinceMs = 0;
        Log.i(TAG, "ruler: armed out=" + outC + "C at " + EffortTable.name(level)
                 + (approx ? "ish" : " exactly"));
        return true;
    }

    /** Reads all car HVAC levers and fits them to the current table level. */
    private void refit() {
        if (table == null) return;
        // Defrost button drives settings the table doesn't describe. Freeze the
        // ruler display until defrost is released.
        Boolean defrost = car().readHvacFlag(CarAccess.HVAC_MAX_DEFROST);
        defrosting = defrost != null && defrost;
        if (defrosting) {
            direction = EffortTable.DIR_GLASS;
            status = ctx.getString(R.string.ruler_defrosting);
            return;
        }
        Boolean ac  = car().readHvacFlag(CarAccess.HVAC_AC_ON);
        Boolean rec = car().readHvacFlag(CarAccess.HVAC_RECIRC_ON);
        Boolean pwr = car().readHvacFlag(CarAccess.HVAC_POWER_ON);
        Integer dir = car().readIntRaw(EffortTable.DIR_PROP, 0);
        float sp    = car().readSetpoint();
        int fan     = car().readFan();
        if (ac == null || dir == null || fan < 0) {
            status = ctx.getString(R.string.ruler_no_car);
            return;
        }
        boolean machine = ac;
        boolean recirc  = rec != null && rec;
        // Treat unreadable power as ON to ensure a running car is describable.
        boolean power   = pwr == null || pwr;

        level  = EffortTable.fit(table, tableOutC, power, machine, sp, fan, dir, recirc);
        approx = !matches(EffortTable.at(table, level), power, machine, sp, fan, dir, recirc);
        status = describe(level);
        direction = power ? dir : 0;
    }

    /** Returns true if the car state matches the given column exactly. */
    private boolean matches(EffortTable.Column c, boolean power, boolean machine, float sp,
                            int fan, int dir, boolean recirc) {
        if (c.power != power) return false;
        // If off, the other levers don't matter. A parked car may have any lever
        // values left over; don't mark it approximate on that basis.
        if (!c.power) return true;
        if (c.machine != machine) return false;
        if (c.fan != fan) return false;
        if (c.direction != dir) return false;
        if (c.recirc != recirc) return false;
        if (c.machine && !Float.isNaN(sp) && Math.abs(c.setpointC - sp) >= 0.5f) return false;
        return true;
    }

    /** Applies the column for the given effort level to the car. */
    private void apply(int lv) {
        // ORDER MATTERS: setpoint before compressor (prevents wrong-mode running).
        // Flags use ensureFlag() to read first (they toggle on write, not set).
        if (!armed || !car().isReady() || table == null) return;
        EffortTable.Column c = EffortTable.at(table, lv);

        // A press claims the whole column back, whatever it is — including from
        // the physical defrost button. ensureFlag no-ops when it was already
        // off, so this costs nothing on an ordinary tap.
        ensureFlag(CarAccess.HVAC_MAX_DEFROST, false);

        // Column zero turns the machine off. Park the setpoint at a neutral value
        // so the compressor doesn't start at full effort if power is restored.
        if (!c.power) {
            float spOff = car().readSetpoint();
            if (!Float.isNaN(spOff) && Math.abs(spOff - EffortTable.CABIN_TARGET_C) >= 0.5f) {
                car().nudgeSetpoint(EffortTable.CABIN_TARGET_C - spOff);
            }
            ensureFlag(CarAccess.HVAC_POWER_ON, false);
            direction = 0;
            approx = false;
            status = describe(lv);
            notifyUi();
            ComfortEvents.event(ctx, "apply", ComfortEvents.snap(car(), lv),
                             "col=" + c + " out=" + tableOutC);
            CarActor.get(ctx).cancelOnCarThread(verify);
            CarActor.get(ctx).runOnCarThreadDelayed(verify, VERIFY_MS);
            return;
        }

        ensureFlag(CarAccess.HVAC_POWER_ON, true);

        if (c.machine && !Float.isNaN(c.setpointC)) {
            float sp = car().readSetpoint();
            if (!Float.isNaN(sp) && Math.abs(sp - c.setpointC) >= 0.5f) {
                car().nudgeSetpoint(c.setpointC - sp);
            }
        }
        ensureFlag(CarAccess.HVAC_AC_ON, c.machine);

        // Park setpoint at a neutral value so future power-on doesn't default to
        // maximum effort.
        if (!c.machine) {
            float sp = car().readSetpoint();
            if (!Float.isNaN(sp) && Math.abs(sp - EffortTable.CABIN_TARGET_C) >= 0.5f) {
                car().nudgeSetpoint(EffortTable.CABIN_TARGET_C - sp);
            }
        }

        ensureFlag(CarAccess.HVAC_RECIRC_ON, c.recirc);

        // Only write direction if it changed (avoid unnecessary re-planning).
        Integer dirNow = car().readIntRaw(EffortTable.DIR_PROP, 0);
        if (dirNow != null && dirNow != c.direction) {
            car().setIntRaw(EffortTable.DIR_PROP, 0, c.direction);
        }

        int fanNow = car().readFan();
        if (fanNow >= 0 && fanNow != c.fan) car().nudgeFan(c.fan - fanNow);

        direction = c.direction;
        approx = false;
        status = describe(lv);
        notifyUi();
        ComfortEvents.event(ctx, "apply", ComfortEvents.snap(car(), lv),
                         "col=" + c + " out=" + tableOutC);
        // Schedule late re-check on CarActor's thread (same as column-zero case).
        CarActor.get(ctx).cancelOnCarThread(verify);
        CarActor.get(ctx).runOnCarThreadDelayed(verify, VERIFY_MS);
    }

    /** One-time late re-check after apply(). Verifies critical settings took effect. */
    private final Runnable verify = new Runnable() {
        @Override public void run() {
            if (!armed || !car().isReady() || table == null) return;
            EffortTable.Column c = EffortTable.at(table, level);
            StringBuilder again = new StringBuilder();

            // Check power first. On column zero, skip other levers (a stopped
            // blower shouldn't trigger a fan re-check that restarts it).
            Boolean pwr = car().readHvacFlag(CarAccess.HVAC_POWER_ON);
            if (pwr != null && pwr != c.power) {
                car().setHvacFlag(CarAccess.HVAC_POWER_ON, c.power);
                again.append(" power");
            }
            if (!c.power) {
                ComfortEvents.event(ctx, "verify", ComfortEvents.snap(car(), level),
                                 again.length() == 0 ? "ok" : "rewrote" + again);
                notifyUi();
                return;
            }

            Boolean ac = car().readHvacFlag(CarAccess.HVAC_AC_ON);
            if (ac != null && ac != c.machine) {
                car().setHvacFlag(CarAccess.HVAC_AC_ON, c.machine);
                again.append(" ac");
            }
            Boolean rec = car().readHvacFlag(CarAccess.HVAC_RECIRC_ON);
            if (rec != null && rec != c.recirc) {
                car().setHvacFlag(CarAccess.HVAC_RECIRC_ON, c.recirc);
                again.append(" recirc");
            }
            if (c.machine && !Float.isNaN(c.setpointC)) {
                float sp = car().readSetpoint();
                if (!Float.isNaN(sp) && Math.abs(sp - c.setpointC) >= 0.5f) {
                    car().nudgeSetpoint(c.setpointC - sp);
                    again.append(" sp");
                    float now = car().readSetpoint();
                    if (!Float.isNaN(now) && Math.abs(now - c.setpointC) >= 0.5f) {
                        status = ctx.getString(R.string.ruler_car_refused, (int) c.setpointC);
                        Log.w(TAG, "ruler: the car held at " + now + " (wanted " + c.setpointC + ")");
                    }
                }
            }
            int fan = car().readFan();
            if (fan >= 0 && fan != c.fan) { car().nudgeFan(c.fan - fan); again.append(" fan"); }

            ComfortEvents.event(ctx, "verify", ComfortEvents.snap(car(), level),
                             again.length() == 0 ? "ok" : "rewrote" + again);
            notifyUi();
        }
    };

    /** Sets an HVAC flag only if it differs from the desired value (flags toggle). */
    private void ensureFlag(int prop, boolean want) {
        Boolean cur = car().readHvacFlag(prop);
        if (cur != null && cur == want) return;
        car().setHvacFlag(prop, want);
    }

    /** Background maintenance: reconcile car state and re-slide if weather drifts. */
    private void doMaintenance() {
        if (!armed || table == null) return;
        long now = System.currentTimeMillis();

        // Reconcile with car state. If a hand on the OEM panel moved a lever,
        // the display must follow it. Read-only operation.
        int was = level;
        refit();
        if (level != was) {
            Log.i(TAG, "ruler: the car moved under us, " + EffortTable.name(was)
                     + " -> " + EffortTable.name(level));
            notifyUi();
        }

        // If a lever is out of line (approx=true), a hand is on the OEM panel.
        // Don't re-slide, since that would just undo the manual change. Tapping
        // Cool or Warm settles the approximate position and returns control.
        if (approx) return;

        Float outC = car().readOutsideTempC();
        if (outC == null) return;

        boolean far = Math.abs(outC - tableOutC) >= DRIFT_C;
        if (!far) { driftSinceMs = 0; return; }          // came back; start over
        if (driftSinceMs == 0) { driftSinceMs = now; return; }
        if (now - driftSinceMs < DRIFT_MS) return;        // not sustained yet
        if (now - lastTapMs < QUIET_AFTER_TAP_MS) return; // never mid-complaint

        // Re-build table and re-apply same level. Effort level is preserved;
        // the recipe adapts as free air takes over from machine.
        int held = level;
        table = EffortTable.build(outC);
        tableOutC = outC;
        driftSinceMs = 0;
        Log.i(TAG, "ruler: weather moved to " + outC + "C — re-applying " + EffortTable.name(held));
        ComfortEvents.event(ctx, "reslide", ComfortEvents.snap(car(), held), "out=" + outC);
        apply(held);
    }

    private String describe(int lv) {
        if (lv == 0) return ctx.getString(R.string.ruler_steady);
        return ctx.getString(R.string.ruler_level,
            ctx.getString(lv > 0 ? R.string.ruler_src_cold : R.string.ruler_src_hot),
            Math.abs(lv));
    }

    private void notifyUi() { if (onChange != null) ui.post(onChange); }
}
