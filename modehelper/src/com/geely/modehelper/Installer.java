package com.geely.modehelper;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.app.PendingIntent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Installs APKs using the system-uid privileges this app holds. Drive Assist cannot
 * call PackageInstaller directly; this is an INSTALL_PACKAGES service. The export
 * and privilege make this a security-critical class: it validates the payload
 * (HTTPS, package name, signature match) rather than trusting the caller. */
public class Installer {
    static final String TAG = "ModeHelper";
    static final String TARGET_PKG = "com.geely.drivemem";
    private static final int MAX_BYTES = 40 * 1024 * 1024;   // an apk, not a disk image

    // Serialize installations: retained MQTT commands trigger multiple broadcasts.
    // Without this flag, concurrent downloads could race on the same cache file.
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void install(Context ctx, String url) {
        if (!BUSY.compareAndSet(false, true)) {
            Log.i(TAG, "install: already running, ignoring the repeat");
            return;
        }
        new Thread(() -> {
            try { run(ctx, url); } finally { BUSY.set(false); }
        }, "installer").start();
    }

    private static void run(Context ctx, String url) {
        File apk = null;
        try {
            if (url == null || !url.startsWith("https://")) {
                Log.w(TAG, "install: refused, URL is not https");
                return;
            }
            apk = download(ctx, url);
            if (apk == null) return;

            if (!verify(ctx, apk)) return;      // logs its own reason

            commit(ctx, apk);
        } catch (Throwable t) {
            Log.e(TAG, "install: failed: " + t, t);
        } finally {
            // the session copied the bytes already; keeping them wastes cache
            if (apk != null && apk.exists() && !apk.delete())
                Log.w(TAG, "install: could not delete " + apk);
        }
    }

    private static File download(Context ctx, String url) throws Exception {
        File out = new File(ctx.getCacheDir(), "update-" + System.currentTimeMillis() + ".apk");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "ModeHelper");
        int code = c.getResponseCode();
        if (code != 200) { Log.w(TAG, "install: HTTP " + code); return null; }
        long total = 0;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) { Log.w(TAG, "install: too big, aborted"); return null; }
                o.write(buf, 0, n);
            }
        }
        Log.i(TAG, "install: downloaded " + (total / 1024) + " KB");
        return out;
    }

    // The three checks that make an exported installer safe to expose.
    private static boolean verify(Context ctx, File apk) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo got = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                                                   PackageManager.GET_SIGNATURES);
        if (got == null) { Log.w(TAG, "install: refused, not a readable apk"); return false; }
        if (!TARGET_PKG.equals(got.packageName)) {
            Log.w(TAG, "install: refused, package is " + got.packageName + " not " + TARGET_PKG);
            return false;
        }
        PackageInfo cur;
        try {
            cur = pm.getPackageInfo(TARGET_PKG, PackageManager.GET_SIGNATURES);
        } catch (Throwable t) {
            Log.w(TAG, "install: refused, " + TARGET_PKG + " is not installed to compare against");
            return false;
        }
        if (!sameSigner(cur.signatures, got.signatures)) {
            Log.w(TAG, "install: refused, signature does not match the installed " + TARGET_PKG);
            return false;
        }
        // Skip if already at this version: avoids redundant reinstalls when both
        // cable install and OTA announcement happen for the same build.
        if (got.versionCode == cur.versionCode) {
            Log.i(TAG, "install: skipped, already at versionCode " + cur.versionCode);
            return false;
        }
        Log.i(TAG, "install: verified " + got.packageName + " v" + got.versionCode);
        return true;
    }

    private static boolean sameSigner(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        for (Signature x : a) {
            boolean hit = false;
            for (Signature y : b) if (x.equals(y)) { hit = true; break; }
            if (!hit) return false;
        }
        return a.length == b.length;
    }

    private static void commit(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(TARGET_PKG);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new java.io.FileInputStream(apk);
                 OutputStream o = s.openWrite("drivemem", 0, apk.length())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            // The result comes back to InstallResultReceiver. It matters that the
            // session is owned by THIS app: the install replaces Drive Assist, killing
            // Drive Assist's process mid-operation, so Drive Assist could never have driven its
            // own session to completion.
            Intent i = new Intent(ctx, InstallResultReceiver.class);
            // No FLAG_MUTABLE: it only exists from API 31 and this builds against
            // android-28, where PendingIntents are mutable by default — which
            // this one must be, since PackageInstaller fills in the status
            // extras. Adding it "for safety" would simply not compile here.
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
            IntentSender sender = pending.getIntentSender();
            Log.i(TAG, "install: committing session " + id);
            s.commit(sender);
        }
    }

    /** Silently uninstalls a package using DELETE_PACKAGES and system UID privileges. */
    public static void uninstall(Context ctx, String pkg) {
        try {
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            Intent i = new Intent(ctx, InstallResultReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
            Log.i(TAG, "uninstall: requesting silent uninstall for " + pkg);
            pi.uninstall(pkg, pending.getIntentSender());
        } catch (Throwable t) {
            Log.w(TAG, "uninstall: failed for " + pkg + ": " + t, t);
        }
    }
}
