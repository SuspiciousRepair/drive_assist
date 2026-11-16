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
        new Thread(() -> exportNow(cb, files), "usb-export").start();
    }

    // Same as exportFiles, on the CALLER's thread — for a caller that is
    // already off the UI thread and has more work to do first (CsvExport
    // writes its files, then copies them here).
    public static void exportNow(Callback cb, File... files) {
        exportNow(null, false, cb, files);
    }

    /** Same, into {@code <drive>/Drive Assist/<subdir>/} (subdir may be null).
     *
     * skipSameSize: a file already on the drive with the same name and size
     * is not copied again, but still counts as copied. Only for files that
     * never change once written (dashcam clips, hundreds of MB each). A
     * database or a CSV file can change and keep its size, so those callers
     * pass false and every export writes the file again. */
    public static void exportNow(String subdir, boolean skipSameSize, Callback cb, File... files) {
        File drive = findDrive();
        if (drive == null) {
            if (cb != null) cb.onDone(false, null, 0);
            return;
        }
        File dest = destDir(drive, subdir);
        if (!dest.exists() && !dest.mkdirs()) {
            Log.w(TAG, "usbexport: could not create " + dest);
            if (cb != null) cb.onDone(false, drive, 0);
            return;
        }
        int copied = 0;
        for (File f : files) {
            if (f == null || !f.exists()) continue;
            try {
                File to = new File(dest, f.getName());
                if (shouldCopy(f, to, skipSameSize)) copy(f, to);
                copied++;
            } catch (Throwable t) {
                Log.w(TAG, "usbexport: " + f + ": " + t);
            }
        }
        if (cb != null) cb.onDone(copied > 0, drive, copied);
    }

    /** Where {@link #exportNow(String, Callback, File...)} puts files. */
    public static File destDir(File drive, String subdir) {
        File d = new File(drive, EXPORT_DIR);
        return subdir == null ? d : new File(d, subdir);
    }

    /** The copy decision for one file. Without skipSameSize, always copy. */
    static boolean shouldCopy(File src, File dst, boolean skipSameSize) {
        return !skipSameSize || !sameSize(src, dst);
    }

    static boolean sameSize(File src, File dst) {
        return dst.isFile() && dst.length() == src.length();
    }

    /** Free bytes on the drive, or -1 when that cannot be read. */
    public static long freeBytes(File drive) {
        try { return new android.os.StatFs(drive.getAbsolutePath()).getAvailableBytes(); }
        catch (Throwable t) { return -1; }
    }

    private static void copy(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                // 1 MB: dashcam clips are hundreds of MB; 8 KB writes to a
                // USB stick are many times slower.
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.getFD().sync();   // on the stick before we say "done"
            } finally { out.close(); }
        } finally { in.close(); }
    }
}
