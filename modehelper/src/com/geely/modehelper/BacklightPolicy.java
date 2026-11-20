package com.geely.modehelper;

/** Darkest mode's backlight rules (BacklightFloorReceiver), kept free of
 * Android so they are tested.
 *
 * Levels: 2 is the panel's lowest lit level, 1 turns the panel off
 * (measured on the car 2026-11-17). Darkness 100 % asks for 1.
 *
 * The heartbeat: a dark panel must never stay dark when Drive Assist is no
 * longer there to bring it back (crash, update, force stop). While it is
 * dark, Drive Assist says "still here" every few seconds; when that stops
 * for {@link #BEAT_TIMEOUT_MS}, this app puts the car's own level back by
 * itself (owner, 2026-11-20: "hopefully we don't get the head unit locked
 * in a darkest mode"). */
final class BacklightPolicy {
    /** The panel's lowest lit level. */
    static final int FLOOR = 2;
    /** The panel off. */
    static final int OFF = 1;
    /** No "still here" for this long: light back. Drive Assist sends one
     * every 5 s, so two may be lost. */
    static final long BEAT_TIMEOUT_MS = 15_000L;

    private BacklightPolicy() {}

    /** A requested dark level, kept to the two allowed: anything but 1
     * (off) is the lowest lit level. A spoofed or bad value can never ask
     * for a bright panel or for 0. */
    static int darkLevel(int requested) {
        return requested == OFF ? OFF : FLOOR;
    }

    /** Whether the heartbeat has stopped: the light must come back. */
    static boolean expired(long lastBeatMs, long nowMs) {
        return nowMs - lastBeatMs >= BEAT_TIMEOUT_MS;
    }

    /** The car's level, kept lit: a stored 0 or 1 must not leave the panel
     * dark after a restore. */
    static int restoreLevel(int carLevel) {
        return Math.max(FLOOR, Math.min(255, carLevel));
    }
}
