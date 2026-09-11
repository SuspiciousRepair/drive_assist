package com.geely.installer;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core orchestration logic for installing Drive Assist components:
 * 1. Verifies platform privileges (UID 1000).
 * 2. Extracts bundled APKs from assets (offline ready).
 * 3. Installs ModeHelper (headless system helper).
 * 4. Installs DriveMem (main application).
 * 5. Starts ModeHelper background service.
 * 6. Launches Drive Assist UI (ComfortActivity).
 * 7. Silently self-uninstalls this installer package.
 */
public class InstallerCore {
    public static final String TAG = "DriveAssistInstaller";
    public static final String PKG_MODEHELPER = "com.geely.modehelper";
    public static final String PKG_DRIVEMEM = "com.geely.drivemem";
    public static final String PKG_SELF = "com.geely.installer";

    public interface Listener {
        void onLog(String message);
        void onProgress(String statusText, int progressPercent);
        void onError(String errorMessage);
        void onSuccess();
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static void run(Context context, Listener listener) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (listener != null) listener.onLog("Installation already in progress.");
            return;
        }

        new Thread(() -> {
            try {
                execute(context, listener);
            } finally {
                RUNNING.set(false);
            }
        }, "installer-worker").start();
    }

    private static void execute(Context ctx, Listener listener) {
        log(listener, "=== Drive Assist Standalone Installer ===");
        log(listener, "Target platform: Geely EX2 (IHU629G)");

        int uid = Process.myUid();
        if (uid == 1000) {
            log(listener, "✓ Verified platform privilege: UID 1000 (System)");
        } else {
            log(listener, "⚠ Warning: Running as UID " + uid + " (platform test key missing?)");
        }

        // Step 1: ModeHelper extraction & install
        update(listener, "Extracting ModeHelper background service...", 15);
        File helperFile = null;
        try {
            helperFile = extractAsset(ctx, "modehelper.apk");
            log(listener, "✓ Extracted ModeHelper (" + (helperFile.length() / 1024) + " KB)");
        } catch (Exception e) {
            error(listener, "Failed to extract modehelper.apk from assets: " + e.getMessage());
            return;
        }

        update(listener, "Installing ModeHelper service...", 30);
        boolean helperOk = installPackage(ctx, helperFile, PKG_MODEHELPER, listener);
        if (helperFile.exists()) helperFile.delete();

        if (!helperOk) {
            error(listener, "ModeHelper installation failed. Check logs for details.");
            return;
        }
        log(listener, "✓ ModeHelper installed successfully.");

        // Step 2: Drive Assist extraction & install
        update(listener, "Extracting Drive Assist main application...", 50);
        File appFile = null;
        try {
            appFile = extractAsset(ctx, "drive_assist.apk");
            log(listener, "✓ Extracted Drive Assist (" + (appFile.length() / 1024) + " KB)");
        } catch (Exception e) {
            error(listener, "Failed to extract drive_assist.apk from assets: " + e.getMessage());
            return;
        }

        update(listener, "Installing Drive Assist application...", 75);
        boolean appOk = installPackage(ctx, appFile, PKG_DRIVEMEM, listener);
        if (appFile.exists()) appFile.delete();

        if (!appOk) {
            error(listener, "Drive Assist installation failed. Check logs for details.");
            return;
        }
        log(listener, "✓ Drive Assist installed successfully.");

        // Step 3: Start ModeHelper service
        update(listener, "Starting background services...", 85);
        startModeHelper(ctx, listener);

        // Step 4: Launch Drive Assist UI
        update(listener, "Launching Drive Assist...", 95);
        launchDriveAssist(ctx, listener);

        // Step 5: Completion & Self-cleanup
        update(listener, "Installation complete! Self-cleaning...", 100);
        log(listener, "✓ All components installed and active.");
        log(listener, "✓ Removing installer in 3 seconds...");

        if (listener != null) listener.onSuccess();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {}

        uninstallSelf(ctx, listener);
    }

    private static File extractAsset(Context ctx, String name) throws Exception {
        File out = new File(ctx.getCacheDir(), name);
        try (InputStream in = ctx.getAssets().open(name);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return out;
    }

    private static boolean installPackage(Context ctx, File apk, String targetPkg, Listener listener) {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(targetPkg);
        params.setSize(apk.length());

        int sessionId = -1;
        try {
            sessionId = pi.createSession(params);
        } catch (Exception e) {
            log(listener, "Failed to create PackageInstaller session: " + e.getMessage());
            return false;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger statusResult = new AtomicInteger(PackageInstaller.STATUS_FAILURE);
        final AtomicReference<String> msgResult = new AtomicReference<>("");

        InstallCallbackReceiver.registerCallback(sessionId, (status, message, intent) -> {
            statusResult.set(status);
            msgResult.set(message != null ? message : "");
            latch.countDown();
        });

        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            try (InputStream in = new java.io.FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }

            Intent callbackIntent = new Intent(ctx, InstallCallbackReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(
                ctx, sessionId, callbackIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            IntentSender sender = pending.getIntentSender();

            log(listener, "Committing installation for " + targetPkg + " (session " + sessionId + ")...");
            session.commit(sender);
        } catch (Exception e) {
            log(listener, "Error streaming APK to session: " + e.getMessage());
            InstallCallbackReceiver.unregisterCallback(sessionId);
            return false;
        }

        try {
            boolean finished = latch.await(90, TimeUnit.SECONDS);
            if (!finished) {
                log(listener, "Timed out waiting for session " + sessionId);
                InstallCallbackReceiver.unregisterCallback(sessionId);
                return false;
            }
        } catch (InterruptedException e) {
            log(listener, "Interrupted while waiting for session " + sessionId);
            InstallCallbackReceiver.unregisterCallback(sessionId);
            return false;
        }

        int st = statusResult.get();
        if (st == PackageInstaller.STATUS_SUCCESS) {
            return true;
        } else {
            log(listener, "Installation failed with status " + st + ": " + msgResult.get());
            return false;
        }
    }

    public static void startModeHelper(Context ctx, Listener listener) {
        try {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName(PKG_MODEHELPER, PKG_MODEHELPER + ".ModeHelperService"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            log(listener, "✓ ModeHelperService started.");
        } catch (Throwable t) {
            log(listener, "Notice: direct startForegroundService: " + t.getMessage());
            try {
                Intent boot = new Intent("android.intent.action.BOOT_COMPLETED");
                boot.setComponent(new ComponentName(PKG_MODEHELPER, PKG_MODEHELPER + ".BootReceiver"));
                ctx.sendBroadcast(boot);
                log(listener, "✓ ModeHelper BootReceiver broadcast dispatched.");
            } catch (Throwable t2) {
                log(listener, "Notice: broadcast fallback: " + t2.getMessage());
            }
        }
    }

    public static void launchDriveAssist(Context ctx, Listener listener) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PKG_DRIVEMEM, PKG_DRIVEMEM + ".ui.ComfortActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(intent);
            log(listener, "✓ ComfortActivity launched.");
        } catch (Throwable t) {
            log(listener, "Notice: could not launch ComfortActivity directly: " + t.getMessage());
        }
    }

    public static void uninstallSelf(Context ctx, Listener listener) {
        try {
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            Intent i = new Intent(ctx, InstallCallbackReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(
                ctx, 9999, i, PendingIntent.FLAG_UPDATE_CURRENT);
            log(listener, "Requesting self-uninstall (" + PKG_SELF + ")...");
            pi.uninstall(PKG_SELF, pending.getIntentSender());
        } catch (Throwable t) {
            log(listener, "Self-uninstall failed: " + t.getMessage());
        }
    }

    private static void log(Listener listener, String text) {
        Log.i(TAG, text);
        if (listener != null) listener.onLog(text);
    }

    private static void update(Listener listener, String status, int progress) {
        Log.i(TAG, "[" + progress + "%] " + status);
        if (listener != null) listener.onProgress(status, progress);
    }

    private static void error(Listener listener, String message) {
        Log.e(TAG, message);
        if (listener != null) listener.onError(message);
    }
}
