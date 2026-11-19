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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The "Car app translations" integration: better text for the car's own apps.
 *
 * The text: the latest commit of the integration-oem-translations repo on
 * GitHub ({@link OemTextPolicy#LATEST_URL}). It is downloaded only when that
 * commit is not the one applied before, always as one snapshot of that
 * commit, and kept in our files dir for a start with no network. GitHub is
 * trusted here as it is for this app's own updates (owner, 2026-11-19): the
 * same account publishes both, and the app signing key is public anyway.
 *
 * The check that protects the car: an app is patched only when the SHA-256
 * of the car's factory apk is one the manifest lists ("apk_sha256"), the
 * very file the text was made for. Any other build of the app, for example
 * after a system update, is left alone.
 *
 * Install patches a copy of each known app ({@link ArscPatch},
 * {@link ApkRewrite}: only resources.arsc changes) and bind-mounts it over
 * the /system apk through the local root shell. /system is never written.
 *
 * Easy to undo:
 *  - Remove unmounts every copy and deletes {@link OemTextPolicy#DIR};
 *  - a reboot drops the mounts; {@link #reapply} makes the copies again,
 *    with the newest text and the same check, only while the integration
 *    is on;
 *  - a crash of a patched app (DropBox) takes that app's copy away at once,
 *    and it stays away until the next Install.
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
    static final String K_APPS = "oem_apps";          // "pkg|/system/..apk" per line, mounted now
    static final String K_CRASHED = "oem_crashed";    // packages, one per line
    static final String K_SOURCE = "oem_source";      // the repo commit of the text the copies were made from
    static final String K_RESULT = "oem_result";      // last result code, see below
    static final String K_NOTE = "oem_note";

    // Result codes, given to Drive Assist in STATUS.
    static final String OK = "ok", PARTIAL = "partial", NONE = "none", ADB = "adb",
            DOWNLOAD = "download", ERROR = "error";

    static final int ADB_MINUTES = 2;
    static final long PORT_WAIT_MS = 8000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** "download", "patch", "mount" or "remove" while a job runs, else null. */
    private static volatile String step;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static File stageDir(Context c) { return new File(c.getFilesDir(), "oem-text-stage"); }
    /** The text last downloaded: manifest.json, apps/*.json and SOURCE (its commit). */
    private static File textDir(Context c) { return new File(c.getFilesDir(), "oem-text"); }

    /** One app in the bundled manifest. */
    static final class Known {
        final String pkg;
        final Map<String, Map<String, String>> tr;
        final List<String> apkSha;
        Known(String pkg, Map<String, Map<String, String>> tr, List<String> apkSha) {
            this.pkg = pkg; this.tr = tr; this.apkSha = apkSha;
        }
    }

    /** One patched app, mounted now. */
    static final class App {
        final String pkg, apk;
        App(String pkg, String apk) { this.pkg = pkg; this.apk = apk; }
    }

    static List<App> apps(Context c) {
        List<App> out = new ArrayList<>();
        for (String l : prefs(c).getString(K_APPS, "").split("\n")) {
            String[] f = l.split("\\|", 2);
            if (f.length == 2 && OemTextPolicy.validPackage(f[0]) && OemTextPolicy.validSystemApk(f[1]))
                out.add(new App(f[0], f[1]));
        }
        return out;
    }

    static Set<String> crashed(Context c) {
        Set<String> s = new LinkedHashSet<>();
        for (String l : prefs(c).getString(K_CRASHED, "").split("\n")) if (!l.isEmpty()) s.add(l);
        return s;
    }

    // ---------------------------------------------------------------- jobs

    static boolean busy() { return step != null; }

    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        WORKER.execute(() -> run(app, "install", () -> apply(app, true)));
    }

    static void remove(Context ctx) {
        final Context app = ctx.getApplicationContext();
        WORKER.execute(() -> run(app, "remove", () -> doRemove(app)));
    }

    /** After a boot, or after this app was updated: puts the copies back
     * while the integration is on, with the newest text. An app already
     * mounted from the same text is left alone, so calling it again does no
     * harm. */
    static void reapply(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!prefs(app).getBoolean(K_ON, false)) return;
        WORKER.execute(() -> run(app, "reapply", () -> apply(app, false)));
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

    /** Gets the newest text, then patches and mounts every known app.
     * {@code fresh} (Install) first takes every copy away; a reapply does
     * that only when the text changed, and otherwise keeps what is mounted. */
    private static void apply(Context app, boolean fresh) throws Exception {
        step = "download";
        try {
            update(app);
        } catch (IOException e) {
            // No network (a car start often has none yet): the text we kept
            // is fine. With none kept, there is nothing to apply.
            if (!new File(textDir(app), "SOURCE").isFile()) { result(app, DOWNLOAD, e.getMessage()); return; }
            Log.i(TAG, "oem text: no download (" + e.getMessage() + "), using the text kept");
        }
        step = "mount";
        String source = savedSource(app);
        List<Known> known = saved(app);
        LocalAdbShell adb = openAdb(app);
        if (adb == null) { result(app, ADB, null); return; }

        if (!source.equals(prefs(app).getString(K_SOURCE, ""))) fresh = true;
        if (fresh) {
            step = "remove";
            String out = adb.execute(OemTextPolicy.wipeCommand(packagesOf(known)));
            if (!out.contains(OemTextPolicy.WIPED)) throw new IOException("wipe: " + out);
            prefs(app).edit().remove(K_APPS).remove(K_CRASHED).commit();
        }
        prefs(app).edit().putBoolean(K_ON, true).putString(K_SOURCE, source).commit();

        List<String> mounted = OemTextPolicy.parseList(adb.execute(OemTextPolicy.listCommand()));
        Set<String> crashed = crashed(app);
        List<App> done = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int want = 0;
        for (Known k : known) {
            if (crashed.contains(k.pkg)) continue;
            String apk = systemApk(app, k.pkg);
            if (apk == null) { notes.add(k.pkg + ": not on this car"); continue; }
            want++;
            if (mounted.contains(apk)) { done.add(new App(k.pkg, apk)); continue; }
            if (patchAndMount(app, adb, k, apk, notes)) done.add(new App(k.pkg, apk));
            // Not applied (another build of the app, after a system update):
            // no old copy of ours stays on the disk.
            else adb.execute(OemTextPolicy.unmountCommand(apk, k.pkg));
        }
        save(app, done);
        String note = String.join("; ", notes);
        result(app, done.isEmpty() ? NONE : done.size() == want ? OK : PARTIAL, note);
    }

    private static void doRemove(Context app) throws Exception {
        step = "remove";
        LocalAdbShell adb = openAdb(app);
        if (adb == null) { result(app, ADB, null); return; }
        // Off first, so nothing mounts it again while this runs.
        prefs(app).edit().putBoolean(K_ON, false).commit();
        String out = adb.execute(OemTextPolicy.wipeCommand(packagesOf(saved(app))));
        if (!out.contains(OemTextPolicy.WIPED)) throw new IOException("wipe: " + out);
        deleteTree(stageDir(app));
        deleteTree(textDir(app));
        prefs(app).edit().remove(K_APPS).remove(K_CRASHED).remove(K_SOURCE).remove(K_RESULT).remove(K_NOTE).commit();
        Log.i(TAG, "oem text: removed");
    }

    /** Patches {@code k}'s factory apk and mounts the copy, only when the
     * apk is the very file the text was made for. True when it is mounted. */
    private static boolean patchAndMount(Context app, LocalAdbShell adb, Known k, String apk,
                                         List<String> notes) throws IOException {
        step = "patch";
        // Check 2, the known file: a different build of the app could hold
        // other strings under the same names.
        String sha = OemTextPolicy.sha256(new File(apk));
        if (!k.apkSha.contains(sha)) {
            notes.add(k.pkg + ": not the app the text was made for (" + sha.substring(0, 12) + ")");
            return false;
        }
        File stage = stageDir(app);
        if (!stage.isDirectory() && !stage.mkdirs()) throw new IOException("mkdir " + stage);
        File out = new File(stage, new File(apk).getName());
        try {
            byte[] arsc = ApkRewrite.read(new File(apk), "resources.arsc");
            if (arsc == null) { notes.add(k.pkg + ": no resources.arsc"); return false; }
            ArscPatch.Result r = ArscPatch.patch(arsc, k.tr);
            if (r.changed() == 0) { notes.add(k.pkg + ": nothing to change"); return false; }
            ApkRewrite.replace(new File(apk), out, "resources.arsc", r.arsc);
            Log.i(TAG, "oem text: " + k.pkg + " replaced=" + r.replaced + " added=" + r.added + " missing=" + r.missing);
        } catch (RuntimeException e) {
            out.delete();
            notes.add(k.pkg + ": " + e.getMessage());
            return false;
        }
        step = "mount";
        String res = adb.execute(OemTextPolicy.mountCommand(out.getAbsolutePath(), apk, k.pkg));
        out.delete();                                    // gone already when the move worked
        if (!res.contains(OemTextPolicy.MOUNTED)) { notes.add(k.pkg + ": mount: " + res); return false; }
        return true;
    }

    private static void save(Context app, List<App> list) {
        StringBuilder s = new StringBuilder();
        for (App a : list) s.append(a.pkg).append('|').append(a.apk).append('\n');
        prefs(app).edit().putString(K_APPS, s.toString()).commit();
    }

    private static List<String> packagesOf(List<Known> list) {
        List<String> out = new ArrayList<>();
        for (Known k : list) out.add(k.pkg);
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

    // ---------------------------------------------------------------- the text

    /** Downloads the latest commit's text when it is not the one we have.
     * The new set replaces the old one only when all of it came down. */
    private static void update(Context app) throws IOException {
        String latest = new String(fetch(OemTextPolicy.LATEST_URL, "application/vnd.github.sha", 256),
            StandardCharsets.UTF_8).trim();
        if (!OemTextPolicy.validCommit(latest)) throw new IOException("latest commit: " + latest);
        if (latest.equals(savedSource(app))) return;
        String base = OemTextPolicy.RAW_URL + latest + "/";
        File tmp = new File(app.getFilesDir(), "oem-text.new");
        deleteTree(tmp);
        if (!new File(tmp, "apps").mkdirs()) throw new IOException("mkdir " + tmp);
        byte[] manifest = fetch(base + "manifest.json", null, OemTextPolicy.MAX_FILE_BYTES);
        write(new File(tmp, "manifest.json"), manifest);
        try {
            JSONArray list = new JSONObject(new String(manifest, StandardCharsets.UTF_8)).getJSONArray("apps");
            for (int i = 0; i < list.length(); i++) {
                String file = list.getJSONObject(i).optString("file");
                if (!OemTextPolicy.validFile(file)) continue;
                write(new File(tmp, file), fetch(base + file, null, OemTextPolicy.MAX_FILE_BYTES));
            }
        } catch (org.json.JSONException e) {
            deleteTree(tmp);
            throw new IOException("manifest: " + e.getMessage());
        }
        write(new File(tmp, "SOURCE"), latest.getBytes(StandardCharsets.UTF_8));
        deleteTree(textDir(app));
        if (!tmp.renameTo(textDir(app))) throw new IOException("rename " + tmp);
        Log.i(TAG, "oem text: text " + latest.substring(0, 7) + " downloaded");
    }

    /** The apps, texts and factory hashes of the text we kept. An entry with
     * no factory hash is never used. */
    static List<Known> saved(Context app) throws Exception {
        List<Known> out = new ArrayList<>();
        File dir = textDir(app);
        if (!new File(dir, "manifest.json").isFile()) return out;
        JSONObject m = new JSONObject(read(new File(dir, "manifest.json")));
        JSONArray list = m.getJSONArray("apps");
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            String pkg = a.optString("package"), file = a.optString("file");
            if (!OemTextPolicy.validPackage(pkg) || !OemTextPolicy.validFile(file)) continue;
            File f = new File(dir, file);
            if (!f.isFile()) continue;
            List<String> apkSha = new ArrayList<>();
            JSONArray hl = a.optJSONArray("apk_sha256");
            if (hl != null) for (int j = 0; j < hl.length(); j++) {
                String h = hl.optString(j);
                if (OemTextPolicy.validSha(h)) apkSha.add(h);
            }
            Map<String, Map<String, String>> tr = parse(read(f));
            if (!apkSha.isEmpty() && !tr.isEmpty()) out.add(new Known(pkg, tr, apkSha));
        }
        return out;
    }

    /** The repo commit of the text we kept, or "". */
    static String savedSource(Context app) {
        try { return read(new File(textDir(app), "SOURCE")).trim(); } catch (IOException e) { return ""; }
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(File f, byte[] b) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) { os.write(b); }
    }

    // https only (the URLs are fixed here), no redirects, size capped.
    private static byte[] fetch(String url, String accept, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            if (accept != null) c.setRequestProperty("Accept", accept);
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

    /** language -> name -> text. */
    static Map<String, Map<String, String>> parse(String json) throws org.json.JSONException {
        JSONObject o = new JSONObject(json);
        Map<String, Map<String, String>> tr = new LinkedHashMap<>();
        for (Iterator<String> li = o.keys(); li.hasNext(); ) {
            String lang = li.next();
            JSONObject t = o.optJSONObject(lang);
            if (!OemTextPolicy.validLang(lang) || t == null) continue;
            Map<String, String> names = new LinkedHashMap<>();
            for (Iterator<String> ni = t.keys(); ni.hasNext(); ) {
                String name = ni.next();
                String text = t.optString(name, "");
                if (!text.isEmpty()) names.put(name, text);
            }
            if (!names.isEmpty()) tr.put(lang, names);
        }
        return tr;
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
        List<App> left = new ArrayList<>();
        for (App a : apps(app)) if (!a.pkg.equals(pkg)) left.add(a);
        save(app, left);
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
        b.putInt("apps", apps(ctx).size());
        b.putString("crashed", String.join(",", crashed(ctx)));
        return b;
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
