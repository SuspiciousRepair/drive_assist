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
 * The comfort ruler (C10..0..W5): one tap is one small move on the list of
 * marks (EffortTable). The ruler holds the state it wants the car in; a tap
 * changes that state on the ruler's own thread, and car I/O (apply, verify,
 * maintenance) runs on CarActor's thread. See COMFORT-TABLE.md.
 */
// Taps are UI-only (no car I/O), so bursts never wait on the car. The car is
// read back in the background (doMaintenance) and before the first tap after
// a quiet spell. A sustained change of the outside temperature (2°C for
// 10min) re-reads the weather rules; the mark stays, and at most
// recirculation or the air on the glass changes.
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

    private volatile int level = 0;            // +10 = C10 ... 0 ... -5 = W5
    private volatile boolean armed = false;
    private volatile String status = "";
    // True if a setting is out of line with the mark (a hand on the car's own
    // climate screen): the mark is drawn hollow.
    private volatile boolean approx = false;
    // What the car is set to (after a read) or what we want it set to (after
    // a tap). null until the first read.
    private volatile EffortTable.State cur;
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

    /** Returns the current mark (-5..10; positive = cooling). */
    public int pointer()  { return level; }

    /** Returns the current status message. */
    public String status(){ return status; }

    /** Returns true if a setting is out of line with the mark. */
    public boolean approx(){ return approx; }

    /** Returns true if the physical defrost button is active. */
    public boolean defrosting(){ return defrosting; }

    /** Returns the current airflow direction bitmask, or 0 if power is off. */
    public int direction() {
        if (defrosting) return EffortTable.DIR_GLASS;
        return direction;
    }

    /** Returns the number of cooling marks (always 10). */
    public int coolMax()  { return EffortTable.COOL_MAX; }

    /** Returns the number of heating marks (always 5). */
    public int heatMax()  { return EffortTable.WARM_MAX; }

    /** One tap: +1 cooler, -1 warmer. */
    public void tap(int dir) {
        h.post(() -> {
            if (!armed && !arm()) { notifyUi(); return; }

            // A press always uses today's weather. The table is built once at
            // arm() but outside temp may have drifted significantly (this unit
            // suspends rather than shutting down). Read-only operation.
            // Uses cachedOutsideTempC() (not direct car access) since this runs
            // on the reducer thread, not CarActor's thread.
            Float outNow = cachedOutsideTempC();
            if (outNow != null && Math.abs(outNow - tableOutC) >= DRIFT_C) {
                Log.i(TAG, "ruler: table was built for " + tableOutC + "C, now "
                         + outNow + "C — rebuilding before the press");
                table = buildTable(outNow);
                tableOutC = outNow;
                driftSinceMs = 0;
            }

            // The FIRST tap after a quiet spell re-reads the car before it moves.
            // When the car wakes it restores its last climate on its own (say
            // C3), but the background refit runs only every 30 s and the beat
            // does not count suspended time — so for the first seconds of a
            // trip the ruler still says 0. A "Cooler" press from that stale 0
            // applied C1 (fan 1, 24 C) over a car already running C3: the
            // button made it warmer, at the moment a hot car most needs it
            // (seen 2026-11-16 14:09, and in 4 earlier trips). Taps INSIDE a
            // burst still never refit: the level is moved purely by taps there
            // and the car catches up via drainApply(), so a read mid-apply would
            // fight the taps.
            if (refitBeforeTap(System.currentTimeMillis(), lastTapMs, applying, applyStale)) {
                refitNow();
            }

            // If a tap is issued, clear any defrosting flag immediately for
            // instant UI feedback.
            defrosting = false;

            long quietS = lastTapMs == 0 ? -1 : (System.currentTimeMillis() - lastTapMs) / 1000;
            ComfortEvents.event(ctx, "tap", ComfortEvents.lite(level),
                             "dir=" + dir + " quiet=" + quietS + (approx ? " approx" : ""));
            lastTapMs = System.currentTimeMillis();

            // One tap is one move on the list (EffortTable.step). On a mark
            // that is the next mark. Off a mark only the setting furthest
            // behind (or ahead) moves, so the fan never jumps.
            EffortTable.State base = cur != null ? cur : EffortTable.State.OFF;
            EffortTable.State next = EffortTable.step(table, base, dir);
            if (next.same(base)) {
                status = ctx.getString(R.string.ruler_at_limit, describe(level));
                notifyUi();
                return;
            }
            show(next);
            notifyUi();          // instant: the dot moves now, not after the car answers
            scheduleApply();     // the car catches up in the background
        });
    }

    /**
     * True when a tap should re-read the car before moving: the last tap was
     * longer than {@link #QUIET_AFTER_TAP_MS} ago (or there was none) and no
     * write of ours is pending or in flight. Inside a burst the level is ours,
     * not the car's, so it is never re-read there.
     */
    static boolean refitBeforeTap(long nowMs, long lastTapMs, boolean applying, boolean applyStale) {
        if (applying || applyStale) return false;
        return lastTapMs == 0 || nowMs - lastTapMs >= QUIET_AFTER_TAP_MS;
    }

    /** Runs refit() on CarActor's thread and waits for it (bounded). Called from the reducer. */
    private void refitNow() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        CarActor.get(ctx).runOnCarThread(() -> {
            try {
                if (armed && car().isReady()) refit();
            } finally {
                latch.countDown();
            }
        });
        // If the car thread is busy, go on with the level we have rather than
        // leave the press hanging.
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
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
                EffortTable.State want = cur;
                if (want != null) apply(want);
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
        table = buildTable(outC);
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

    /** Takes a state as the ruler's own: the mark, hollow or not, and the air direction. */
    private void show(EffortTable.State s) {
        EffortTable.Fit f = EffortTable.fit(table, s);
        cur = s;
        level = f.level;
        approx = !f.exact;
        direction = s.power ? s.direction : 0;
        status = describe(level);
    }

    /** Reads the car's climate settings and finds their mark. */
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

        show(power ? new EffortTable.State(true, machine, sp, fan, dir, recirc) : EffortTable.State.OFF);
    }

    /** Writes the wanted state to the car. */
    private void apply(EffortTable.State c) {
        // ORDER MATTERS: setpoint before compressor (prevents wrong-mode running).
        // Flags use ensureFlag() to read first (they toggle on write, not set).
        if (!armed || !car().isReady() || table == null) return;
        String col = EffortTable.label(table, c);

        // A press claims the climate back, whatever it is — including from
        // the physical defrost button. ensureFlag no-ops when it was already
        // off, so this costs nothing on an ordinary tap.
        ensureFlag(CarAccess.HVAC_MAX_DEFROST, false);

        // Mark zero turns the climate off. Park the setpoint at a neutral value
        // so the compressor doesn't start at full effort if power is restored.
        if (!c.power) {
            parkSetpoint();
            ensureFlag(CarAccess.HVAC_POWER_ON, false);
            ComfortEvents.event(ctx, "apply", ComfortEvents.snap(car(), level),
                             "col=" + col + " out=" + tableOutC);
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
        if (!c.machine) parkSetpoint();

        ensureFlag(CarAccess.HVAC_RECIRC_ON, c.recirc);

        // Only write direction if it changed (avoid unnecessary re-planning).
        Integer dirNow = car().readIntRaw(EffortTable.DIR_PROP, 0);
        if (dirNow != null && dirNow != c.direction) {
            car().setIntRaw(EffortTable.DIR_PROP, 0, c.direction);
        }

        int fanNow = car().readFan();
        if (fanNow >= 0 && fanNow != c.fan) car().nudgeFan(c.fan - fanNow);

        ComfortEvents.event(ctx, "apply", ComfortEvents.snap(car(), level),
                         "col=" + col + " out=" + tableOutC);
        CarActor.get(ctx).cancelOnCarThread(verify);
        CarActor.get(ctx).runOnCarThreadDelayed(verify, VERIFY_MS);
    }

    private void parkSetpoint() {
        float sp = car().readSetpoint();
        if (!Float.isNaN(sp) && Math.abs(sp - EffortTable.CABIN_TARGET_C) >= 0.5f) {
            car().nudgeSetpoint(EffortTable.CABIN_TARGET_C - sp);
        }
    }

    /** One-time late re-check after apply(). Verifies critical settings took effect. */
    private final Runnable verify = new Runnable() {
        @Override public void run() {
            EffortTable.State c = cur;
            if (!armed || !car().isReady() || table == null || c == null) return;
            StringBuilder again = new StringBuilder();

            // Check power first. With the climate off, skip the other settings
            // (a stopped blower shouldn't trigger a fan re-check that restarts it).
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

    /** Background maintenance: follow the car, and the weather rules. */
    private void doMaintenance() {
        if (!armed || table == null) return;
        // A tap of ours is waiting or being written: the state is ours, and a
        // read now would see the car half way.
        if (applying || applyStale) return;
        long now = System.currentTimeMillis();

        // Reconcile with car state. If a hand on the OEM panel moved a setting,
        // the display must follow it. Read-only operation.
        int was = level;
        boolean wasApprox = approx;
        refit();
        if (level != was || approx != wasApprox) {
            Log.i(TAG, "ruler: the car moved under us, " + EffortTable.name(was)
                     + " -> " + EffortTable.name(level) + (approx ? "~" : ""));
            notifyUi();
        }

        // A setting is out of line: a hand is on the OEM panel. Don't follow
        // the weather, since that would just undo the manual change.
        if (approx || defrosting) return;

        Float outC = car().readOutsideTempC();
        if (outC == null) return;

        boolean far = Math.abs(outC - tableOutC) >= DRIFT_C;
        if (!far) { driftSinceMs = 0; return; }          // came back; start over
        if (driftSinceMs == 0) { driftSinceMs = now; return; }
        if (now - driftSinceMs < DRIFT_MS) return;        // not sustained yet
        if (now - lastTapMs < QUIET_AFTER_TAP_MS) return; // never mid-complaint

        // Same mark, today's weather rules. Only recirculation (cool side) or
        // the air on the glass (warm side) can differ.
        int held = level;
        table = EffortTable.build(outC);
        tableOutC = outC;
        driftSinceMs = 0;
        EffortTable.State want = EffortTable.State.of(weatherMark(table, held));
        if (cur == null || want.same(cur)) return;
        Log.i(TAG, "ruler: weather moved to " + outC + "C — re-applying " + EffortTable.name(held));
        ComfortEvents.event(ctx, "reslide", ComfortEvents.snap(car(), held), "out=" + outC);
        show(want);
        notifyUi();
        apply(want);
    }

    /** The mark to hold after a weather change: the same one, or the one
     * before it when the weather took it off the ruler (C4 on a mild day). */
    static EffortTable.Column weatherMark(EffortTable.Column[] table, int level) {
        EffortTable.Column c = EffortTable.at(table, level);
        return c.skipped ? EffortTable.at(table, level - 1) : c;
    }

    private EffortTable.Column[] buildTable(float outC) { return EffortTable.build(outC); }

    private String describe(int lv) {
        if (lv == 0) return ctx.getString(R.string.ruler_steady);
        return ctx.getString(R.string.ruler_level,
            ctx.getString(lv > 0 ? R.string.ruler_src_cold : R.string.ruler_src_hot),
            Math.abs(lv));
    }

    private void notifyUi() { if (onChange != null) ui.post(onChange); }
}
