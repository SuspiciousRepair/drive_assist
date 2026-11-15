package com.geely.drivemem.web;

import android.content.Context;

import com.geely.drivemem.controls.AdbGate;
import com.geely.drivemem.util.Prefs;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;

/** Phone access: who may use the page on the car, and when it runs.
 *
 * When it runs, like the ADB switch:
 *  - OFF  (default): only while the QR code is on the car's screen.
 *  - AUTO: also while the car is on the home Wi-Fi (the same "Home Wi-Fi"
 *          the ADB block uses).
 *  - ON:   whenever the car has a network.
 * In every mode it answers only in Park and only to private addresses.
 *
 * Who may use it: a phone that scanned the QR code. The QR code holds a
 * random token; the page checks it on every request (as a cookie, after the
 * first one). "Forget paired phones" makes a new token, so every phone has
 * to scan again. The token is never in a backup. */
public final class PhoneAccess {
    private PhoneAccess() {}

    public static final String OFF = "off", AUTO = "auto", ON = "on";
    public static final int PORT = 8765;

    /** The QR screen keeps the server up for at most this long, even if the
     * screen is left open. */
    static final long HOLD_MAX_MS = 30 * 60 * 1000L;
    private static volatile long holdUntilMs;

    // ---- mode ------------------------------------------------------------

    public static String mode(Context ctx) {
        String m = Prefs.getPhoneAccessMode(ctx);
        return AUTO.equals(m) || ON.equals(m) ? m : OFF;
    }

    public static void setMode(Context ctx, String m) {
        Prefs.setPhoneAccessMode(ctx, AUTO.equals(m) || ON.equals(m) ? m : OFF);
        PhoneServer.ensure(ctx);
    }

    /** The QR screen is open (true) or closed (false). */
    public static void hold(Context ctx, boolean on) {
        holdUntilMs = on ? System.currentTimeMillis() + HOLD_MAX_MS : 0;
        PhoneServer.ensure(ctx);
    }

    static boolean holding(long nowMs) { return nowMs < holdUntilMs; }

    /** The whole rule, without Android, for the tests. */
    static boolean shouldRun(String mode, boolean holding, boolean onHomeWifi) {
        if (holding) return true;
        if (ON.equals(mode)) return true;
        return AUTO.equals(mode) && onHomeWifi;
    }

    static boolean shouldRun(Context ctx) {
        String m = mode(ctx);
        boolean hold = holding(System.currentTimeMillis());
        // Reading the SSID costs a binder call: skip it when it cannot matter.
        boolean home = !hold && AUTO.equals(m) && onHomeWifi(ctx);
        return shouldRun(m, hold, home);
    }

    static boolean onHomeWifi(Context ctx) {
        String want = AdbGate.getTrustedWifi(ctx);
        String now = AdbGate.getCurrentSsid(ctx);
        return want != null && !want.trim().isEmpty() && want.trim().equals(now);
    }

    // ---- token -----------------------------------------------------------

    /** The token the QR code carries. Made on first use. */
    public static synchronized String token(Context ctx) {
        String t = Prefs.getPhoneAccessToken(ctx);
        if (t == null || t.length() < 32) {
            t = newToken();
            Prefs.setPhoneAccessToken(ctx, t);
        }
        return t;
    }

    /** "Forget paired phones": every phone has to scan the new QR code. */
    public static synchronized void forgetPhones(Context ctx) {
        Prefs.setPhoneAccessToken(ctx, newToken());
    }

    static String newToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Same time whatever the input, so the answer time gives nothing away. */
    static boolean tokenMatches(String given, String want) {
        if (given == null || want == null || want.isEmpty()) return false;
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
            want.getBytes(StandardCharsets.UTF_8));
    }

    // ---- address ---------------------------------------------------------

    /** The address the phone uses, for the QR code: the car's IPv4 address
     * on Wi-Fi (or on its own hotspot). null with no network. */
    public static String localAddress() {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    int rank = rank(ni.getName());
                    if (rank < bestRank) { bestRank = rank; best = a.getHostAddress(); }
                }
            }
        } catch (Throwable ignored) {}
        return best;
    }

    private static int rank(String name) {
        if (name == null) return 9;
        if (name.startsWith("wlan")) return 0;
        if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")) return 1;
        if (name.startsWith("eth")) return 2;
        return 5;
    }

    public static String url(String host, String token) {
        return "http://" + host + ":" + PORT + "/pair?t=" + token;
    }

    /** Only the car's own network neighbours: private, link-local or
     * loopback. Never the mobile network. */
    static boolean isPrivate(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            return (b[0] & 0xfe) == 0xfc;   // fc00::/7, unique local
        }
        return false;
    }
}
