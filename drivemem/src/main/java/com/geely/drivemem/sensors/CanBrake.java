package com.geely.drivemem.sensors;

import com.geely.drivemem.car.CarActor;

/** The brake pedal switch, from the CAN bus: frame 0x125 (ESC),
 * {@code ESC_BrakePedalSwitchStatus} with its {@code ...Invalid} flag.
 *
 * Checked against a real drive (2026-11-14 recording): 104 presses, the speed
 * fell during every one, and holding the brake at a stop reads pressed at
 * 0 km/h. It is the switch, not pressure: on/off only.
 *
 * It goes into the hub as {@link CanHub#BRAKE} the moment it changes,
 * injected rather than polled: a brake light 2 s late is no brake light, and
 * the pedal changes only a few times a minute, so pushing each change costs
 * the hub nothing. */
final class CanBrake {
    static final String STATUS = "ESC_BrakePedalSwitchStatus";
    static final String INVALID = "ESC_BrakePedalSwitchInvalid";
    static final String[] SIGNALS = {STATUS, INVALID};

    private Boolean pressed;          // null = not seen yet, or not valid
    private boolean invalid;

    /** Feeds one roadcast signal. Returns the hub reading when it is one of
     * the brake signals (the hub drops it if nothing changed), else null. */
    synchronized CarActor.Reading onSignal(String name, double physical, boolean valid) {
        if (STATUS.equals(name)) {
            pressed = valid ? physical >= 0.5 : null;
        } else if (INVALID.equals(name)) {
            invalid = valid && physical >= 0.5;
        } else {
            return null;
        }
        return current();
    }

    synchronized CarActor.Reading current() {
        if (pressed == null || invalid) return CarActor.Reading.NOT_AVAILABLE;
        return CarActor.Reading.ok(pressed ? 1 : 0);
    }

    synchronized void reset() { pressed = null; invalid = false; }
}
