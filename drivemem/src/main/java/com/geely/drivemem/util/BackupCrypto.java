package com.geely.drivemem.util;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Locks a backup with the user's password. Plain Java, no Android, so the
 * unit tests and tools/backup-open.sh use this same file.
 *
 * Other apps on the car can read the USB stick and the car storage, and a
 * backup holds the MQTT password, the ABRP and Spotify keys and every trip
 * with its GPS points. So the whole backup is one encrypted file:
 *
 *   "DABK" | format 1 | PBKDF2 rounds (int) | salt (16) | nonce prefix (8)
 *   then chunks: length (int) | AES-256-GCM ciphertext and tag
 *
 * The key comes from the password (PBKDF2-HMAC-SHA256). Each chunk holds at
 * most CHUNK plain bytes, so a 10 MB car.db never sits in memory at once.
 * Chunk i uses nonce = prefix | i, and its associated data is the header plus
 * a "last chunk" flag: a chunk that is changed, moved, dropped, or cut short
 * makes the file fail to open, never open wrong.
 *
 * The app keeps the derived key (never the password) in its private prefs,
 * so the daily backup needs no typing. Deleting the app deletes that key:
 * then the password is the only way in. That is the point.
 */
public final class BackupCrypto {
    private BackupCrypto() {}

    static final byte[] MAGIC = {'D', 'A', 'B', 'K'};
    static final int FORMAT = 1;
    public static final int ROUNDS = 200_000;
    static final int SALT_LEN = 16;
    static final int PREFIX_LEN = 8;
    static final int HEADER_LEN = MAGIC.length + 1 + 4 + SALT_LEN + PREFIX_LEN;
    static final int CHUNK = 1 << 20;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    /** The first chunk did not open: the password is wrong. */
    public static final class WrongPassword extends IOException {
        WrongPassword() { super("wrong password"); }
    }

    /** What the plain header says. salt decides which password opens it. */
    public static final class Header {
        public final int rounds;
        public final byte[] salt;
        final byte[] prefix;
        final byte[] raw;
        Header(int rounds, byte[] salt, byte[] prefix, byte[] raw) {
            this.rounds = rounds; this.salt = salt; this.prefix = prefix; this.raw = raw;
        }
    }

    public static byte[] newSalt() {
        byte[] s = new byte[SALT_LEN];
        RNG.nextBytes(s);
        return s;
    }

    public static byte[] deriveKey(char[] password, byte[] salt, int rounds) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, rounds, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    // ---- Lock -------------------------------------------------------------

    /** Writes the header to out and returns a stream that encrypts into it.
     * close() writes the last chunk and closes out. */
    public static OutputStream encrypt(OutputStream out, byte[] key, byte[] salt, int rounds) throws IOException {
        byte[] prefix = new byte[PREFIX_LEN];
        RNG.nextBytes(prefix);
        ByteBuffer h = ByteBuffer.allocate(HEADER_LEN);
        h.put(MAGIC).put((byte) FORMAT).putInt(rounds).put(salt).put(prefix);
        byte[] raw = h.array();
        out.write(raw);
        return new Enc(out, key, new Header(rounds, salt, prefix, raw));
    }

    private static final class Enc extends OutputStream {
        private final OutputStream out;
        private final byte[] key;
        private final Header h;
        private final byte[] buf = new byte[CHUNK];
        private int n;
        private int index;
        private boolean closed;

        Enc(OutputStream out, byte[] key, Header h) { this.out = out; this.key = key; this.h = h; }

        @Override public void write(int b) throws IOException {
            if (n == CHUNK) flushChunk(false);
            buf[n++] = (byte) b;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (n == CHUNK) flushChunk(false);
                int k = Math.min(len, CHUNK - n);
                System.arraycopy(b, off, buf, n, k);
                n += k; off += k; len -= k;
            }
        }

