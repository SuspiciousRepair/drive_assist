package com.geely.drivemem.sensors;

/** Vehicle speed from the CAN bus: frame 0x125 (ESC), {@code ESC_VehicleSpeed}
 * with its {@code ...Invalid} flag. The same frame as the brake pedal.
 *
 * SCALE: km/h = raw x 0.05625, i.e. the raw count is in 1/64 m/s
 * (1/64 x 3.6 = 0.05625): binary fixed point, as an ECU would keep it; 13 bits
 * reach ~460 km/h. roadcast's catalog says x1, which is wrong: on
 * the 2026-11-14 drive the raw value reached 936. Against 29 speeds the app
 * saved from the VHAL in the same minutes, the ratio was 0.05616 (median),
 * and the VHAL's own speeds are exact multiples of 0.05625 — it decodes the
 * same signal. So the raw count is used, not roadcast's "physical" value.
 *
 * Why read it here at all: the VHAL speed is polled once a second, while this
 * arrives on a socket the CAN reader already holds open, on every change
 * (about 10 Hz while moving), at no extra cost. */
final class CanSpeed {
    static final String SPEED = "ESC_VehicleSpeed";
    static final String INVALID = "ESC_VehicleSpeedInvalid";
    static final String[] SIGNALS = {SPEED, INVALID};
    static final double KMH_PER_RAW = 0.05625;

    private Float kmh;              // null = not seen yet, or not valid
    private boolean invalid;

    /** Feeds one roadcast signal. True if it was one of the speed signals. */
    synchronized boolean onSignal(String name, long raw, boolean valid) {
        if (SPEED.equals(name)) {
            kmh = valid ? (float) (raw * KMH_PER_RAW) : null;
            return true;
        }
        if (INVALID.equals(name)) {
            invalid = valid && raw != 0;
            return true;
        }
        return false;
    }

    /** km/h, or null when unknown or flagged invalid. */
    synchronized Float kmh() { return invalid ? null : kmh; }

    synchronized void reset() { kmh = null; invalid = false; }
}
