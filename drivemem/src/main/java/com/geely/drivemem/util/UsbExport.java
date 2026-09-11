package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Exports files to mounted USB drives at their root directory.
 *
 * Files are copied directly to /storage/<UUID>, not nested in app directories,
 * so they are immediately visible when connected to a computer.
 */
public final class UsbExport {
    static final String TAG = CarAccess.TAG;
    private static final File STORAGE_ROOT = new File("/storage");
    private static final String EXPORT_DIR = "Drive Assist";

    // No free-text message here on purpose — this is a low-level, reusable
    // utility (comfort.log and dashcam clips are future callers too), and a
    // message baked in here would either be English-only or need its own
    // translation this class has no business owning. The caller has the
    // string resources; this just reports what happened.
    public interface Callback { void onDone(boolean ok, File drive, int copied); }

    private UsbExport() {}

    // The first mounted, writable, non-internal volume under /storage.
    // "emulated" and "self" are this API level's own aliases for internal
    // storage, never the drive we want.
    public static File findDrive() {
        File[] entries = STORAGE_ROOT.listFiles();
        if (entries == null) return null;
        for (File f : entries) {
            String name = f.getName();
            if (name.equals("emulated") || name.equals("self")) continue;
            if (f.isDirectory() && f.canWrite()) return f;
        }
        return null;
    }

    // Copies each file into <drive>/Drive Assist/. Runs on its own background
    // thread; cb fires on THAT thread, same convention CarActor's callbacks
    // already use — a caller that needs the UI thread posts there itself.
    public static void exportFiles(Callback cb, File... files) {
        new Thread(() -> {
            File drive = findDrive();
            if (drive == null) {
                if (cb != null) cb.onDone(false, null, 0);
                return;
            }
            File dest = new File(drive, EXPORT_DIR);
            if (!dest.exists() && !dest.mkdirs()) {
                Log.w(TAG, "usbexport: could not create " + dest);
                if (cb != null) cb.onDone(false, drive, 0);
                return;
            }
            int copied = 0;
            for (File f : files) {
                if (f == null || !f.exists()) continue;
                try {
                    copy(f, new File(dest, f.getName()));
                    copied++;
                } catch (Throwable t) {
                    Log.w(TAG, "usbexport: " + f + ": " + t);
                }
            }
            if (cb != null) cb.onDone(copied > 0, drive, copied);
        }, "usb-export").start();
    }

    private static void copy(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
    }
}
