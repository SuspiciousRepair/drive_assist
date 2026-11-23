package com.geely.modehelper;

import java.util.Arrays;
import java.util.List;

/** Small dependency-free test runner for {@link CompiledCode}. */
public final class CompiledCodeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final String PKG = "com.geely.modehelper";
    private static final String DIR = "/data/app/com.geely.modehelper-AbCdEf0123456789abcdEQ==/oat/arm64/";

    public static void main(String[] args) {
        // The helper as it was on the car: only the old file, replaced by
        // the compile, and no executable part.
        List<String> interpreted = Arrays.asList(
            "78226c0000-78226c3000 r--p 00000000 103:11 1177466                       " + DIR + "base.odex (deleted)",
            "78226f4000-78226f5000 r--p 00003000 103:11 1177466                       " + DIR + "base.odex (deleted)",
            "78226f5000-78226f6000 rw-p 00004000 103:11 1177466                       " + DIR + "base.odex (deleted)",
            "7829000000-7829200000 r-xp 00000000 103:09 1234                          /system/lib64/libart.so",
            "7fd269d000-7fd269f000 rw-p 00000000 00:00 0                              [stack]",
            "");
        check(!CompiledCode.loaded(interpreted, PKG), "no executable odex is not compiled code");

        // A process started after the compile.
        List<String> compiled = Arrays.asList(
            "780c7c0000-780c8cd000 r--p 00000000 103:11 1177468                       " + DIR + "base.odex",
            "780c8cd000-780cbf0000 r-xp 0010d000 103:11 1177468                       " + DIR + "base.odex");
        check(CompiledCode.loaded(compiled, PKG), "executable odex is compiled code");

        // The compiled code of another app, or a replaced file, does not count.
        check(!CompiledCode.loaded(Arrays.asList(
            "780c8cd000-780cbf0000 r-xp 0010d000 103:11 1177468                       "
                + "/data/app/com.geely.drivemem-ZyXw9876==/oat/arm64/base.odex"), PKG),
            "another package's odex");
        check(!CompiledCode.loaded(Arrays.asList(
            "780c8cd000-780cbf0000 r-xp 0010d000 103:11 1177468                       " + DIR + "base.odex (deleted)"), PKG),
            "a replaced odex");

        // Wanted only after the compile of this version, and only once.
        check(CompiledCode.wanted(7, 7, -1, false), "compiled, not loaded, not yet restarted");
        check(!CompiledCode.wanted(7, 6, -1, false), "this version is not compiled yet");
        check(!CompiledCode.wanted(7, 7, 7, false), "already restarted for this version");
        check(!CompiledCode.wanted(7, 7, -1, true), "already runs compiled code");
        check(CompiledCode.wanted(8, 8, 7, false), "a new version restarts again");

        System.out.println("CompiledCodeTest OK");
    }
}
