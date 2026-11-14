package com.geely.drivemem.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import com.geely.drivemem.BuildConfig;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarDb;
import com.geely.drivemem.net.MqttTls;
import com.geely.drivemem.state.CarState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Backup and restore that a user can do alone, with no computer.
 *
 * One backup is one folder, readable on any computer:
 *
 *   <drive>/Drive Assist/Backups/2026-11-14_013000/
 *       settings.json      the user's settings (SettingsFile), plain JSON
 *       car.db             trips, charges, telemetry (SQLite)
 *       mqtt_client.p12    MQTT client certificate, when there is one
 *       mqtt_ca.crt        custom MQTT CA, when there is one
 *
 * It is written to every place that is there: the USB stick (survives a
 * workshop wipe) and the car's own storage, outside the app (survives
 * deleting the app). Once a day, while parked, it runs by itself. The newest
 * KEEP folders per place are kept.
 *
 * Restore puts the settings and certificates back, and MERGES the trips: a
 * car that was driven after the wipe keeps those trips too (DbMerge). A
 * backup whose trips overlap the car's own is not merged again, so pressing
 * restore twice never doubles a trip. Then the app restarts.
 *
 * settings.json holds secrets (MQTT password, ABRP and Spotify tokens), and
 * the folder says so in its README.
 */
public final class AppBackup {
    private AppBackup() {}

    static final String TAG = CarAccess.TAG;
    public static final String DIR = "Drive Assist/Backups";
    static final String SETTINGS = "settings.json";
    static final String DB = "car.db";
    static final String[] CERTS = {MqttTls.CLIENT_P12, MqttTls.CUSTOM_CA_CRT};
    static final int KEEP = 7;
    static final long AUTO_EVERY_MS = 24L * 3600 * 1000;
    static final String PREF_LAST_AUTO = "backup_last_auto_ms";
    static final String PREF_OFFER_DONE = "backup_offer_done";
    /** Set by a restore just before the restart, read once by the new process. */
    static final String PREF_NOTE = "backup_restore_note";
    private static final String STAMP = "yyyy-MM-dd_HHmmss";

    /** Prefs that hold row ids, and the table each id points into. A merge
     * renumbers the live rows, so these move by the same offset. */
    private static final String[][] ID_PREFS = {
        {"trip_open_start_sample_id", "telemetry_sample"},
        {"charge_open_start_sample_id", "telemetry_sample"},
        {"charge_open_row_id", "charge_session"},
        {"valet_row_id", "valet_session"},
        {"drive_card_dismissed_trip", "trip"},
    };

    private static final AtomicBoolean running = new AtomicBoolean(false);

    /** One backup folder found on a drive. */
    public static final class Found {
        public final File dir;
        public final long createdMs;
        public final boolean onUsb;
        Found(File dir, long createdMs, boolean onUsb) {
            this.dir = dir; this.createdMs = createdMs; this.onUsb = onUsb;
        }
    }

    static final class RestoreResult {
        int settings;
        boolean tripsAlreadyThere;
        long tripsAdded;
    }

    // ---- Where ------------------------------------------------------------

    /** USB stick first, then the car's storage outside the app. */
    static List<File> roots() {
        List<File> r = new ArrayList<>();
        File usb = UsbExport.findDrive();
        if (usb != null) r.add(usb);
        File internal = Environment.getExternalStorageDirectory();
        if (internal != null) r.add(internal);
        return r;
    }

    /** True when dir is on the USB stick, not on the car's own storage. */
    public static boolean onUsb(File dir) {
        File usb = UsbExport.findDrive();
        return usb != null && dir.getAbsolutePath().startsWith(usb.getAbsolutePath() + "/");
    }

