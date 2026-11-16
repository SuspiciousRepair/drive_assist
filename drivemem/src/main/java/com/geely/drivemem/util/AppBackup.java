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
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Backup and restore that a user can do alone, with no computer.
 *
 * One backup is one file, locked with the user's backup password
 * (BackupCrypto), because other apps can read the USB stick and the car
 * storage:
 *
 *   <drive>/Drive Assist/Backups/2026-11-14_013000.dabackup
 *
 * Inside the lock is a zip with:
 *       settings.json      the user's settings (SettingsFile), plain JSON
 *       car.db             trips, charges, telemetry (SQLite)
 *       mqtt_client.p12    MQTT client certificate, when there is one
 *       mqtt_ca.crt        custom MQTT CA, when there is one
 * Unlocked data only ever sits in the app's private cache, never on a drive.
 * tools/backup-open.sh unlocks a backup on a computer.
 *
 * It is written to every place that is there: the USB stick (survives a
 * workshop wipe) and the car's own storage, outside the app (survives
 * deleting the app). Once a day, while parked, it runs by itself. The newest
 * KEEP files per place are kept. With no password set, there is no backup.
 *
 * Restore puts the settings and certificates back, and MERGES the trips: a
 * car that was driven after the wipe keeps those trips too (DbMerge). A
 * backup whose trips overlap the car's own is not merged again, so pressing
 * restore twice never doubles a trip. Then the app restarts.
 *
 * The app keeps the key made from the password (not the password) in its
 * private prefs, so the daily backup needs no typing. After the app is
 * deleted, only the password opens a backup.
 */
public final class AppBackup {
    private AppBackup() {}

    static final String TAG = CarAccess.TAG;
    public static final String DIR = "Drive Assist/Backups";
    static final String SETTINGS = "settings.json";
    static final String DB = "car.db";
    static final String[] CERTS = {MqttTls.CLIENT_P12, MqttTls.CUSTOM_CA_CRT};
    public static final String EXT = ".dabackup";
    static final int KEEP = 7;
    static final long AUTO_EVERY_MS = 24L * 3600 * 1000;
    static final String PREF_LAST_AUTO = "backup_last_auto_ms";
    /** When the daily backup last started, and how many starts in a row did
     * not finish. Both are the app's own state (backup_*: never in a backup). */
    static final String PREF_LAST_TRY = "backup_last_try_ms";
    static final String PREF_AUTO_FAILS = "backup_auto_fails";
    static final long RETRY_FIRST_MS = 3600L * 1000;
    static final String PREF_OFFER_DONE = "backup_offer_done";
    /** Set by a restore just before the restart, read once by the new process. */
    static final String PREF_NOTE = "backup_restore_note";
    /** The key made from the backup password, and its salt (base64). Never
     * in the backup itself: see Prefs.isBackedUp. */
    static final String PREF_KEY = "backup_key";
    static final String PREF_SALT = "backup_salt";
    public static final int MIN_PASSWORD = 6;
    private static final String STAMP = "yyyy-MM-dd_HHmmss";
    private static final String STAMP_RE = "\\d{4}-\\d{2}-\\d{2}_\\d{6}";

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

    /** One backup file found on a drive. */
    public static final class Found {
        public final File file;
        public final long createdMs;
        public final boolean onUsb;
        Found(File file, long createdMs, boolean onUsb) {
            this.file = file; this.createdMs = createdMs; this.onUsb = onUsb;
        }
    }

    /** No backup password is set, so there is no key to lock a backup with. */
    public static final class NoPassword extends IllegalStateException {
        NoPassword() { super("no backup password set"); }
    }

    /** The car left Park before the restore reached the step that changes
     * things. Nothing was changed. */
    public static final class NotParked extends IllegalStateException {
        NotParked() { super("the car is not in Park"); }
    }

    /** Where a restore was asked from. */
    public enum Origin {
        /** The car's own screen, or adb (BackupReceiver): a person with the
         * car. Every setting in the backup comes back. */
        CAR,
        /** A file sent by a phone (web/PhoneServer). The phone token goes
         * over plain HTTP, so this file may be made by someone else: the
         * settings in Prefs.isPhoneRestorable only, and the car keeps its
         * own backup password. */
        PHONE
    }

    /** True when a restore also stores the key that opened the backup, so
     * the daily backup goes on with that password. Never from a phone: with
     * a key of their choice, and the backup download on the phone pages,
     * someone else could read every later backup of this car. */
    static boolean storesKey(Origin origin) {
        return origin == Origin.CAR;
    }

    /** This backup was locked with another password than the one set here. */
    public static final class NeedPassword extends IllegalStateException {
        NeedPassword() { super("backup password needed"); }
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

