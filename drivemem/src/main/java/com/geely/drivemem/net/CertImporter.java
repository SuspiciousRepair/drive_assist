package com.geely.drivemem.net;

import com.geely.drivemem.car.CarAccess;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Handles inspecting, importing, and validating certificates for MQTT TLS/mTLS.
 *
 * Supports PKCS#12 (.p12, .pfx) client bundles, PEM certificates (.crt, .cer, .pem),
 * USB drive scanning, URL downloads, and live TLS handshake testing.
 */
public final class CertImporter {
    private static final String TAG = CarAccess.TAG;

    public static class CertInfo {
        public final String alias;
        public final String subject;
        public final String issuer;
        public final Date notBefore;
        public final Date notAfter;
        public final boolean isExpired;
        public final boolean hasPrivateKey;
        public final int certCount;

        public CertInfo(String alias, String subject, String issuer, Date notBefore, Date notAfter,
                        boolean hasPrivateKey, int certCount) {
            this.alias = alias;
            this.subject = subject;
            this.issuer = issuer;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
            Date now = new Date();
            this.isExpired = (notAfter != null && now.after(notAfter)) ||
                             (notBefore != null && now.before(notBefore));
            this.hasPrivateKey = hasPrivateKey;
            this.certCount = certCount;
        }

        public String getFormattedExpiry() {
            if (notAfter == null) return "—";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return sdf.format(notAfter);
        }

        public String getCommonName() {
            return extractCN(subject);
        }
    }

    public static class ImportResult {
        public final boolean success;
        public final boolean passwordRequired;
        public final String message;
        public final CertInfo certInfo;

        public ImportResult(boolean success, boolean passwordRequired, String message, CertInfo certInfo) {
            this.success = success;
            this.passwordRequired = passwordRequired;
            this.message = message;
            this.certInfo = certInfo;
        }
    }

    public interface FetchCallback {
        void onDone(boolean ok, byte[] data, String error);
    }

    public interface TlsCallback {
        void onResult(boolean ok, String message);
    }

    private CertImporter() {}

    private static void logW(String msg) {
        try { Log.w(TAG, msg); } catch (Throwable t) { System.err.println("[DriveMem] " + msg); }
    }

    private static void logW(String msg, Throwable cause) {
        try { Log.w(TAG, msg, cause); } catch (Throwable t) { System.err.println("[DriveMem] " + msg + ": " + cause); }
    }