        private void flushChunk(boolean last) throws IOException {
            byte[] ct = seal(key, h, index++, last, buf, n);
            out.write(ByteBuffer.allocate(4).putInt(ct.length).array());
            out.write(ct);
            n = 0;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                flushChunk(true);   // always one last chunk, even an empty one
            } finally {
                Arrays.fill(buf, (byte) 0);
                out.close();
            }
        }
    }

    // ---- Open -------------------------------------------------------------

    public static Header readHeader(InputStream in) throws IOException {
        byte[] raw = new byte[HEADER_LEN];
        try {
            new DataInputStream(in).readFully(raw);
        } catch (EOFException e) {
            throw new IOException("not a Drive Assist backup");
        }
        ByteBuffer b = ByteBuffer.wrap(raw);
        byte[] magic = new byte[MAGIC.length];
        b.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("not a Drive Assist backup");
        int format = b.get();
        if (format != FORMAT) throw new IOException("backup format " + format + " is from a newer Drive Assist");
        int rounds = b.getInt();
        if (rounds < 1 || rounds > 50_000_000) throw new IOException("damaged backup header");
        byte[] salt = new byte[SALT_LEN];
        byte[] prefix = new byte[PREFIX_LEN];
        b.get(salt).get(prefix);
        return new Header(rounds, salt, prefix, raw);
    }

    /** Reads the header of a backup file without opening it. */
    public static Header readHeader(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readHeader(in); }
    }

    /** The plain bytes, after readHeader(in) gave h. Throws WrongPassword
     * when the first chunk does not open, IOException when a later one does
     * not or the file stops before its last chunk. */
    public static InputStream decrypt(InputStream in, Header h, byte[] key) {
        return new Dec(new DataInputStream(in), h, key);
    }

    private static final class Dec extends InputStream {
        private final DataInputStream in;
        private final Header h;
        private final byte[] key;
        private byte[] plain = new byte[0];
        private int pos;
        private int index;
        private boolean done;

        Dec(DataInputStream in, Header h, byte[] key) { this.in = in; this.h = h; this.key = key; }

        private boolean fill() throws IOException {
            while (pos == plain.length) {
                if (done) return false;
                int len;
                byte[] ct;
                try {
                    len = in.readInt();
                    if (len < TAG_BITS / 8 || len > CHUNK + TAG_BITS / 8) throw new IOException("damaged backup");
                    ct = new byte[len];
                    in.readFully(ct);
                } catch (EOFException e) {
                    throw new IOException("backup is cut short");
                }
                // The last flag is not stored: try "not last" first, then "last".
                byte[] p = open(key, h, index, false, ct);
                if (p == null) {
                    p = open(key, h, index, true, ct);
                    if (p == null) {
                        if (index == 0) throw new WrongPassword();
                        throw new IOException("damaged backup (chunk " + index + ")");
                    }
                    done = true;
                    if (in.read() != -1) throw new IOException("damaged backup (data after the end)");
                }
                index++;
                plain = p;
                pos = 0;
            }
            return true;
        }

        @Override public int read() throws IOException {
            if (!fill()) return -1;
            return plain[pos++] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int k = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, k);
            pos += k;
            return k;
        }

        @Override public void close() throws IOException { in.close(); }
    }

    // ---- AES-GCM ----------------------------------------------------------

    private static byte[] nonce(Header h, int index) {
        return ByteBuffer.allocate(PREFIX_LEN + 4).put(h.prefix).putInt(index).array();
    }

    private static byte[] aad(Header h, boolean last) {
        byte[] a = Arrays.copyOf(h.raw, h.raw.length + 1);
        a[h.raw.length] = (byte) (last ? 1 : 0);
        return a;
    }

    private static byte[] seal(byte[] key, Header h, int index, boolean last, byte[] p, int len) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce(h, index)));
            c.updateAAD(aad(h, last));
            return c.doFinal(p, 0, len);
        } catch (GeneralSecurityException e) {
            throw new IOException("encrypt: " + e, e);
        }
    }

    /** null when the tag does not match. */
    private static byte[] open(byte[] key, Header h, int index, boolean last, byte[] ct) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce(h, index)));
            c.updateAAD(aad(h, last));
            return c.doFinal(ct);
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IOException("decrypt: " + e, e);
        }
    }

    // ---- On a computer: tools/backup-open.sh --------------------------------

    /** java BackupCrypto <file.dabackup> <out-dir>: asks the password and
     * unpacks settings.json, car.db and the certificates into out-dir. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: backup-open.sh <file.dabackup> <out-dir>");
            System.exit(2);
        }
        char[] pw = System.getenv("BACKUP_PASSWORD") != null
            ? System.getenv("BACKUP_PASSWORD").toCharArray()
            : (System.console() != null ? System.console().readPassword("Backup password: ") : null);
        if (pw == null) { System.err.println("no password (no terminal, no BACKUP_PASSWORD)"); System.exit(2); }
        File out = new File(args[1]);
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot create " + out);
        try (InputStream raw = new FileInputStream(args[0])) {
            Header h = readHeader(raw);
            byte[] key = deriveKey(pw, h.salt, h.rounds);
            for (String name : unzip(decrypt(raw, h, key), out)) System.out.println(new File(out, name));
        } catch (WrongPassword e) {
            System.err.println("Wrong password.");
            System.exit(1);
        }
    }

    /** Unpacks a backup's zip into dir. Only plain file names: a name with a
     * path in it is refused. Returns the names written. */
    public static java.util.List<String> unzip(InputStream plain, File dir) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(plain)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || name.isEmpty() || name.contains("/") || name.contains("\\")
                        || name.startsWith(".")) {
                    throw new IOException("unexpected entry in backup: " + name);
                }
                try (OutputStream o = new FileOutputStream(new File(dir, name))) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
                names.add(name);
            }
        }
        return names;
    }
}