    /** True when f is on the USB stick, not on the car's own storage. */
    public static boolean onUsb(File f) {
        File usb = UsbExport.findDrive();
        return usb != null && f.getAbsolutePath().startsWith(usb.getAbsolutePath() + "/");
    }

    /** Every complete backup on every drive, newest first. A backup is
     * written as .part and renamed at the end, so a cut-short one never lists. */
    public static List<Found> list() {
        List<Found> out = new ArrayList<>();
        for (File root : roots()) {
            File[] files = new File(root, DIR).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || !f.getName().matches(STAMP_RE + "\\" + EXT)) continue;
                long created;
                try {
                    created = new SimpleDateFormat(STAMP, Locale.US)
                        .parse(f.getName().substring(0, f.getName().length() - EXT.length())).getTime();
                } catch (Throwable t) {
                    created = f.lastModified();
                }
                out.add(new Found(f, created, onUsb(f)));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.createdMs, a.createdMs));
        return out;
    }

    // ---- Password ---------------------------------------------------------

    public static boolean hasPassword(Context ctx) {
        return Prefs.file(ctx).getString(PREF_KEY, null) != null;
    }

    /** Sets or changes the backup password. Slow (key stretching): call off
     * the UI thread. Backups made before still need the old password. */
    public static void setPassword(Context ctx, char[] password) throws Exception {
        if (password.length < MIN_PASSWORD) throw new IllegalArgumentException("password too short");
        byte[] salt = BackupCrypto.newSalt();
        byte[] key = BackupCrypto.deriveKey(password, salt, BackupCrypto.ROUNDS);
        storeKey(Prefs.file(ctx).edit(), key, salt).commit();
    }

    private static SharedPreferences.Editor storeKey(SharedPreferences.Editor e, byte[] key, byte[] salt) {
        return e.putString(PREF_KEY, Base64.getEncoder().encodeToString(key))
            .putString(PREF_SALT, Base64.getEncoder().encodeToString(salt));
    }

    /** {key, salt} as set here, or null. */
    private static byte[][] storedKey(Context ctx) {
        SharedPreferences p = Prefs.file(ctx);
        String k = p.getString(PREF_KEY, null);
        String s = p.getString(PREF_SALT, null);
        if (k == null || s == null) return null;
        try {
            return new byte[][]{Base64.getDecoder().decode(k), Base64.getDecoder().decode(s)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when restoring f needs the password typed: the key set here did
     * not lock it (fresh install, or the password changed since). */
    public static boolean needsPassword(Context ctx, File f) {
        byte[][] ks = storedKey(ctx);
        if (ks == null) return true;
        try {
            return !java.util.Arrays.equals(BackupCrypto.readHeader(f).salt, ks[1]);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- Backup -----------------------------------------------------------

    /** Writes a backup to every drive that takes it. Call off the UI thread.
     * Returns the files written; throws NoPassword when no password is set,
     * and another exception when no drive took it. */
    public static List<File> backupNow(Context ctx) throws Exception {
        final Context app = ctx.getApplicationContext();
        byte[][] ks = storedKey(app);
        if (ks == null) throw new NoPassword();
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("backup already running");
        try {
            String name = new SimpleDateFormat(STAMP, Locale.US).format(new Date()) + EXT;
            List<File> written = new ArrayList<>();
            File first = null;
            Throwable lastErr = null;
            for (File root : roots()) {
                File backups = new File(root, DIR);
                File f = new File(backups, name);
                File part = new File(backups, name + ".part");
                try {
                    if (!backups.isDirectory() && !backups.mkdirs()) throw new java.io.IOException("cannot create " + backups);
                    if (first == null) {
                        writeBackup(app, part, ks[0], ks[1]);
                        // On the drive before it gets its final name: prune
                        // below may delete an older good backup.
                        syncFile(part);
                    } else {
                        copy(first, part);
                    }
                    if (!part.renameTo(f)) throw new java.io.IOException("rename " + part);
                    if (first == null) first = f;
                    writeReadme(backups);
                    prune(backups);
                    written.add(f);
                    Log.i(TAG, "backup: done " + f);
                } catch (Throwable t) {
                    lastErr = t;
                    Log.w(TAG, "backup: " + f + ": " + t);
                    part.delete();
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

    /** Called by the watchdog (every 10 min, and on every resume). Once a
     * day, parked only, on its own thread. A backup that fails (drive full,
     * stick read-only) is tried again later, not on every call: see
     * {@link #autoDue}. */
    public static void autoIfDue(Context ctx) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences p = Prefs.file(app);
        long now = System.currentTimeMillis();
        final int fails = p.getInt(PREF_AUTO_FAILS, 0);
        if (!autoDue(now, p.getLong(PREF_LAST_AUTO, 0), p.getLong(PREF_LAST_TRY, 0), fails)) return;
        if (!hasPassword(app)) return;   // the Backup card says backups are off
        if (!CarState.isParked() || running.get()) return;
        // Counted as failed until it finishes: a backup cut off by a suspend
        // or a killed process waits like one that failed.
        p.edit().putLong(PREF_LAST_TRY, now).putInt(PREF_AUTO_FAILS, Math.min(fails + 1, 100)).apply();
        new Thread(() -> {
            try {
                backupNow(app);
                Prefs.file(app).edit().putLong(PREF_LAST_AUTO, System.currentTimeMillis())
                    .putInt(PREF_AUTO_FAILS, 0).apply();
            } catch (Throwable t) {
                Log.w(TAG, "backup: auto: " + t + " (try " + (fails + 1) + ", next in "
                    + retryDelayMs(fails + 1) / 60_000L + " min)");
            }
        }, "auto-backup").start();
    }

    /** True when the daily backup must start now.
     *
     * lastOkMs: when the last one finished. lastTryMs: when the last one
     * started. fails: how many starts in a row did not finish (0 after a
     * good one). After a failure the next try waits {@link #retryDelayMs}.
     * A last try in the future (the clock was set back) does not block. */
    static boolean autoDue(long nowMs, long lastOkMs, long lastTryMs, int fails) {
        if (nowMs - lastOkMs < AUTO_EVERY_MS) return false;
        if (fails <= 0) return true;
        long since = nowMs - lastTryMs;
        return since < 0 || since >= retryDelayMs(fails);
    }

    /** The wait after this many failed tries in a row: 1 h, 2 h, 4 h, ...
     * and never more than one day. */
    static long retryDelayMs(int fails) {
        if (fails <= 0) return 0;
        if (fails > 5) return AUTO_EVERY_MS;             // 32 h is already over the cap
        return Math.min(RETRY_FIRST_MS << (fails - 1), AUTO_EVERY_MS);
    }

    /** The database snapshot goes to the private cache first; only the
     * locked file reaches the drive. */
    private static void writeBackup(Context app, File out, byte[] key, byte[] salt) throws Exception {
        File snap = new File(app.getCacheDir(), "backup-snap.db");
        try {
            onDbThread(app, () -> { snapshotDb(app, snap); return null; });
            String json = SettingsFile.toJson(Prefs.file(app).getAll(),
                BuildConfig.VERSION_NAME, CarDb.version(), System.currentTimeMillis());
            try (ZipOutputStream z = new ZipOutputStream(
                    BackupCrypto.encrypt(new FileOutputStream(out), key, salt, BackupCrypto.ROUNDS))) {
                z.putNextEntry(new ZipEntry(SETTINGS));
                z.write(json.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                addFile(z, snap, DB);
                for (String name : CERTS) {
                    File f = new File(app.getFilesDir(), name);
                    if (f.isFile()) addFile(z, f, name);
                }
            }
        } finally {
            deleteDb(snap);
        }
    }

    private static void addFile(ZipOutputStream z, File f, String name) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
        }
        z.closeEntry();
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

    /** The same snapshot for the USB export (CsvExport): out is a private
     * file the caller removes with {@link #deleteSnapshot}. Call off the UI
     * thread; it waits for the db writer thread. */
    static void snapshotForExport(Context ctx, File out) throws Exception {
        final Context app = ctx.getApplicationContext();
        onDbThread(app, () -> { snapshotDb(app, out); return null; });
    }

    static void deleteSnapshot(File snap) { deleteDb(snap); }

    private static void prune(File backups) {
        File[] files = backups.listFiles();
        if (files == null) return;
        List<File> ours = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().matches(STAMP_RE + "\\" + EXT)) ours.add(f);
            // A .part left by a backup that was cut off (power lost).
            else if (f.isFile() && f.getName().endsWith(EXT + ".part")
                && System.currentTimeMillis() - f.lastModified() > 3600_000L) f.delete();
        }
        Collections.sort(ours, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = KEEP; i < ours.size(); i++) ours.get(i).delete();
    }

    private static void writeReadme(File backups) {
        File f = new File(backups, "README.txt");
        if (f.exists()) return;
        try {
            writeText(f, "Drive Assist backups. One .dabackup file per backup, the newest 7 kept.\n\n"
                + "Each file is locked with your backup password (AES-256). It holds your\n"
                + "settings, your trips and charges, and your MQTT certificates.\n\n"
                + "To restore: Drive Assist > Settings (gear) > System > Restore.\n"
                + "After installing Drive Assist again, it also offers the newest backup by itself.\n"
                + "You need the backup password. Without it, nobody can open these files.\n\n"
                + "On a computer: tools/backup-open.sh <file> <folder> (in the Drive Assist source).\n");
        } catch (Throwable ignored) {}
    }

    // ---- Restore ----------------------------------------------------------

    /** Restores a backup file and restarts the app: on success this call
     * does not return. Call off the UI thread. password may be null when the
     * key set here locked the backup (see needsPassword).
     *
     * Two parts, and the trip merge is the line between them:
     *
     *  1. Before the merge: unlock, read, migrate a copy of the backup's
     *     database, and put the certificates next to their final place under
     *     a temporary name. The merge itself is one transaction. A failure
     *     anywhere here throws WITH NOTHING CHANGED (NeedPassword,
     *     BackupCrypto.WrongPassword, a full disk, a bad file).
     *
     *  2. After the merge: the live rows have new ids, so there is no way
     *     back. The settings and the moved row ids are saved (the save is
     *     checked and tried again), the certificates get their final names,
     *     and the app ALWAYS restarts, also when one of these steps fails.
     *     Such a failure is in logcat ("backup: restore finished with an
     *     error"); this call does not return to report it.
     *
     * The restarted app shows what happened through {@link #takeNote}. */
    public static void restore(Context ctx, File file, char[] password) throws Exception {
        restore(ctx, file, password, Origin.CAR);
    }

    /** Same, for a file from the given origin (see {@link Origin}).
     *
     * Also throws NotParked: the car must be in Park at the moment the
     * restore starts to change things. The caller's own check is too early
     * for that, because unlocking a big backup, or a slow upload before it,
     * takes long enough for the driver to leave Park. */
    public static void restore(Context ctx, File file, char[] password, Origin origin) throws Exception {
        final Context app = ctx.getApplicationContext();
        final RestoreResult res = new RestoreResult();
        final File dir = new File(app.getCacheDir(), "restore");
        deleteTree(dir);
        if (!dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
        try {
            restoreUnpacked(app, file, password, dir, res, origin);
        } finally {
            deleteTree(dir);   // only reached on failure: success restarts the app
        }
    }

    private static void restoreUnpacked(Context app, File file, char[] password, File dir,
                                        RestoreResult res, Origin origin) throws Exception {
        // Unlock into the private cache.
        final byte[] key;
        final byte[] salt;
        try (InputStream raw = new FileInputStream(file)) {
            BackupCrypto.Header h = BackupCrypto.readHeader(raw);
            byte[][] ks = storedKey(app);
            if (ks != null && java.util.Arrays.equals(ks[1], h.salt)) {
                key = ks[0];
            } else if (password != null) {
                key = BackupCrypto.deriveKey(password, h.salt, h.rounds);
            } else {
                throw new NeedPassword();
            }
            salt = h.salt;
            BackupCrypto.unzip(BackupCrypto.decrypt(raw, h, key), dir);
        }

        File settingsFile = new File(dir, SETTINGS);
        if (!settingsFile.isFile()) throw new java.io.IOException("not a backup: " + file);
        String json = readText(settingsFile);
        final Map<String, Object> settings = SettingsFile.parseSettings(json, origin == Origin.PHONE);
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

        // Every step that can fail for lack of space runs BEFORE the merge:
        // the certificates are copied next to their final place now, and
        // only renamed later.
        final List<File[]> staged = stageFiles(dir, app.getFilesDir(), CERTS);
        try {
            onDbThread(app, () -> {
                // Last moment before anything changes. The restore ends in an
                // app restart, and that must not happen while driving.
                if (!CarState.isParked()) throw new NotParked();
                // Throws: the transaction rolled back, nothing is changed.
                final DbMerge.Result merged = haveDb ? mergeInto(app, tmp, res) : null;

                // From here the live rows may have new ids, and the running
                // sessions still hold the old ones in memory. So nothing below
                // may stop the id prefs from being saved, or the restart.
                // Restart HERE, on the writer thread: nothing may write with
                // the old ids, so nothing runs after this.
                try {
                    final SharedPreferences.Editor e = Prefs.file(app).edit();
                    if (merged != null) shiftIdPrefs(Prefs.file(app).getAll(), e, merged.offsets);
                    SettingsFile.apply(e, settings);
                    e.putBoolean(PREF_OFFER_DONE, true);
                    // The password that opened it locks the next backups too,
                    // so the daily backup goes on after a reinstall.
                    // Not for a file from a phone: see storesKey.
                    if (storesKey(origin)) storeKey(e, key, salt);
                    e.putString(PREF_NOTE, res.settings + "|" + res.tripsAdded + "|" + res.tripsAlreadyThere);
                    final boolean[] first = {true};
                    finishRestore(
                        () -> {
                            // A commit that failed left the new values in
                            // memory: any later commit writes them all again.
                            if (first[0]) { first[0] = false; return e.commit(); }
                            return Prefs.file(app).edit().commit();
                        },
                        () -> restartApp(app),
                        () -> moveStaged(staged),
                        // modehelper keeps its own copy of a few settings, and
                        // after a wipe it starts from its defaults. Tell it
                        // now: the boot sync only covers the switches it
                        // re-sends on every tick.
                        () -> com.geely.drivemem.controls.HelperSync.pushRestored(app, settings),
                        () -> { deleteDb(tmp); deleteTree(dir); },
                        () -> Log.i(TAG, "backup: restored " + file + " settings=" + res.settings
                            + " trips+" + res.tripsAdded + (res.tripsAlreadyThere ? " (already there)" : "")));
                } catch (Throwable t) {
                    Log.w(TAG, "backup: restore finished with an error: " + t);
                    restartApp(app);
                }
                return null;
            });
        } finally {
            // Only reached when the merge did not happen.
            deleteDb(tmp);
            discardStaged(staged);
        }
    }

    interface Step { void run() throws Exception; }
    interface Save { boolean commit(); }
    static final int SAVE_TRIES = 3;

    /** The end of a restore, after the trip merge has committed. The order
     * is the point:
     *
     *  - prefs first, because they hold the moved row ids. A save that
     *    reports false is tried again, {@link #SAVE_TRIES} times in all.
     *  - then every other step, each one also when a step before it failed.
     *  - restart last, ALWAYS.
     *
     * Returns the first failure, or null. On the car restart ends the
     * process, so nothing reads the result there; the tests do. */
    static Throwable finishRestore(Save prefs, Runnable restart, Step... steps) {
        Throwable first = null;
        try {
            boolean saved = false;
            for (int i = 0; i < SAVE_TRIES && !saved; i++) {
                try {
                    saved = prefs.commit();
                } catch (Throwable t) {
                    if (first == null) first = t;
                }
            }
            if (saved) first = null;
            else if (first == null) first = new java.io.IOException("could not save the settings");
            for (Step s : steps) {
                try {
                    s.run();
                } catch (Throwable t) {
                    if (first == null) first = t;
                }
            }
            if (first != null) Log.w(TAG, "backup: restore finished with an error: " + first);
        } finally {
            restart.run();
        }
        return first;
    }

    static final String STAGED_SUFFIX = ".restore";

    /** Copies each named file that is in fromDir into toDir under a temporary
     * name, on the disk (synced). Returns {temporary, final} pairs for
     * {@link #moveStaged}. On a failure it removes what it made and throws:
     * the files with the final names are never touched here. */
    static List<File[]> stageFiles(File fromDir, File toDir, String[] names) throws Exception {
        List<File[]> staged = new ArrayList<>();
        try {
            for (String name : names) {
                File src = new File(fromDir, name);
                if (!src.isFile()) continue;
                File part = new File(toDir, name + STAGED_SUFFIX);
                staged.add(new File[]{part, new File(toDir, name)});
                copy(src, part);
            }
        } catch (Throwable t) {
            discardStaged(staged);
            throw t;
        }
        return staged;
    }

    /** Gives every staged file its final name. A rename needs no new space.
     * Tries them all, then throws when one did not move. */
    static void moveStaged(List<File[]> staged) throws Exception {
        File failed = null;
        for (File[] pair : staged) {
            if (!pair[0].renameTo(pair[1]) && failed == null) failed = pair[1];
        }
        if (failed != null) throw new java.io.IOException("rename to " + failed);
    }

    static void discardStaged(List<File[]> staged) {
        for (File[] pair : staged) pair[0].delete();
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
            // The merge is committed. Nothing after this line may throw, or
            // the caller would take a finished merge for a failed one.
            try { db.execSQL("DETACH DATABASE old"); }
            catch (Throwable t) { Log.w(TAG, "backup: detach: " + t); }
            Long t = r.added.get("trip");
            res.tripsAdded = t == null ? 0 : t;
            return r;
        } finally {
            try { db.close(); } catch (Throwable t) { Log.w(TAG, "backup: close: " + t); }
        }
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
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();   // on the drive before the caller renames it
        }
    }

    /** Forces a file that is already written and closed onto its drive. */
    static void syncFile(File f) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.getFD().sync();
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
