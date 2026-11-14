package com.geely.modehelper;

/** The pure parts of CanReader: shell commands and timing rules. No Android
 * types, so modehelper's plain-JVM tests can check them. */
final class CanReaderPolicy {
    static final String DIR = "/data/local/tmp/drive_assist";
    /** Not /data/local/tmp/roadcastd: that path is Capy's, and Capy rewrites
     * whatever it finds there. A different path means neither app ever
     * overwrites the other's binary. */
    static final String BIN = DIR + "/roadcastd";
    static final String SOCKET = "@roadcast";
    /** 10 Hz: measured on the car 2026-11-14 at 0.4 % of one core, against
     * 2.2 % at 60 Hz. The BMS current frame itself changes about 10 times a
     * second, so a faster read only resends the same value. */
    static final int HZ = 10;

    static final long CHECK_EVERY_MS = 60_000L;
    static final long RETRY_AFTER_FAIL_MS = 5 * 60_000L;
    static final long RETRY_AFTER_MANY_FAILS_MS = 30 * 60_000L;
    static final int MANY_FAILS = 3;
    /** Auto-off for a window CanReader opens. It closes ADB itself after a
     * few seconds; this is only the net for a crash in between. */
    static final int ADB_WINDOW_MINUTES = 2;

    static final String LAUNCHED = "DA_LAUNCHED";
    static final String MISSING = "DA_ERR_MISSING";

    private CanReaderPolicy() {}

    /** When the next start attempt may happen after {@code fails} failures in a row. */
    static long nextAttemptAt(long lastAttemptMs, int fails) {
        if (fails <= 0 || lastAttemptMs <= 0) return 0;
        return lastAttemptMs + (fails >= MANY_FAILS ? RETRY_AFTER_MANY_FAILS_MS : RETRY_AFTER_FAIL_MS);
    }

    /** Kills only OUR daemon: matched by executable path, so a roadcastd that
     * another app (Capy) started is never touched. */
    static String stopOursCommand() {
        return "for p in $(pidof roadcastd 2>/dev/null); do "
             + "[ \"$(readlink /proc/$p/exe)\" = \"" + BIN + "\" ] && kill $p; done; true";
    }

    static String sha256Command() {
        return "sha256sum " + BIN + " 2>/dev/null";
    }

    /** Copies the staged binary in, root:root 0755, and only moves it into
     * place if the checksum is the expected one. */
    static String installCommand(String stagedPath, String sha256) {
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("bad sha256");
        if (!stagedPath.matches("[A-Za-z0-9_./-]+")) throw new IllegalArgumentException("bad path");
        String tmp = BIN + ".new";
        return stopOursCommand() + "; "
             + "mkdir -p " + DIR + " && chmod 0755 " + DIR + " && "
             + "cp " + stagedPath + " " + tmp + " && chown root:root " + tmp + " && chmod 0755 " + tmp + " && "
             + "[ \"$(sha256sum " + tmp + " | cut -d' ' -f1)\" = \"" + sha256 + "\" ] && "
             + "mv -f " + tmp + " " + BIN + " && echo DA_INSTALLED || { rm -f " + tmp + "; echo DA_ERR_INSTALL; }";
    }

    /** Starts the daemon so that it outlives adbd.
     *
     * Switching ADB off makes init kill adbd's whole process group, the
     * cpuacct cgroup /acct/uid_0/pid_&lt;adbd&gt;, and every child of adbd is
     * born in it. setsid is not enough. So the inner shell first writes its
     * own pid to /acct/cgroup.procs (the root group) and only then execs the
     * daemon, which inherits the move. Proven on the car 2026-11-14: the
     * daemon kept its pid while adbd was switched off and restarted.
     *
     * The daemon stays uid 0 in u:r:su:s0. It has to: no other domain may read
     * the vehicle HAL's memory. What keeps it apart from adbd is the cgroup,
     * not the credentials. */
    static String launchCommand(int... allowUids) {
        StringBuilder allow = new StringBuilder();
        for (int uid : allowUids) {
            if (uid <= 0) throw new IllegalArgumentException("bad uid");
            allow.append(" --allow-uid ").append(uid);
        }
        return stopOursCommand() + "; sleep 1; "
             + "if [ ! -x " + BIN + " ]; then echo " + MISSING + "; else "
             + "setsid sh -c 'echo $$ > /acct/cgroup.procs; exec " + BIN
             + " --hz " + HZ + " --socket " + SOCKET + allow + "' "
             + "</dev/null >/dev/null 2>&1 & echo " + LAUNCHED + "; fi";
    }

    /** Prints, for our daemon only: its SELinux context, then its cpuacct line. */
    static String inspectCommand() {
        return "for p in $(pidof roadcastd 2>/dev/null); do "
             + "[ \"$(readlink /proc/$p/exe)\" = \"" + BIN + "\" ] && "
             + "{ cat /proc/$p/attr/current; echo; grep cpuacct /proc/$p/cgroup; }; done; true";
    }

    /** Output of {@link #inspectCommand}: running as root in su, outside adbd's group. */
    static boolean inspectOk(String out) {
        if (out == null) return false;
        boolean su = false, rootGroup = false;
        for (String line : out.split("\n")) {
            String l = line.trim().replace("\0", "");
            if (l.equals("u:r:su:s0")) su = true;
            if (l.endsWith("cpuacct:/")) rootGroup = true;
        }
        return su && rootGroup;
    }

    static String parseSha256(String out) {
        if (out == null) return null;
        for (String tok : out.trim().split("\\s+")) {
            if (tok.matches("[0-9a-fA-F]{64}")) return tok.toLowerCase();
        }
        return null;
    }
}
