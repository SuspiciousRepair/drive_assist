package com.geely.modehelper;

/** The brake pedal, as Drive Assist reports it, for the dashcam subtitle.
 *
 * This process has no brake signal of its own: the pedal switch is on the
 * CAN bus, which Drive Assist reads. It sends DASH_BRAKE when the pedal
 * changes, and again every 2 s while it is pressed (see its DashBrake).
 *
 * "Pressed" is believed for HOLD_MS after it was last heard, no longer: if
 * Drive Assist dies with the pedal down, the line stops saying BRAKE within
 * seconds instead of saying it for the rest of the drive.
 *
 * The subtitle has one line a second, and a short tap can begin and end
 * between two lines. A press is therefore remembered until one line has
 * shown it.
 *
 * Times are elapsedRealtime, which counts a sleep: nothing from before one
 * is believed after it. Plain Java, so it is tested on a desktop JVM. */
final class BrakeSignal {
    static final String ACTION = "com.geely.modehelper.DASH_BRAKE";
    static final String EXTRA = "pressed";
    // Drive Assist repeats every 2 s; this allows one repeat to be late.
    static final long HOLD_MS = 5_000L;

    /** The one the receiver writes and the recorder reads. */
    static final BrakeSignal SHARED = new BrakeSignal();

    private long pressedAtMs = -1;    // last "pressed" heard and not released since
    private long tapAtMs = -1;        // a "pressed" no line has shown yet

    /** `state`: 1 pressed, 0 released, anything else not known. */
    synchronized void report(int state, long nowMs) {
        if (state == 1) {
            pressedAtMs = nowMs;
            tapAtMs = nowMs;
        } else {
            // It was pressed up to this moment: the line for this second
            // still shows it.
            if (fresh(pressedAtMs, nowMs)) tapAtMs = nowMs;
            pressedAtMs = -1;
        }
    }

    /** For one subtitle line: pressed now, or pressed at some moment since
     * the line before. */
    synchronized boolean take(long nowMs) {
        boolean held = fresh(pressedAtMs, nowMs);
        boolean tapped = fresh(tapAtMs, nowMs);
        tapAtMs = -1;
        return held || tapped;
    }

    private static boolean fresh(long atMs, long nowMs) {
        return atMs >= 0 && nowMs >= atMs && nowMs - atMs <= HOLD_MS;
    }
}
