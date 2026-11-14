package com.geely.drivemem;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.geely.drivemem.util.BackupCrypto;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupCryptoTest {
    // Few rounds: the format is under test here, not the key stretching.
    private static final int ROUNDS = 1000;
    private static final int HEADER = 33;

    private static byte[] lock(byte[] plain, char[] pw, byte[] salt) throws Exception {
        byte[] key = BackupCrypto.deriveKey(pw, salt, ROUNDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream enc = BackupCrypto.encrypt(out, key, salt, ROUNDS)) {
            // Odd write sizes, so chunk edges fall inside writes.
            int i = 0;
            while (i < plain.length) {
                int n = Math.min(777_777, plain.length - i);
                enc.write(plain, i, n);
                i += n;
            }
        }
        return out.toByteArray();
    }

    private static byte[] open(byte[] locked, char[] pw) throws Exception {
        InputStream in = new ByteArrayInputStream(locked);
        BackupCrypto.Header h = BackupCrypto.readHeader(in);
        byte[] key = BackupCrypto.deriveKey(pw, h.salt, h.rounds);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream d = BackupCrypto.decrypt(in, h, key)) {
            byte[] buf = new byte[5000];
            int n;
            while ((n = d.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test public void roundTripsManyChunks() throws Exception {
        byte[] plain = random(3 * (1 << 20) + 12345);   // 4 chunks, the last partly full
        byte[] locked = lock(plain, "secret1".toCharArray(), BackupCrypto.newSalt());
        assertArrayEquals(plain, open(locked, "secret1".toCharArray()));
    }

    @Test public void roundTripsExactChunkAndEmpty() throws Exception {
        for (int n : new int[]{0, 1, 1 << 20, 2 << 20}) {
            byte[] plain = random(n);
            byte[] locked = lock(plain, "secret1".toCharArray(), BackupCrypto.newSalt());
            assertArrayEquals("size " + n, plain, open(locked, "secret1".toCharArray()));
        }
    }

    @Test public void plainTextIsNotInTheFile() throws Exception {
        byte[] plain = "\"mqtt_pass\":\"hunter22\"".getBytes(StandardCharsets.UTF_8);
        byte[] locked = lock(plain, "secret1".toCharArray(), BackupCrypto.newSalt());
        String s = new String(locked, StandardCharsets.ISO_8859_1);
        assertTrue(!s.contains("hunter22") && !s.contains("mqtt_pass"));
    }

    @Test public void wrongPasswordIsSaidSo() throws Exception {
        byte[] locked = lock(random(5000), "secret1".toCharArray(), BackupCrypto.newSalt());
        try {
            open(locked, "secret2".toCharArray());
            fail("opened with the wrong password");
        } catch (BackupCrypto.WrongPassword expected) {
            // ok
        }
    }

    @Test public void cutShortFailsInsteadOfOpeningPart() throws Exception {
        byte[] locked = lock(random(2 * (1 << 20) + 10), "secret1".toCharArray(), BackupCrypto.newSalt());
        // Drop the last chunk entirely: every chunk left is valid, but the end is missing.
        int firstLen = java.nio.ByteBuffer.wrap(locked, HEADER, 4).getInt();
        int secondAt = HEADER + 4 + firstLen;
        int secondLen = java.nio.ByteBuffer.wrap(locked, secondAt, 4).getInt();
        byte[] cut = Arrays.copyOf(locked, secondAt + 4 + secondLen);
        expectDamaged(cut);
        // And a cut in the middle of a chunk.
        expectDamaged(Arrays.copyOf(locked, locked.length - 100));
    }

    @Test public void changedByteFails() throws Exception {
        byte[] locked = lock(random(2 * (1 << 20) + 10), "secret1".toCharArray(), BackupCrypto.newSalt());
        byte[] bad = locked.clone();
        bad[bad.length - 50] ^= 1;
        expectDamaged(bad);
    }

    @Test public void swappedChunksFail() throws Exception {
        byte[] locked = lock(random(3 * (1 << 20)), "secret1".toCharArray(), BackupCrypto.newSalt());
        int len = java.nio.ByteBuffer.wrap(locked, HEADER, 4).getInt();
        int a = HEADER, b = HEADER + 4 + len;   // chunks 0 and 1 have the same length
        byte[] bad = locked.clone();
        System.arraycopy(locked, a, bad, b, 4 + len);
        System.arraycopy(locked, b, bad, a, 4 + len);
        try {
            open(bad, "secret1".toCharArray());
            fail("opened swapped chunks");
        } catch (IOException expected) {
            // chunk 0 now holds chunk 1: reads as a wrong password, which is fine
        }
    }

    @Test public void notABackupIsSaidSo() {
        try {
            BackupCrypto.readHeader(new ByteArrayInputStream("hello world, this is no backup at all".getBytes()));
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("not a Drive Assist backup"));
        }
    }

    @Test public void unzipRefusesPaths() throws Exception {
        File dir = Files.createTempDirectory("bk").toFile();
        ByteArrayOutputStream zb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zb)) {
            z.putNextEntry(new ZipEntry("settings.json"));
            z.write("{}".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("../evil"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        try {
            BackupCrypto.unzip(new ByteArrayInputStream(zb.toByteArray()), dir);
            fail();
        } catch (IOException expected) {
            assertTrue(!new File(dir.getParentFile(), "evil").exists());
        }
    }

    @Test public void zipThroughTheLockRoundTrips() throws Exception {
        byte[] key = BackupCrypto.deriveKey("secret1".toCharArray(), new byte[16], ROUNDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(BackupCrypto.encrypt(out, key, new byte[16], ROUNDS))) {
            z.putNextEntry(new ZipEntry("settings.json"));
            z.write("{\"format\":1}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("car.db"));
            z.write(random(1_500_000));
            z.closeEntry();
        }
        InputStream in = new ByteArrayInputStream(out.toByteArray());
        BackupCrypto.Header h = BackupCrypto.readHeader(in);
        File dir = Files.createTempDirectory("bk").toFile();
        List<String> names = BackupCrypto.unzip(BackupCrypto.decrypt(in, h, key), dir);
        assertEquals(Arrays.asList("settings.json", "car.db"), names);
        assertEquals("{\"format\":1}", new String(Files.readAllBytes(new File(dir, "settings.json").toPath()),
            StandardCharsets.UTF_8));
        assertArrayEquals(random(1_500_000), Files.readAllBytes(new File(dir, "car.db").toPath()));
    }

    private static void expectDamaged(byte[] bad) throws Exception {
        try {
            open(bad, "secret1".toCharArray());
            fail("opened a damaged file");
        } catch (BackupCrypto.WrongPassword e) {
            fail("damage after chunk 0 must not read as a wrong password");
        } catch (IOException expected) {
            // ok
        }
    }
}
