package com.geely.modehelper;

/**
 * Remembers and restores one car mode (drive mode or regen) across power
 * cycles (issues #130, #201). Plain Java, so it is tested on a desktop JVM.
 * Not thread-safe: the poll thread is its only caller. Times are
 * SystemClock.elapsedRealtime() milliseconds.
 *
 * Adapted from Funkyfal's ModePolicy (github.com/Funkyfal/drive_assist),
 * covering both halves:
 *
 * 1. Restore (after start / wake / screen-on, while pending):
 * - If nothing was ever saved yet (saved == null), exits pending immediately
 *   so the car's current steady mode can be learned.
 * - A value different from the saved one is written, only in Park, at most
 *   MAX_WRITES times, SETTLE_MS apart, for at most RESTORE_MS.
 * - The first different value seen is the car's reset. A third value, seen
 *   after our last write had time to land, is the driver's pick in the car's
 *   own screen: the restore stops and that pick becomes the candidate to
 *   learn. A third value that shows up while our write is still landing is
 *   the car answering it (it can put a second default over our write): it
 *   becomes the reset value and is written over like the first.
 * - Once the car shows the saved value, it is held for HOLD_MS more, because
 *   the car can apply its own default a few seconds after our write (the
 *   v0.4.9 race). After that the restore ends.
 * - If the restore gives up (timeout or MAX_WRITES), the un-restored car
 *   reset value is remembered as ignored so the learner never overwrites the
 *   driver's saved choice with the car's factory reset.
 *
 * 2. Learn (once restore is idle, while the car is on and no temporary boost
 *    such as Turbo or Strong-regen toggle is active):
 * - A steady valid value different from the saved choice (and different from
 *   an ignored un-restored reset) for LEARN_STABLE_MS is recorded as the new
 *   saved choice. Requiring stability prevents a shutdown reset that happens
 *   a second before VEHICLE_STATE flips off from overwriting the saved mode.
 *
 * Drive mode and regen are never written outside Park, so a long restore does
 * not change the car under a moving driver (the #88 concern).
 */
final class ModeRestore {
    /** Longest time after the car starts that a restore may still write. */
    static final long RESTORE_MS = 3 * 60_000L;
    /** Gap between two writes, so the car's answer to one is read before the next. */
    static final long SETTLE_MS = 6_000L;
    /** Most writes per start. */
    static final int MAX_WRITES = 6;
    /** How long a match is held against a late car default. */
    static final long HOLD_MS = RestorePolicy.WINDOW_MS;
    /** How long an external change must hold steady before being learned. */
    static final long LEARN_STABLE_MS = 6_000L;

    /** Result of one observe() tick: at most one of write or learn is non-null. */
    static final class Action {
        static final Action NONE = new Action(null, null);
        final Integer write;
        final Integer learn;

        private Action(Integer write, Integer learn) {
            this.write = write;
            this.learn = learn;
        }

        static Action write(int value) { return new Action(value, null); }
        static Action learn(int value) { return new Action(null, value); }
    }

    private boolean pending;
    private long until;
    private Integer resetValue;
    private int writes;
    private long lastWriteMs = Long.MIN_VALUE / 2;
    private long matchedAt = -1;
    private Integer ignoreValue;
    private Integer candidate;
    private long candidateSince;
    private String note;

    /** The car started (or woke): restore from now. */
    void arm(long now) {
        pending = true;
        until = now + RESTORE_MS;
        resetValue = null;
        writes = 0;
        matchedAt = -1;
        ignoreValue = null;
        candidate = null;
    }

    /** The car is going off: stop restoring and drop any unconfirmed learn candidate. */
    void cancel() {
        pending = false;
        candidate = null;
    }

    /** The driver explicitly chose a value in Drive Assist: stop any startup
     * restore and reset learning state so nothing fights the new choice. */
    void onManualSave() {
        pending = false;
        ignoreValue = null;
        candidate = null;
    }

    boolean isPending() { return pending; }

    /** A remark for the log about the last observe(), or null. Cleared by reading it. */
    String takeNote() {
        String n = note;
        note = null;
        return n;
    }

    /**
     * One reading of the car's value. Returns what to write or learn now.
     *
     * @param car    the car's valid value now, null if unreadable or invalid
     * @param saved  the saved choice, null if none has been saved yet
     * @param parked the car is in Park
     * @param held   a temporary boost (Turbo / Strong-regen toggle) is active
     */
    Action observe(Integer car, Integer saved, boolean parked, boolean held, long now) {
        if (car == null) {
            candidate = null;
            return Action.NONE;
        }
        if (held) {
            candidate = null;
            if (pending) {
                pending = false;
                note = "boost active, restore stopped";
            }
            return Action.NONE;
        }

        if (pending) {
            if (saved == null) {
                pending = false;
            } else {
                return observeRestore(car, saved.intValue(), parked, now);
            }
        }

        return observeLearn(car.intValue(), saved, now);
    }

    private Action observeRestore(int car, int saved, boolean parked, long now) {
        boolean settled = now - lastWriteMs >= SETTLE_MS;

        if (car == saved) {
            if (matchedAt < 0) matchedAt = now;
            resetValue = null;
            if (now - matchedAt >= HOLD_MS || now > until) {
                pending = false;
                note = "as saved";
            }
            return Action.NONE;
        }

        if (matchedAt >= 0) {
            // It matched, then moved away. Inside the hold this is the car's late default.
            matchedAt = -1;
            resetValue = car;
        }
        if (resetValue == null) resetValue = car;

        if (car != resetValue.intValue()) {
            if (settled) {
                pending = false;                   // the driver picked another mode: keep and learn it
                candidate = car;
                candidateSince = now;
                note = "driver picked " + car + ", restore stopped";
                return Action.NONE;
            }
            // Changed while our write was landing: the car's own doing.
            resetValue = car;
            note = "car changed its default to " + car;
        }
        if (now > until) {
            pending = false;
            ignoreValue = car;
            note = "restore gave up (time)";
            return Action.NONE;
        }
        if (writes >= MAX_WRITES) {
            if (settled) {
                pending = false;
                ignoreValue = car;
                note = "restore gave up after " + writes + " writes";
            }
            return Action.NONE;
        }
        if (!parked || !settled) return Action.NONE;
        writes++;
        lastWriteMs = now;
        note = "restore write " + writes;
        return Action.write(saved);
    }

    private Action observeLearn(int car, Integer saved, long now) {
        if (saved != null && car == saved.intValue()) {
            candidate = null;
            ignoreValue = null;
            return Action.NONE;
        }
        if (ignoreValue != null && car == ignoreValue.intValue()) {
            // Un-restored factory reset from a restore that gave up: do not learn it.
            candidate = null;
            return Action.NONE;
        }
        ignoreValue = null;
        if (candidate == null || candidate.intValue() != car) {
            candidate = car;
            candidateSince = now;
            return Action.NONE;
        }
        if (now - candidateSince >= LEARN_STABLE_MS) {
            candidate = null;
            note = "learned " + car;
            return Action.learn(car);
        }
        return Action.NONE;
    }
}
