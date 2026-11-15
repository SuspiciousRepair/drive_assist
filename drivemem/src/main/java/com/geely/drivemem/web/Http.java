package com.geely.drivemem.web;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The smallest HTTP/1.1 the phone page needs: one request per connection,
 * GET and a small POST form, no keep-alive, no chunked bodies. Kept free of
 * Android classes so the tests can run it.
 *
 * Limits are tight on purpose: the head unit is slow, and nothing on this
 * server needs a big request. */
final class Http {
    private Http() {}

    static final int MAX_HEAD = 8 * 1024;
    static final int MAX_BODY = 16 * 1024;

    static final class Request {
        String method = "";
        String path = "/";
        final Map<String, String> query = new LinkedHashMap<>();
        /** Header names in lower case. */
        final Map<String, String> headers = new LinkedHashMap<>();
        final Map<String, String> form = new LinkedHashMap<>();
        long contentLength = -1;
        /** Set for a multipart/form-data POST; its body is still unread. */
        String boundary;

        String cookie(String name) {
            String all = headers.get("cookie");
            if (all == null) return null;
            for (String part : all.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
            }
            return null;
        }
    }

    /** Thrown for a request this server will not read. */
    static final class BadRequest extends IOException {
        final int code;
        BadRequest(int code, String why) { super(why); this.code = code; }
    }

