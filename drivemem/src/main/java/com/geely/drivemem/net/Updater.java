package com.geely.drivemem.net;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.geely.drivemem.BuildConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Auto-update: downloads the apk from a URL (HA's /local or custom HTTPS) and installs it via
// ModeHelper (INSTALL_PACKAGES) or root (`su 0 pm install -r`).
public class Updater {
    static final String TAG = "DriveMem";
    // Default public OTA update URL pointing to the latest release on GitHub
    public static final String DEFAULT_URL =
            "https://github.com/SuspiciousRepair/drive_assist/releases/latest/download/drive_assist.apk";
    public static final String ACTION_UPDATE_AVAILABLE = "com.geely.drivemem.UPDATE_AVAILABLE";

    public interface Progress { void step(String s); }

    public static class UpdateInfo {
        public final String versionName;
        public final int versionCode;
        public final String changelog;
        public final String apkUrl;

        public UpdateInfo(String versionName, int versionCode, String changelog, String apkUrl) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.changelog = changelog;
            this.apkUrl = apkUrl;
        }
    }

    public interface CheckCallback {
        void onUpdateAvailable(UpdateInfo info);
        void onAlreadyUpToDate(String currentVer);
        void onError(String error);
    }

    // The privileged helper, if it is installed. See its Installer class.
    public static final String HELPER_PKG = "com.geely.modehelper";
    static final String HELPER_INSTALL = "com.geely.modehelper.INSTALL_APK";

    /**
     * Checks if a software update is available without immediately installing it.
     * Fetches the release notes and compares version codes.
     */
    public static void check(final Context ctx, final String url, final CheckCallback cb) {
        new Thread(() -> {
            try {
                String u = (url == null) ? "" : url.trim();
                if (u.isEmpty() || u.equalsIgnoreCase("go")) {
                    android.content.SharedPreferences pfUrl =
                        ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
                    u = pfUrl.getString("update_url", DEFAULT_URL);
                    if (u.trim().isEmpty()) u = DEFAULT_URL;
                }
                if (!u.startsWith("https://")) {
                    if (cb != null) cb.onError("URL must start with https://");
                    return;
                }

                int curVc = BuildConfig.VERSION_CODE;
                String curVn = BuildConfig.VERSION_NAME;

                // 1. Check if the URL carries a version query parameter (?v=...)
                int remoteVc = -1;
                Matcher m = Pattern.compile("[?&]v=([0-9]+)").matcher(u);
                if (m.find()) {
                    try { remoteVc = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                }

                if (remoteVc > 0 && remoteVc <= curVc) {
                    if (cb != null) cb.onAlreadyUpToDate(curVn);
                    return;
                }

                // 2. Fetch changelog from the server, GitHub, or APK
                String changelog = fetchChangelog(ctx, u);

                // 3. Resolve remote version name
                String remoteVn = remoteVc > 0 ? "build " + remoteVc : "Update";
                if (changelog != null && !changelog.isEmpty()) {
                    Matcher vm = Pattern.compile("\\[v([0-9.]+)\\]").matcher(changelog);
                    if (vm.find()) remoteVn = "v" + vm.group(1);
                }

                if (remoteVc > 0 && remoteVc > curVc) {
                    if (cb != null) cb.onUpdateAvailable(new UpdateInfo(remoteVn, remoteVc, changelog, u));
                    return;
                }

                // 4. If remote version code wasn't specified in URL, check APK directly
                File checkApk = new File(ctx.getCacheDir(), "check-update.apk");
                try {
                    boolean downloaded = downloadFile(u, checkApk, 15000);
                    if (downloaded && checkApk.exists()) {
                        PackageManager pm = ctx.getPackageManager();
                        PackageInfo pi = pm.getPackageArchiveInfo(checkApk.getAbsolutePath(), 0);
                        if (pi != null) {
                            remoteVc = pi.versionCode;
                            if (pi.versionName != null) remoteVn = pi.versionName;

                            // Read bundled changelog from APK if available
                            String apkChangelog = readZipAsset(checkApk, "assets/changelog.txt");
                            if (apkChangelog != null && !apkChangelog.isEmpty()) {
                                changelog = apkChangelog;
                            }

                            if (remoteVc <= curVc) {
                                if (cb != null) cb.onAlreadyUpToDate(curVn);
                                return;
                            }
                        }
                    }
                } finally {
                    if (checkApk.exists()) checkApk.delete();
                }

                if (cb != null) {
                    cb.onUpdateAvailable(new UpdateInfo(remoteVn, remoteVc > 0 ? remoteVc : curVc + 1, changelog, u));
                }
            } catch (Throwable t) {
                Log.w(TAG, "check error: " + t);
                if (cb != null) cb.onError(t.getMessage() != null ? t.getMessage() : String.valueOf(t));
            }
        }, "update-checker").start();
    }

    private static String fetchChangelog(Context ctx, String updateUrl) {
        // Attempt 1: Fetch changelog.txt from the same directory as updateUrl on the update server
        if (updateUrl != null && updateUrl.startsWith("http")) {
            String clean = updateUrl.split("\\?")[0];
            int lastSlash = clean.lastIndexOf('/');
            if (lastSlash > 0) {
                String clUrl = clean.substring(0, lastSlash + 1) + "changelog.txt";
                String result = httpGetText(clUrl);
                if (result != null && !result.trim().isEmpty()) return result.trim();
            }
        }

        // Attempt 2: Read bundled assets/changelog.txt from the local APK
        try (InputStream in = ctx.getAssets().open("changelog.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString().trim();
        } catch (Throwable ignored) {}

        return "• Performance improvements and bug fixes.";
    }

    private static String httpGetText(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Drive Assist");
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    return sb.toString().trim();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readZipAsset(File zipFile, String entryPath) {
        try (ZipFile zf = new ZipFile(zipFile)) {
            ZipEntry ze = zf.getEntry(entryPath);
            if (ze != null) {
                try (InputStream in = zf.getInputStream(ze);
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    return sb.toString().trim();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean downloadFile(String urlStr, File out, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "Drive Assist");
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void update(final Context ctx, final String url, final Progress p) {
        new Thread(() -> {
            try {
                String u = (url == null) ? "" : url.trim();
                if (u.isEmpty() || u.equalsIgnoreCase("go")) {
                    android.content.SharedPreferences pfUrl =
                        ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
                    u = pfUrl.getString("update_url", DEFAULT_URL);
                    if (u.trim().isEmpty()) u = DEFAULT_URL;
                }
                if (!u.startsWith("https://")) { p.step("erro: URL precisa ser https"); return; }

                boolean force = url != null && url.toLowerCase(java.util.Locale.US).contains("force");
                if (!force && !com.geely.drivemem.state.CarState.isParked()) {
                    if (p != null) p.step("bloqueado: veículo em movimento");
                    Log.w(TAG, "Update blocked: vehicle is not parked");
                    return;
                }

                // CloudFlare caches /local with 31-day max-age. Append a
                // timestamp query to force a MISS at the edge, unless the URL
                // already carries a query (versioned URLs are meant to be stable).
                if (u.indexOf('?') < 0) u = u + "?t=" + System.currentTimeMillis();

                // Has this URL already been applied? Retained MQTT commands are
                // redelivered on every reconnect, so this check avoids repeated
                // downloads/installs of the same APK.
                android.content.SharedPreferences pf =
                    ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
                if (u.equals(pf.getString("update_last_url", ""))) {
                    p.step("já aplicado"); return;
                }

                // Delegate to the helper if installed: modehelper runs as uid
                // system with INSTALL_PACKAGES and can install silently. Hand
                // over the URL, not the file, because modehelper cannot read
                // Drive Assist's 0700-owned cache directory. Record the URL BEFORE
                // delegating to prevent an install loop: a successful install
                // replaces this process mid-call, so the recorded URL is the
                // only way to detect if this URL was already applied.
                if (helperPresent(ctx)) {
                    // Recorded BEFORE handing over, not after: a successful
                    // install kills this process mid-call.
                    pf.edit().putString("update_last_url", u).apply();
                    p.step("entregue ao helper de sistema");
                    Intent i = new Intent(HELPER_INSTALL).setPackage(HELPER_PKG);
                    i.putExtra("url", u);
                    ctx.sendBroadcast(i);
                    return;
                }
                p.step("sem helper — tentando root");

                p.step("baixando");
                File out = new File(ctx.getCacheDir(), "update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Drive Assist");
                int code = c.getResponseCode();
                if (code != 200) { p.step("erro: HTTP " + code); return; }
                long total = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); total += n; }
                }
                // validate the PK "magic" (zip/apk) before trying to install
                byte[] head = new byte[2];
                try (FileInputStream fi = new FileInputStream(out)) { if (fi.read(head) < 2) head[0] = 0; }
                if (head[0] != 'P' || head[1] != 'K') { p.step("erro: arquivo nao e apk"); return; }

                // Compare the downloaded APK's hash with the installed one to
                // verify they are identical. This catches relabeled APKs and
                // CloudFlare cache issues more reliably than version comparison.
                String mine = sha256(new File(ctx.getPackageCodePath()));
                String got  = sha256(out);
                if (mine != null && mine.equals(got)) {
                    pf.edit().putString("update_last_url", u).apply();
                    p.step("já instalado (" + got.substring(0, 8) + ")");
                    return;
                }
                Log.i(TAG, "update: installed=" + mine + " downloaded=" + got);

                p.step("instalando (" + (total / 1024) + " KB)");
                Process pr = Runtime.getRuntime().exec(new String[]{"su", "0", "pm", "install", "-r", out.getAbsolutePath()});
                String res = readAll(pr.getInputStream()) + " " + readAll(pr.getErrorStream());
                pr.waitFor();
                Log.i(TAG, "update pm install: " + res);
                boolean ok = res.toLowerCase().contains("success");
                if (ok) pf.edit().putString("update_last_url", u).apply();
                p.step(ok ? "OK — atualizado" : "erro: " + res.trim());
            } catch (Throwable t) { Log.w(TAG, "update error: " + t); p.step("erro: " + t); }
        }, "updater").start();
    }

    // SHA-256 of a file, in hex; null if it cannot be read
    public static String sha256(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { Log.w(TAG, "sha256: " + t); return null; }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] u = new byte[512]; int n;
        while ((n = in.read(u)) > 0) b.write(u, 0, n);
        return b.toString("UTF-8").trim();
    }

    // Is the privileged helper installed? Its absence is a normal state, not an
    // error: a unit without it simply falls back to the root path, which is what
    // shipped before.
    private static boolean helperPresent(Context ctx) {
        try { ctx.getPackageManager().getPackageInfo(HELPER_PKG, 0); return true; }
        catch (Throwable t) { return false; }
    }
}