    public static byte[] decodeBase64(String str) {
        if (str == null) return null;
        String clean = str.replaceAll("\\s+", "");
        try {
            return java.util.Base64.getDecoder().decode(clean);
        } catch (Throwable t) {
            try {
                return android.util.Base64.decode(clean, android.util.Base64.DEFAULT);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static String encodeBase64(byte[] bytes) {
        if (bytes == null) return "";
        try {
            return java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes);
        } catch (Throwable t) {
            try {
                return android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT);
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /** Returns information on the currently installed client certificate, or null if none. */
    public static CertInfo getClientCertInfo(Context ctx) {
        return getClientCertInfo(ctx != null ? ctx.getFilesDir() : null);
    }

    public static CertInfo getClientCertInfo(File filesDir) {
        if (filesDir == null) return null;
        File f = new File(filesDir, MqttTls.CLIENT_P12);
        if (!f.isFile() || f.length() == 0) return null;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            FileInputStream fis = new FileInputStream(f);
            try {
                ks.load(fis, new char[0]);
            } finally {
                fis.close();
            }
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                if (ks.isKeyEntry(a)) {
                    Certificate c = ks.getCertificate(a);
                    if (c instanceof X509Certificate) {
                        X509Certificate x = (X509Certificate) c;
                        Certificate[] chain = ks.getCertificateChain(a);
                        int count = chain != null ? chain.length : 1;
                        return new CertInfo(a, x.getSubjectX500Principal().getName(),
                                x.getIssuerX500Principal().getName(),
                                x.getNotBefore(), x.getNotAfter(), true, count);
                    }
                }
            }
        } catch (Throwable t) {
            logW("cert: failed to inspect client cert: " + t);
        }
        return null;
    }

    /** Returns information on the active CA certificate (custom if present, otherwise bundled). */
    public static CertInfo getCaCertInfo(Context ctx) {
        if (ctx == null) return null;
        CertInfo custom = getCaCertInfo(ctx.getFilesDir());
        if (custom != null) return custom;

        // Bundled APK CA
        try {
            int caId = ctx.getResources().getIdentifier("mqtt_ca", "raw", ctx.getPackageName());
            if (caId != 0) {
                InputStream is = ctx.getResources().openRawResource(caId);
                try {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    Certificate c = cf.generateCertificate(is);
                    if (c instanceof X509Certificate) {
                        X509Certificate x = (X509Certificate) c;
                        return new CertInfo("bundled_ca", x.getSubjectX500Principal().getName(),
                                x.getIssuerX500Principal().getName(),
                                x.getNotBefore(), x.getNotAfter(), false, 1);
                    }
                } finally {
                    is.close();
                }
            }
        } catch (Throwable t) {
            logW("cert: failed to inspect bundled CA cert: " + t);
        }
        return null;
    }

    public static CertInfo getCaCertInfo(File filesDir) {
        if (filesDir == null) return null;
        File customCa = new File(filesDir, MqttTls.CUSTOM_CA_CRT);
        if (customCa.isFile() && customCa.length() > 0) {
            try {
                FileInputStream fis = new FileInputStream(customCa);
                try {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    Collection<? extends Certificate> certs = cf.generateCertificates(fis);
                    for (Certificate c : certs) {
                        if (c instanceof X509Certificate) {
                            X509Certificate x = (X509Certificate) c;
                            return new CertInfo("custom_ca", x.getSubjectX500Principal().getName(),
                                    x.getIssuerX500Principal().getName(),
                                    x.getNotBefore(), x.getNotAfter(), false, certs.size());
                        }
                    }
                } finally {
                    fis.close();
                }
            } catch (Throwable t) {
                logW("cert: failed to inspect custom CA cert: " + t);
            }
        }
        return null;
    }

    public static boolean hasCustomCa(Context ctx) {
        return hasCustomCa(ctx != null ? ctx.getFilesDir() : null);
    }

    public static boolean hasCustomCa(File filesDir) {
        if (filesDir == null) return false;
        File f = new File(filesDir, MqttTls.CUSTOM_CA_CRT);
        return f.isFile() && f.length() > 0;
    }

    public static boolean removeClientCert(Context ctx) {
        return removeClientCert(ctx != null ? ctx.getFilesDir() : null);
    }

    public static boolean removeClientCert(File filesDir) {
        if (filesDir == null) return false;
        File f = new File(filesDir, MqttTls.CLIENT_P12);
        return f.delete();
    }

    public static boolean removeCustomCaCert(Context ctx) {
        return removeCustomCaCert(ctx != null ? ctx.getFilesDir() : null);
    }

    public static boolean removeCustomCaCert(File filesDir) {
        if (filesDir == null) return false;
        File f = new File(filesDir, MqttTls.CUSTOM_CA_CRT);
        return f.delete();
    }

    /**
     * Imports certificate data (raw bytes from file, download, or paste).
     *
     * Automatically detects format (PKCS#12 bundle vs PEM certs/key) and determines
     * whether it should be installed as client certificate (mTLS) or custom CA certificate.
     */
    public static ImportResult importData(Context ctx, byte[] rawData, String password) {
        if (ctx == null) return new ImportResult(false, false, "Invalid context", null);
        return importData(ctx.getFilesDir(), rawData, password);
    }

    public static ImportResult importData(File filesDir, byte[] rawData, String password) {
        if (filesDir == null) return new ImportResult(false, false, "Invalid storage directory", null);
        if (rawData == null || rawData.length == 0) {
            return new ImportResult(false, false, "No certificate data provided", null);
        }

        String asText = null;
        try {
            asText = new String(rawData, "UTF-8");
        } catch (Throwable ignored) {}

        // --- 1. Check for PEM format ---
        if (asText != null && asText.contains("-----BEGIN")) {
            return importPem(filesDir, rawData, asText);
        }

        // --- 2. Check for Base64 encoded binary PKCS#12 ---
        byte[] p12Bytes = rawData;
        if (asText != null && !asText.contains("-----BEGIN") && asText.trim().startsWith("MII")) {
            byte[] decoded = decodeBase64(asText.trim());
            if (decoded != null && decoded.length > 32) {
                p12Bytes = decoded;
            }
        }

        // --- 3. Treat as PKCS#12 ---
        return importPkcs12(filesDir, p12Bytes, password);
    }

    private static ImportResult importPem(File filesDir, byte[] rawData, String pemText) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(rawData));
            if (certs == null || certs.isEmpty()) {
                return new ImportResult(false, false, "No valid X.509 certificates found in PEM", null);
            }

            List<Certificate> certList = new ArrayList<>(certs);
            X509Certificate firstX509 = null;
            for (Certificate c : certList) {
                if (c instanceof X509Certificate) {
                    firstX509 = (X509Certificate) c;
                    break;
                }
            }
            if (firstX509 == null) {
                return new ImportResult(false, false, "No valid X509Certificate in chain", null);
            }

            // Check if PEM contains a private key
            PrivateKey privKey = extractPrivateKeyFromPem(pemText);
            if (privKey != null) {
                // Client certificate + Private Key!
                KeyStore targetKs = KeyStore.getInstance("PKCS12");
                targetKs.load(null, null);
                targetKs.setKeyEntry("client", privKey, new char[0], certList.toArray(new Certificate[0]));

                File out = new File(filesDir, MqttTls.CLIENT_P12);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    targetKs.store(fos, new char[0]);
                } finally {
                    fos.close();
                }
                out.setReadable(true, true);
                out.setWritable(true, true);

                CertInfo info = new CertInfo("client", firstX509.getSubjectX500Principal().getName(),
                        firstX509.getIssuerX500Principal().getName(),
                        firstX509.getNotBefore(), firstX509.getNotAfter(), true, certList.size());
                return new ImportResult(true, false, "Client certificate installed: " + info.getCommonName(), info);
            } else {
                // CA certificate (trust root only, no private key)
                File out = new File(filesDir, MqttTls.CUSTOM_CA_CRT);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    fos.write(rawData);
                } finally {
                    fos.close();
                }
                out.setReadable(true, true);
                out.setWritable(true, true);

