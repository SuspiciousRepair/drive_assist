package com.geely.drivemem.hvac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Learn mode for the comfort ruler: the table follows the driver's own hand.
 *
 * The signal is a change made by hand on the car's own climate panel after
 * the ruler set a column exactly, then held for {@link #HOLD_MS}. That is how
 * the C3 fan step was found (2026-11-17: fan 3 at C3's setpoint and recirc,
 * held 40 and 20 minutes). Each held change is a "hold": one count for each
 * changed lever's value, in the column it is nearest to.
 *
 * The ruler itself changes once a week ({@link #PERIOD_MS}), never mid-drive:
 * with the climate off, {@link #decide} takes, per column and lever, the value
 * held most often that week, if it was held at least {@link #MIN_HOLDS} times
 * and more often than any other value. Then the week's holds are cleared.
 *
 * What is learned is per column AND per weather band: the key is the level
 * plus the number of free (outside-air) columns on that side, so a change
 * made at 26 C changes C3 only where C3 means the same thing (24-28 C).
 *
 * Limits, so the table stays a ruler:
 *  - only fan, setpoint, air direction and recirculation are learned; the
 *    machine (AC) on/off and the pinned columns (0, C5, W5) never change;
 *  - a hand state more than {@link #MAX_DIFFS} levers away from its nearest
 *    column is not a tweak of it: it is logged and ignored;
 *  - a learned value is used only if the column stays between its
 *    neighbours and every column still reads back as itself.
 *
 * Pure Java (no Android), so the rules are unit-tested.
 */
public final class ComfortLearn {
    private ComfortLearn() {}

    static final long HOLD_MS = 10 * 60_000L;
    // The ruler's beat is 30 s (10 s with the screen open). A longer gap is a
    // suspend: the hold starts again, so a parked night never counts as "held".
    static final long MAX_GAP_MS = 2 * 60_000L;
    static final long PERIOD_MS = 7L * 24 * 60 * 60_000L;
    static final int MIN_HOLDS = 2;
    static final int MAX_DIFFS = 2;
    static final int LEARN_MAX_LEVEL = EffortTable.MAX_LEVEL - 1;

    /** The car's climate levers as read. */
    public static final class State {
        final boolean machine; final float sp; final int fan; final int dir; final boolean recirc;

        public State(boolean machine, float sp, int fan, int dir, boolean recirc) {
            this.machine = machine; this.sp = sp; this.fan = fan; this.dir = dir; this.recirc = recirc;
        }

        boolean same(State o) {
            if (o == null) return false;
            if (machine != o.machine || fan != o.fan || dir != o.dir || recirc != o.recirc) return false;
            if (Float.isNaN(sp) || Float.isNaN(o.sp)) return Float.isNaN(sp) == Float.isNaN(o.sp);
            return Math.abs(sp - o.sp) < 0.5f;
        }
    }

    /** Learned values for one column. null = the table's own value. */
    public static final class Patch {
        public Integer fan; public Float sp; public Integer dir; public Boolean rec;
        boolean empty() { return fan == null && sp == null && dir == null && rec == null; }
    }

    /** The learned values, this week's holds, and the dates. Stored as text. */
    public static final class Book {
        final Map<String, Patch> learned = new TreeMap<>();
        // "<key>/<lever>" -> value -> holds this week
        final Map<String, Map<String, Integer>> holds = new TreeMap<>();
        long periodStartMs = 0, lastChangeMs = 0;

        public boolean isEmpty() { return learned.isEmpty(); }

        /** Learned columns, keyed "<level>@<free>". */
        public Map<String, Patch> learned() { return learned; }

        /** When the ruler last changed; 0 = never. */
        public long lastChangeMs() { return lastChangeMs; }

        /** When this week's decision is due; 0 = not started. */
        public long nextDecisionMs() { return periodStartMs == 0 ? 0 : periodStartMs + PERIOD_MS; }

        /** Holds counted this week. */
        public int holdCount() {
            int n = 0;
            for (Map<String, Integer> m : holds.values()) for (int c : m.values()) n += c;
            return n;
        }

        public String encode() {
            StringBuilder b = new StringBuilder();
            b.append("S ").append(periodStartMs).append('\n');
            b.append("C ").append(lastChangeMs).append('\n');
            for (Map.Entry<String, Patch> e : learned.entrySet()) {
                Patch p = e.getValue();
                b.append("L ").append(e.getKey());
                if (p.fan != null) b.append(" fan=").append(p.fan);
                if (p.sp  != null) b.append(" sp=").append(p.sp);
                if (p.dir != null) b.append(" dir=").append(p.dir);
                if (p.rec != null) b.append(" rec=").append(p.rec ? 1 : 0);
                b.append('\n');
            }
            for (Map.Entry<String, Map<String, Integer>> e : holds.entrySet()) {
                for (Map.Entry<String, Integer> v : e.getValue().entrySet()) {
                    b.append("H ").append(e.getKey()).append(' ').append(v.getKey())
                     .append(' ').append(v.getValue()).append('\n');
                }
            }
            return b.toString();
        }

        /** Never throws: a line it cannot read is dropped. */
        public static Book decode(String s) {
            Book b = new Book();
            if (s == null) return b;
            for (String line : s.split("\n")) {
                String[] t = line.trim().split(" ");
                try {
                    if (t.length == 2 && t[0].equals("S")) {
                        b.periodStartMs = Long.parseLong(t[1]);
                    } else if (t.length == 2 && t[0].equals("C")) {
                        b.lastChangeMs = Long.parseLong(t[1]);
                    } else if (t.length >= 2 && t[0].equals("L")) {
                        Patch p = new Patch();
                        for (int i = 2; i < t.length; i++) {
                            String[] kv = t[i].split("=", 2);
                            if (kv.length != 2) continue;
                            switch (kv[0]) {
                                case "fan": p.fan = Integer.parseInt(kv[1]); break;
                                case "sp":  p.sp  = Float.parseFloat(kv[1]); break;
                                case "dir": p.dir = Integer.parseInt(kv[1]); break;
                                case "rec": p.rec = kv[1].equals("1"); break;
                                default: break;
                            }
                        }
                        if (!p.empty() && parseKey(t[1]) != null) b.learned.put(t[1], p);
                    } else if (t.length == 4 && t[0].equals("H")) {
                        int n = Integer.parseInt(t[3]);
                        if (n > 0 && t[1].contains("/")) {
                            b.holds.computeIfAbsent(t[1], x -> new TreeMap<>()).put(t[2], n);
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
            return b;
        }
    }

    // ------------------------------------------------------------ the table

    static String key(int level, int free) { return level + "@" + free; }

    /** The table for this weather, with what was learned for it. book == null is the default. */
    public static EffortTable.Column[] build(float outC, Book book) {
        EffortTable.Column[] base = EffortTable.build(outC);
        if (book == null || book.learned.isEmpty()) return base;
        EffortTable.Column[] t = base.clone();
        for (int k = 1; k <= LEARN_MAX_LEVEL; k++) {
            for (int level : new int[]{k, -k}) {
                Patch p = book.learned.get(key(level, EffortTable.freeColumns(outC, level > 0)));
                if (p == null) continue;
                EffortTable.Column[] cand = t.clone();
                cand[EffortTable.MAX_LEVEL - level] = patched(EffortTable.at(base, level), p);
                if (keepsOrder(t, cand, outC, level)) t = cand;
            }
        }
        return t;
    }

    static EffortTable.Column patched(EffortTable.Column c, Patch p) {
        return new EffortTable.Column(c.level, c.machine,
            c.machine && p.sp != null ? p.sp : c.setpointC,
            p.fan != null ? p.fan : c.fan,
            p.dir != null ? p.dir : c.direction,
            p.rec != null ? p.rec : c.recirc);
    }

    static boolean selfFits(EffortTable.Column[] t, float outC, int level) {
        EffortTable.Column c = EffortTable.at(t, level);
        return EffortTable.fit(t, outC, c.power, c.machine, c.setpointC, c.fan, c.direction, c.recirc) == level;
    }

    /**
     * The changed column still sits between its neighbours (fan never softer
     * than the level below it nor stronger than the level above it, setpoint
     * likewise), reads back as itself, and no other column stops doing so.
     */
    static boolean keepsOrder(EffortTable.Column[] before, EffortTable.Column[] after, float outC, int changed) {
        int side = changed > 0 ? 1 : -1, k = Math.abs(changed);
        EffortTable.Column c = EffortTable.at(after, changed);
        EffortTable.Column in  = EffortTable.at(after, side * (k - 1));
        EffortTable.Column out = EffortTable.at(after, side * (k + 1));
        if (in.fan > c.fan || c.fan > out.fan) return false;
        if (c.machine) {
            // Cold side: a stronger level is a lower setpoint. Warm side: higher.
            if (in.machine && side * (c.setpointC - in.setpointC) > 0) return false;
            if (out.machine && side * (out.setpointC - c.setpointC) > 0) return false;
        }
        if (!selfFits(after, outC, changed)) return false;
        for (int lv = -EffortTable.MAX_LEVEL; lv <= EffortTable.MAX_LEVEL; lv++) {
            if (selfFits(before, outC, lv) && !selfFits(after, outC, lv)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ the holds

    /** Levers where the state differs from the column, as "lever=value". Machine is not a lever here. */
    static List<String> diffs(EffortTable.Column c, State s) {
        List<String> d = new ArrayList<>();
        if (c.fan != s.fan) d.add("fan=" + s.fan);
        if (c.machine && s.machine && !Float.isNaN(s.sp) && Math.abs(c.setpointC - s.sp) >= 0.5f) {
            d.add("sp=" + (Math.round(s.sp * 2f) / 2f));
        }
        if (c.direction != s.dir) d.add("dir=" + s.dir);
        if (c.recirc != s.recirc) d.add("rec=" + (s.recirc ? 1 : 0));
        return d;
    }

    /**
     * The column a hand-set state is a tweak of: on the fitted side, the one
     * with the fewest different levers (a different machine counts as many).
     * Ties go to the column the ruler last set, else the fitted one.
     */
    static int nearest(EffortTable.Column[] t, int fitted, int lastApplied, State s) {
        if (fitted == 0) return 0;
        int side = fitted > 0 ? 1 : -1;
        int best = 0, bestN = Integer.MAX_VALUE;
        for (int k = 1; k <= EffortTable.MAX_LEVEL; k++) {
            int lv = side * k;
            EffortTable.Column c = EffortTable.at(t, lv);
            int n = diffs(c, s).size() + (c.machine != s.machine ? 10 : 0);
            boolean better = n < bestN
                || (n == bestN && lv == lastApplied)
                || (n == bestN && best != lastApplied && Math.abs(lv - fitted) < Math.abs(best - fitted));
            if (better) { best = lv; bestN = n; }
        }
        return best;
    }

    /**
     * Counts one held hand state. Returns the climate-log note: "hold ..." when
     * counted, "skip ..." when it is not a tweak of any learnable column, or
     * null when there is nothing to say (pinned column, nothing different).
     * {@code t} is the table in use. The ruler does not change here.
     */
    public static String hold(Book book, EffortTable.Column[] t, float outC, long nowMs,
                              int fitted, int lastApplied, State s) {
        int lv = nearest(t, fitted, lastApplied, s);
        if (lv == 0 || Math.abs(lv) > LEARN_MAX_LEVEL) return null;
        EffortTable.Column c = EffortTable.at(t, lv);
        String where = EffortTable.name(lv) + "@" + EffortTable.freeColumns(outC, lv > 0);
        if (c.machine != s.machine) return "skip " + where + " ac";
        List<String> d = diffs(c, s);
        if (d.isEmpty()) return null;
        if (d.size() > MAX_DIFFS) return "skip " + where + " " + String.join(" ", d);

        if (book.periodStartMs == 0) book.periodStartMs = nowMs;
        String k = key(lv, EffortTable.freeColumns(outC, lv > 0));
        StringBuilder note = new StringBuilder("hold ").append(where);
        for (String lever : d) {
            String[] kv = lever.split("=", 2);
            Map<String, Integer> m = book.holds.computeIfAbsent(k + "/" + kv[0], x -> new TreeMap<>());
            int n = m.getOrDefault(kv[1], 0) + 1;
            m.put(kv[1], n);
            note.append(' ').append(lever).append(" x").append(n);
        }
        return note.toString();
    }

    /** What a weekly decision did. */
    public static final class Outcome {
        public final List<String> notes = new ArrayList<>();
        public boolean changed;
    }

    /**
     * The weekly decision. Does nothing before the week is over. Otherwise:
     * per column and lever, the value held most often (at least
     * {@link #MIN_HOLDS} times, and more than any other value) is learned,
     * if the table can use it; then the week's holds are cleared and the
     * next week starts now.
     */
    public static Outcome decide(Book book, long nowMs) {
        Outcome o = new Outcome();
        if (book.periodStartMs == 0) { book.periodStartMs = nowMs; return o; }
        if (nowMs - book.periodStartMs < PERIOD_MS && nowMs >= book.periodStartMs) return o;

        for (Map.Entry<String, Map<String, Integer>> e : book.holds.entrySet()) {
            String[] kl = e.getKey().split("/", 2);
            int[] k = parseKey(kl[0]);
            if (k == null || kl.length != 2) continue;
            String best = null; int bestN = 0; boolean tie = false;
            for (Map.Entry<String, Integer> v : e.getValue().entrySet()) {
                if (v.getValue() > bestN) { best = v.getKey(); bestN = v.getValue(); tie = false; }
                else if (v.getValue() == bestN) tie = true;
            }
            String where = EffortTable.name(k[0]) + "@" + k[1] + " " + kl[1] + "=" + best;
            if (best == null || bestN < MIN_HOLDS || tie) {
                o.notes.add("undecided " + where + " x" + bestN + (tie ? " tie" : ""));
                continue;
            }
            float outC = bandOutC(k[0], k[1]);
            EffortTable.Column[] t = build(outC, book);
            EffortTable.Column def = EffortTable.at(EffortTable.build(outC), k[0]);
            Patch p = copy(book.learned.get(kl[0]));
            set(p, kl[1], best, def);
            EffortTable.Column[] cand = t.clone();
            cand[EffortTable.MAX_LEVEL - k[0]] = patched(def, p);
            if (!keepsOrder(t, cand, outC, k[0])) {
                o.notes.add("refuse " + where + " x" + bestN);
                continue;
            }
            if (p.empty()) book.learned.remove(kl[0]); else book.learned.put(kl[0], p);
            o.changed = true;
            o.notes.add("learn " + where + " x" + bestN);
        }
        book.holds.clear();
        book.periodStartMs = nowMs;
        if (o.changed) book.lastChangeMs = nowMs;
        if (o.notes.isEmpty()) o.notes.add("week: nothing to learn");
        return o;
    }

    private static Patch copy(Patch p) {
        Patch c = new Patch();
        if (p != null) { c.fan = p.fan; c.sp = p.sp; c.dir = p.dir; c.rec = p.rec; }
        return c;
    }

    // A value equal to the table's own is stored as "not learned", so a later
    // change of the default still reaches this car.
    private static void set(Patch p, String lever, String v, EffortTable.Column def) {
        switch (lever) {
            case "fan": { int x = Integer.parseInt(v); p.fan = x == def.fan ? null : x; break; }
            case "sp":  { float x = Float.parseFloat(v);
                          p.sp = def.machine && Math.abs(x - def.setpointC) < 0.5f ? null : x; break; }
            case "dir": { int x = Integer.parseInt(v); p.dir = x == def.direction ? null : x; break; }
            case "rec": { boolean x = v.equals("1"); p.rec = x == def.recirc ? null : x; break; }
            default: break;
        }
    }

    // ------------------------------------------------------------ the hold

    /** Waits for one hand state to be held {@link #HOLD_MS} without a suspend in between.
     * A state counts once per drive: a short break in the hold (a defrost
     * press, a passenger's lever moved and back, one failed read) starts the
     * 10 minutes again but does not count the same hand change a second time.
     * Only a suspend (no beat for {@link #MAX_GAP_MS}) lets it count again. */
    public static final class Watcher {
        private State held, voted;
        private long since, lastSeen;

        /** The state to count, once per hold; null otherwise. */
        public State observe(long now, State s, boolean eligible) {
            boolean gap = now - lastSeen > MAX_GAP_MS || now < lastSeen;
            lastSeen = now;
            if (gap) voted = null;
            if (!eligible || s == null) { held = null; return null; }
            if (held == null || !held.same(s) || gap) { held = s; since = now; }
            if (voted != null && voted.same(held)) return null;
            if (now - since >= HOLD_MS) { voted = held; return held; }
            return null;
        }
    }

    // ------------------------------------------------------------ bands

    /** The outside temperature band a key's free-column count stands for: {low, high}, NaN = open. */
    public static float[] band(int level, int free) {
        float nan = Float.NaN;
        if (level > 0) {
            float f = EffortTable.FRESH_LIMIT_C;
            if (free == 0) return new float[]{f, nan};
            if (free >= 3) return new float[]{nan, f - 8f};
            return new float[]{f - 4f * free, f - 4f * (free - 1)};
        }
        float c = EffortTable.CABIN_TARGET_C;
        if (free == 0) return new float[]{nan, c};
        if (free >= 3) return new float[]{c + 4f, nan};
        return new float[]{c + 2f * (free - 1), c + 2f * free};
    }

    /** One outside temperature inside a band, to check a learned value against its table. */
    static float bandOutC(int level, int free) {
        float[] b = band(level, free);
        if (Float.isNaN(b[0])) return b[1] - 2f;
        if (Float.isNaN(b[1])) return b[0] + 2f;
        return (b[0] + b[1]) / 2f;
    }

    /** Parses "<level>@<free>"; null if it is not one. */
    public static int[] parseKey(String k) {
        try {
            String[] p = k.split("@");
            if (p.length != 2) return null;
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (RuntimeException e) { return null; }
    }
}
