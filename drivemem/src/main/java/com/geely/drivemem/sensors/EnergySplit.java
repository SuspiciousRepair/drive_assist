package com.geely.drivemem.sensors;

/** Where a trip's battery energy went, from the raw trip columns
 * (CarDb.TRIP_SPLIT_COLUMNS). Over the CAN samples, battery = drive +
 * climate + other:
 *
 * - drive: the motor's energy out minus what it sent back (VCU_DrvPwrAct).
 * - climate: the corrected climate signal (ClimatePower).
 * - other: the rest. 12 V loads (lights, screens, fan, phone) and losses.
 *   During regen the motor signal reads ~20 % more than the battery gets,
 *   so that loss lands here too.
 *
 * The three are then scaled to the trip's own net_kwh, so they add up to
 * the number the trip shows whatever measured it: CAN, or the dongle for
 * the moments CAN dropped out (it reads ~5 % higher). The split is a share,
 * measured on CAN; the total is the trip's. */
public final class EnergySplit {
    /** Below this CAN net, shares are noise: the parts are shown unscaled. */
    static final double MIN_SCALE_KWH = 0.05;

    public final double driveKwh;
    public final double climateKwh;
    public final double otherKwh;

    private EnergySplit(double drive, double climate, double other) {
        driveKwh = drive;
        climateKwh = climate;
        otherKwh = other;
    }

    public static EnergySplit of(double tripNetKwh, double canNetKwh, double driveSpentKwh,
                                 double driveRegenKwh, double climateRawKwh, double climateOnSeconds) {
        double drive = driveSpentKwh - driveRegenKwh;
        double climate = ClimatePower.correctedKwh(climateRawKwh, climateOnSeconds);
        double other = canNetKwh - drive - climate;
        double k = (canNetKwh > MIN_SCALE_KWH && tripNetKwh > 0) ? tripNetKwh / canNetKwh : 1.0;
        return new EnergySplit(drive * k, climate * k, other * k);
    }
}
