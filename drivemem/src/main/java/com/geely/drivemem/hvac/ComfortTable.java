package com.geely.drivemem.hvac;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The comfort ruler's table as data: the one thing the ruler reads.
 *
 * WHY A TABLE AND NOT THE RULES: EffortTable's rules made every column
 * from the exact outside temperature, so a setpoint moved inside one
 * weather band, sometimes the wrong way (C4 went 18, 19, 18, 19, 20, 19 C
 * from 20 to 25 C outside, 2026-11-18). Here each weather band has one
 * fixed set of columns, and it can be read, shown and edited.
 *
 * Shape: per side (cold C1..C5, warm W1..W5), four weather bands, keyed by
 * how many fan-only ("free") columns that side has at that outside
 * temperature (EffortTable.freeColumns, 0..3). Column 0 (off) is not in
 * here: it is always the climate off.
 *
 * Who writes it: the code. The defaults ({@link #defaults()}, the rules at
 * one temperature inside each band) on the first start, then the weekly
 * learning changes a column in place (ComfortLearn.decide). A person may
 * edit the file by hand; the ruler does not care who wrote a value. A file
 * that is missing, cannot be read, or breaks the table's rules is written
 * again from the defaults, and what was learned is tried again on top
 * ({@link #resolve}). So is a file of another {@link #VERSION}: that is how
 * a software update replaces the table.
 *
 * Pure (org.json only), so the rules are unit-tested.
 */
public final class ComfortTable {

    /** Bump when the default columns change. A file of another version is
     * written again from the new defaults, then the learned values are
     * tried again. */
    public static final int VERSION = 1;
    public static final int BANDS = 4;
    private static final int K = EffortTable.MAX_LEVEL;

    public final int version;
    // [0 = cold, 1 = warm][free 0..3][k - 1], k = 1..5
    private final EffortTable.Column[][][] cols;

    private ComfortTable(int version, EffortTable.Column[][][] cols) {
        this.version = version;
        this.cols = cols;
    }

    private static int side(int level) { return level > 0 ? 0 : 1; }

    /** The column for a level (+-1..5) in a band. */
    public EffortTable.Column get(int level, int free) {
        return cols[side(level)][free][Math.abs(level) - 1];
    }

    /** A copy with one column replaced. */
    public ComfortTable with(int level, int free, EffortTable.Column c) {
        EffortTable.Column[][][] n = new EffortTable.Column[2][BANDS][];
        for (int s = 0; s < 2; s++) for (int f = 0; f < BANDS; f++) n[s][f] = cols[s][f].clone();
        n[side(level)][free][Math.abs(level) - 1] = c;
        return new ComfortTable(version, n);
    }

    /** The 11 columns for this outside temperature: each side from its own band. */
    public EffortTable.Column[] build(float outC) {
        EffortTable.Column[] t = new EffortTable.Column[2 * K + 1];
        int fc = EffortTable.freeColumns(outC, true), fw = EffortTable.freeColumns(outC, false);
        for (int k = 1; k <= K; k++) {
            t[K - k] = cols[0][fc][k - 1];
            t[K + k] = cols[1][fw][k - 1];
        }
        t[K] = EffortTable.column(outC, 0);
        return t;
    }

    // ------------------------------------------------------------ defaults

    private static ComfortTable defaultsCache;

    /** The rules (EffortTable.column) at one temperature inside each band. */
    public static synchronized ComfortTable defaults() {
        if (defaultsCache == null) {
            EffortTable.Column[][][] c = new EffortTable.Column[2][BANDS][K];
            for (int f = 0; f < BANDS; f++) {
                for (int k = 1; k <= K; k++) {
                    c[0][f][k - 1] = EffortTable.column(ComfortLearn.bandOutC(k, f), k);
                    c[1][f][k - 1] = EffortTable.column(ComfortLearn.bandOutC(-k, f), -k);
                }
            }
            defaultsCache = new ComfortTable(VERSION, c);
        }
        return defaultsCache;
    }

    // ------------------------------------------------------------ the file

    /** What to use, and whether the file must be written again (and why). */
    public static final class Resolved {
        public final ComfortTable table;
        public final boolean write;
        public final String why;
        Resolved(ComfortTable table, boolean write, String why) {
            this.table = table; this.write = write; this.why = why;
        }
    }

    /** The table in this file text, or, when there is none, it is bad or of
     * another version: the defaults with the book's learned values tried
     * again on top, to be written back. text == null: no file. */
    public static Resolved resolve(String text, ComfortLearn.Book book) {
        String why;
        if (text == null) {
            why = "no file";
        } else {
            try {
                ComfortTable t = fromJson(text);
                if (t.version == VERSION) return new Resolved(t, false, null);
                why = "version " + t.version + ", now " + VERSION;
            } catch (Exception e) {
                why = "bad file: " + e.getMessage();
            }
        }
        return new Resolved(ComfortLearn.replay(book, defaults()), true, why);
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", version);
            root.put("about", "Drive Assist climate ruler. C1..C5 cool, W1..W5 heat; one group per "
                + "outside temperature band. ac: the AC compressor; sp: its setpoint (C); fan 1..8; "
                + "dir: face, feet, face+feet, glass, glass+feet; rec: recirculation. Written by the "
                + "app (defaults, then the weekly learning). A file that cannot be used is written "
                + "again from the defaults.");
            root.put("cold", sideJson(1));
            root.put("warm", sideJson(-1));
            return root.toString(2);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONArray sideJson(int sign) throws Exception {
        JSONArray a = new JSONArray();
        for (int f = 0; f < BANDS; f++) {
            JSONObject band = new JSONObject();
            band.put("outside", bandLabel(sign, f));
            band.put("fan_only_columns", f);
            for (int k = 1; k <= K; k++) band.put(EffortTable.name(sign * k), col(get(sign * k, f)));
            a.put(band);
        }
        return a;
    }

    private static JSONObject col(EffortTable.Column c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ac", c.machine);
        if (c.machine) o.put("sp", (double) c.setpointC);
        o.put("fan", c.fan);
        o.put("dir", EffortTable.dirName(c.direction));
        o.put("rec", c.recirc);
        return o;
    }

    /** "20-24 C", "below 20 C", "28 C and up". */
    static String bandLabel(int sign, int free) {
        float[] b = ComfortLearn.band(sign, free);
        if (Float.isNaN(b[0])) return "below " + (int) b[1] + " C";
        if (Float.isNaN(b[1])) return (int) b[0] + " C and up";
        return (int) b[0] + "-" + (int) b[1] + " C";
    }

    /** Reads a file. Throws on anything the ruler cannot use: a missing or
     * wrong value, AC on a fan-only column (or off on an AC one), a cold
     * setpoint on the warm side, or a column that does not read back as
     * itself. A file of another version is returned without the checks
     * (it is replaced anyway). */
    static ComfortTable fromJson(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        int version = root.getInt("version");
        if (version != VERSION) return new ComfortTable(version, defaults().cols);
        EffortTable.Column[][][] c = new EffortTable.Column[2][BANDS][K];
        readSide(root.getJSONArray("cold"), 1, c[0]);
        readSide(root.getJSONArray("warm"), -1, c[1]);
        ComfortTable t = new ComfortTable(version, c);
        for (int f = 0; f < BANDS; f++) {
            for (int sign : new int[]{1, -1}) {
                float out = ComfortLearn.bandOutC(sign, f);
                EffortTable.Column[] built = t.build(out);
                for (int k = 1; k <= K; k++) {
                    if (!ComfortLearn.selfFits(built, out, sign * k)) {
                        throw new IllegalArgumentException(EffortTable.name(sign * k)
                            + " in " + bandLabel(sign, f) + " does not read back as itself");
                    }
                }
            }
        }
        return t;
    }

    private static void readSide(JSONArray a, int sign, EffortTable.Column[][] out) throws Exception {
        if (a.length() != BANDS) throw new IllegalArgumentException("need " + BANDS + " bands");
        for (int f = 0; f < BANDS; f++) {
            JSONObject band = a.getJSONObject(f);
            if (band.has("fan_only_columns") && band.getInt("fan_only_columns") != f) {
                throw new IllegalArgumentException("bands out of order");
            }
            for (int k = 1; k <= K; k++) {
                String name = EffortTable.name(sign * k);
                out[f][k - 1] = readCol(band.getJSONObject(name), sign * k, f, name);
            }
        }
    }

    private static EffortTable.Column readCol(JSONObject o, int level, int free, String name) throws Exception {
        boolean machine = o.getBoolean("ac");
        if (machine != (Math.abs(level) > free)) {
            throw new IllegalArgumentException(name + ": ac must be " + !machine + " in this band");
        }
        float sp = Float.NaN;
        if (machine) {
            sp = (float) o.getDouble("sp");
            if (!(sp >= EffortTable.TEMP_MIN && sp <= EffortTable.TEMP_MAX)) {
                throw new IllegalArgumentException(name + ": sp " + sp);
            }
            // The side is read from the setpoint (EffortTable.sideIsCold).
            if ((level > 0) != (sp < EffortTable.MIDDLE_C)) {
                throw new IllegalArgumentException(name + ": sp " + sp + " is on the other side");
            }
        }
        int fan = o.getInt("fan");
        if (fan < 1 || fan > 8) throw new IllegalArgumentException(name + ": fan " + fan);
        int dir = dirOf(o.getString("dir"));
        if (dir < 0) throw new IllegalArgumentException(name + ": dir " + o.getString("dir"));
        return new EffortTable.Column(level, machine, sp, fan, dir, o.getBoolean("rec"));
    }

    private static int dirOf(String s) {
        for (int d : new int[]{EffortTable.DIR_FACE, EffortTable.DIR_FEET, EffortTable.DIR_FACE_FEET,
                               EffortTable.DIR_GLASS, EffortTable.DIR_GLASS_FEET}) {
            if (EffortTable.dirName(d).equals(s)) return d;
        }
        return -1;
    }

    /** Same columns, ignoring the version. */
    public boolean sameColumns(ComfortTable o) {
        for (int s = 0; s < 2; s++) for (int f = 0; f < BANDS; f++) for (int k = 0; k < K; k++) {
            if (!cols[s][f][k].toString().equals(o.cols[s][f][k].toString())) return false;
        }
        return true;
    }
}