    static Request read(InputStream in) throws IOException {
        byte[] head = readHead(in);
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3 || !first[2].startsWith("HTTP/1.")) throw new BadRequest(400, "request line");
        Request r = new Request();
        r.method = first[0];
        String target = first[1];
        if (!target.startsWith("/")) throw new BadRequest(400, "target");
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) parseForm(target.substring(q + 1), r.query);
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US), lines[i].substring(c + 1).trim());
        }
        if ("POST".equals(r.method)) {
            long len;
            try { len = Long.parseLong(r.headers.getOrDefault("content-length", "")); }
            catch (NumberFormatException e) { throw new BadRequest(411, "length"); }
            if (len < 0) throw new BadRequest(400, "length");
            r.contentLength = len;
            String type = r.headers.getOrDefault("content-type", "");
            // A file upload is left in the stream: the handler reads it with
            // multipart(), straight to disk.
            if (type.startsWith("multipart/form-data")) {
                r.boundary = boundary(type);
                if (r.boundary == null) throw new BadRequest(400, "boundary");
                return r;
            }
            if (!type.startsWith("application/x-www-form-urlencoded")) throw new BadRequest(415, "form only");
            if (len > MAX_BODY) throw new BadRequest(413, "body too big");
            byte[] body = new byte[(int) len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, body.length - off);
                if (n < 0) throw new BadRequest(400, "short body");
                off += n;
            }
            parseForm(new String(body, StandardCharsets.UTF_8), r.form);
        }
        return r;
    }

    /** Bytes up to the blank line that ends the head, without it. */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        int state = 0;   // how much of \r\n\r\n has been seen
        while (true) {
            int c = in.read();
            if (c < 0) throw new BadRequest(400, "closed");
            b.write(c);
            if (b.size() > MAX_HEAD) throw new BadRequest(431, "head too big");
            if (c == '\r') state = (state == 2) ? 3 : 1;
            else if (c == '\n') state = (state == 1) ? 2 : (state == 3 ? 4 : 0);
            else state = 0;
            if (state == 4) break;
        }
        byte[] all = b.toByteArray();
        byte[] head = new byte[all.length - 4];
        System.arraycopy(all, 0, head, 0, head.length);
        return head;
    }

    static String boundary(String contentType) {
        for (String part : contentType.split(";")) {
            String t = part.trim();
            if (t.startsWith("boundary=")) {
                String b = t.substring(9).trim();
                if (b.length() > 1 && b.startsWith("\"") && b.endsWith("\"")) b = b.substring(1, b.length() - 1);
                return b.isEmpty() || b.length() > 70 ? null : b;
            }
        }
        return null;
    }

    // ---- multipart/form-data upload ----------------------------------------

    /** Field multipart() adds with the size of the uploaded file. */
    static final String FILE_BYTES = "__file_bytes";

    /** Reads a multipart body of exactly len bytes. The part named fileField
     * is written to dest (at most maxFile bytes); every other part is a short
     * text field, returned by name. Throws BadRequest when the body is not
     * what a browser form sends, or too big. Returns the text fields, plus
     * {@link #FILE_BYTES} with the size written (absent when there was no file). */
    static Map<String, String> multipart(InputStream in, long len, String boundary,
                                         String fileField, File dest, long maxFile) throws IOException {
        Source src = new Source(in, len);
        byte[] delim = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int[] fail = failure(delim);
        Map<String, String> fields = new LinkedHashMap<>();
        // The body starts with "--boundary", no CRLF before it: start the
        // search as if the CRLF had been seen. Anything before is preamble.
        if (!scan(src, delim, fail, 2, null)) throw new BadRequest(400, "no first boundary");
        while (true) {
            int a = src.read(), b = src.read();
            if (a == '-' && b == '-') break;                       // closing boundary
            if (a != '\r' || b != '\n') throw new BadRequest(400, "boundary end");
            String headers = partHeaders(src);
            String name = dispositionParam(headers, "name");
            if (name == null) throw new BadRequest(400, "part name");
            if (name.equals(fileField) && dispositionParam(headers, "filename") != null) {
                long written;
                try (OutputStream o = new java.io.FileOutputStream(dest)) {
                    Sink sink = new Sink(o, maxFile);
                    if (!scan(src, delim, fail, 0, sink)) throw new BadRequest(400, "file not closed");
                    sink.flush();
                    written = sink.total;
                }
                fields.put(FILE_BYTES, String.valueOf(written));
            } else {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                Sink sink = new Sink(text, 4096);
                if (!scan(src, delim, fail, 0, sink)) throw new BadRequest(400, "field not closed");
                sink.flush();
                if (!fields.containsKey(name)) fields.put(name, new String(text.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static String partHeaders(Source src) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(256);
        int state = 0;
        while (state != 4) {
            int c = src.read();
            if (c < 0) throw new BadRequest(400, "part head");
            b.write(c);
            if (b.size() > 4096) throw new BadRequest(431, "part head too big");
            if (c == '\r') state = (state == 2) ? 3 : 1;
            else if (c == '\n') state = (state == 1) ? 2 : (state == 3 ? 4 : 0);
            else state = 0;
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** name="..." or filename="..." from the Content-Disposition line. */
    static String dispositionParam(String headers, String param) {
        for (String line : headers.split("\r\n")) {
            if (!line.toLowerCase(Locale.US).startsWith("content-disposition:")) continue;
            for (String p : line.substring(line.indexOf(':') + 1).split(";")) {
                String t = p.trim();
                if (t.startsWith(param + "=")) {
                    String v = t.substring(param.length() + 1);
                    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                    return v;
                }
            }
        }
        return null;
    }

    private static int[] failure(byte[] d) {
        int[] f = new int[d.length];
        for (int i = 1, k = 0; i < d.length; i++) {
            while (k > 0 && d[i] != d[k]) k = f[k - 1];
            if (d[i] == d[k]) k++;
            f[i] = k;
        }
        return f;
    }

    /** Copies bytes to sink (null = drop them) until the delimiter, which is
     * eaten. Returns false when the body ends first. Bytes that only looked
     * like the start of the delimiter are given back to the sink. */
    private static boolean scan(Source src, byte[] d, int[] fail, int j, Sink sink) throws IOException {
        while (true) {
            int c = src.read();
            if (c < 0) return false;
            byte b = (byte) c;
            while (j > 0 && b != d[j]) {
                int back = fail[j - 1];
                if (sink != null) sink.write(d, 0, j - back);
                j = back;
            }
            if (b == d[j]) {
                j++;
                if (j == d.length) return true;
            } else if (sink != null) {
                sink.write(b);
            }
        }
    }

    /** The request body: exactly len bytes, read in big blocks. */
    private static final class Source {
        private final InputStream in;
        private long left;
        private final byte[] buf = new byte[64 * 1024];
        private int pos, lim;
        Source(InputStream in, long len) { this.in = in; this.left = len; }
        int read() throws IOException {
            if (pos == lim) {
                if (left <= 0) return -1;
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) return -1;
                left -= n; pos = 0; lim = n;
            }
            return buf[pos++] & 0xff;
        }
    }

    private static final class Sink {
        private final OutputStream out;
        private final long max;
        private final byte[] buf = new byte[64 * 1024];
        private int n;
        long total;
        Sink(OutputStream out, long max) { this.out = out; this.max = max; }
        void write(byte b) throws IOException {
            if (++total > max) throw new BadRequest(413, "too big");
            if (n == buf.length) flush();
            buf[n++] = b;
        }
        void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) write(b[off + i]);
        }
        void flush() throws IOException {
            if (n > 0) { out.write(buf, 0, n); n = 0; }
        }
    }

    static void parseForm(String s, Map<String, String> out) {
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (k != null && v != null && !out.containsKey(k)) out.put(k, v);
        }
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return null; }   // bad % escape: drop the pair
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** Percent-encodes one query value. */
    static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 409: return "Conflict";
            case 415: return "Unsupported Media Type";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    /** Headers every answer carries: nothing is cached, framed, sniffed, or
     * leaks the address to another site. Not "no-referrer": with it, browsers
     * send "Origin: null" on a form POST, and the POST check needs the real one. */
    private static final String COMMON =
        "Connection: close\r\n"
        + "Cache-Control: no-store\r\n"
        + "X-Content-Type-Options: nosniff\r\n"
        + "X-Frame-Options: DENY\r\n"
        + "Referrer-Policy: same-origin\r\n"
        + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'self'; frame-ancestors 'none'\r\n";

    static void send(OutputStream out, int code, String type, byte[] body, String... extra) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n").append(COMMON);
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        for (String x : extra) h.append(x).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    static void html(OutputStream out, int code, String page, String... extra) throws IOException {
        send(out, code, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8), extra);
    }

    static void redirect(OutputStream out, String to, String... extra) throws IOException {
        String[] all = new String[extra.length + 1];
        all[0] = "Location: " + to;
        System.arraycopy(extra, 0, all, 1, extra.length);
        send(out, 303, null, new byte[0], all);
    }

    /** Streams a file as a download. downloadName must be a plain file name. */
    static void file(OutputStream out, File f, String type, String downloadName) throws IOException {
        long len = f.length();
        String safe = downloadName.replaceAll("[^A-Za-z0-9._-]", "_");
        String h = "HTTP/1.1 200 OK\r\n" + COMMON
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + len + "\r\n"
            + "Content-Disposition: attachment; filename=\"" + safe + "\"\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        byte[] buf = new byte[64 * 1024];
        long left = len;
        try (InputStream in = new FileInputStream(f)) {
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }
}
