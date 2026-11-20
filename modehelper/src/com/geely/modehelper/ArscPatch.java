package com.geely.modehelper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replaces string values in an app's resources.arsc, one language at a time.
 * Used by {@link OemText} to fix the text of the car's own apps.
 *
 * The rules (owner, 2026-11-19):
 *  - each language only touches its own slots (values-pt, values-pt-rBR, ...);
 *  - when the app has no value for a name in that language, one is ADDED to
 *    the plain slot of that language (values-pt), if the app has that slot;
 *  - the default slot (the fallback, values/) is never touched.
 *
 * A name the app does not have, or a language the app has no slot for, is
 * skipped and counted as missing: the same translation file then works on
 * an older or newer version of the app.
 *
 * Only string values change. Every other byte of the table is kept; the
 * global string pool grows, and the chunk sizes are rewritten to match.
 * Port of tools/patch_arsc.py in the integration-oem-translations repo; the
 * two must give the same bytes for the same input.
 *
 * Pure Java, no Android, so tools/verify.sh can test it on the desktop. */
final class ArscPatch {
    private ArscPatch() {}

    static final int NO_ENTRY = 0xffffffff;
    static final int RES_STRING_POOL = 0x0001, RES_TABLE = 0x0002,
            RES_TABLE_PACKAGE = 0x0200, RES_TABLE_TYPE = 0x0201;
    static final int TYPE_STRING = 0x03;
    static final int FLAG_COMPLEX = 0x0001;
    static final int POOL_UTF8 = 0x100, POOL_SORTED = 0x1;

    static final class Result {
        final byte[] arsc;
        int replaced, added, missing;
        final List<String> notes = new ArrayList<>();
        Result(byte[] arsc) { this.arsc = arsc; }
        /** Lines that went into the table: replaced in place, or added. */
        int changed() { return replaced + added; }
    }