    /** Every complete backup on every drive, newest first. */
    public static List<Found> list() {
        List<Found> out = new ArrayList<>();
        for (File root : roots()) {
            File[] dirs = new File(root, DIR).listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                File s = new File(d, SETTINGS);
                if (!d.isDirectory() || !s.isFile()) continue;   // no settings.json: incomplete
                long created = 0;
                try { created = SettingsFile.createdMs(readText(s)); } catch (Throwable ignored) {}
                if (created == 0) created = s.lastModified();
                out.add(new Found(d, created, onUsb(d)));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.createdMs, a.createdMs));
        return out;
    }

    // ---- Backup -----------------------------------------------------------

    /** Writes a backup to every drive that takes it. Call off the UI thread.
     * Returns the folders written; throws when none could be. */
    public static List<File> backupNow(Context ctx) throws Exception {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("backup already running");
        try {
            final Context app = ctx.getApplicationContext();
            String stamp = new SimpleDateFormat(STAMP, Locale.US).format(new Date());
            List<File> written = new ArrayList<>();
            File first = null;
            Throwable lastErr = null;
            for (File root : roots()) {
                File dir = new File(new File(root, DIR), stamp);
                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
                    if (first == null) {
                        writeBackup(app, dir);
                        first = dir;
                    } else {
                        copyBackup(first, dir);
                    }
                    writeReadme(new File(root, DIR));
                    prune(new File(root, DIR));
                    written.add(dir);
                    Log.i(TAG, "backup: done " + dir);
                } catch (Throwable t) {
                    lastErr = t;
                    Log.w(TAG, "backup: " + dir + ": " + t);
                    deleteTree(dir);
                }
            }
            if (written.isEmpty()) {
                throw new java.io.IOException("no drive took the backup"
                    + (lastErr != null ? ": " + lastErr.getMessage() : ""));
            }
            return written;
        } finally {
            running.set(false);
        }
    }

    /** Called by the watchdog. Once a day, parked only, on its own thread. */
    public static void autoIfDue(Context ctx) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences p = Prefs.file(app);
        long now = System.currentTimeMillis();
        if (now - p.getLong(PREF_LAST_AUTO, 0) < AUTO_EVERY_MS) return;
        if (!CarState.isParked() || running.get()) return;
        new Thread(() -> {
            try {
                backupNow(app);
                Prefs.file(app).edit().putLong(PREF_LAST_AUTO, System.currentTimeMillis()).apply();
            } catch (Throwable t) {
                Log.w(TAG, "backup: auto: " + t);
            }
        }, "auto-backup").start();
    }

    private static void writeBackup(Context app, File dir) throws Exception {
        // Database first, settings.json LAST: a folder with settings.json is
        // complete, so a cut-short backup never lists as one.
        File db = new File(dir, DB);
        File part = new File(dir, DB + ".part");
        onDbThread(app, () -> { snapshotDb(app, part); return null; });
        if (!part.renameTo(db)) throw new java.io.IOException("rename " + part);

        for (String name : CERTS) {
            File f = new File(app.getFilesDir(), name);
            if (f.isFile()) copy(f, new File(dir, name));
        }
        String json = SettingsFile.toJson(Prefs.file(app).getAll(),
            BuildConfig.VERSION_NAME, CarDb.version(), System.currentTimeMillis());
        writeText(new File(dir, SETTINGS), json);
    }

    private static void copyBackup(File from, File to) throws Exception {
        File[] files = from.listFiles();
        if (files == null) throw new java.io.IOException("cannot read " + from);
        File settings = null;
        for (File f : files) {
            if (f.getName().equals(SETTINGS)) { settings = f; continue; }
            copy(f, new File(to, f.getName()));
        }
        if (settings != null) copy(settings, new File(to, SETTINGS));   // last, as above
    }

    /** A consistent copy of the live car.db in ONE self-contained file.
     * Runs on the db writer thread, so nothing writes while it reads. A
     * separate connection ATTACHes the live file: ATTACH on CarDb's own
     * connection would make Android drop its write-ahead log. */
    static void snapshotDb(Context ctx, File out) {
        deleteDb(out);
        File live = CarDb.file(ctx);
        SQLiteDatabase dst = SQLiteDatabase.openOrCreateDatabase(out, null);
        try {
            dst.execSQL("ATTACH DATABASE ? AS live", new Object[]{live.getPath()});
            int version = (int) DatabaseUtils.longForQuery(dst, "PRAGMA live.user_version", null);
            List<String[]> tables = new ArrayList<>();
            List<String> indexes = new ArrayList<>();
            Cursor c = dst.rawQuery("SELECT type, name, sql FROM live.sqlite_master "
                + "WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'", null);
            try {
                while (c.moveToNext()) {
                    if ("table".equals(c.getString(0))) tables.add(new String[]{c.getString(1), c.getString(2)});
                    else if ("index".equals(c.getString(0))) indexes.add(c.getString(2));
                }
            } finally { c.close(); }
            dst.beginTransaction();
            try {
                for (String[] t : tables) {
                    dst.execSQL(t[1]);
                    dst.execSQL("INSERT INTO main.\"" + t[0] + "\" SELECT * FROM live.\"" + t[0] + "\"");
                }
                for (String sql : indexes) dst.execSQL(sql);
                if (hasTable(dst, "main", "sqlite_sequence") && hasTable(dst, "live", "sqlite_sequence")) {
                    dst.execSQL("DELETE FROM main.sqlite_sequence");
                    dst.execSQL("INSERT INTO main.sqlite_sequence SELECT * FROM live.sqlite_sequence");
                }
                dst.setVersion(version);
                dst.setTransactionSuccessful();
            } finally { dst.endTransaction(); }
            dst.execSQL("DETACH DATABASE live");
        } finally { dst.close(); }
    }

    private static void prune(File backups) {
        File[] dirs = backups.listFiles();
        if (dirs == null) return;
        List<File> ours = new ArrayList<>();
        for (File d : dirs) {
            if (d.isDirectory() && d.getName().matches("\\d{4}-\\d{2}-\\d{2}_\\d{6}")) ours.add(d);
        }
        Collections.sort(ours, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = KEEP; i < ours.size(); i++) deleteTree(ours.get(i));
    }

    private static void writeReadme(File backups) {
        File f = new File(backups, "README.txt");
        if (f.exists()) return;
        try {
            writeText(f, "Drive Assist backups. One folder per backup, newest kept.\n\n"
                + "To restore: Drive Assist > Settings (gear) > System > Restore.\n"
                + "After installing Drive Assist again, it also offers the newest backup by itself.\n\n"
                + "settings.json   your settings (plain text)\n"
                + "car.db          trips and charges (SQLite)\n"
                + "mqtt_*.p12/crt  MQTT certificates, if you use them\n\n"
                + "PRIVATE: settings.json holds your MQTT password and your ABRP and Spotify keys.\n"
                + "Do not share these folders.\n");
        } catch (Throwable ignored) {}
    }

    // ---- Restore ----------------------------------------------------------

    /** Restores a backup folder and restarts the app: on success this call
     * does not return. Call off the UI thread. Throws, with nothing changed,
     * when the backup cannot be used. The restarted app shows what happened
     * through {@link #takeNote}. */
    public static void restore(Context ctx, File dir) throws Exception {
        final Context app = ctx.getApplicationContext();
        final RestoreResult res = new RestoreResult();
        File settingsFile = new File(dir, SETTINGS);
        if (!settingsFile.isFile()) throw new java.io.IOException("not a backup: " + dir);
        String json = readText(settingsFile);
        final Map<String, Object> settings = SettingsFile.parseSettings(json);
        res.settings = settings.size();

        int dbVersion = SettingsFile.dbVersion(json);
        if (dbVersion > CarDb.version()) {
            throw new IllegalStateException("backup is from a newer Drive Assist; update the app first");
        }

        // Work on a private copy: migrate it to this build's schema, then merge.
        File src = new File(dir, DB);
        final File tmp = app.getDatabasePath("restore-src.db");
        boolean haveDb = src.isFile();
        if (haveDb) {
            deleteDb(tmp);
            copy(src, tmp);
            if (dbVersion < CarDb.version()) CarDb.migrateCopy(app, tmp);
        }

        try {
            onDbThread(app, () -> {
                DbMerge.Result merged = null;
                if (haveDb) {
                    merged = mergeInto(app, tmp, res);
                }
                SharedPreferences.Editor e = Prefs.file(app).edit();
                if (merged != null) shiftIdPrefs(Prefs.file(app).getAll(), e, merged.offsets);
                SettingsFile.apply(e, settings);
                e.putBoolean(PREF_OFFER_DONE, true);
                e.putString(PREF_NOTE, res.settings + "|" + res.tripsAdded + "|" + res.tripsAlreadyThere);
                for (String name : CERTS) {
                    File f = new File(dir, name);
                    if (f.isFile()) copy(f, new File(app.getFilesDir(), name));
                }
                e.commit();
                deleteDb(tmp);
                Log.i(TAG, "backup: restored " + dir + " settings=" + res.settings
                    + " trips+" + res.tripsAdded + (res.tripsAlreadyThere ? " (already there)" : ""));
                // Restart HERE, on the writer thread: the merge renumbered the
                // live rows, and the running sessions still hold the old ids in
                // memory. Nothing may write with them, so nothing runs after this.
                restartApp(app);
                return null;
            });
        } finally {
            deleteDb(tmp);
        }
    }

    /** What the last restore did, once: {settings, tripsAdded, alreadyThere},
     * or null. Read by the restarted app to tell the user. */
    public static String[] takeNote(Context ctx) {
        SharedPreferences p = Prefs.file(ctx);
        String n = p.getString(PREF_NOTE, null);
        if (n == null) return null;
        p.edit().remove(PREF_NOTE).apply();
        String[] parts = n.split("\\|");
        return parts.length == 3 ? parts : null;
    }

    /** Restarts the app so every part reads the restored data. */
    static void restartApp(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(app, 7301, i, PendingIntent.FLAG_CANCEL_CURRENT);
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static DbMerge.Result mergeInto(Context app, File oldDb, RestoreResult res) throws Exception {
        // Own connection, for the same ATTACH reason as snapshotDb. We are on
        // the writer thread, so CarDb writes nothing meanwhile.
        SQLiteDatabase db = SQLiteDatabase.openDatabase(CarDb.file(app).getPath(), null,
            SQLiteDatabase.OPEN_READWRITE);
        try {
            db.execSQL("ATTACH DATABASE ? AS old", new Object[]{oldDb.getPath()});
            DbMerge.Sql sql = sql(db);
            if (DbMerge.overlaps(sql)) {
                res.tripsAlreadyThere = true;
                db.execSQL("DETACH DATABASE old");
                return null;
            }
            DbMerge.Result r;
            db.beginTransaction();
            try {
                r = DbMerge.merge(sql);
                String ok = DatabaseUtils.stringForQuery(db, "PRAGMA main.integrity_check", null);
                if (!"ok".equals(ok)) throw new IllegalStateException("integrity_check: " + ok);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            db.execSQL("DETACH DATABASE old");
            Long t = r.added.get("trip");
            res.tripsAdded = t == null ? 0 : t;
            return r;
        } finally { db.close(); }
    }

    static void shiftIdPrefs(Map<String, ?> all, SharedPreferences.Editor e, Map<String, Long> offsets) {
        for (String[] kv : ID_PREFS) {
            Object v = all.get(kv[0]);
            Long off = offsets.get(kv[1]);
            if (off == null || off == 0 || !(v instanceof Number)) continue;
            long id = ((Number) v).longValue();
            if (id <= 0) continue;                       // -1 / 0 mean "none"
            if (v instanceof Integer) e.putInt(kv[0], (int) (id + off));
            else e.putLong(kv[0], id + off);
        }
    }

    // ---- Offer after a fresh install --------------------------------------

    /** True when the user has set nothing yet: a fresh install. */
    public static boolean looksFresh(Context ctx) {
        Map<String, ?> all = Prefs.file(ctx).getAll();
        for (String k : new String[]{"mqtt_uri", "abrp_user_token", "spotify_client_id", "theme", "appearance"}) {
            if (all.containsKey(k)) return false;
        }
        return true;
    }

    public static boolean offerDone(Context ctx) {
        return Prefs.file(ctx).getBoolean(PREF_OFFER_DONE, false);
    }

    public static void setOfferDone(Context ctx) {
        Prefs.file(ctx).edit().putBoolean(PREF_OFFER_DONE, true).apply();
    }

    // ---- Plumbing ---------------------------------------------------------

    interface DbJob { Object run() throws Exception; }

    /** Runs job on CarDb's single writer thread and waits for it. */
    private static void onDbThread(Context app, DbJob job) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        CarDb.get(app).write(() -> {
            try { job.run(); } catch (Throwable t) { err.set(t); } finally { done.countDown(); }
        });
        done.await();
        Throwable t = err.get();
        if (t instanceof Exception) throw (Exception) t;
        if (t != null) throw new RuntimeException(t);
    }

    private static DbMerge.Sql sql(SQLiteDatabase db) {
        return new DbMerge.Sql() {
            @Override public void exec(String s, Object... args) { db.execSQL(s, args); }
            @Override public long queryLong(String s, Object... args) {
                String[] a = new String[args.length];
                for (int i = 0; i < args.length; i++) a[i] = String.valueOf(args[i]);
                return DatabaseUtils.longForQuery(db, s, a);
            }
            @Override public List<String> columns(String schema, String table) {
                List<String> out = new ArrayList<>();
                Cursor c = db.rawQuery("PRAGMA " + schema + ".table_info(\"" + table + "\")", null);
                try { while (c.moveToNext()) out.add(c.getString(1)); } finally { c.close(); }
                return out;
            }
        };
    }

    private static boolean hasTable(SQLiteDatabase db, String schema, String table) {
        return DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM " + schema
            + ".sqlite_master WHERE type='table' AND name=?", new String[]{table}) > 0;
    }

    private static void deleteDb(File f) {
        for (String sfx : new String[]{"", "-wal", "-shm", "-journal"}) {
            new File(f.getPath() + sfx).delete();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String s) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
