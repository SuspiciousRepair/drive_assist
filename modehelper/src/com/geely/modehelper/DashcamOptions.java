package com.geely.modehelper;

/** Values accepted across the main-app/system-helper preference boundary. */
final class DashcamOptions {
    static final String SEGMENT_MINUTES = "dashcam_segment_minutes";
    static final String STORAGE = "dashcam_storage";
    static final String INTERNAL = "internal";
    static final int DEFAULT_SEGMENT_MINUTES = 5;

    private DashcamOptions() { }

    static int segmentMinutes(int value) {
        return value == 1 || value == 3 || value == 5 || value == 10
            ? value : DEFAULT_SEGMENT_MINUTES;
    }

    static boolean removableId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
            && !INTERNAL.equalsIgnoreCase(value)
            && !"emulated".equalsIgnoreCase(value)
            && !"self".equalsIgnoreCase(value)
            && !"primary".equalsIgnoreCase(value);
    }

    static String storage(String value) {
        return removableId(value) ? value : INTERNAL;
    }
}