    static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
    static void p16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); }
    static void p32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }

    /** A growable byte array: the pool and the chunks grow by appending. */
    static final class Buf {
        byte[] b; int n;
        Buf(byte[] src, int from, int len) {
            b = new byte[Math.max(16, len + len / 4)];
            System.arraycopy(src, from, b, 0, len);
            n = len;
        }
        void add(byte[] x) {
            if (n + x.length > b.length) {
                byte[] nb = new byte[Math.max(b.length * 2, n + x.length)];
                System.arraycopy(b, 0, nb, 0, n);
                b = nb;
            }
            System.arraycopy(x, 0, b, n, x.length);
            n += x.length;
        }
    }

    /** ResStringPool. Strings can be added; styles are kept as they are. */
    static final class Pool {
        final int off, type, hsize, scount;
        int count, flags;
        final boolean utf8;
        int[] offs;
        final int[] soffs;
        final Buf sdata;
        final byte[] ydata;

        Pool(byte[] b, int off) {
            this.off = off;
            type = u16(b, off); hsize = u16(b, off + 2);
            int size = u32(b, off + 4);
            count = u32(b, off + 8); scount = u32(b, off + 12); flags = u32(b, off + 16);
            int sstart = u32(b, off + 20), ystart = u32(b, off + 24);
            if (type != RES_STRING_POOL || hsize != 28) throw new IllegalArgumentException("not a string pool at " + off);
            utf8 = (flags & POOL_UTF8) != 0;
            offs = new int[count];
            for (int i = 0; i < count; i++) offs[i] = u32(b, off + hsize + 4 * i);
            soffs = new int[scount];
            for (int i = 0; i < scount; i++) soffs[i] = u32(b, off + hsize + 4 * count + 4 * i);
            int send = scount != 0 ? off + ystart : off + size;
            sdata = new Buf(b, off + sstart, send - (off + sstart));
            if (scount != 0) {
                ydata = new byte[off + size - (off + ystart)];
                System.arraycopy(b, off + ystart, ydata, 0, ydata.length);
            } else {
                ydata = new byte[0];
            }
        }

        String get(int i) {
            byte[] d = sdata.b;
            int o = offs[i];
            if (utf8) {
                o += (d[o] & 0x80) != 0 ? 2 : 1;            // length in UTF-16 units
                int n = d[o] & 0xff;
                if ((n & 0x80) != 0) { n = ((n & 0x7f) << 8) | (d[o + 1] & 0xff); o += 2; } else { o += 1; }
                return new String(d, o, n, StandardCharsets.UTF_8);
            }
            int n = u16(d, o); o += 2;
            if ((n & 0x8000) != 0) { n = ((n & 0x7fff) << 16) | u16(d, o); o += 2; }
            return new String(d, o, 2 * n, StandardCharsets.UTF_16LE);
        }

        int add(String s) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            if (utf8) {
                byte[] e = s.getBytes(StandardCharsets.UTF_8);
                int c = s.length();                          // UTF-16 units, as Java counts
                if (c > 0x7fff || e.length > 0x7fff) throw new IllegalArgumentException("string too long");
                len8(raw, c); len8(raw, e.length);
                raw.write(e, 0, e.length); raw.write(0);
            } else {
                byte[] e = s.getBytes(StandardCharsets.UTF_16LE);
                int n = e.length / 2;
                if (n < 0x8000) { raw.write(n & 0xff); raw.write(n >>> 8); }
                else {
                    int hi = 0x8000 | (n >>> 16);
                    raw.write(hi & 0xff); raw.write(hi >>> 8); raw.write(n & 0xff); raw.write((n >>> 8) & 0xff);
                }
                raw.write(e, 0, e.length); raw.write(0); raw.write(0);
            }
            if (count == offs.length) {
                int[] no = new int[Math.max(8, offs.length * 2)];
                System.arraycopy(offs, 0, no, 0, count);
                offs = no;
            }
            offs[count] = sdata.n;
            sdata.add(raw.toByteArray());
            count++;
            // An added string is not in sorted order.
            flags &= ~POOL_SORTED;
            return count - 1;
        }

        private static void len8(ByteArrayOutputStream o, int n) {
            if (n < 0x80) o.write(n);
            else { o.write(0x80 | (n >>> 8)); o.write(n & 0xff); }
        }

        byte[] build() {
            int pad = (-sdata.n) & 3;
            int sstart = hsize + 4 * count + 4 * scount;
            int ystart = scount != 0 ? sstart + sdata.n + pad : 0;
            int size = sstart + sdata.n + pad + ydata.length;
            byte[] out = new byte[size];
            p16(out, 0, type); p16(out, 2, hsize); p32(out, 4, size);
            p32(out, 8, count); p32(out, 12, scount); p32(out, 16, flags);
            p32(out, 20, sstart); p32(out, 24, ystart);
            for (int i = 0; i < count; i++) p32(out, hsize + 4 * i, offs[i]);
            for (int i = 0; i < scount; i++) p32(out, hsize + 4 * count + 4 * i, soffs[i]);
            System.arraycopy(sdata.b, 0, out, sstart, sdata.n);
            System.arraycopy(ydata, 0, out, sstart + sdata.n + pad, ydata.length);
            return out;
        }
    }

    /** One ResTable_type chunk: one configuration (language, ...) of the string type. */
    static final class TypeChunk {
        final Buf raw;
        final int hs, n, est;
        final boolean sparse, plain;
        final String lang, country;

        TypeChunk(byte[] b, int from, int len) {
            raw = new Buf(b, from, len);
            byte[] r = raw.b;
            hs = u16(r, 2);
            sparse = (r[9] & 1) != 0;
            n = u32(r, 12); est = u32(r, 16);
            int cfg = 20, csize = u32(r, cfg);
            lang = code(r, cfg + 8); country = code(r, cfg + 10);
            boolean p = true;
            for (int i = cfg + 12; i < cfg + csize; i++) if (r[i] != 0) { p = false; break; }
            plain = p;
        }

        private static String code(byte[] r, int o) {
            if ((r[o] & 0x80) != 0) return "?";             // packed 3-letter code: never one of ours
            StringBuilder s = new StringBuilder(2);
            for (int i = 0; i < 2; i++) if (r[o + i] != 0) s.append((char) (r[o + i] & 0xff));
            return s.toString();
        }

        /** {index, offset of the entry in raw} for every present entry. */
        List<int[]> entries() {
            byte[] r = raw.b;
            List<int[]> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (sparse) {
                    out.add(new int[] { u16(r, hs + 4 * i), est + u16(r, hs + 4 * i + 2) * 4 });
                } else {
                    int o = u32(r, hs + 4 * i);
                    if (o != NO_ENTRY) out.add(new int[] { i, est + o });
                }
            }
            return out;
        }

        /** True when a plain string entry can be added for {@code idx}. */
        boolean canAppend(int idx) {
            return !sparse && idx < n && u32(raw.b, hs + 4 * idx) == NO_ENTRY;
        }

        void append(int idx, int key, int valueIndex) {
            p32(raw.b, hs + 4 * idx, raw.n - est);
            byte[] e = new byte[16];
            p16(e, 0, 8); p16(e, 2, 0); p32(e, 4, key);          // ResTable_entry
            p16(e, 8, 8); e[10] = 0; e[11] = TYPE_STRING; p32(e, 12, valueIndex);  // Res_value
            raw.add(e);
            p32(raw.b, 4, raw.n);
        }

        byte[] bytes() {
            byte[] out = new byte[raw.n];
            System.arraycopy(raw.b, 0, out, 0, raw.n);
            return out;
        }
    }

    /** One package of the table, split into chunks; string type chunks parsed. */
    static final class Pkg {
        final byte[] header;
        final Pool keys;
        final List<Object> chunks = new ArrayList<>();      // TypeChunk or byte[]
        final List<TypeChunk> strs = new ArrayList<>();

        Pkg(byte[] b, int off) {
            int ptype = u16(b, off), phs = u16(b, off + 2), psize = u32(b, off + 4);
            if (ptype != RES_TABLE_PACKAGE) throw new IllegalArgumentException("not a package at " + off);
            header = new byte[phs];
            System.arraycopy(b, off, header, 0, phs);
            Pool types = new Pool(b, off + u32(b, off + 268));
            keys = new Pool(b, off + u32(b, off + 276));
            int c = off + phs;
            while (c < off + psize) {
                int cs = u32(b, c + 4);
                if (u16(b, c) == RES_TABLE_TYPE && "string".equals(types.get((b[c + 8] & 0xff) - 1))) {
                    TypeChunk t = new TypeChunk(b, c, cs);
                    chunks.add(t); strs.add(t);
                } else {
                    byte[] raw = new byte[cs];
                    System.arraycopy(b, c, raw, 0, cs);
                    chunks.add(raw);
                }
                c += cs;
            }
        }

        byte[] build() {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (Object o : chunks) {
                byte[] x = o instanceof TypeChunk ? ((TypeChunk) o).bytes() : (byte[]) o;
                body.write(x, 0, x.length);
            }
            byte[] hdr = header.clone();
            p32(hdr, 4, hdr.length + body.size());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(hdr, 0, hdr.length);
            byte[] bb = body.toByteArray();
            out.write(bb, 0, bb.length);
            return out.toByteArray();
        }
    }

    static final class Table {
        final byte[] head;
        final Pool global;
        final List<Pkg> pkgs = new ArrayList<>();

        Table(byte[] b) {
            if (u16(b, 0) != RES_TABLE) throw new IllegalArgumentException("not a resource table");
            int hs = u16(b, 2);
            head = new byte[hs];
            System.arraycopy(b, 0, head, 0, hs);
            global = new Pool(b, hs);
            int off = hs + u32(b, hs + 4);
            while (off < b.length) {
                pkgs.add(new Pkg(b, off));
                off += u32(b, off + 4);
            }
        }

        byte[] build() {
            byte[] pool = global.build();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<byte[]> ps = new ArrayList<>();
            int total = head.length + pool.length;
            for (Pkg p : pkgs) { byte[] x = p.build(); ps.add(x); total += x.length; }
            byte[] h = head.clone();
            p32(h, 4, total);
            out.write(h, 0, h.length);
            out.write(pool, 0, pool.length);
            for (byte[] x : ps) out.write(x, 0, x.length);
            return out.toByteArray();
        }
    }

    /** {@code tr} = language -> (string name -> text). */
    static Result patch(byte[] arsc, Map<String, Map<String, String>> tr) {
        Table t = new Table(arsc);
        int replaced = 0, added = 0, missing = 0;
        List<String> notes = new ArrayList<>();
        for (Pkg p : t.pkgs) {
            // name -> {entry index, key index}, from every configuration
            Map<String, int[]> where = new HashMap<>();
            for (TypeChunk c : p.strs) {
                for (int[] e : c.entries()) {
                    int k = u32(c.raw.b, e[1] + 4);
                    String name = p.keys.get(k);
                    if (!where.containsKey(name)) where.put(name, new int[] { e[0], k });
                }
            }
            for (Map.Entry<String, Map<String, String>> lt : tr.entrySet()) {
                String lang = lt.getKey();
                List<TypeChunk> mine = new ArrayList<>();
                TypeChunk home = null;
                for (TypeChunk c : p.strs) {
                    if (!c.lang.equals(lang)) continue;
                    mine.add(c);
                    if (home == null && c.plain && c.country.isEmpty()) home = c;
                }
                for (Map.Entry<String, String> nt : lt.getValue().entrySet()) {
                    String name = nt.getKey();
                    int[] w = where.get(name);
                    if (w == null) { missing++; notes.add("MISS " + lang + " " + name + ": no such string"); continue; }
                    int value = -1;
                    boolean hit = false;
                    for (TypeChunk c : mine) {
                        for (int[] e : c.entries()) {
                            if (e[0] != w[0]) continue;
                            byte[] r = c.raw.b;
                            int flags = u16(r, e[1] + 2);
                            int v = e[1] + u16(r, e[1]);
                            if ((flags & FLAG_COMPLEX) != 0 || (r[v + 3] & 0xff) != TYPE_STRING) {
                                notes.add("SKIP " + lang + " " + name + ": not a plain string");
                                continue;
                            }
                            if (value < 0) value = t.global.add(nt.getValue());
                            p32(r, v + 4, value);
                            hit = true;
                        }
                    }
                    if (hit) { replaced++; continue; }
                    if (home == null || !home.canAppend(w[0])) {
                        missing++;
                        notes.add("MISS " + lang + " " + name + ": app has no " + lang + " slot for it");
                        continue;
                    }
                    if (value < 0) value = t.global.add(nt.getValue());
                    home.append(w[0], w[1], value);
                    added++;
                }
            }
        }
        Result r = new Result(t.build());
        r.replaced = replaced; r.added = added; r.missing = missing;
        r.notes.addAll(notes);
        return r;
    }

    /** name -> text of the plain string values of {@code lang} ("" = the
     * default slot), for checks and tests. Country slots are not read. */
    static Map<String, String> strings(byte[] arsc, String lang) {
        Table t = new Table(arsc);
        Map<String, String> out = new LinkedHashMap<>();
        for (Pkg p : t.pkgs) {
            for (TypeChunk c : p.strs) {
                if (!c.lang.equals(lang) || !c.country.isEmpty() || !c.plain) continue;
                for (int[] e : c.entries()) {
                    byte[] r = c.raw.b;
                    if ((u16(r, e[1] + 2) & FLAG_COMPLEX) != 0) continue;
                    int v = e[1] + u16(r, e[1]);
                    if ((r[v + 3] & 0xff) != TYPE_STRING) continue;
                    out.put(p.keys.get(u32(r, e[1] + 4)), t.global.get(u32(r, v + 4)));
                }
            }
        }
        return out;
    }
}
