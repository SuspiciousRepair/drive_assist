package com.geely.drivemem.sensors;

/** The climate fan's power by level. The fan runs on the 12 V system, so
 * CAN's climate signal (VCU_ThermalPwrAct) never includes it; without this
 * it landed in "other".
 *
 * Measured 2026-11-17, parked, compressor off, stepping the fan 8 → 0: the
 * battery power above fan-off, read on the car and in a CAN recording.
 * CAN current moves in 0.1 A steps (~40 W), so each value is about ±20 W.
 * Level 0 = climate off (the car reports 0 then). */
public final class FanPower {
    static final double[] KW = {0, 0, 0.01, 0.03, 0.055, 0.075, 0.085, 0.10, 0.12};

    private FanPower() {}

    /** kW for a fan level; 0 for unknown or out-of-range levels. */
    public static double kw(Integer level) {
        if (level == null || level < 0 || level >= KW.length) return 0;
        return KW[level];
    }
}
