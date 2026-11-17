package com.geely.drivemem.util;

import java.util.Locale;

/** The one place that decides how consumption is SHOWN: Wh/km, whole
 * numbers (152 Wh/km = 15.2 kWh/100 km). The app still computes and stores
 * kWh and kWh/100 km internally; only screens and the CSV export convert,
 * through here. Wh/km is what ABRP uses, and needs no decimals. */
public final class EnergyUnits {
    public static final String WH_PER_KM = "Wh/km";

    private EnergyUnits() {}

    /** kWh/100 km to Wh/km. */
    public static double whPerKm(double kwh100km) { return kwh100km * 10.0; }

    /** Wh/km from energy and distance; NaN without distance. */
    public static double whPerKm(double kwh, double km) { return km > 0 ? kwh * 1000.0 / km : Double.NaN; }

    /** The number as shown, from kWh/100 km: "152". */
    public static String number(double kwh100km) { return wholeWh(whPerKm(kwh100km)); }

    /** A Wh/km value, whole: "152". */
    public static String wholeWh(double whPerKm) { return String.format(Locale.US, "%.0f", whPerKm); }
}
