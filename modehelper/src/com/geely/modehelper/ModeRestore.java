package com.geely.modehelper;

/**
 * Puts one saved car mode (drive mode or regen) back after the car starts
 * (issue #130). Plain Java, so it is tested on a desktop JVM. Not
 * thread-safe: the poll thread is its only caller. Times are
 * SystemClock.elapsedRealtime() milliseconds.
 *
 * Adapted from the restore half of Funkyfal's ModePolicy
 * (github.com/Funkyfal/drive_assist). Their learning half is not taken: the
 * saved value stays the one picked in Drive Assist's Config.
 *
 * Why not RestorePolicy's 15 s window: that window opens when the screen
 * wakes. A driver who sits a while before pressing the brake starts the car
 * after it closed, and the car sets its own default at that moment. So the
 * restore is armed again by the car's on signal (ModeHelperService), and
 * it waits for Park instead of a clock:
 *
 * - a value different from the saved one is written, only in Park, at most
 *   MAX_WRITES times, SETTLE_MS apart, for at most RESTORE_MS;
 * - the first different value seen is the car's reset. A third value, seen
 *   after our last write had time to land, is the driver's pick in the car's
 *   own screen: the restore stops and the pick stays. A third value that
 *   shows up while our write is still landing is the car answering it (it
 *   can put a second default over our write): it becomes the reset value
 *   and is written over like the first (review of next, 2026-11-20);
 * - once the car shows the saved value, it is held for HOLD_MS more, because
 *   the car can apply its own default a few seconds after our write (the
 *   v0.4.9 race). After that the restore ends.
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

    private boolean pending;
    private long until;
    private Integer resetValue;
    private int writes;
    private long lastWriteMs = Long.MIN_VALUE / 2;
    private long matchedAt = -1;
    private String note;

    /** The car started (or woke): restore from now. */
    void arm(long now) {
        pending = true;
        until = now + RESTORE_MS;
        resetValue = null;
        writes = 0;
        matchedAt = -1;
    }

    /** The car is going off: what it does to the mode now is not a choice to fight. */
    void cancel() {
        pending = false;
    }

    boolean isPending() { return pending; }

    /** A remark for the log about the last observe(), or null. Cleared by reading it. */
    String takeNote() {
        String n = note;
        note = null;
        return n;
    }

    /**
     * One reading of the car's value. Returns the value to write now, or null.
     *
     * @param car    the car's value now, null if unreadable
     * @param saved  the saved choice
     * @param parked the car is in Park
     */
    Integer observe(Integer car, int saved, boolean parked, long now) {
        if (!pending || car == null) return null;
        boolean settled = now - lastWriteMs >= SETTLE_MS;

        if (car == saved) {
            if (matchedAt < 0) matchedAt = now;
            resetValue = null;
            if (now - matchedAt >= HOLD_MS || now > until) {
                pending = false;
                note = "as saved";
            }
            return null;
        }

        if (matchedAt >= 0) {
            // It matched, then moved away. Inside the hold this is the car's late default.
            matchedAt = -1;
            resetValue = car;
        }
        if (resetValue == null) resetValue = car;

        if (car != resetValue.intValue()) {
            if (settled) {
                pending = false;                   // the driver picked another mode: keep it
                note = "driver picked " + car + ", restore stopped";
                return null;
            }
            // Changed while our write was landing: the car's own doing.
            resetValue = car;
            note = "car changed its default to " + car;
        }
        if (now > until) {
            pending = false;
            note = "restore gave up (time)";
            return null;
        }
        if (writes >= MAX_WRITES) {
            if (settled) {
                pending = false;
                note = "restore gave up after " + writes + " writes";
            }
            return null;
        }
        if (!parked || !settled) return null;
        writes++;
        lastWriteMs = now;
        note = "restore write " + writes;
        return saved;
    }
}
