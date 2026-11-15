package com.geely.drivemem.web;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void readsGetWithQueryAndCookie() throws Exception {
        Http.Request r = Http.read(in("GET /pair?t=ab%2Dc&x=1 HTTP/1.1\r\nHost: 10.0.0.2:8765\r\n" // pii: allow (example address)
            + "Cookie: a=1; da=TOKEN ; z=2\r\n\r\n"));
        assertEquals("GET", r.method);
        assertEquals("/pair", r.path);
        assertEquals("ab-c", r.query.get("t"));
        assertEquals("1", r.query.get("x"));
        assertEquals("10.0.0.2:8765", r.headers.get("host")); // pii: allow (example address)
        assertEquals("TOKEN", r.cookie("da"));
        assertNull(r.cookie("nope"));
    }

    @Test public void readsUrlEncodedPost() throws Exception {
        String body = "mqtt_uri=tcp%3A%2F%2Fbroker%3A1883&mqtt_pass=a+b%26c";
        Http.Request r = Http.read(in("POST /settings HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n"
            + "Content-Length: " + body.length() + "\r\n\r\n" + body));
        assertEquals("tcp://broker:1883", r.form.get("mqtt_uri"));
        assertEquals("a b&c", r.form.get("mqtt_pass"));
    }

    @Test public void multipartPostLeavesTheBodyForTheHandler() throws Exception {
        Http.Request r = Http.read(in("POST /restore HTTP/1.1\r\n"
            + "Content-Type: multipart/form-data; boundary=----X1\r\nContent-Length: 999\r\n\r\nrest"));
        assertEquals("----X1", r.boundary);
        assertEquals(999, r.contentLength);
        assertTrue(r.form.isEmpty());
    }

    @Test public void refusesWhatItShouldNotRead() {
        expect(400, "BAD\r\n\r\n");
        expect(400, "GET http://evil/ HTTP/1.1\r\n\r\n");
        expect(411, "POST /settings HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n");
        expect(413, "POST /settings HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\n"
            + "Content-Length: 999999\r\n\r\n");
        expect(415, "POST /settings HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
        char[] big = new char[Http.MAX_HEAD + 10];
        Arrays.fill(big, 'a');
        expect(431, "GET / HTTP/1.1\r\nX: " + new String(big) + "\r\n\r\n");
    }

    private static void expect(int code, String req) {
        try {
            Http.read(in(req));
            fail("read " + req);
        } catch (Http.BadRequest e) {
            assertEquals(req, code, e.code);
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @Test public void escapesHtml() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&#39;&amp;", Http.esc("<a href=\"x\">'&"));
        assertEquals("", Http.esc(null));
    }

    // ---- multipart ----

    /** What a browser sends for the restore form, password first. */
    private static byte[] form(String boundary, String password, byte[] file) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"password\"\r\n\r\n" + password + "\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"2026-11-15_101010.dabackup\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n";
        b.write(head.getBytes(StandardCharsets.UTF_8));
        b.write(file);
        b.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    private Map<String, String> upload(String boundary, String pw, byte[] file, File dest) throws Exception {
        byte[] body = form(boundary, pw, file);
        return Http.multipart(new ByteArrayInputStream(body), body.length, boundary, "file", dest, 10_000_000);
    }

    @Test public void multipartWritesTheFileByteForByte() throws Exception {
        byte[] file = new byte[300_000];
        new Random(7).nextBytes(file);
        File dest = tmp.newFile();
        Map<String, String> f = upload("----WebKitFormBoundaryAbC123", "s3cret pw", file, dest);
        assertEquals("s3cret pw", f.get("password"));
        assertEquals(String.valueOf(file.length), f.get(Http.FILE_BYTES));
        assertArrayEquals(file, Files.readAllBytes(dest.toPath()));
    }

    /** The hard case for the search: data that almost holds the boundary,
     * many times, across the 64 KB read blocks. */
    @Test public void multipartKeepsBytesThatOnlyLookLikeTheBoundary() throws Exception {
        String boundary = "--bb--bb";
        byte[] near = "\r\n----bb--b\r\n--\r\n----bb--bX\r\r\n\r\n---".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < 6000; i++) { data.write(near); data.write(i & 0xff); }
        byte[] file = data.toByteArray();
        File dest = tmp.newFile();
        Map<String, String> f = upload(boundary, "", file, dest);
        assertEquals("", f.get("password"));
        assertArrayEquals(file, Files.readAllBytes(dest.toPath()));
    }

    @Test public void multipartEmptyFile() throws Exception {
        File dest = tmp.newFile();
        Map<String, String> f = upload("B0", "x", new byte[0], dest);
        assertEquals("0", f.get(Http.FILE_BYTES));
    }

    @Test public void multipartRefusesATooBigFile() throws Exception {
        byte[] body = form("B1", "", new byte[5000]);
        try {
            Http.multipart(new ByteArrayInputStream(body), body.length, "B1", "file", tmp.newFile(), 4000);
            fail("took it");
        } catch (Http.BadRequest e) {
            assertEquals(413, e.code);
        }
    }

    @Test public void multipartRefusesACutBody() throws Exception {
        byte[] body = form("B2", "", new byte[5000]);
        byte[] cut = Arrays.copyOf(body, body.length - 20);
        try {
            Http.multipart(new ByteArrayInputStream(cut), cut.length, "B2", "file", tmp.newFile(), 1_000_000);
            fail("took it");
        } catch (Http.BadRequest e) {
            assertEquals(400, e.code);
        }
    }

    @Test public void boundaryFromContentType() {
        assertEquals("abc", Http.boundary("multipart/form-data; boundary=abc"));
        assertEquals("a b", Http.boundary("multipart/form-data; boundary=\"a b\""));
        assertNull(Http.boundary("multipart/form-data"));
        assertFalse("x".equals(Http.boundary("multipart/form-data; boundary=")));
    }

    @Test public void dispositionParams() {
        String h = "Content-Disposition: form-data; name=\"file\"; filename=\"a.dabackup\"\r\nContent-Type: x";
        assertEquals("file", Http.dispositionParam(h, "name"));
        assertEquals("a.dabackup", Http.dispositionParam(h, "filename"));
        assertNull(Http.dispositionParam("Content-Disposition: form-data; name=\"pw\"", "filename"));
    }
}
