package com.geely.modehelper;

/** Decides when the helper must start again to run its compiled code.
 *
 * Dexopt compiles the helper while the helper is running. A running process
 * keeps the code it loaded at its start: the compiled file on disk is used
 * only by the next process. This head unit suspends and does not shut down,
 * so without a restart the helper ran in the interpreter for days. Measured
 * on the car 2026-11-22: the parked motion check alone took about 40 % of a
 * core, and the process had no executable mapping of its own .odex.
 *
 * So the helper ends its own process once per installed version, as part of
 * the install: its first start compiles it and starts it again at once,
 * before the dashcam or anything else runs. If that could not happen (an
 * install was in progress), it happens as soon as that install is over: the
 * open clip is closed and the dashcam starts a new one. It does not wait for
 * Park or for a quiet camera, which the owner does not control. The service
 * is START_STICKY.
 *
 * Pure Java (no Android), so the rules are unit-tested. */
final class CompiledCode {
    private CompiledCode() { }

    /** True if this process runs compiled code of `pkg`: `maps` (the lines
     * of /proc/self/maps) has an executable mapping of the package's .odex
     * that is still on disk. */
    static boolean loaded(Iterable<String> maps, String pkg) {
        for (String line : maps) {
            String[] f = line.trim().split("\\s+", 6);
            if (f.length < 6 || f[1].indexOf('x') < 0) continue;
            String path = f[5];
            if (path.endsWith(".odex") && path.contains("/" + pkg + "-")) return true;
        }
        return false;
    }

    /** A restart is wanted: this version was compiled, this process does
     * not run the result, and this version has not restarted for it yet.
     * Once per version, so a compile that gave no code cannot make a loop. */
    static boolean wanted(int installedVc, int compiledVc, int restartedVc, boolean loaded) {
        return compiledVc == installedVc && restartedVc != installedVc && !loaded;
    }
}
