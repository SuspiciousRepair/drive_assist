package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The "OEM translations" integration: better text for the car's own apps.
 *
 * Install downloads the translation files from the integration repo
 * ({@link OemTextPolicy#BASE_URL}), makes a patched copy of each listed app
 * from the copy this car has ({@link ArscPatch}, {@link ApkRewrite}: only
 * resources.arsc changes), and bind-mounts the copy over the /system apk
 * through the local root shell. /system itself is never written.
 *
 * Easy to undo, by the owner's rule (2026-11-19):
 *  - Remove unmounts every copy and deletes {@link OemTextPolicy#DIR};
 *  - a reboot drops the mounts too; {@link #reapply} puts them back after
 *    boot only while the integration is on;
 *  - a crash of a patched app (DropBox "system_app_crash"/"data_app_crash")
 *    takes that app's copy away at once, and it stays away until the next
 *    Install.
 *
 * Only on the system versions the manifest lists ({@link
 * OemTextPolicy#VERSION_PROP}): the text was made against those apps. On any
 * other, Install changes nothing, and after a system update the next boot
 * removes the copies and turns the integration off.
 *
 * Text only. The Wi-Fi strength word (a hard-coded string in code) is not
 * fixed here.
 *
 * All work runs on one worker thread, in order. State is in our prefs:
 * this app owns it; Drive Assist asks with STATUS and keeps nothing. */
final class OemText {
    private OemText() {}

    static final String TAG = "ModeHelper";
    static final String PREFS = "modehelper";
    static final String K_ON = "oem_on";
    static final String K_APPS = "oem_apps";          // "pkg|/system/..apk|fingerprint" per line
    static final String K_CRASHED = "oem_crashed";    // packages, one per line
    static final String K_MOUNTED_AT = "oem_mounted_at";
    static final String K_RESULT = "oem_result";      // last result code, see below
    static final String K_NOTE = "oem_note";
    static final String K_SYSTEM = "oem_system";      // system version the copies were made on

    // Result codes, given to Drive Assist in STATUS.
    static final String OK = "ok", PARTIAL = "partial", DOWNLOAD = "download", FORMAT = "format",
            ADB = "adb", PATCH = "patch", NONE = "none", ERROR = "error", SYSTEM = "system", HASH = "hash";

    static final int ADB_MINUTES = 2;
    static final long PORT_WAIT_MS = 8000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** "download", "patch", "mount" or "remove" while a job runs, else null. */
    private static volatile String step;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static File srcDir(Context c) { return new File(c.getFilesDir(), "oem-text"); }
    private static File stageDir(Context c) { return new File(c.getFilesDir(), "oem-text-stage"); }

    /** One patched app, as remembered between boots: the factory apk's
     * SHA-256 (one the manifest allowed) and our copy's. */
    static final class App {
        final String pkg, apk, fingerprint, srcSha, copySha;
        App(String pkg, String apk, String fingerprint, String srcSha, String copySha) {
            this.pkg = pkg; this.apk = apk; this.fingerprint = fingerprint; this.srcSha = srcSha; this.copySha = copySha;
        }
        String line() { return pkg + "|" + apk + "|" + fingerprint + "|" + srcSha + "|" + copySha; }
        static App parse(String line) {
            String[] f = line.split("\\|", 5);
            return f.length == 5 && OemTextPolicy.validSha(f[3]) && OemTextPolicy.validSha(f[4])
                ? new App(f[0], f[1], f[2], f[3], f[4]) : null;
        }
    }

    /** What the manifest gives for one app: the text, and the SHA-256 of the
     * factory apks the text was made for. */
    static final class Source {
        final Map<String, Map<String, String>> tr;
        final List<String> apkSha;
        Source(Map<String, Map<String, String>> tr, List<String> apkSha) { this.tr = tr; this.apkSha = apkSha; }
    }

    static List<App> apps(Context c) {
        List<App> out = new ArrayList<>();
        for (String l : prefs(c).getString(K_APPS, "").split("\n")) {
            App a = l.isEmpty() ? null : App.parse(l);
            if (a != null && OemTextPolicy.validPackage(a.pkg) && OemTextPolicy.validSystemApk(a.apk)) out.add(a);
        }
        return out;
    }

    static Set<String> crashed(Context c) {
        Set<String> s = new LinkedHashSet<>();
        for (String l : prefs(c).getString(K_CRASHED, "").split("\n")) if (!l.isEmpty()) s.add(l);
        return s;
    }

    /** Size and time of the factory apk: when an OEM update changes them,
     * the copy is made again from the new one. Only read while nothing of
     * ours is mounted over it, or it would describe our copy. */
    static String fingerprint(String apk) {
        File f = new File(apk);
        return f.length() + ":" + f.lastModified();
    }

    // ---------------------------------------------------------------- jobs

    static boolean busy() { return step != null; }

    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        WORKER.execute(() -> run(app, "install", () -> doInstall(app)));
    }

    static void remove(Context ctx) {
        final Context app = ctx.getApplicationContext();
        WORKER.execute(() -> run(app, "remove", () -> doRemove(app)));
    }

    /** After a boot: mounts the copies again while the integration is on.
     * Safe to call any number of times: an app already mounted is left alone. */
    static void reapply(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!prefs(app).getBoolean(K_ON, false)) return;
        WORKER.execute(() -> run(app, "reapply", () -> doReapply(app)));
    }

    private interface Job { void go() throws Exception; }

    private static void run(Context app, String what, Job job) {
        try {
            job.go();
        } catch (Throwable t) {
            Log.w(TAG, "oem text " + what + ": " + t);
            result(app, ERROR, String.valueOf(t.getMessage()));
        } finally {
            step = null;
        }
    }

    private static void result(Context app, String code, String note) {
        prefs(app).edit().putString(K_RESULT, code).putString(K_NOTE, note == null ? "" : note).commit();
        Log.i(TAG, "oem text: " + code + (note == null || note.isEmpty() ? "" : " (" + note + ")"));
    }

    private static void doInstall(Context app) throws Exception {
        step = "download";
        Map<String, Source> files;
        String system = systemVersion();
        try {
            files = download(app, system);
        } catch (SystemException e) {
            result(app, SYSTEM, e.getMessage());
            return;
        } catch (HashException e) {
            result(app, HASH, e.getMessage());
            return;
        } catch (FormatException e) {
            result(app, FORMAT, e.getMessage());
            return;
        } catch (IOException e) {
            result(app, DOWNLOAD, e.getMessage());
            return;
        }
        LocalAdbShell adb = openAdb(app);
        if (adb == null) { result(app, ADB, null); return; }

        // Start clean: every copy of ours off, so each app is read from
        // /system as the factory made it.
        step = "remove";
        String wiped = adb.execute(OemTextPolicy.wipeCommand(packagesOf(apps(app))));
        if (!wiped.contains(OemTextPolicy.WIPED)) throw new IOException("wipe: " + wiped);
        prefs(app).edit().putBoolean(K_ON, true).putString(K_SYSTEM, system)
            .remove(K_APPS).remove(K_CRASHED).commit();

        List<App> done = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Source> e : files.entrySet()) {
            String pkg = e.getKey();
            String apk = systemApk(app, pkg);
            if (apk == null) { notes.add(pkg + ": not on this car"); continue; }
            App a = patchAndMount(app, adb, pkg, apk, e.getValue().tr, e.getValue().apkSha, notes);
            if (a != null) done.add(a);
        }
        save(app, done);
        if (done.isEmpty()) result(app, files.isEmpty() ? NONE : PATCH, String.join("; ", notes));
        else result(app, done.size() == files.size() ? OK : PARTIAL, String.join("; ", notes));
    }

    private static void doRemove(Context app) throws Exception {
        step = "remove";
        LocalAdbShell adb = openAdb(app);
        if (adb == null) { result(app, ADB, null); return; }
        // Off first, so nothing mounts it again while this runs.
        prefs(app).edit().putBoolean(K_ON, false).commit();
        String out = adb.execute(OemTextPolicy.wipeCommand(packagesOf(apps(app))));
        if (!out.contains(OemTextPolicy.WIPED)) throw new IOException("wipe: " + out);
        deleteTree(srcDir(app));
        deleteTree(stageDir(app));
        prefs(app).edit().remove(K_APPS).remove(K_CRASHED).remove(K_MOUNTED_AT).remove(K_RESULT).remove(K_NOTE)
            .remove(K_SYSTEM).commit();
        Log.i(TAG, "oem text: removed");
    }

    private static void doReapply(Context app) throws Exception {
        if (!prefs(app).getBoolean(K_ON, false)) return;
        List<App> list = apps(app);
        Set<String> crashed = crashed(app);
        if (list.isEmpty()) return;
        step = "mount";
        LocalAdbShell adb = openAdb(app);
        if (adb == null) { result(app, ADB, null); return; }
        // A system update came in: these copies were made for the old apps.
        // Take them away and stay off until an Install on a listed version.
        String system = systemVersion();
        if (!system.equals(prefs(app).getString(K_SYSTEM, ""))) {
            prefs(app).edit().putBoolean(K_ON, false).commit();
            String out = adb.execute(OemTextPolicy.wipeCommand(packagesOf(list)));
            prefs(app).edit().remove(K_APPS).remove(K_CRASHED).remove(K_SYSTEM).commit();
            result(app, SYSTEM, system + (out.contains(OemTextPolicy.WIPED) ? "" : "; wipe: " + out));
            return;
        }
        List<String> mounted = OemTextPolicy.parseList(adb.execute(OemTextPolicy.listCommand()));
        List<App> keep = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int ok = 0, want = 0;
        for (App a : list) {
            if (crashed.contains(a.pkg)) { keep.add(a); continue; }
            want++;
            if (mounted.contains(a.apk)) { keep.add(a); ok++; continue; }
            if (a.fingerprint.equals(fingerprint(a.apk))) {
                // Mounts our copy only if it still has the hash we wrote.
                String out = adb.execute(OemTextPolicy.remountCommand(a.apk, a.pkg, a.copySha));
                if (out.contains(OemTextPolicy.MOUNTED)) { keep.add(a); ok++; continue; }
                if (!out.contains(OemTextPolicy.NO_FILE)) { notes.add(a.pkg + ": " + out); keep.add(a); continue; }
            }
            // Our copy is gone (or not ours any more): make it again from the
            // translation file we kept, but only from the same factory apk
            // as before. A changed OEM app is refused: Install again.
            Map<String, Map<String, String>> tr = readSaved(app, a.pkg);
            App b = tr == null ? null
                : patchAndMount(app, adb, a.pkg, a.apk, tr, java.util.Collections.singletonList(a.srcSha), notes);
            if (b != null) { keep.add(b); ok++; }
            else if (tr == null) notes.add(a.pkg + ": translation file missing, Install again");
        }
        save(app, keep);
        prefs(app).edit().putLong(K_MOUNTED_AT, System.currentTimeMillis()).commit();
        result(app, ok == want ? OK : PARTIAL, String.join("; ", notes));
    }

    /** Patches {@code pkg}'s factory apk with {@code tr} and mounts the copy,
     * only when the factory apk's SHA-256 is one of {@code allowedSha}.
     * Returns the record to keep, or null (with a note) when it failed. */
    private static App patchAndMount(Context app, LocalAdbShell adb, String pkg, String apk,
                                     Map<String, Map<String, String>> tr, List<String> allowedSha,
                                     List<String> notes) throws IOException {
        step = "patch";
        File stage = stageDir(app);
        if (!stage.isDirectory() && !stage.mkdirs()) throw new IOException("mkdir " + stage);
        File out = new File(stage, new File(apk).getName());
        String fp = fingerprint(apk);
        // The very file the text was made for, or nothing: a different build
        // of the app could hold other strings under the same names.
        String srcSha = OemTextPolicy.sha256(new File(apk));
        if (allowedSha == null || !allowedSha.contains(srcSha)) {
            notes.add(pkg + ": not the app the text was made for (" + srcSha.substring(0, 12) + ")");
            return null;
        }
        String copySha;
        try {
            byte[] arsc = ApkRewrite.read(new File(apk), "resources.arsc");
            if (arsc == null) { notes.add(pkg + ": no resources.arsc"); return null; }
            ArscPatch.Result r = ArscPatch.patch(arsc, tr);
            if (r.changed() == 0) { notes.add(pkg + ": nothing to change"); return null; }
            // Read our own result back before the car sees it.
            if (!checks(arsc, r.arsc, tr)) { notes.add(pkg + ": patch check failed"); return null; }
            ApkRewrite.replace(new File(apk), out, "resources.arsc", r.arsc);
            copySha = OemTextPolicy.sha256(out);
            Log.i(TAG, "oem text: " + pkg + " replaced=" + r.replaced + " added=" + r.added + " missing=" + r.missing);
        } catch (RuntimeException e) {
            out.delete();
            notes.add(pkg + ": " + e.getMessage());
            return null;
        }
        // Root moves it from our dir, so it must be readable on the way.
        out.setReadable(true, false);
        step = "mount";
        String res = adb.execute(OemTextPolicy.mountCommand(out.getAbsolutePath(), apk, pkg));
        out.delete();                                    // gone already when the move worked
        if (!res.contains(OemTextPolicy.MOUNTED)) { notes.add(pkg + ": mount: " + res); return null; }
        prefs(app).edit().putLong(K_MOUNTED_AT, System.currentTimeMillis()).commit();
        return new App(pkg, apk, fp, srcSha, copySha);
    }

    /** Every changed line reads back as the text we asked for, and the
     * default slot (the fallback) is exactly as it was. */
    static boolean checks(byte[] before, byte[] after, Map<String, Map<String, String>> tr) {
        if (!ArscPatch.strings(before, "").equals(ArscPatch.strings(after, ""))) return false;
        for (Map.Entry<String, Map<String, String>> lt : tr.entrySet()) {
            Map<String, String> got = ArscPatch.strings(after, lt.getKey());
            for (Map.Entry<String, String> nt : lt.getValue().entrySet()) {
                String g = got.get(nt.getKey());
                if (g != null && !g.equals(nt.getValue())) return false;
            }
        }
        return true;
    }

    private static void save(Context app, List<App> list) {
        StringBuilder s = new StringBuilder();
        for (App a : list) s.append(a.line()).append('\n');
        prefs(app).edit().putString(K_APPS, s.toString()).commit();
    }

    private static List<String> packagesOf(List<App> list) {
        List<String> out = new ArrayList<>();
        for (App a : list) out.add(a.pkg);
        return out;
    }

    /** The factory apk of {@code pkg}, only when it is a /system app. */
    private static String systemApk(Context app, String pkg) {
        try {
            ApplicationInfo ai = app.getPackageManager().getApplicationInfo(pkg, 0);
            return OemTextPolicy.validSystemApk(ai.sourceDir) ? ai.sourceDir : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- crashes

    /** Called once by ModeHelperService: watches DropBox for crashes of the
     * apps we patched. */
    static void watchCrashes(Context ctx) {
        final Context app = ctx.getApplicationContext();
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String tag = i.getStringExtra(DropBoxManager.EXTRA_TAG);
                if (!"system_app_crash".equals(tag) && !"data_app_crash".equals(tag)) return;
                if (!prefs(app).getBoolean(K_ON, false)) return;
                final long at = i.getLongExtra(DropBoxManager.EXTRA_TIME, 0);
                WORKER.execute(() -> run(app, "crash", () -> onCrash(app, tag, at)));
            }
        }, new IntentFilter(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED));
    }

    private static void onCrash(Context app, String tag, long at) throws Exception {
        DropBoxManager db = (DropBoxManager) app.getSystemService(Context.DROPBOX_SERVICE);
        if (db == null) return;
        DropBoxManager.Entry e = db.getNextEntry(tag, at - 1);
        String text = null;
        if (e != null) {
            try { text = e.getText(4096); } finally { e.close(); }
        }
        String pkg = OemTextPolicy.crashedPackage(text);
        if (pkg == null) return;
        App hit = null;
        for (App a : apps(app)) if (a.pkg.equals(pkg)) hit = a;
        if (hit == null || crashed(app).contains(pkg)) return;
        step = "remove";
        Log.w(TAG, "oem text: " + pkg + " crashed with our copy, taking it away");
        Set<String> s = crashed(app);
        s.add(pkg);
        prefs(app).edit().putString(K_CRASHED, String.join("\n", s)).commit();
        LocalAdbShell adb = openAdb(app);
        if (adb == null) { result(app, ADB, pkg + " crashed, copy still mounted"); return; }
        String out = adb.execute(OemTextPolicy.unmountCommand(hit.apk, hit.pkg));
        result(app, PARTIAL, pkg + " crashed, factory text back" + (out.contains(OemTextPolicy.REMOVED) ? "" : ": " + out));
    }

    // ---------------------------------------------------------------- status

    /** For Drive Assist's page. state: "busy", "on", "off" or "error". */
    static Bundle status(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Bundle b = new Bundle();
        String s = step;
        String res = p.getString(K_RESULT, "");
        String state = s != null ? "busy"
            : p.getBoolean(K_ON, false) ? (OK.equals(res) || PARTIAL.equals(res) ? "on" : "error")
            : (res.isEmpty() || OK.equals(res) ? "off" : "error");
        b.putString("state", state);
        if (s != null) b.putString("step", s);
        b.putString("result", res);
        b.putString("note", p.getString(K_NOTE, ""));
        int on = 0;
        Set<String> crashed = crashed(ctx);
        for (App a : apps(ctx)) if (!crashed.contains(a.pkg)) on++;
        b.putInt("apps", on);
        b.putString("crashed", String.join(",", crashed));
        b.putString("system", systemVersion());
        return b;
    }

    // ---------------------------------------------------------------- download

    static final class FormatException extends Exception {
        FormatException(String m) { super(m); }
    }

    /** A downloaded file is not pinned, or its SHA-256 is not the pinned one. */
    static final class HashException extends Exception {
        HashException(String m) { super(m); }
    }

    /** The manifest does not list this car's system version. */
    static final class SystemException extends Exception {
        SystemException(String m) { super(m); }
    }

    /** The car's system version ({@link OemTextPolicy#VERSION_PROP}), or "". */
    static String systemVersion() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, OemTextPolicy.VERSION_PROP, "");
            return v == null ? "" : v.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** package -> language -> name -> text, from the repo. Also kept in our
     * files dir, for a re-patch after an OEM update with no network. */
    private static Map<String, Source> download(Context app, String system)
            throws IOException, FormatException, SystemException, HashException {
        Map<String, Source> out = new LinkedHashMap<>();
        Map<String, byte[]> raw = new LinkedHashMap<>();
        try {
            byte[] mb = fetch(OemTextPolicy.BASE_URL + OemTextPolicy.MANIFEST, OemTextPolicy.MAX_MANIFEST_BYTES);
            // Checked before anything in it is read.
            if (!OemTextPolicy.hashOk(OemTextPolicy.MANIFEST, mb)) throw new HashException(OemTextPolicy.MANIFEST);
            JSONObject m = new JSONObject(new String(mb, StandardCharsets.UTF_8));
            if (m.optInt("format", -1) != OemTextPolicy.FORMAT)
                throw new FormatException("manifest format " + m.opt("format") + ", this helper reads " + OemTextPolicy.FORMAT);
            // Checked before any app file is fetched: on a version the text
            // was not made for, nothing is downloaded and nothing changes.
            List<String> systems = new ArrayList<>();
            JSONArray sl = m.optJSONArray("systems");
            if (sl != null) for (int i = 0; i < sl.length(); i++) systems.add(sl.optString(i));
            if (!OemTextPolicy.systemAllowed(system, systems))
                throw new SystemException(system.isEmpty() ? "unknown system version" : system);
            JSONArray list = m.getJSONArray("apps");
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                String pkg = a.optString("package"), file = a.optString("file");
                if (!OemTextPolicy.validPackage(pkg) || !OemTextPolicy.validFile(file)) continue;
                if (OemTextPolicy.pinnedSha256(file) == null) throw new HashException("not pinned: " + file);
                byte[] body = fetch(OemTextPolicy.BASE_URL + file, OemTextPolicy.MAX_FILE_BYTES);
                if (!OemTextPolicy.hashOk(file, body)) throw new HashException(file);
                Map<String, Map<String, String>> tr = parse(body);
                List<String> apkSha = new ArrayList<>();
                JSONArray hl = a.optJSONArray("apk_sha256");
                if (hl != null) for (int j = 0; j < hl.length(); j++) {
                    String h = hl.optString(j).toLowerCase(java.util.Locale.ROOT);
                    if (OemTextPolicy.validSha(h)) apkSha.add(h);
                }
                // No factory hash listed: nothing to check against, so no.
                if (tr.isEmpty() || apkSha.isEmpty()) continue;
                out.put(pkg, new Source(tr, apkSha));
                raw.put(pkg, body);
            }
        } catch (org.json.JSONException e) {
            throw new FormatException(e.getMessage());
        }
        // Keep what we got, replacing the old set only when all came down.
        File dir = srcDir(app);
        deleteTree(dir);
        if (!dir.mkdirs()) throw new IOException("mkdir " + dir);
        for (Map.Entry<String, byte[]> e : raw.entrySet()) {
            try (OutputStream os = new FileOutputStream(new File(dir, e.getKey() + ".json"))) { os.write(e.getValue()); }
        }
        return out;
    }

    private static Map<String, Map<String, String>> readSaved(Context app, String pkg) {
        File f = new File(srcDir(app), pkg + ".json");
        if (!f.isFile() || f.length() > OemTextPolicy.MAX_FILE_BYTES) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int got = 0;
            for (int n; got < b.length && (n = in.read(b, got, b.length - got)) > 0; ) got += n;
            byte[] body = Arrays.copyOf(b, got);
            // Still one of the pinned files: after an update with new pins,
            // an old saved file is refused and the owner taps Install again.
            if (!OemTextPolicy.pinnedAnywhere(body)) return null;
            Map<String, Map<String, String>> tr = parse(body);
            return tr.isEmpty() ? null : tr;
        } catch (Exception e) {
            return null;
        }
    }

    /** language -> name -> text; anything that fails a check is left out. */
    static Map<String, Map<String, String>> parse(byte[] body) throws org.json.JSONException {
        JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
        Map<String, Map<String, String>> tr = new LinkedHashMap<>();
        for (Iterator<String> li = o.keys(); li.hasNext(); ) {
            String lang = li.next();
            JSONObject t = o.optJSONObject(lang);
            if (!OemTextPolicy.validLang(lang) || t == null) continue;
            Map<String, String> names = new LinkedHashMap<>();
            for (Iterator<String> ni = t.keys(); ni.hasNext(); ) {
                String name = ni.next();
                String text = t.optString(name, null);
                if (OemTextPolicy.validName(name) && OemTextPolicy.validText(text)) names.put(name, text);
            }
            if (!names.isEmpty()) tr.put(lang, names);
        }
        return tr;
    }

    // https only, no redirects, size capped.
    private static byte[] fetch(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status + " for " + url);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (out.size() + n > max) throw new IOException("too big: " + url);
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The local root shell, opening ADB for {@link #ADB_MINUTES} when it is
     * off (the same short window the backlight floor and the cleanup use).
     * Null when it did not open. */
    private static LocalAdbShell openAdb(Context app) {
        LocalAdbShell adb = new LocalAdbShell();
        if (adb.portOpen(500)) return adb;
        if (!AdbControl.isEnabled(app)) AdbControl.set(app, true, ADB_MINUTES);
        long until = System.currentTimeMillis() + PORT_WAIT_MS;
        while (System.currentTimeMillis() < until) {
            if (adb.portOpen(1000)) return adb;
            try { Thread.sleep(300); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
