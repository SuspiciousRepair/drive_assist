package com.geely.drivemem.car;

import com.geely.drivemem.util.Modes;

/** Which colour the cabin light really shows (issue #17).
 *
 * The car reports the light's colour on 537528576 (AMBIENT_COLOR), but that is
 * only the colour picked by hand. When the light is set to follow the drive
 * mode, that property keeps its last value (measured on the car 2026-11-14:
 * it stayed 0xC30000 through Eco, Comfort and Sport) while the light itself
 * goes green, blue or red. The light's mode is a separate property, MODE_PROP:
 *
 *   537526787  colour picked by hand (AMBIENCE_LIGHT_MAINCOLOR_SETCOLOR)
 *   7          follow the drive mode (what this car reports)
 *   537526786  follow the drive mode (the documented value, AMBIENCE_LIGHT_MAINCOLOR_DRIVERMODE)
 *
 * Pure, for tests: CarActor derives telemetry.ambient_color through it. */
public final class AmbientLight {
    private AmbientLight() {}

    /** SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR: the light's mode. Area 0. */
    public static final int MODE_PROP = 537526784;
    public static final int MODE_SET_COLOR = 537526787;
    public static final int MODE_FOLLOW_DRIVE = 7;
    public static final int MODE_FOLLOW_DRIVE_DOC = 537526786;

    /** The colours the owner sees in follow mode: Eco green, Comfort blue,
     * Sport red. The car does not report them; these are close RGB values. */
    public static final int ECO_RGB = 0x00C853;
    public static final int COMFORT_RGB = 0x1E6CFF;
    public static final int SPORT_RGB = 0xE01010;

    public static boolean followsDrive(Integer mode) {
        return mode != null && (mode == MODE_FOLLOW_DRIVE || mode == MODE_FOLLOW_DRIVE_DOC);
    }

    /** Colour for a drive mode in follow mode, or null if the mode is unknown. */
    public static Integer driveColor(Integer driveMode) {
        if (driveMode == null) return null;
        switch (driveMode) {
            case Modes.DRIVE_ECO:     return ECO_RGB;
            case Modes.DRIVE_COMFORT: return COMFORT_RGB;
            case Modes.DRIVE_SPORT:   return SPORT_RGB;
            default: return null;
        }
    }

    /** The colour the light shows: the drive-mode colour in follow mode,
     * else the colour the car reports. Falls back to the reported colour
     * when the drive mode is unknown. */
    public static Integer effective(Integer mode, Integer setColor, Integer driveMode) {
        if (followsDrive(mode)) {
            Integer c = driveColor(driveMode);
            if (c != null) return c;
        }
        return setColor;
    }

    /** Hub derivation: telemetry.ambient_color from
     * telemetry.ambient_color_set, telemetry.ambient_mode and car.drive_mode. */
    public static CarActor.Reading derive(CarActor.Reading setColor, CarActor.Reading mode,
                                          CarActor.Reading driveMode) {
        Integer c = effective(intOf(mode), intOf(setColor), intOf(driveMode));
        if (c != null) return CarActor.Reading.ok(c);
        return setColor;
    }

    private static Integer intOf(CarActor.Reading r) {
        return (r != null && r.status == CarActor.Reading.Status.OK && r.value instanceof Number)
            ? ((Number) r.value).intValue() : null;
    }
}