                CertInfo info = new CertInfo("custom_ca", firstX509.getSubjectX500Principal().getName(),
                        firstX509.getIssuerX500Principal().getName(),
                        firstX509.getNotBefore(), firstX509.getNotAfter(), false, certList.size());
                return new ImportResult(true, false, "CA certificate installed: " + info.getCommonName(), info);
            }
        } catch (Throwable t) {
            logW("cert: error parsing PEM: " + t, t);
            return new ImportResult(false, false, "PEM parsing failed: " + t.getMessage(), null);
        }
    }

    private static ImportResult importPkcs12(File filesDir, byte[] p12Bytes, String password) {
        char[] passChars = (password != null) ? password.toCharArray() : new char[0];
        KeyStore ks;
        try {
            ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(p12Bytes), passChars);
        } catch (Exception e) {
            // Check if password required or incorrect
            if (password == null || password.isEmpty()) {
                return new ImportResult(false, true, "Certificate is password-protected", null);
            } else {
                return new ImportResult(false, false, "Incorrect password or invalid PKCS#12 file", null);
            }
        }

        try {
            String keyAlias = null;
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                if (ks.isKeyEntry(a)) {
                    keyAlias = a;
                    break;
                }
            }

            if (keyAlias != null) {
                // Client bundle with key: re-store with empty password for unattended app startup
                Key key = ks.getKey(keyAlias, passChars);
                Certificate[] chain = ks.getCertificateChain(keyAlias);
                if (chain == null || chain.length == 0) {
                    Certificate c = ks.getCertificate(keyAlias);
                    if (c != null) chain = new Certificate[]{c};
                }

                KeyStore targetKs = KeyStore.getInstance("PKCS12");
                targetKs.load(null, null);
                targetKs.setKeyEntry("client", key, new char[0], chain);

                File out = new File(filesDir, MqttTls.CLIENT_P12);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    targetKs.store(fos, new char[0]);
                } finally {
                    fos.close();
                }
                out.setReadable(true, true);
                out.setWritable(true, true);

                X509Certificate x = (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate)
                        ? (X509Certificate) chain[0] : null;
                CertInfo info = (x != null) ? new CertInfo("client", x.getSubjectX500Principal().getName(),
                        x.getIssuerX500Principal().getName(), x.getNotBefore(), x.getNotAfter(), true, chain.length)
                        : new CertInfo("client", "Client Cert", "Unknown", null, null, true, 1);
                return new ImportResult(true, false, "Client certificate installed: " + info.getCommonName(), info);
            } else {
                // Keystore contains certificates only (CA bundle)
                List<Certificate> certList = new ArrayList<>();
                Enumeration<String> allAliases = ks.aliases();
                while (allAliases.hasMoreElements()) {
                    Certificate c = ks.getCertificate(allAliases.nextElement());
                    if (c != null) certList.add(c);
                }

                if (certList.isEmpty()) {
                    return new ImportResult(false, false, "PKCS#12 keystore contains no keys or certificates", null);
                }

                // Write certificates in PEM format to custom CA
                File out = new File(filesDir, MqttTls.CUSTOM_CA_CRT);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    for (Certificate c : certList) {
                        fos.write("-----BEGIN CERTIFICATE-----\n".getBytes("UTF-8"));
                        fos.write(encodeBase64(c.getEncoded()).getBytes("UTF-8"));
                        fos.write("\n-----END CERTIFICATE-----\n".getBytes("UTF-8"));
                    }
                } finally {
                    fos.close();
                }
                out.setReadable(true, true);
                out.setWritable(true, true);

                X509Certificate x = (certList.get(0) instanceof X509Certificate)
                        ? (X509Certificate) certList.get(0) : null;
                CertInfo info = (x != null) ? new CertInfo("custom_ca", x.getSubjectX500Principal().getName(),
                        x.getIssuerX500Principal().getName(), x.getNotBefore(), x.getNotAfter(), false, certList.size())
                        : new CertInfo("custom_ca", "CA Cert", "Unknown", null, null, false, certList.size());
                return new ImportResult(true, false, "CA certificate installed: " + info.getCommonName(), info);
            }
        } catch (Throwable t) {
            logW("cert: error saving PKCS#12: " + t, t);
            return new ImportResult(false, false, "Failed to save certificate: " + t.getMessage(), null);
        }
    }

    public static PrivateKey extractPrivateKeyFromPem(String pem) {
        try {
            // Check standard PKCS#8: -----BEGIN PRIVATE KEY-----
            int start = pem.indexOf("-----BEGIN PRIVATE KEY-----");
            int end = pem.indexOf("-----END PRIVATE KEY-----");
            if (start != -1 && end != -1) {
                String b64 = pem.substring(start + "-----BEGIN PRIVATE KEY-----".length(), end);
                byte[] decoded = decodeBase64(b64);
                if (decoded != null) {
                    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
                    for (String alg : new String[]{"RSA", "EC", "DSA"}) {
                        try {
                            return KeyFactory.getInstance(alg).generatePrivate(spec);
                        } catch (Throwable ignored) {}
                    }
                }
            }

            // Check RSA PKCS#1: -----BEGIN RSA PRIVATE KEY-----
            start = pem.indexOf("-----BEGIN RSA PRIVATE KEY-----");
            end = pem.indexOf("-----END RSA PRIVATE KEY-----");
            if (start != -1 && end != -1) {
                String b64 = pem.substring(start + "-----BEGIN RSA PRIVATE KEY-----".length(), end);
                byte[] pkcs1Bytes = decodeBase64(b64);
                if (pkcs1Bytes != null) {
                    byte[] pkcs8Bytes = wrapPkcs1ToPkcs8(pkcs1Bytes);
                    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8Bytes);
                    return KeyFactory.getInstance("RSA").generatePrivate(spec);
                }
            }
        } catch (Throwable t) {
            logW("cert: extractPrivateKeyFromPem failed: " + t);
        }
        return null;
    }

    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1) throws Exception {
        byte[] rsaId = new byte[] {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        ByteArrayOutputStream octet = new ByteArrayOutputStream();
        writeAsn1Length(octet, 0x04, pkcs1.length);
        octet.write(pkcs1, 0, pkcs1.length);
        byte[] octetBytes = octet.toByteArray();

        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.write(0x02); seq.write(0x01); seq.write(0x00); // version 0
        seq.write(rsaId, 0, rsaId.length);
        seq.write(octetBytes, 0, octetBytes.length);
        byte[] seqBytes = seq.toByteArray();

        ByteArrayOutputStream top = new ByteArrayOutputStream();
        writeAsn1Length(top, 0x30, seqBytes.length);
        top.write(seqBytes, 0, seqBytes.length);
        return top.toByteArray();
    }

    private static void writeAsn1Length(ByteArrayOutputStream out, int tag, int length) {
        out.write(tag);
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
    }

    /** Scans plugged-in USB storage volumes for certificate files (.p12, .pfx, .crt, .cer, .pem). */
    public static List<File> scanUsbForCerts() {
        List<File> certs = new ArrayList<>();
        File storage = new File("/storage");
        File[] drives = storage.listFiles();
        if (drives == null) return certs;
        for (File drive : drives) {
            String name = drive.getName();
            if ("emulated".equals(name) || "self".equals(name)) continue;
            if (drive.isDirectory() && drive.canRead()) {
                searchDir(drive, 3, certs);
            }
        }
        return certs;
    }

    private static void searchDir(File dir, int depth, List<File> out) {
        if (depth < 0 || dir == null || !dir.isDirectory()) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    searchDir(f, depth - 1, out);
                }
            } else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".p12") || n.endsWith(".pfx") ||
                    n.endsWith(".crt") || n.endsWith(".cer") ||
                    n.endsWith(".pem")) {
                    out.add(f);
                }
            }
        }
    }

    /** Downloads certificate data from a given HTTP/HTTPS URL asynchronously. */
    public static void fetchUrl(String urlStr, FetchCallback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            InputStream in = null;
            try {
                URL url = new URL(urlStr.trim());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    final String respMsg = conn.getResponseMessage();
                    main.post(() -> cb.onDone(false, null, "HTTP " + code + ": " + respMsg));
                    return;
                }
                in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 5 * 1024 * 1024) { // 5MB limit
                        main.post(() -> cb.onDone(false, null, "File exceeds 5MB limit"));
                        return;
                    }
                    out.write(buf, 0, n);
                }
                byte[] res = out.toByteArray();
                main.post(() -> cb.onDone(true, res, null));
            } catch (Throwable t) {
                main.post(() -> cb.onDone(false, null, t.getMessage() != null ? t.getMessage() : t.toString()));
            } finally {
                if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            }
        }, "cert-fetch").start();
    }

    /**
     * Performs an active TLS handshake against the specified MQTT target URI.
     * Validates CA trust, client certificate presentation (mTLS), and cipher negotiation.
     */
    public static void testTls(Context ctx, String targetUri, TlsCallback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Socket socket = null;
            SSLSocket sslSocket = null;
            try {
                if (targetUri == null || targetUri.trim().isEmpty()) {
                    main.post(() -> cb.onResult(false, "Nenhum endereço de broker informado"));
                    return;
                }
                // Handle multi-target inputs: split on newlines, commas, or whitespace
                String[] candidates = targetUri.trim().split("[,\\s\n]+");
                String chosen = candidates[0].trim();
                for (String cand : candidates) {
                    String c = cand.trim().toLowerCase();
                    if (c.startsWith("ssl://") || c.startsWith("tls://") || c.startsWith("wss://")
                            || c.endsWith(":8883") || c.endsWith(":8884") || c.contains(":8883") || c.contains(":8884")) {
                        chosen = cand.trim();
                        break;
                    }
                }

                String clean = chosen;
                boolean isPlainTcp = clean.startsWith("tcp://");
                int defaultPort = 8883;
                if (clean.startsWith("ssl://") || clean.startsWith("tls://")) {
                    clean = clean.substring(clean.indexOf("://") + 3);
                } else if (clean.startsWith("wss://")) {
                    clean = clean.substring(6);
                    defaultPort = 8884;
                } else if (clean.startsWith("tcp://")) {
                    clean = clean.substring(6);
                    defaultPort = 1883;
                }

                if (clean.contains("/")) {
                    clean = clean.substring(0, clean.indexOf("/"));
                }

                String host = clean;
                int port = defaultPort;
                if (clean.contains(":")) {
                    String[] parts = clean.split(":");
                    host = parts[0];
                    try { port = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                }

                if (isPlainTcp && port == 1883) {
                    final String failUri = chosen;
                    main.post(() -> cb.onResult(false,
                        "URI '" + failUri + "' é texto plano (porta 1883). O teste TLS requer ssl:// ou porta 8883."));
                    return;
                }

                SSLSocketFactory sf = MqttTls.build(ctx);
                if (sf == null) {
                    main.post(() -> cb.onResult(false, "Could not build TLS socket factory (missing CA)"));
                    return;
                }

                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 8000);
                sslSocket = (SSLSocket) sf.createSocket(socket, host, port, true);
                sslSocket.setSoTimeout(10000);

                boolean isIp = host != null && (host.matches("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$") || host.contains(":"));
                if (host != null && !host.isEmpty() && !isIp) {
                    try {
                        javax.net.ssl.SSLParameters params = sslSocket.getSSLParameters();
                        if (params != null) {
                            java.util.List<javax.net.ssl.SNIServerName> sni = new java.util.ArrayList<>();
                            sni.add(new javax.net.ssl.SNIHostName(host));
                            params.setServerNames(sni);
                            sslSocket.setSSLParameters(params);
                        }
                    } catch (Throwable t) {
                        logW("cert: SNI setup note: " + t);
                    }
                }

                sslSocket.startHandshake();

                Certificate[] peers = sslSocket.getSession().getPeerCertificates();
                String peerCn = "unknown";
                if (peers != null && peers.length > 0 && peers[0] instanceof X509Certificate) {
                    peerCn = extractCN(((X509Certificate) peers[0]).getSubjectX500Principal().getName());
                }
                String cipher = sslSocket.getSession().getCipherSuite();
                String proto = sslSocket.getSession().getProtocol();

                final String successMsg = "TLS handshake OK: " + host + ":" + port +
                        "\nProtocol: " + proto + " (" + cipher + ")" +
                        "\nBroker CN: " + peerCn;
                main.post(() -> cb.onResult(true, successMsg));
            } catch (Throwable t) {
                logW("cert: TLS handshake test failed: " + t, t);
                String msg = t.getMessage();
                if (msg == null) msg = t.getClass().getSimpleName();
                String diag = "";
                String full = t.toString();
                if (full.contains("certificate_required") || full.contains("CERTIFICATE_REQUIRED")) {
                    diag = "\n(Broker exige mTLS / certificado de cliente. Verifique mqtt_client.p12)";
                } else if (full.contains("CertPathValidatorException") || full.contains("Trust anchor")) {
                    diag = "\n(Certificado da CA não confiável pelo Android. Verifique mqtt_ca.crt)";
                }
                final String errMsg = "TLS handshake failed: " + msg + diag;
                main.post(() -> cb.onResult(false, errMsg));
            } finally {
                if (sslSocket != null) { try { sslSocket.close(); } catch (Throwable ignored) {} }
                if (socket != null) { try { socket.close(); } catch (Throwable ignored) {} }
            }
        }, "tls-test").start();
    }

    public static byte[] readFileBytes(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            return readStreamBytes(fis);
        } finally {
            fis.close();
        }
    }

    public static byte[] readStreamBytes(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    public static String extractCN(String dn) {
        if (dn == null) return "Unknown";
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=") || p.startsWith("cn=")) {
                return p.substring(3);
            }
        }
        return dn;
    }
}
