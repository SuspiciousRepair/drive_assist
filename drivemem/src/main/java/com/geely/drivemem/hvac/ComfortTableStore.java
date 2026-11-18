package com.geely.drivemem.hvac;

import android.content.Context;
import android.util.Log;

import com.geely.drivemem.util.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The comfort table's file: {@code files/comfort-table.json}, the one place
 * the ruler's columns live (ComfortTable). Read on every use, so a file
 * edited by hand counts from the next tap or weather change. In the app
 * backup (AppBackup.FILES).
 */
public final class ComfortTableStore {
    private ComfortTableStore() {}

    static final String TAG = "DriveMem";
    public static final String FILE = "comfort-table.json";

    public static File file(Context ctx) { return new File(ctx.getFilesDir(), FILE); }

    /** The table to use. A missing, bad or old-version file is written again
     * first (defaults plus the learned values that still fit). */
    public static synchronized ComfortTable get(Context ctx) {
        File f = file(ctx);
        String text = null;
        try {
            if (f.isFile()) text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "comfort table: read " + e);
        }
        ComfortTable.Resolved r = ComfortTable.resolve(text,
            ComfortLearn.Book.decode(Prefs.getComfortBook(ctx)));
        if (r.write) {
            Log.i(TAG, "comfort table: writing it again (" + r.why + ")");
            save(ctx, r.table);
        }
        return r.table;
    }

    /** Writes the table: a temp file, then a rename, so a crash never leaves half a file. */
    public static synchronized void save(Context ctx, ComfortTable t) {
        File f = file(ctx);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(t.toJson().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + tmp);
        } catch (Exception e) {
            Log.w(TAG, "comfort table: write " + e);
            tmp.delete();
        }
    }

    /** Reset: the defaults, nothing learned (the caller clears the book). */
    public static synchronized void reset(Context ctx) {
        save(ctx, ComfortTable.defaults());
    }
}
