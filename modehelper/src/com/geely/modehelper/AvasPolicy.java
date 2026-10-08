package com.geely.modehelper;

/**
 * Decides whether the saved AVAS choice must be written to the car.
 *
 * Mode 0 = muted, 1.. = an active sound type. The saved choice is the truth and
 * is only changed by the Drive Assist toggle: the car re-arms AVAS on wake and
 * power-on, so copying the car's value back over the saved one (the old
 * "mirror") turned a saved "off" into "on" for good.
 */
final class AvasPolicy {
    private AvasPolicy() {}

    /** The mode to write to the car, or null when it already matches the choice. */
    static Integer toWrite(int car, int saved) {
        if (saved == 0) return car == 0 ? null : 0;
        // Saved "on": any active type is fine, so a sound type picked in the
        // car's own Settings is not overridden. Only a muted car is brought back.
        return car == 0 ? saved : null;
    }
}
