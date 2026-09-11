package com.geely.drivemem.net;

import com.geely.drivemem.car.CarAccess;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

// TODO: MQTT server is owned by the user not us. So the certificate cannot be shiped. We should be able to import and save one.

// TLS for the remote MQTT.
//
// WHY THIS EXISTS: away from home the car connects through a broker reachable
// over a secure remote-access tunnel, with TLS terminating on the broker
// itself rather than at the tunnel's edge. That enables two things:
//
//   1. the broker requires a client certificate (require_certificate), so a
//      leaked password is not enough to talk to a broker exposed to the
//      internet;
//   2. the server uses a certificate from OUR CA — Let's Encrypt does not issue
//      for *.ts.net, which is not a domain of ours.
//
// That is why Android's default truststore is not enough: it does not know our
// CA. We build an SSLContext with
//   - trust: our CA (validates the server) — res/raw/mqtt_ca.crt, a CA
//     certificate, public by nature and fine to ship;
//   - key:   the car's PKCS#12 (identifies the client) — NOT shipped, see below.
//
// Both are read only when the URI is TLS — at home, on the LAN's tcp://, none of
// this is used.
public final class MqttTls {
    private static final String TAG = CarAccess.TAG;

    // The client certificate is provisioned or imported by the user. It lives
    // in the app's private storage (0600, owned by the app's uid).
    // This survives OTA updates since install -r preserves app data.
    public static final String CLIENT_P12 = "mqtt_client.p12";
    public static final String CUSTOM_CA_CRT = "mqtt_ca.crt";
    private static final char[] NO_PASS = new char[0];

    private MqttTls() {}

    private static javax.net.ssl.X509TrustManager pickX509(TrustManagerFactory f) {
        for (javax.net.ssl.TrustManager m : f.getTrustManagers())
            if (m instanceof javax.net.ssl.X509TrustManager) return (javax.net.ssl.X509TrustManager) m;
        return null;
    }

    // true if the address requires TLS (and therefore the SSLSocketFactory)
    public static boolean isTls(String uri) {
        if (uri == null) return false;
        String u = uri.trim().toLowerCase();
        return u.startsWith("ssl://") || u.startsWith("wss://") || u.startsWith("tls://");
    }

    public static boolean isTls(String[] uris) {
        if (uris == null) return false;
        for (String u : uris) if (isTls(u)) return true;
        return false;
    }

