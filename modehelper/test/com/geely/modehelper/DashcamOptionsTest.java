package com.geely.modehelper;

/** Dependency-free checks for untrusted option values sent to the system helper. */
public final class DashcamOptionsTest {
    public static void main(String[] args) {
        for (int minutes : new int[]{1, 3, 5, 10})
            check(DashcamOptions.segmentMinutes(minutes) == minutes, "supported duration");
        for (int minutes : new int[]{Integer.MIN_VALUE, -1, 0, 2, 4, 6, 9, 11, Integer.MAX_VALUE})
            check(DashcamOptions.segmentMinutes(minutes) == 5, "invalid duration defaults to five");
        for (String id : new String[]{"1234-ABCD", "usb_drive-1", "A", repeat('A', 64)})
            check(id.equals(DashcamOptions.storage(id)), "valid removable ID");
        for (String id : new String[]{null, "", "internal", "INTERNAL", "emulated", "Emulated", "self",
                "primary", "..", "../USB", "/storage/1234-ABCD", "a/b", "-usb", "_usb", "a.b", "usb disk",
                "ไทย", repeat('A', 65), "USB\n"})
            check("internal".equals(DashcamOptions.storage(id)), "unsafe or reserved ID rejected");
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
