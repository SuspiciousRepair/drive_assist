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
        // Null for both = this app's own update, exactly as before (dialog
        // falls back to the plain title and BuildConfig.VERSION_NAME). Set
        // both when this UpdateInfo describes a DIFFERENT installed package
        // (e.g. modehelper) — targetLabel is what the dialog titles itself
        // with, currentVersionName is that package's own installed version,
        // not this app's.
        public final String targetLabel;
        public final String currentVersionName;

        public UpdateInfo(String versionName, int versionCode, String changelog, String apkUrl) {
            this(versionName, versionCode, changelog, apkUrl, null, null);
        }

        public UpdateInfo(String versionName, int versionCode, String changelog, String apkUrl,
                           String targetLabel, String currentVersionName) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.changelog = changelog;
            this.apkUrl = apkUrl;
            this.targetLabel = targetLabel;
            this.currentVersionName = currentVersionName;
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

    // The lowest ModeHelper versionCode whose own Installer.java accepts an
    // update FOR ITSELF -- earlier builds only ever recognized drivemem as a
    // valid update target and silently reject anything else (safe, but
    // silent: no crash, no feedback, the confirm dialog just does nothing).
    // Below this floor, check() refuses to offer a helper update at all,
    // rather than dangling a prompt that fails after the user confirms it.
    // See v0.2.0's changelog upgrade note -- a one-time `adb install`/
    // installer re-run past this floor is what unblocks it for good.
    public static final int HELPER_MIN_SELF_UPDATE_VC = 29825020;

    /**
     * Checks if a software update is available without immediately installing it.
     * Fetches the release notes and compares version codes.
     */
    public static void check(final Context ctx, final String url, final CheckCallback cb) {
        check(ctx, url, null, cb);
    }

    /**
     * Same as {@link #check(Context, String, CheckCallback)}, but compares
     * against a different installed package's version instead of this app's
     * own — e.g. "com.geely.modehelper", to check for a helper update. Pass
     * null (or this app's own package) for the original self-update behavior.
     */
    /** The URL check()/update() actually resolve "" / "go" / null to — the
     * user's configured drivemem update URL, or DEFAULT_URL. Exposed so a
     * caller can derive a related URL (e.g. swap the filename to check for
     * a modehelper update at the same server) without duplicating this
     * resolution logic. */
    public static String resolveUrl(Context ctx, String url) {
        String u = (url == null) ? "" : url.trim();
        if (u.isEmpty() || u.equalsIgnoreCase("go")) {
            android.content.SharedPreferences pfUrl =
                ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
            u = pfUrl.getString("update_url", DEFAULT_URL);
            if (u.trim().isEmpty()) u = DEFAULT_URL;
        }
        return u;
    }

    public static void check(final Context ctx, final String url, final String targetPkg, final CheckCallback cb) {
        new Thread(() -> {
            try {
                String u = resolveUrl(ctx, url);
                if (!u.startsWith("https://")) {
                    if (cb != null) cb.onError("URL must start with https://");
                    return;
                }

                int curVc;
                String curVn;
                // Null for a self-update, exactly as before this method took a
                // targetPkg at all -- UpdateInfo below only tags itself with a
                // target label when this check is genuinely for a different
                // installed package.
                String targetLabel = null;
                if (targetPkg == null || targetPkg.equals(ctx.getPackageName())) {
                    curVc = BuildConfig.VERSION_CODE;
                    curVn = BuildConfig.VERSION_NAME;
                } else {
                    targetLabel = HELPER_PKG.equals(targetPkg) ? "ModeHelper" : targetPkg;
                    // A public PackageInfo query — no special permission needed to
                    // read another installed app's own versionCode/versionName.
                    PackageInfo self;
                    try {
                        self = ctx.getPackageManager().getPackageInfo(targetPkg, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        if (cb != null) cb.onError(targetPkg + " is not installed");
                        return;
                    }
                    curVc = self.versionCode;
                    curVn = self.versionName;
                    if (HELPER_PKG.equals(targetPkg) && curVc < HELPER_MIN_SELF_UPDATE_VC) {
                        if (cb != null) cb.onError("ModeHelper " + curVn
                            + " predates self-update support -- reinstall drive_assist_installer.apk once");
                        return;
                    }
                }

                // A version the user explicitly dismissed with "Skip this
                // version" (UpdateDialog) acts as a floor on top of curVc for
                // "is this newer" purposes only -- every comparison below uses
                // effectiveVc, not curVc, so the dialog itself (built from
                // curVc/curVn, untouched) still shows what's REALLY installed,
                // not what was last skipped. A newer release than the skipped
                // one still prompts normally.
                String skipKey = (targetLabel != null) ? "skip_update_vc_modehelper" : "skip_update_vc";
                int skippedVc = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE).getInt(skipKey, 0);
                int effectiveVc = Math.max(curVc, skippedVc);

                // 1. Check if the URL carries a version query parameter (?v=...)
                int remoteVc = -1;
                Matcher m = Pattern.compile("[?&]v=([0-9]+)").matcher(u);
                if (m.find()) {
                    try { remoteVc = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                }

                if (remoteVc > 0 && remoteVc <= effectiveVc) {
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

                if (remoteVc > 0 && remoteVc > effectiveVc) {
                    if (cb != null) cb.onUpdateAvailable(new UpdateInfo(remoteVn, remoteVc, changelog, u, targetLabel, curVn));
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

                            if (remoteVc <= effectiveVc) {
                                if (cb != null) cb.onAlreadyUpToDate(curVn);
                                return;
                            }
                        }
                    }
                } finally {
                    if (checkApk.exists()) checkApk.delete();
                }

                if (cb != null) {
                    cb.onUpdateAvailable(new UpdateInfo(remoteVn, remoteVc > 0 ? remoteVc : effectiveVc + 1, changelog, u, targetLabel, curVn));
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

    private static final String AUTO_CHECK_PREF = "auto_update_last_check_ms";
    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 3600 * 1000;

    /** Unattended update check against whatever URL is configured -- for
     * most installs, nobody: that's what makes it resolve to DEFAULT_URL,
     * the GitHub Releases download, same as a manual "Check Update" tap.
     * Without this, an install with no private MQTT/HA of its own only
     * ever finds out about a new release if someone remembers to press
     * that button. Throttled to once a day (SharedPrefs timestamp, so it
     * survives a process restart) and called from ComfortActivity.onResume
     * -- opening the app is already the moment BootReceiver re-arms the
     * watchdog, same "cheap, idempotent, do it whenever the screen opens"
     * shape. Silent on already-up-to-date or error: the only visible
     * effect, if any, is the same Park-gated UpdateDialog a manual check
     * or MQTT command already produces. */
    public static void autoCheckIfDue(final Context ctx) {
        android.content.SharedPreferences pf = ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        long last = pf.getLong(AUTO_CHECK_PREF, 0);
        long now = System.currentTimeMillis();
        if (now - last < AUTO_CHECK_INTERVAL_MS) return;
        pf.edit().putLong(AUTO_CHECK_PREF, now).apply();

        check(ctx, null, new CheckCallback() {
            @Override public void onUpdateAvailable(UpdateInfo info) { broadcastAvailable(ctx, info); }
            @Override public void onAlreadyUpToDate(String currentVer) {}
            @Override public void onError(String error) {}
        });

        // Same filename-swap TelemetryActivity.updateCheckHelper() already
        // uses for the manual button -- one more automatic consumer of the
        // same derivation, not a second one to keep in sync.
        String base = resolveUrl(ctx, null);
        int slash = base.lastIndexOf('/');
        if (slash < 0) return;
        String helperUrl = base.substring(0, slash + 1) + "modehelper.apk";
        check(ctx, helperUrl, HELPER_PKG, new CheckCallback() {
            @Override public void onUpdateAvailable(UpdateInfo info) { broadcastAvailable(ctx, info); }
            @Override public void onAlreadyUpToDate(String currentVer) {}
            @Override public void onError(String error) {}
        });
    }

    private static void broadcastAvailable(Context ctx, UpdateInfo info) {
        Intent it = new Intent(ACTION_UPDATE_AVAILABLE);
        it.putExtra("versionName", info.versionName);
        it.putExtra("versionCode", info.versionCode);
        it.putExtra("changelog", info.changelog);
        it.putExtra("url", info.apkUrl);
        if (info.targetLabel != null) it.putExtra("targetLabel", info.targetLabel);
        if (info.currentVersionName != null) it.putExtra("currentVersionName", info.currentVersionName);
        ctx.sendBroadcast(it);
    }

    /** Same delivery mechanism as {@link #update}, but for ModeHelper: swaps
     * the filename to drive_assist_installer.apk before handing the URL
     * over, so modehelper installs the standalone installer instead of
     * modehelper.apk directly. The installer bundles fresh copies of BOTH
     * drivemem and modehelper and refreshes them together -- simpler and
     * more robust than modehelper "self-updating", and the only way an
     * update ever reaches modehelper at all: nothing delivered THROUGH
     * modehelper can fix modehelper's own rule about what it accepts, so
     * this only works once modehelper is already new enough to accept the
     * installer package in the first place (see HELPER_MIN_SELF_UPDATE_VC,
     * already checked by check() before this is ever reachable). Callers
     * that already know they're updating the helper should call this
     * instead of update() directly -- one place doing the URL swap, not
     * one per call site. */
    public static void updateHelper(final Context ctx, final String helperApkUrl, final Progress p) {
        String u = helperApkUrl;
        int slash = (u == null) ? -1 : u.lastIndexOf('/');
        if (slash >= 0) {
            String rest = u.substring(slash + 1);
            int q = rest.indexOf('?');
            String query = q >= 0 ? rest.substring(q) : "";
            u = u.substring(0, slash + 1) + "drive_assist_installer.apk" + query;
        }
        update(ctx, u, p);
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
