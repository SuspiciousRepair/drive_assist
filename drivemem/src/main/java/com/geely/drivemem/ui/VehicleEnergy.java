package com.geely.drivemem.ui;

import java.util.Map;

/** Validated display values from one complete telemetry snapshot. No vehicle writes. */
final class VehicleEnergy {
    final Double battery, range, chargePower;
    final Boolean charging;

    VehicleEnergy(Map<?, ?> telemetry, Object plug, Object chargingReading) {
        battery = number(telemetry.get("battery"), 0, 100);
        range = number(telemetry.get("range"), 0, 2000);
        Double plugged = number(plug, 0, 1);
        Double active = number(chargingReading, 0, 1);
        // The current sensor can latch after unplugging. A known disconnected
        // connector always wins, just as it does in ChargeSession.
        if (plugged != null && plugged == 0) charging = Boolean.FALSE;
        else if (active == null) charging = null;
        else charging = active == 1;
        Double amps = number(telemetry.get("charge_a"), 0, 2000);
        Double volts = number(telemetry.get("charge_v"), 0, 1500);
        if (Boolean.FALSE.equals(charging)) chargePower = 0.0;
        else if (Boolean.TRUE.equals(charging) && amps != null && volts != null) {
            chargePower = amps * volts / 1000.0;
        } else chargePower = null;
    }

    static Double number(Object value, double min, double max) {
        if (!(value instanceof Number)) return null;
        double n = ((Number) value).doubleValue();
        return Double.isFinite(n) && n >= min && n <= max ? n : null;
    }
}
