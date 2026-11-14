package com.geely.modehelper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps roadcastd (the CAN reader, third_party/roadcast) running while
 * Drive Assist's "Exact energy" switch is on. Drive Assist reads the daemon
 * itself; this class only starts and watches it, because starting it needs
 * two things only modehelper has: the daemon binary (an asset of this apk)
 * and the right to switch ADB on (WRITE_SECURE_SETTINGS).
 *
 * The rule for ADB: use a window that is already open (5 minutes after boot,
 * trusted Wi-Fi, the owner's own switch). Only when the daemon is gone AND
 * ADB is closed does this open ADB, start the daemon, and close ADB again
 * within seconds. Every such opening is counted and logged.
 *
 * Never kills a roadcastd it did not start (another app's): if @roadcast
 * already answers protocol 3, that daemon is used as it is. Any app may read
 * the daemon this starts (docs/CAN-API.md). */
final class CanReader {
    static final String TAG = "ModeHelper";
    static final String PREF_ENABLED = "can_energy";
    static final String STATUS_ACTION = "com.geely.drivemem.CAN_STATUS";
    static final String DRIVEMEM = "com.geely.drivemem";
    private static final String ASSET = "roadcastd";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "can-reader");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static volatile long lastCheckMs = 0;
    private static volatile boolean binaryVerified = false;
    private static volatile String lastStatus;

    private CanReader() {}

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean on) {
        if (enabled(ctx) == on) return;
        prefs(ctx).edit().putBoolean(PREF_ENABLED, on)
            .putInt("can_fails", 0).putLong("can_last_attempt", 0).apply();
        Log.i(TAG, "can: switch " + (on ? "ON" : "OFF"));
        lastCheckMs = 0;   // act on the next tick, not in a minute
    }

    /** From the 4 s poll loop. Cheap: does real work about once a minute,
     * on its own thread, never two at once. */
    static void tick(Context ctx) {
        long now = System.currentTimeMillis();
        if (lastCheckMs != 0 && now - lastCheckMs < CanReaderPolicy.CHECK_EVERY_MS && now >= lastCheckMs) return;
        if (!busy.compareAndSet(false, true)) return;
        lastCheckMs = now;
        final Context app = ctx.getApplicationContext();
        WORKER.execute(() -> {
            try { check(app); }
            catch (Throwable t) { Log.w(TAG, "can: " + t); report(app, "error", String.valueOf(t)); }
            finally { busy.set(false); }
        });
    }

    private static void check(Context ctx) throws Exception {
        boolean on = enabled(ctx);
        boolean adbOn = AdbControl.isEnabled(ctx);
        LocalAdbShell adb = new LocalAdbShell();

        if (!on) {
            // Stop our daemon, but never open ADB just for that: if ADB is
            // closed it stays running until the next window. It only reads.
            // can_started is set only after we started one, so with the
            // switch never used this touches nothing at all.
            if (prefs(ctx).getBoolean("can_started", false) && adbOn && adb.portOpen(1000)) {
                try {
                    adb.execute(CanReaderPolicy.stopOursCommand());
                    prefs(ctx).edit().remove("can_started").apply();
                    Log.i(TAG, "can: switch is off, reader stopped");
                } catch (Exception ignored) {}
            }
            if (!"off".equals(lastStatus)) report(ctx, "off", null);
            return;
        }

        if (!hasAsset(ctx)) { report(ctx, "no_binary", null); return; }

        boolean answers = probe();
        if (answers) {
            // Running. Opening ADB only to inspect it is not worth it; while
            // ADB happens to be open anyway, check it is ours and current (a
            // helper update brings a new binary).
            if (!adbOn || !adb.portOpen(1000)) { ok(ctx, "running"); return; }
            if (!isOurs(adb)) { ok(ctx, "running_other"); return; }   // another app's: use it, never touch it
            if (!binaryVerified) {
                String remote = CanReaderPolicy.parseSha256(adb.execute(CanReaderPolicy.sha256Command()));
                binaryVerified = assetSha(ctx).equals(remote);
            }
            if (binaryVerified) { ok(ctx, "running"); return; }
            Log.i(TAG, "can: restarting the reader (new binary)");
            start(ctx, adb);
            return;
        }

        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        int fails = p.getInt("can_fails", 0);
        if (now < CanReaderPolicy.nextAttemptAt(p.getLong("can_last_attempt", 0), fails)) return;
        p.edit().putLong("can_last_attempt", now).apply();

        boolean weOpened = false;
        int openToken = 0;
        try {
            if (!adbOn) {
                if (!AdbControl.set(ctx, true, CanReaderPolicy.ADB_WINDOW_MINUTES)) {
                    fail(ctx, "adb_refused", "could not switch ADB on");
                    return;
                }
                weOpened = true;
                openToken = AdbControl.openCount();
                int opens = p.getInt("can_adb_opens", 0) + 1;
                p.edit().putInt("can_adb_opens", opens).putLong("can_adb_opened_at", now).apply();
                Log.i(TAG, "can: opened ADB to start the CAN reader (opening #" + opens + ")");
            }
            if (!waitPort(adb, 15_000)) { fail(ctx, "adb_unreachable", "nothing on 127.0.0.1:5555"); return; }
            start(ctx, adb);
        } finally {
            if (weOpened) closeIfStillOurs(ctx, openToken);
        }
    }

    /** Installs the binary if needed, launches it, checks it came up right. */
    private static void start(Context ctx, LocalAdbShell adb) throws Exception {
        String want = assetSha(ctx);
        String have = CanReaderPolicy.parseSha256(adb.execute(CanReaderPolicy.sha256Command()));
        if (!want.equals(have)) {
            File staged = new File(ctx.getFilesDir(), "roadcastd.stage");
            try {
                try (InputStream in = ctx.getAssets().open(ASSET); OutputStream out = new FileOutputStream(staged)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                String out = adb.execute(CanReaderPolicy.installCommand(staged.getAbsolutePath(), want));
                if (!out.contains("DA_INSTALLED")) { fail(ctx, "install_failed", out); return; }
            } finally {
                staged.delete();
            }
        }
        binaryVerified = true;

        String out = adb.execute(CanReaderPolicy.launchCommand());
        if (out.contains(CanReaderPolicy.MISSING)) { fail(ctx, "install_failed", "binary missing after install"); return; }
        if (!out.contains(CanReaderPolicy.LAUNCHED)) { fail(ctx, "launch_failed", out); return; }

        boolean up = false;
        for (int i = 0; i < 20 && !up; i++) { Thread.sleep(250); up = probe(); }
        if (!up) { fail(ctx, "launch_failed", "@roadcast did not come up"); return; }
        String inspect = adb.execute(CanReaderPolicy.inspectCommand());
        if (!CanReaderPolicy.inspectOk(inspect)) {
            // Most likely still in adbd's cgroup: closing ADB would kill it.
            fail(ctx, "launch_failed", "not root/su outside adbd's group: " + inspect);
            return;
        }
        prefs(ctx).edit().putBoolean("can_started", true).apply();
        Log.i(TAG, "can: reader started");
        ok(ctx, "running");
    }

    private static boolean isOurs(LocalAdbShell adb) {
        try { return CanReaderPolicy.inspectOk(adb.execute(CanReaderPolicy.inspectCommand())); }
        catch (Exception e) { return false; }
    }

    /** Closes ADB only if nobody else asked for it since we opened it (owner's
     * switch, trusted Wi-Fi). */
    private static void closeIfStillOurs(Context ctx, int token) {
        if (AdbControl.openCount() != token || WifiGuardReceiver.isActive()) {
            Log.i(TAG, "can: ADB was claimed by something else meanwhile, leaving it on");
            return;
        }
        AdbControl.set(ctx, false, 0);
        Log.i(TAG, "can: closed ADB again");
    }

    private static boolean waitPort(LocalAdbShell adb, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (adb.portOpen(1000)) return true;
            Thread.sleep(500);
        }
        return false;
    }

    /** Connects to @roadcast and checks the WELCOME of protocol 3. */
    static boolean probe() {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress("roadcast", LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(2000);
            ByteBuffer b = ByteBuffer.allocate(40);
            b.putInt(0x52444354).putShort((short) 3).putShort((short) 1).putInt(0).putInt(8)
             .putLong(0).putLong(0).putShort((short) 3).putShort((short) 3).putInt(3);
            s.getOutputStream().write(b.array());
            byte[] h = new byte[32];
            new DataInputStream(s.getInputStream()).readFully(h);
            ByteBuffer r = ByteBuffer.wrap(h);
            return r.getInt() == 0x52444354 && r.getShort() == 3 && r.getShort() == 2;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean hasAsset(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET)) { return in.read() >= 0; }
        catch (Exception e) { return false; }
    }

    private static String cachedSha;
    private static synchronized String assetSha(Context ctx) throws Exception {
        if (cachedSha != null) return cachedSha;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return cachedSha = sb.toString();
    }

    private static void ok(Context ctx, String status) {
        prefs(ctx).edit().putInt("can_fails", 0).apply();
        report(ctx, status, null);
    }

    private static void fail(Context ctx, String status, String detail) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt("can_fails", p.getInt("can_fails", 0) + 1).apply();
        Log.w(TAG, "can: " + status + (detail != null ? " - " + detail : ""));
        report(ctx, status, detail);
    }

    /** Tells Drive Assist how the reader is doing, for its Diagnostics. Only
     * a status word and a short text: it carries no vehicle data. */
    private static void report(Context ctx, String status, String detail) {
        lastStatus = status;
        SharedPreferences p = prefs(ctx);
        Intent i = new Intent(STATUS_ACTION).setPackage(DRIVEMEM)
            .putExtra("status", status)
            .putExtra("adb_opens", p.getInt("can_adb_opens", 0))
            .putExtra("adb_opened_at", p.getLong("can_adb_opened_at", 0));
        if (detail != null) i.putExtra("detail", detail.length() > 200 ? detail.substring(0, 200) : detail);
        try { ctx.sendBroadcast(i); } catch (Throwable ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("modehelper", Context.MODE_PRIVATE);
    }
}
