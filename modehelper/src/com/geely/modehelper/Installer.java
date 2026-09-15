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
    // Which package a downloaded APK is FOR is read from the APK's own
    // declared package name, not passed in by the caller — same "verify the
    // payload, not the caller" principle as everything else here. This
    // allowlist is what stops that from becoming "install anything": only
    // these two packages, both already part of this device's own install,
    // are ever eligible. Modehelper installing an update to ITSELF (the
    // com.geely.modehelper case) works the same way drivemem's own OTA
    // already does — the installing process gets killed mid-call by the
    // replace, and comes back via MY_PACKAGE_REPLACED, which BootReceiver
    // already listens for.
    // com.geely.installer is the standalone setup installer: it bundles
    // fresh copies of BOTH drivemem and modehelper and installs them
    // together, so an OTA that wants to refresh everything can deliver it
    // instead of modehelper.apk directly -- see run()'s launch step below.
    // It normally deletes itself after each run, which is why verify()
    // below has to be able to check its signature without an existing
    // install to compare against.
    private static final java.util.Set<String> ALLOWED_PKGS =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "com.geely.drivemem", "com.geely.modehelper", "com.geely.installer"));
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

            String targetPkg = verify(ctx, apk);      // logs its own reason
            if (targetPkg == null) return;

            commit(ctx, apk, targetPkg);
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
    // @return the verified target package name, or null if the payload was refused.
    private static String verify(Context ctx, File apk) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo got = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                                                   PackageManager.GET_SIGNATURES);
        if (got == null) { Log.w(TAG, "install: refused, not a readable apk"); return null; }
        if (!ALLOWED_PKGS.contains(got.packageName)) {
            Log.w(TAG, "install: refused, package " + got.packageName + " is not on the allowlist");
            return null;
        }
        PackageInfo cur = null;
        try {
            cur = pm.getPackageInfo(got.packageName, PackageManager.GET_SIGNATURES);
        } catch (Throwable ignored) {
            // Not currently installed -- expected for com.geely.installer,
            // which deletes itself after every run. Fall through to the
            // signature fallback below instead of refusing outright.
        }

        // Signature baseline: the target's own existing install if it has
        // one, otherwise ourselves. drivemem, modehelper and the installer
        // are always signed with the same platform key in this project, so
        // falling back to our own signature is the same trust anchor, not
        // a weaker one -- it only ever applies to a package with no install
        // of its own to compare against yet.
        PackageInfo baseline = cur;
        if (baseline == null) {
            try {
                baseline = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            } catch (Throwable t) {
                Log.w(TAG, "install: refused, no signature baseline available for " + got.packageName);
                return null;
            }
        }
        if (!sameSigner(baseline.signatures, got.signatures)) {
            Log.w(TAG, "install: refused, signature does not match for " + got.packageName);
            return null;
        }
        // Skip if already at this version: avoids redundant reinstalls when both
        // cable install and OTA announcement happen for the same build. Only
        // meaningful when the target is already installed -- a fresh install
        // (the installer, most of the time) has nothing to skip against.
        if (cur != null && got.versionCode == cur.versionCode) {
            Log.i(TAG, "install: skipped, already at versionCode " + cur.versionCode);
            return null;
        }
        Log.i(TAG, "install: verified " + got.packageName + " v" + got.versionCode);
        return got.packageName;
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

    private static void commit(Context ctx, File apk, String targetPkg) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(targetPkg);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new java.io.FileInputStream(apk);
                 OutputStream o = s.openWrite(targetPkg, 0, apk.length())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            // The result comes back to InstallResultReceiver. It matters that the
            // session is owned by THIS app: the install replaces the target package,
            // killing its process mid-operation (including modehelper's own, when
            // targetPkg is itself) — so that process could never have driven its
            // own session to completion.
            Intent i = new Intent(ctx, InstallResultReceiver.class);
            i.putExtra("targetPkg", targetPkg);
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