    // Builds the SSLSocketFactory.
    public static SSLSocketFactory build(Context ctx) {
        if (ctx == null) { Log.w(TAG, "tls: no Context — whoever created the MqttReporter did not pass one"); return null; }
        try {
            // --- trusting the server: custom imported CA + bundled CA + system CAs ---
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            boolean caLoaded = false;

            // 1. Custom imported CA certificate
            java.io.File customCa = new java.io.File(ctx.getFilesDir(), CUSTOM_CA_CRT);
            if (customCa.isFile() && customCa.length() > 0) {
                InputStream fis = new java.io.FileInputStream(customCa);
                try {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    java.util.Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(fis);
                    int idx = 0;
                    for (java.security.cert.Certificate c : certs) {
                        trust.setCertificateEntry("custom-ca-" + (++idx), c);
                        caLoaded = true;
                    }
                    Log.i(TAG, "tls: custom CA loaded from " + customCa + " (" + idx + " certs)");
                } finally { try { fis.close(); } catch (Throwable ignored) {} }
            }

            // 2. Bundled CA certificate from APK resources
            int caId = ctx.getResources().getIdentifier("mqtt_ca", "raw", ctx.getPackageName());
            if (caId != 0) {
                InputStream caIn = ctx.getResources().openRawResource(caId);
                try {
                    X509Certificate ca = (X509Certificate) CertificateFactory
                            .getInstance("X.509").generateCertificate(caIn);
                    trust.setCertificateEntry("mqtt-ca", ca);
                    caLoaded = true;
                    Log.i(TAG, "tls: CA loaded — " + ca.getSubjectDN());
                } finally { try { caIn.close(); } catch (Throwable ignored) {} }
            }

            if (!caLoaded) {
                Log.w(TAG, "tls: neither custom nor bundled CA found — relying on system CAs only");
            }

            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);

            // Also trust the system CAs. Without this, only our CA is trusted
            // and public certificates (e.g., Tailscale's) fail.
            TrustManagerFactory sysTmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sysTmf.init((KeyStore) null);
            final javax.net.ssl.X509TrustManager ours = pickX509(tmf);
            final javax.net.ssl.X509TrustManager sys = pickX509(sysTmf);
            javax.net.ssl.TrustManager[] both = { new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a)
                        throws java.security.cert.CertificateException {
                    if (sys != null) sys.checkClientTrusted(c, a);
                }
                @Override public void checkServerTrusted(X509Certificate[] c, String a)
                        throws java.security.cert.CertificateException {
                    // try our CA; if that does not do it, try the system ones
                    try { if (ours != null) { ours.checkServerTrusted(c, a); return; } }
                    catch (java.security.cert.CertificateException ignored) {}
                    if (sys == null) throw new java.security.cert.CertificateException("sem trust manager");
                    sys.checkServerTrusted(c, a);
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    java.util.List<X509Certificate> list = new java.util.ArrayList<>();
                    if (ours != null) {
                        for (X509Certificate c : ours.getAcceptedIssuers()) list.add(c);
                    }
                    if (sys != null) {
                        for (X509Certificate c : sys.getAcceptedIssuers()) list.add(c);
                    }
                    return list.toArray(new X509Certificate[0]);
                }
            }};

            // --- identifying the client: the provisioned PKCS#12 (optional) ---
            KeyManagerFactory kmf = null;
            java.io.File p12 = new java.io.File(ctx.getFilesDir(), CLIENT_P12);
            if (p12.isFile()) {
                KeyStore key = KeyStore.getInstance("PKCS12");
                InputStream p12In = new java.io.FileInputStream(p12);
                try { key.load(p12In, NO_PASS); }
                finally { try { p12In.close(); } catch (Throwable ignored) {} }
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(key, NO_PASS);
                Log.i(TAG, "tls: client certificate loaded from " + p12);
            } else {
                // Without a client cert it still connects to a broker that does
                // not require one — the LAN's tcp:// does not even come through
                // here. What breaks is the remote path, and it breaks as a TLS
                // handshake failure rather than a credentials error, so say so
                // plainly: this line is the diagnostic for "remote telemetry went
                // quiet after a reinstall on a fresh unit".
                Log.w(TAG, "tls: no " + CLIENT_P12 + " in " + ctx.getFilesDir()
                         + " — run helpers/provision-cert.sh; a broker with require_certificate will refuse us");
            }

            // "TLS" and not "TLSv1.2": lets Android negotiate the best version
            // it supports. Pinning the version here narrowed the set of ciphers
            // offered and the server closed the handshake.
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(kmf == null ? null : kmf.getKeyManagers(), both, null);
            SSLSocketFactory f = sc.getSocketFactory();
            if (f == null) {
                Log.w(TAG, "tls: SSLContext returned null SSLSocketFactory");
                return null;
            }
            Log.i(TAG, "tls: context ready, wrapping socket factory for TLS 1.3 / SNI support");
            return new TlsSocketFactoryWrapper(f);
        } catch (Throwable t) {
            Log.w(TAG, "tls: failed to build the context: " + t, t);
            return null;
        }
    }

    /**
     * Wraps an SSLSocketFactory to ensure TLSv1.3 and TLSv1.2 are explicitly enabled
     * on Android 9 (where TLS 1.3 is supported but disabled by default on client sockets),
     * and sets SNI correctly for hostnames.
     */
    public static class TlsSocketFactoryWrapper extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        public TlsSocketFactoryWrapper(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override public java.net.Socket createSocket() throws java.io.IOException {
            return configureSocket(delegate.createSocket(), null);
        }

        @Override public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return configureSocket(delegate.createSocket(s, host, port, autoClose), host);
        }

        @Override public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return configureSocket(delegate.createSocket(host, port), host);
        }

        @Override public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
            return configureSocket(delegate.createSocket(host, port, localHost, localPort), host);
        }

        @Override public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return configureSocket(delegate.createSocket(host, port), null);
        }

        @Override public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
            return configureSocket(delegate.createSocket(address, port, localAddress, localPort), null);
        }

        private java.net.Socket configureSocket(java.net.Socket socket, String host) {
            if (socket instanceof javax.net.ssl.SSLSocket) {
                javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) socket;
                try {
                    String[] supported = ssl.getSupportedProtocols();
                    java.util.List<String> enabled = new java.util.ArrayList<>();
                    for (String p : supported) {
                        if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) enabled.add(p);
                    }
                    if (!enabled.isEmpty()) {
                        ssl.setEnabledProtocols(enabled.toArray(new String[0]));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "tls: error enabling protocols: " + t);
                }

                if (host != null && !isIpAddress(host)) {
                    try {
                        javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                        if (params != null) {
                            java.util.List<javax.net.ssl.SNIServerName> sni = new java.util.ArrayList<>();
                            sni.add(new javax.net.ssl.SNIHostName(host));
                            params.setServerNames(sni);
                            ssl.setSSLParameters(params);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "tls: error configuring SNI for " + host + ": " + t);
                    }
                }
            }
            return socket;
        }

        private static boolean isIpAddress(String host) {
            if (host == null || host.isEmpty()) return false;
            return host.matches("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$") || host.contains(":");
        }
    }
}
