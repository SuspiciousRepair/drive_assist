package com.geely.modehelper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free test runner for {@link ArscPatch}, on a small resource
 * table built here byte by byte: three strings a, b, c in the default slot,
 * and a "pt" slot that has only a. There is no "es" slot. */
public final class ArscPatchTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    // ---- a tiny resources.arsc writer, independent of ArscPatch

    static void w16(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >>> 8) & 0xff); }
    static void w32(ByteArrayOutputStream o, int v) { w16(o, v & 0xffff); w16(o, v >>> 16); }
    static void p32(byte[] b, int at, int v) {
        b[at] = (byte) v; b[at + 1] = (byte) (v >>> 8); b[at + 2] = (byte) (v >>> 16); b[at + 3] = (byte) (v >>> 24);
    }

    static byte[] pool(boolean utf8, String... ss) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[ss.length];
        for (int i = 0; i < ss.length; i++) {
            offs[i] = data.size();
            if (utf8) {
                byte[] e = ss[i].getBytes(StandardCharsets.UTF_8);
                len8(data, ss[i].length()); len8(data, e.length);
                data.write(e, 0, e.length); data.write(0);
            } else {
                byte[] e = ss[i].getBytes(StandardCharsets.UTF_16LE);
                w16(data, e.length / 2); data.write(e, 0, e.length); w16(data, 0);
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int sstart = 28 + 4 * ss.length;
        w16(o, 0x0001); w16(o, 28); w32(o, sstart + data.size());
        w32(o, ss.length); w32(o, 0); w32(o, utf8 ? 0x100 : 0); w32(o, sstart); w32(o, 0);
        for (int off : offs) w32(o, off);
        byte[] d = data.toByteArray();
        o.write(d, 0, d.length);
        return o.toByteArray();
    }

    static void len8(ByteArrayOutputStream o, int n) {
        if (n < 0x80) o.write(n); else { o.write(0x80 | (n >>> 8)); o.write(n & 0xff); }
    }

    /** A dense string type chunk. values[i] = global pool index, or -1 for no entry. */
    static byte[] type(String lang, int[] values) {
        int n = values.length, hs = 20 + 64;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w16(o, 0x0201); w16(o, hs); w32(o, 0);                 // size patched below
        o.write(1); o.write(0); w16(o, 0);                      // id 1 = "string", flags, reserved
        w32(o, n); w32(o, hs + 4 * n);
        byte[] cfg = new byte[64];
        p32(cfg, 0, 64);
        if (lang != null) { cfg[8] = (byte) lang.charAt(0); cfg[9] = (byte) lang.charAt(1); }
        o.write(cfg, 0, cfg.length);
        int at = 0;
        for (int v : values) { w32(o, v < 0 ? 0xffffffff : at); if (v >= 0) at += 16; }
        for (int i = 0; i < n; i++) {
            if (values[i] < 0) continue;
            w16(o, 8); w16(o, 0); w32(o, i);                    // entry: key index = i
            w16(o, 8); o.write(0); o.write(0x03); w32(o, values[i]);
        }
        byte[] b = o.toByteArray();
        p32(b, 4, b.length);
        return b;
    }

    static byte[] table(boolean utf8) {
        // global pool: 0 A, 1 B, 2 C, 3 A-pt
        byte[] global = pool(utf8, "A", "B", "C", "A velho");
        byte[] types = pool(false, "string");
        byte[] keys = pool(utf8, "a", "b", "c");
        byte[] spec = new byte[16 + 12];
        spec[0] = 0x02; spec[1] = 0x02; spec[2] = 16; p32(spec, 4, spec.length); spec[8] = 1; p32(spec, 12, 3);
        byte[] def = type(null, new int[] { 0, 1, 2 });
        byte[] pt = type("pt", new int[] { 3, -1, -1 });

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] x : new byte[][] { types, keys, spec, def, pt }) body.write(x, 0, x.length);
        byte[] hdr = new byte[288];
        hdr[0] = 0x00; hdr[1] = 0x02; hdr[2] = (byte) (288 & 0xff); hdr[3] = (byte) (288 >>> 8);
        p32(hdr, 4, 288 + body.size());
        p32(hdr, 8, 0x7f);
        p32(hdr, 268, 288);
        p32(hdr, 276, 288 + types.length);

        ByteArrayOutputStream t = new ByteArrayOutputStream();
        w16(t, 0x0002); w16(t, 12); w32(t, 12 + global.length + hdr.length + body.size()); w32(t, 1);
        t.write(global, 0, global.length);
        t.write(hdr, 0, hdr.length);
        byte[] bb = body.toByteArray();
        t.write(bb, 0, bb.length);
        return t.toByteArray();
    }

    static Map<String, Map<String, String>> tr(String... langNameText) {
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        for (int i = 0; i < langNameText.length; i += 3) {
            Map<String, String> l = m.get(langNameText[i]);
            if (l == null) { l = new LinkedHashMap<>(); m.put(langNameText[i], l); }
            l.put(langNameText[i + 1], langNameText[i + 2]);
        }
        return m;
    }

    public static void main(String[] args) {
        String longText = new String(new char[100]).replace("\0", "ç");   // 200 UTF-8 bytes: 2-byte lengths
        for (boolean utf8 : new boolean[] { true, false }) {
            String kind = utf8 ? "utf8" : "utf16";
            byte[] in = table(utf8);
            check(ArscPatch.strings(in, "").get("b").equals("B"), kind + ": the test table must read back");
            check(ArscPatch.strings(in, "pt").get("a").equals("A velho"), kind + ": pt a must read back");

            ArscPatch.Result r = ArscPatch.patch(in, tr(
                "pt", "a", "A novo",
                "pt", "b", longText,
                "pt", "zz", "no such name",
                "es", "a", "A es"));
            check(r.replaced == 1, kind + ": pt a must be replaced, got " + r.replaced);
            check(r.added == 1, kind + ": pt b must be added to the pt slot, got " + r.added);
            check(r.missing == 2, kind + ": an unknown name and a language with no slot are missing, got " + r.missing);

            Map<String, String> def = ArscPatch.strings(r.arsc, "");
            check("A".equals(def.get("a")) && "B".equals(def.get("b")) && "C".equals(def.get("c")) && def.size() == 3,
                kind + ": the default slot must not change: " + def);
            Map<String, String> pt = ArscPatch.strings(r.arsc, "pt");
            check("A novo".equals(pt.get("a")), kind + ": pt a = " + pt.get("a"));
            check(longText.equals(pt.get("b")), kind + ": pt b (long, non-ASCII) must read back");
            check(!pt.containsKey("c"), kind + ": pt c was not asked for and must stay absent");
            check(ArscPatch.strings(r.arsc, "es").isEmpty(), kind + ": no es slot must be made up");

            // Patching the result again with the same text is stable.
            ArscPatch.Result again = ArscPatch.patch(r.arsc, tr("pt", "a", "A novo", "pt", "b", longText));
            check(again.replaced == 2 && again.added == 0, kind + ": second pass must only replace");
            check(ArscPatch.strings(again.arsc, "pt").equals(pt), kind + ": second pass must read the same");

            // Nothing asked: the bytes do not change at all.
            ArscPatch.Result none = ArscPatch.patch(in, tr());
            check(java.util.Arrays.equals(none.arsc, in), kind + ": an empty file must leave the table as it was");
        }
        boolean threw = false;
        try { ArscPatch.patch(new byte[] { 1, 0, 8, 0, 8, 0, 0, 0 }, tr()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "a file that is not a resource table must be refused");
    }
}
