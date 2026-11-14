package com.geely.modehelper;

/** Plain-JVM checks for {@link CanReaderPolicy}: the commands that run as
 * root on the car, and the retry timing. */
public final class CanReaderPolicyTest {
    private static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        // Launch: leaves adbd's cgroup BEFORE exec, so the daemon inherits it.
        String launch = CanReaderPolicy.launchCommand(1000, 10032);
        int move = launch.indexOf("echo $$ > /acct/cgroup.procs");
        int exec = launch.indexOf("exec " + CanReaderPolicy.BIN);
        check(move > 0 && exec > move, "cgroup move must come before exec: " + launch);
        check(launch.contains("setsid sh -c '"), "inner shell is single-quoted so $$ is the inner pid");
        check(launch.contains("--allow-uid 1000 --allow-uid 10032"), "allow-list passed");
        check(launch.contains("--hz 10 "), "10 Hz");
        check(launch.contains("--socket @roadcast"), "socket");
        check(!launch.contains("/data/local/tmp/roadcastd"), "never Capy's path");
        boolean threw = false;
        try { CanReaderPolicy.launchCommand(0); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "uid 0 is not passed on (root is always allowed anyway)");

        // Stop only matches our executable path.
        check(CanReaderPolicy.stopOursCommand().contains("readlink /proc/$p/exe)\" = \"" + CanReaderPolicy.BIN + "\""),
              "stop is limited to our binary");

        // Install: checksum checked before the move, bad input refused.
        String sha = "e8d3aab94944e6fbe46b977ece03362caba148f3bcb48100438be2b1c8e5de90";
        String inst = CanReaderPolicy.installCommand("/data/user/0/com.geely.modehelper/files/roadcastd.stage", sha);
        check(inst.indexOf(sha) < inst.indexOf("mv -f"), "checksum before move");
        threw = false;
        try { CanReaderPolicy.installCommand("/x; rm -rf /", sha); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "path with shell characters refused");
        threw = false;
        try { CanReaderPolicy.installCommand("/x", "nothex"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "bad checksum refused");

        // Inspect output, as the car printed it on 2026-11-14.
        check(CanReaderPolicy.inspectOk("u:r:su:s0\u0000\n1:cpuacct:/\n"), "su + root group is ok");
        check(!CanReaderPolicy.inspectOk("u:r:su:s0\n1:cpuacct:/uid_0/pid_15657\n"), "still in adbd's group is not ok");
        check(!CanReaderPolicy.inspectOk("u:r:system_app:s0\n1:cpuacct:/\n"), "wrong domain is not ok");
        check(!CanReaderPolicy.inspectOk(""), "no daemon is not ok");

        check(sha.equals(CanReaderPolicy.parseSha256(sha + "  " + CanReaderPolicy.BIN)), "sha parse");
        check(CanReaderPolicy.parseSha256("sha256sum: not found") == null, "no sha");

        // Retry timing.
        long t = 1_000_000L;
        check(CanReaderPolicy.nextAttemptAt(t, 0) == 0, "no failure, no wait");
        check(CanReaderPolicy.nextAttemptAt(t, 1) == t + CanReaderPolicy.RETRY_AFTER_FAIL_MS, "5 min after one failure");
        check(CanReaderPolicy.nextAttemptAt(t, CanReaderPolicy.MANY_FAILS) == t + CanReaderPolicy.RETRY_AFTER_MANY_FAILS_MS,
              "30 min after many failures");

        // shell v2 decoding: stdout and stderr kept, exit frame dropped, split frames joined.
        try {
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            LocalAdbShell.ShellV2 d = new LocalAdbShell.ShellV2(sink);
            byte[] f = {1, 3, 0, 0, 0, 'a', 'b', 'c', 3, 1, 0, 0, 0, 0};
            d.accept(java.util.Arrays.copyOfRange(f, 0, 6));
            d.accept(java.util.Arrays.copyOfRange(f, 6, f.length));
            check("abc".equals(sink.toString("UTF-8")), "shell v2 frames: " + sink);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        check(LocalAdbShell.shellService("id").equals("shell,v2,raw:id\0"), "shell v2 service");
        System.out.println("CanReaderPolicyTest OK");
    }
}
