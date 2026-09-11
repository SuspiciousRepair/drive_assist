package com.geely.drivemem.util;

/** VHAL constants for drive modes, regen levels, and HVAC control on Geely IHU629G. */
public final class Modes {
    public static final int PROP_DRIVE = 570491136;
    public static final int PROP_REGEN = 537003264;

    public static final int DRIVE_ECO     = 570491137;
    public static final int DRIVE_COMFORT = 570491138;
    public static final int DRIVE_SPORT   = 570491139;

    public static final int REGEN_LOW  = 537003265;
    public static final int REGEN_MID  = 537003266;
    public static final int REGEN_HIGH = 537003267;

    public static final int[] DRIVE_AREAS = {0, 1, 16777216};
    public static final int[] REGEN_AREAS = {0, 1};

    public static final int GEAR_SELECTION = 0x2140800f;
    // Geely's ADAPTED reading: PARK shows up as 4 (not 1)
    public static final int GEAR_PARK_ADAPTED = 4;

    // HVAC (standard AOSP properties, READ_WRITE, they show up in car_service)
    // ZONED_TEMP_SETPOINT 16..32. A REAL target the car holds — it has a cabin
    // sensor of its own, it just does not expose it. The middle of the scale is a
    // mode boundary, not a value: crossing it reverses the machine
    // (historical/COMFORT.md; current design: COMFORT-TABLE.md).
    public static final int HVAC_TEMP = 0x15600503;
    public static final int AC_AMBIENT_TEMP = 557884279; // int, formula (raw-80)/2 = outside C
    public static final int BATTERY_SOC = 557885165;     // float, real value already (94.1 = 94.1%)
    // fan: functionId from Geely's layer (confirmed in the logs), area 75, 1..8
    public static final int HVAC_FAN = 356517120;
    public static final int[] FAN_AREAS = {75, 0, 1};
    // EIGHT, not seven: the car accepts fan level 8 and reads it back.
    // nudgeFan() clamps to this value, so a wrong max here is load-bearing: the
    // effort table's top column asks for 8, the car stops one short at 7, and 7
    // is not a value in the fan row — so the very next re-fit counts a lever out
    // of line and reports C4 instead of C5. Press
    // cool, watch it reach C5 and fall straight back. Observed in the car.
    public static final int FAN_MIN = 1, FAN_MAX = 8;

    public static String driveName(int v) {
        if (v == DRIVE_ECO) return "Eco";
        if (v == DRIVE_COMFORT) return "Comfort";
        if (v == DRIVE_SPORT) return "Sport";
        return "?(" + v + ")";
    }
    public static String regenName(int v) {
        if (v == REGEN_LOW) return "Low";
        if (v == REGEN_MID) return "Mid";
        if (v == REGEN_HIGH) return "High";
        return "?(" + v + ")";
    }
}
