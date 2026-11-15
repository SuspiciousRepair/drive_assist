package com.geely.drivemem.web;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.AppBackup;
import com.geely.drivemem.util.BackupCrypto;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.CsvExport;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.SpotifyClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** The page a paired phone opens: download stats, the newest backup and
 * dashcam clips, and type the long settings (tokens, URLs) with a real
 * keyboard. See {@link PhoneAccess} for when it runs and who may use it.
 *
 * The head unit is slow, so the car does little work for the page: files
 * are downloads (no video streaming), and an uploaded backup goes straight
 * to disk before the normal restore runs. At most {@link #MAX_CLIENTS} requests run at once, on background-priority
 * threads, so the screen and the dashcam always come first. */
public final class PhoneServer {
    private PhoneServer() {}

    static final String TAG = CarAccess.TAG;
    static final String COOKIE = "da";
    static final int MAX_CLIENTS = 2;
    static final int READ_TIMEOUT_MS = 15_000;

    private static ServerSocket socket;
    private static Thread acceptThread;
    private static final Semaphore slots = new Semaphore(MAX_CLIENTS);

    /** Starts or stops the server to match {@link PhoneAccess#shouldRun}.
     * Cheap; called by the watchdog, the app screen and the switches. */
    public static synchronized void ensure(Context ctx) {
        Context app = ctx.getApplicationContext();
        boolean want;
        try { want = PhoneAccess.shouldRun(app); }
        catch (Throwable t) { Log.w(TAG, "phone: " + t); want = false; }
        if (want && acceptThread == null) start(app);
        else if (!want && acceptThread != null) stop();
    }

    public static synchronized boolean running() { return acceptThread != null; }

    private static void start(Context app) {
        Thread t = new Thread(() -> acceptLoop(app), "phone-server");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
    }

    private static synchronized void stop() {
        Log.i(TAG, "phone: server stopping");
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        acceptThread = null;
    }

    private static void acceptLoop(Context app) {
        ServerSocket ss;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PhoneAccess.PORT));
        } catch (Throwable t) {
            Log.w(TAG, "phone: cannot listen on " + PhoneAccess.PORT + ": " + t);
            synchronized (PhoneServer.class) { acceptThread = null; }
            return;
        }
        synchronized (PhoneServer.class) {
            if (acceptThread != Thread.currentThread()) {   // stopped before we bound
                try { ss.close(); } catch (Throwable ignored) {}
                return;
            }
            socket = ss;
        }
        Log.i(TAG, "phone: server listening on " + PhoneAccess.PORT);
        while (!ss.isClosed()) {
            final Socket s;
            try { s = ss.accept(); }
            catch (Throwable t) { break; }
            if (!PhoneAccess.isPrivate(s.getInetAddress())) { close(s); continue; }
            if (!slots.tryAcquire()) {
                try { Http.send(s.getOutputStream(), 503, "text/plain", "busy".getBytes()); }
                catch (Throwable ignored) {}
                close(s);
                continue;
            }
            Thread w = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try { serve(app, s); }
                catch (Throwable t) { Log.w(TAG, "phone: " + t); }
                finally { close(s); slots.release(); }
            }, "phone-request");
            w.setDaemon(true);
            w.start();
        }
        Log.i(TAG, "phone: server stopped");
    }

    private static void close(Socket s) {
        try { s.close(); } catch (Throwable ignored) {}
    }

    private static void serve(Context app, Socket s) throws Exception {
        s.setSoTimeout(READ_TIMEOUT_MS);
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
        Http.Request r;
        try { r = Http.read(in); }
        catch (Http.BadRequest e) { Http.send(out, e.code, "text/plain", e.getMessage().getBytes()); return; }

        // The mode may have changed (left home Wi-Fi, QR screen closed for
        // too long) since the last watchdog tick: check it on every request.
        if (!PhoneAccess.shouldRun(app)) {
            Http.html(out, 403, PhonePages.message(app, PhonePages.Msg.CLOSED));
            new Thread(() -> ensure(app)).start();
            return;
        }
        if ("/favicon.ico".equals(r.path)) { Http.send(out, 404, "text/plain", new byte[0]); return; }

        String token = PhoneAccess.token(app);
        if ("/pair".equals(r.path)) {
            if (!PhoneAccess.tokenMatches(r.query.get("t"), token)) {
                Thread.sleep(300);   // a guess costs time
                Http.html(out, 403, PhonePages.message(app, PhonePages.Msg.SCAN_AGAIN));
                return;
            }
            // The token moves into a cookie, and out of the address bar.
            Http.redirect(out, "/", "Set-Cookie: " + COOKIE + "=" + token
                + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict");
            return;
        }
        if (!PhoneAccess.tokenMatches(r.cookie(COOKIE), token)) {
            Thread.sleep(300);
            Http.html(out, 403, PhonePages.message(app, PhonePages.Msg.SCAN_AGAIN));
            return;
        }
        if (!CarState.isParked()) {
            Http.html(out, 503, PhonePages.message(app, PhonePages.Msg.PARK));
            return;
        }

        if ("POST".equals(r.method)) {
            // A form from another site cannot carry our cookie (SameSite),
            // and must not carry our Origin either.
            String origin = r.headers.get("origin");
            String host = r.headers.get("host");
            if (origin != null && host != null && !origin.equals("http://" + host)) {
                Http.send(out, 403, "text/plain", "origin".getBytes());
                return;
            }
            if ("/settings".equals(r.path) && r.boundary == null) {
                saveSettings(app, r);
                Http.redirect(out, "/settings?saved=1");
            } else if ("/restore".equals(r.path) && r.boundary != null) {
                restoreUpload(app, r, in, out);
            } else {
                Http.send(out, 405, "text/plain", new byte[0]);
            }
            return;
        }
        if (!"GET".equals(r.method)) { Http.send(out, 405, "text/plain", new byte[0]); return; }

        switch (r.path) {
            case "/":
                Http.html(out, 200, PhonePages.index(app));
                return;
            case "/trips.csv":
            case "/charges.csv": {
                // One at a time: write() overwrites the same two files, so a
                // second download must not rewrite them under the first.
                synchronized (CsvExport.class) {
                    File[] csv = CsvExport.write(app);   // {charges, trips}
                    File f = "/trips.csv".equals(r.path) ? csv[1] : csv[0];
                    Http.file(out, f, "text/csv; charset=utf-8", f.getName());
                }
                return;
            }
            case "/backup": {
                List<AppBackup.Found> all = AppBackup.list();
                if (all.isEmpty()) { Http.html(out, 404, PhonePages.message(app, PhonePages.Msg.NO_BACKUP)); return; }
                File f = all.get(0).file;
                Http.file(out, f, "application/octet-stream", f.getName());
                return;
            }
            case "/clips":
                Http.html(out, 200, PhonePages.clips(app, Clips.list(app)));
                return;
            case "/clip": {
                Clips.Clip c = findClip(app, r.query.get("f"));
                if (c == null) { Http.send(out, 404, "text/plain", new byte[0]); return; }
                Http.file(out, c.mp4, "video/mp4", c.mp4.getName());
                return;
            }
            case "/settings":
                Http.html(out, 200, PhonePages.settings(app, "1".equals(r.query.get("saved"))));
                return;
            case "/restore":
                Http.html(out, 200, PhonePages.restore(app, restoring.get(), restoreError));
                return;
            default:
                Http.send(out, 404, "text/plain", new byte[0]);
        }
    }

    // ---- restore by upload -------------------------------------------------

    static final long MAX_UPLOAD = 1024L * 1024 * 1024;
    private static final AtomicBoolean restoring = new AtomicBoolean();
    /** Why the last restore from the phone failed, for the status page. A
     * restore that works restarts the app, so success is never stored. */
    private static volatile String restoreError;

    /** The phone sends a .dabackup and, when the backup was locked with
     * another password than the one set here, that password. The file goes
     * to the private cache in big blocks (never all in memory), then the
     * same restore as the car's own Restore button runs. On success it
     * restarts the app, and the car's screen says what came back. */
    private static void restoreUpload(Context app, Http.Request r, InputStream in, OutputStream out) throws Exception {
        if (r.contentLength > MAX_UPLOAD + 64 * 1024) {
            Http.html(out, 413, PhonePages.message(app, PhonePages.Msg.TOO_BIG));
            return;
        }
        if (app.getCacheDir().getUsableSpace() < r.contentLength + 64L * 1024 * 1024) {
            Http.html(out, 507, PhonePages.message(app, PhonePages.Msg.NO_SPACE));
            return;
        }
        if (!restoring.compareAndSet(false, true)) {
            Http.html(out, 409, PhonePages.message(app, PhonePages.Msg.BUSY));
            return;
        }
        final File dest = new File(app.getCacheDir(), "phone-restore" + AppBackup.EXT);
        boolean started = false;
        try {
            Map<String, String> f;
            try {
                f = Http.multipart(in, r.contentLength, r.boundary, "file", dest, MAX_UPLOAD);
            } catch (Http.BadRequest e) {
                Http.html(out, e.code, PhonePages.message(app,
                    e.code == 413 ? PhonePages.Msg.TOO_BIG : PhonePages.Msg.NO_FILE));
                return;
            }
            String size = f.get(Http.FILE_BYTES);
            if (size == null || "0".equals(size)) {
                Http.html(out, 400, PhonePages.message(app, PhonePages.Msg.NO_FILE));
                return;
            }
            String pw = f.get("password");
            final char[] pass = pw == null || pw.isEmpty() ? null : pw.toCharArray();
            restoreError = null;
            Log.i(TAG, "phone: restore upload " + size + " bytes");
            Thread t = new Thread(() -> {
                try {
                    AppBackup.restore(app, dest, pass);   // on success the app restarts here
                } catch (AppBackup.NeedPassword e) {
                    restoreError = app.getString(R.string.phone_restore_need_pw);
                } catch (BackupCrypto.WrongPassword e) {
                    restoreError = app.getString(R.string.backup_pw_wrong);
                } catch (Throwable e) {
                    Log.w(TAG, "phone: restore: " + e);
                    restoreError = app.getString(R.string.backup_restore_failed, String.valueOf(e.getMessage()));
                } finally {
                    dest.delete();
                    restoring.set(false);
                }
            }, "phone-restore");
            t.start();
            started = true;
            Http.redirect(out, "/restore");
        } finally {
            if (!started) { dest.delete(); restoring.set(false); }
        }
    }

    /** Only a finished clip from the dashcam's own list, matched by name, so
     * the address can never point anywhere else. */
    static Clips.Clip findClip(Context app, String name) {
        if (name == null || name.isEmpty()) return null;
        for (Clips.Clip c : Clips.list(app)) {
            if (c.kind == Clips.Kind.DONE && c.mp4.getName().equals(name)) return c;
        }
        return null;
    }

    /** An empty field keeps what is set. Clearing a setting is done on the car. */
    private static void saveSettings(Context app, Http.Request r) {
        boolean mqtt = false;
        for (PhonePages.Field f : PhonePages.FIELDS) {
            String v = r.form.get(f.key);
            if (v == null) continue;
            if (!f.secret) v = v.trim();
            if (v.isEmpty() || v.length() > 2048 || hasControl(v)) continue;
            switch (f.key) {
                case "mqtt_uri": Prefs.setMqttUri(app, v); Prefs.setMqttUriAlt(app, ""); mqtt = true; break;
                case "mqtt_user": Prefs.setMqttUser(app, v); mqtt = true; break;
                case "mqtt_pass": Prefs.setMqttPass(app, v); mqtt = true; break;
                case "abrp_user_token": Prefs.setAbrpUserToken(app, v); break;
                case "spotify_client_id": SpotifyClient.setClientId(app, v); break;
                case "update_url": Prefs.setUpdateUrl(app, v); break;
                default: break;
            }
            Log.i(TAG, "phone: saved " + f.key);
        }
        // TelemetryService reconnects when it sees the broker changed.
        if (mqtt && Prefs.getTeleEnabled(app)) {
            try { app.startForegroundService(new Intent(app, TelemetryService.class)); }
            catch (Throwable t) { Log.w(TAG, "phone: mqtt restart: " + t); }
        }
    }

    static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) return true;
        return false;
    }
}
