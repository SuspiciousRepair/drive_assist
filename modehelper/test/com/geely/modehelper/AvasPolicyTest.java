package com.geely.modehelper;

/** Dependency-free test runner for {@link AvasPolicy}. */
public final class AvasPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(Integer.valueOf(0).equals(AvasPolicy.toWrite(1, 0)),
              "saved off must be written when the car re-armed AVAS");
        check(Integer.valueOf(0).equals(AvasPolicy.toWrite(3, 0)),
              "saved off must beat any active sound type");
        check(AvasPolicy.toWrite(0, 0) == null,
              "already muted must not be written again");
        check(Integer.valueOf(2).equals(AvasPolicy.toWrite(0, 2)),
              "saved on must restore a muted car");
        check(AvasPolicy.toWrite(3, 1) == null,
              "saved on must keep a sound type chosen in the car's Settings");
        check(AvasPolicy.toWrite(1, 1) == null,
              "matching active mode must not be written");
    }
}
