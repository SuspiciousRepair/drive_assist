package com.geely.drivemem.sensors;

/** The one place that turns CAN's climate signal (VCU_ThermalPwrAct) into
 * the climate's real electrical power. The signal reads low: a parked step
 * test on 2026-11-17 and an AC charge on 2026-11-14 all fit
 *
 *   real kW = 1.19 x signal + 0.2   while the climate runs (signal > 0)
 *
 * (plan/active/CAN-ENERGY-ROADMAP.md, "Power map"). The 1.19 suggests the
 * true scale is 0.1 kW/bit, not the catalog's 0.08; the 0.2 kW is likely
 * pumps and electronics the signal leaves out.
 *
 * Raw values are what gets stored (trip.climate_raw_kwh, climate_on_s), so
 * a better fit later re-reads old trips through this class, unchanged. */
public final class ClimatePower {
    static final double SCALE = 1.19;
    static final double ON_KW = 0.2;

    private ClimatePower() {}

    /** Real kW from one raw reading. 0 when the climate is off. */
    public static double correctedKw(double rawKw) {
        return rawKw > 0 ? SCALE * rawKw + ON_KW : 0;
    }

    /** Real kWh from an integrated raw kWh and the seconds it was on. */
    public static double correctedKwh(double rawKwh, double onSeconds) {
        if (rawKwh <= 0 && onSeconds <= 0) return 0;
        return SCALE * rawKwh + ON_KW * onSeconds / 3600.0;
    }
}
