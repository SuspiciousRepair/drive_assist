package com.geely.drivemem.art;

/** The art's "pedal" value from the drive motor's real power.
 *
 * The art used to know only the speed, and took the pedal from how fast the
 * speed changed. That is late by its nature: the speed has to change first,
 * over a window long enough to hide its noise, so the wheel's glow came half
 * a second to a second after the foot moved (one to two without the CAN
 * reader). The car sends the drive motor's power on the CAN bus
 * ({@code VCU_DrvPwrAct}, + pushing, - regenerating) the moment it changes.
 *
 * The value keeps the meaning it had, km/h per second over
 * {@link #FULL_KMH_S}, so nothing downstream changes: power over speed is
 * the force at the wheels, force over mass is acceleration.
 *
 * The two sides are not the same on purpose:
 *
 * - REGEN (negative) is the motor's own force, nothing taken off. Blue
 *   means energy is coming in, and it is coming in whether the car slows
 *   down or holds its speed down a hill.
 * - PUSH (positive) has the road taken off first: what it costs to hold
 *   this speed on a flat road (rolling drag and air). A motor only holding
 *   the speed is not "accelerating", and without this the red breath never
 *   went out on a motorway. What is left shows when the car speeds up or
 *   climbs.
 *
 * The mass and the drag are round figures for this car. They set how bright
 * a glow is, nothing is measured with them. No Android, so it is unit
 * tested. */
public final class MotorPedal {
    private MotorPedal() {}

    /** km/h per second that reads as a full pedal; the same as the art's
     * speed-derived acceleration. */
    static final float FULL_KMH_S = 6f;
    /** The car with a driver, kg. */
    static final float MASS_KG = 1400f;
    /** Rolling drag, N: 0.010 x mass x g. */
    static final float ROLL_N = 140f;
    /** Air drag, N per (m/s)^2: 0.5 x 1.2 kg/m3 x CdA 0.67 m2. A little
     * high on purpose, so holding a speed never reads as a push. */
    static final float AIR_N_PER_MS2 = 0.40f;
    /** Below this the speed is taken as this, m/s (10 km/h): power over a
     * speed near zero is not a force any more. */
    static final float MIN_MS = 10f / 3.6f;

    /** -1..1 from the drive motor power (kW, + pushing, - regenerating) at
     * a speed (km/h). */
    public static float of(float driveKw, float kmh) {
        float real = Math.max(0f, kmh) / 3.6f;
        float force = driveKw * 1000f / Math.max(MIN_MS, real);
        if (force > 0f) force = Math.max(0f, force - (ROLL_N + AIR_N_PER_MS2 * real * real));
        float kmhPerS = force / MASS_KG * 3.6f;
        return Math.max(-1f, Math.min(1f, kmhPerS / FULL_KMH_S));
    }

    /** Where the red breath starts, of a full pedal: about 1 km/h per
     * second. Under it the car is only creeping up on its speed. */
    static final float RED_FROM = 0.15f;

    /** How strong the red breath is for a pedal value: nothing up to
     * {@link #RED_FROM}, then rising from zero (no jump) to 1 at a full
     * pedal. For both sources of the value, the motor and the speed. */
    public static float red(float pedal) {
        if (pedal <= RED_FROM) return 0f;
        return Math.min(1f, (pedal - RED_FROM) / (1f - RED_FROM));
    }
}
