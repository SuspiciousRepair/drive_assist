package com.geely.drivemem.hvac;

/**
 * The comfort ruler's list of marks: C10 (all-out cooling) through 0 (off) to
 * W5 (all-out heating). A mark is one small move from the mark before it, so
 * one tap changes one setting (two at four steps of the cool side and at W5).
 * A mark always means the same thing: the list does not change with the
 * weather or with use. See COMFORT-TABLE.md.
 *
 * Touches no Android API, so it runs in plain unit tests.
 */
public final class EffortTable {

    /** Marks on the cool side (C1..C10) and on the warm side (W1..W5). */
    public static final int COOL_MAX = 10, WARM_MAX = 5;
    public static final float TEMP_MIN = 17f, TEMP_MAX = 32f;
    // Where the setpoint is parked while the AC is off, so a later power-on
    // does not start at full effort.
    public static final float CABIN_TARGET_C = 23f;

    // Air direction. The area is ignored on this unit and 356517121 is an alias
    // of the same value — see field-catalog.md.
    public static final int DIR_PROP = 557846560;
    // The AOSP bitmask. Confirmed in the car; 5 (face+defrost) is REFUSED by
    // this unit, so it is not in here. 6 (defrost+floor) is valid.
    public static final int DIR_FACE = 1, DIR_FEET = 2, DIR_FACE_FEET = 3, DIR_GLASS = 4, DIR_GLASS_FEET = 6;
    public static final int DIR_DEFROST_FLOOR = DIR_GLASS_FEET;

    // Where the machine reverses. A property of the CAR, measured, and it does
    // not move with the weather (historical/COMFORT.md). Every setpoint of the
    // list is a whole degree clear of it.
    public static final float MIDDLE_C = (TEMP_MIN + TEMP_MAX) / 2f;   // 24.5

    // One row per mark: AC, recirculation, direction, fan, setpoint (0 = none).
    // The move to the face (C5 -> C6) takes the fan one step down: direct air
    // feels stronger, so the step stays small. The setpoint is blunt, so it is
    // used late, with the loud fan speeds.
    private static final int[][] COOL = {
        {0, 0, DIR_FACE,  1, 0},     // C1  outside air, low
        {1, 0, DIR_GLASS, 1, 24},    // C2  gentle, nothing cold on the arm
        {1, 0, DIR_GLASS, 2, 24},    // C3
        {1, 1, DIR_GLASS, 2, 24},    // C4  recirculation: colder, still indirect
        {1, 1, DIR_GLASS, 3, 24},    // C5
        {1, 1, DIR_FACE,  2, 24},    // C6  direct air, fan one step down
        {1, 1, DIR_FACE,  3, 24},    // C7
        {1, 1, DIR_FACE,  4, 24},    // C8
        {1, 1, DIR_FACE,  6, 22},    // C9  colder and loud
        {1, 1, DIR_FACE,  8, 17},    // C10 all-out
    };
    // The heat is on at every warm mark (an EV: the heat pump runs through the
    // same compressor flag). Never recirculation: outside air keeps the glass
    // clear.
    private static final int[][] WARM = {
        {1, 0, DIR_FEET,      1, 26},   // W1  a little warm at the feet
        {1, 0, DIR_FEET,      2, 26},   // W2
        {1, 0, DIR_FEET,      3, 26},   // W3
        {1, 0, DIR_FEET,      3, 28},   // W4  warmer air, same noise
        {1, 0, DIR_FACE_FEET, 6, 32},   // W5  all-out
    };

    /** One mark of the ruler with its settings. */
    public static final class Column {
        public final int level;          // +10 = C10 ... 0 ... -5 = W5
        public final boolean machine;    // HVAC_AC_ON. TRUE ON THE WARM SIDE TOO.
        public final float setpointC;    // NaN when the machine is off
        public final int fan;
        public final int direction;
        public final boolean recirc;
        /** Power (HVAC_POWER_ON). Only mark 0 is off. */
        public final boolean power;

        Column(int level, boolean machine, float sp, int fan, int dir, boolean recirc) {
            this.level = level; this.machine = machine; this.setpointC = sp;
            this.fan = fan; this.direction = dir; this.recirc = recirc;
            this.power = level != 0;
        }

        /** Returns true if this is a cooling mark (level > 0). */
        public boolean cold() { return level > 0; }

        /** Returns true if this is a heating mark (level < 0). */
        public boolean warm() { return level < 0; }

        @Override public String toString() {
            if (!power) return name(level) + "@off";
            return name(level) + settings(machine, setpointC, fan, direction, recirc);
        }
    }

    /** What the car is set to, or what we want it set to. Immutable. */
    public static final class State {
        public final boolean power, machine, recirc;
        public final float setpointC;
        public final int fan, direction;

        public State(boolean power, boolean machine, float setpointC, int fan, int direction, boolean recirc) {
            this.power = power; this.machine = machine; this.setpointC = setpointC;
            this.fan = fan; this.direction = direction; this.recirc = recirc;
        }

        public static final State OFF = new State(false, false, Float.NaN, 0, 0, false);

        public static State of(Column c) {
            return c.power ? new State(true, c.machine, c.setpointC, c.fan, c.direction, c.recirc) : OFF;
        }

        /** Same settings. With the power off nothing else counts. */
        public boolean same(State o) {
            if (o == null || power != o.power) return false;
            if (!power) return true;
            if (machine != o.machine || recirc != o.recirc || fan != o.fan || direction != o.direction) return false;
            return !machine || sameTemp(setpointC, o.setpointC);
        }
    }

    /** Where a state stands on the ruler. */
    public static final class Fit {
        public final int level;
        /** False when a setting is out of line: the mark is drawn hollow. */
        public final boolean exact;
        Fit(int level, boolean exact) { this.level = level; this.exact = exact; }
    }

    /** Returns the name of a mark (e.g., "C3", "W2", "0"). */
    public static String name(int level) {
        return level == 0 ? "0" : (level > 0 ? "C" + level : "W" + (-level));
    }

    /** Returns the string name of an air direction constant. */
    public static String dirName(int d) {
        switch (d) {
            case DIR_FACE:       return "face";
            case DIR_FEET:       return "feet";
            case DIR_FACE_FEET:  return "face+feet";
            case DIR_GLASS:      return "glass";
            case DIR_GLASS_FEET: return "glass+feet";
            default:             return "dir" + d;
        }
    }

    private static String settings(boolean machine, float sp, int fan, int dir, boolean recirc) {
        return (machine && !Float.isNaN(sp) ? "@" + (int) sp : "@--")
             + "/f" + fan + (recirc ? "/rec" : "") + "/" + dirName(dir);
    }

    /** A state for the climate log, e.g. "C3@24/f2/glass"; "~" marks a state
     * that is not exactly on its mark. */
    public static String label(Column[] table, State s) {
        if (!s.power) return "0@off";
        Fit f = fit(table, s);
        return name(f.level) + (f.exact ? "" : "~")
             + settings(s.machine, s.setpointC, s.fan, s.direction, s.recirc);
    }

    private EffortTable() {}

    // ---------------------------------------------------------------- the list

    private static final Column[] TABLE = new Column[COOL_MAX + WARM_MAX + 1];
    static { for (int i = 0; i < TABLE.length; i++) TABLE[i] = make(COOL_MAX - i); }

    private static Column make(int level) {
        if (level == 0) return new Column(0, false, Float.NaN, 0, DIR_GLASS, false);
        int[] r = level > 0 ? COOL[level - 1] : WARM[-level - 1];
        boolean machine = r[0] == 1;
        return new Column(level, machine, machine ? r[4] : Float.NaN, r[3], r[2], r[1] == 1);
    }

    /** The mark for the given level. */
    public static Column column(int level) { return TABLE[COOL_MAX - level]; }

    /** The full list (C10..0..W5). The marks are immutable; the array is a copy. */
    public static Column[] build() { return TABLE.clone(); }

    /**
     * The mark under a finger on the bar. {@code x} is the place of the
     * finger, {@code w} the width of the bar and {@code gap} the space between
     * two marks, all in pixels. {@code cold} tells which side the bar shows:
     * 10 cool marks with C1 at the right end, or 5 warm marks with W1 at the
     * left end. A finger left or right of the bar gets the mark at that end,
     * so the result is never 0.
     */
    public static int markAt(float x, float w, float gap, boolean cold) {
        int marks = max(cold);
        float pitch = (w - gap * (marks - 1)) / marks + gap;
        int i = w > 0 && pitch > 0 ? (int) Math.floor(x / pitch) : 0;
        i = Math.max(0, Math.min(marks - 1, i));
        return cold ? marks - i : -(i + 1);
    }

    /** Returns the mark at the given level. */
    public static Column at(Column[] table, int level) { return table[COOL_MAX - level]; }

    private static int max(boolean cold) { return cold ? COOL_MAX : WARM_MAX; }

    /** The mark at distance {@code abs} from zero on one side. */
    private static Column side(Column[] table, boolean cold, int abs) {
        return at(table, cold ? abs : -abs);
    }

    // ---------------------------------------------------------------- the fitting

    private static boolean sameTemp(float a, float b) {
        return !Float.isNaN(a) && !Float.isNaN(b) && Math.abs(a - b) < 0.5f;
    }

    // The settings of a mark, in the order the car is written.
    private static final int MACHINE = 0, RECIRC = 1, DIR = 2, FAN = 3, TEMP = 4, SETTINGS = 5;

    /** True when this setting of the state is the one the mark has. */
    private static boolean holds(Column c, State s, int setting) {
        switch (setting) {
            case MACHINE: return c.machine == s.machine;
            case RECIRC:  return c.recirc == s.recirc;
            case DIR:     return c.direction == s.direction;
            case FAN:     return c.fan == s.fan;
            // The setpoint counts only while the machine runs on both.
            default:      return !c.machine || !s.machine || sameTemp(c.setpointC, s.setpointC);
        }
    }

    private static int misses(Column c, State s) {
        int n = 0;
        for (int i = 0; i < SETTINGS; i++) if (!holds(c, s, i)) n++;
        return n;
    }

    /**
     * Where the state stands: the mark with the fewest settings out of line
     * (the lower mark on a tie). Exact when none is out of line.
     */
    public static Fit fit(Column[] table, State s) {
        if (!s.power) return new Fit(0, true);
        boolean cold = sideIsCold(s);
        int best = 1, bestMiss = Integer.MAX_VALUE;
        for (int k = 1; k <= max(cold); k++) {
            Column c = side(table, cold, k);
            int m = misses(c, s);
            if (m < bestMiss) { bestMiss = m; best = k; }
        }
        return new Fit(cold ? best : -best, bestMiss == 0);
    }

    // Which side of the ruler the car is on. With the machine on: the setpoint
    // against MIDDLE_C, the point where the machine itself reverses. NOT against
    // the outside temperature: on a cool evening a cold setpoint is not below
    // the outside air, and that comparison read a car blowing cold as "heating"
    // (issue #6). With the machine off: the air direction (feet = warm side).
    private static boolean sideIsCold(State s) {
        if (s.machine && !Float.isNaN(s.setpointC)) return s.setpointC < MIDDLE_C;
        return s.direction != DIR_FEET && s.direction != DIR_FACE_FEET && s.direction != DIR_GLASS_FEET;
    }

    // ---------------------------------------------------------------- the tap

    /**
     * The state after one tap. {@code dir} +1 = cooler, -1 = warmer.
     *
     * On a mark: the next mark. At the end of
     * the ruler the same state comes back.
     *
     * Not on a mark (a hand on the car's own climate screen): every setting has
     * its own place on the list. A tap away from zero moves the setting that is
     * furthest behind one place forward. A tap towards zero moves the setting
     * that is furthest ahead one place back. Nothing else moves, so the fan
     * never jumps. A value that is not on the list at all (fan 5, air to the
     * feet on the cool side) is put on the list by the first tap.
     */
    public static State step(Column[] table, State s, int dir) {
        if (!s.power) return State.of(at(table, dir > 0 ? 1 : -1));
        Fit f = fit(table, s);
        boolean cold = f.level > 0;
        int here = Math.abs(f.level);
        boolean away = cold == (dir > 0);
        int top = max(cold);

        if (f.exact) {
            int k = here + (away ? 1 : -1);
            if (k > top) return s;
            return k < 1 ? State.OFF : State.of(side(table, cold, k));
        }

        // Place of each setting: the mark nearest to "here" that has its value.
        // 0 = the value is on no mark.
        int[] place = new int[SETTINGS];
        for (int i = 0; i < SETTINGS; i++) place[i] = place(table, cold, s, i, here);

        if (away) {
            int from = top;
            for (int p : place) if (p > 0) from = Math.min(from, p);
            for (int k = from + 1; k <= top; k++) {
                State next = moved(side(table, cold, k), s, place, k, true, cold);
                if (next != null) return next;
            }
            // Nothing is behind: make the mark we stand on exact.
            return State.of(side(table, cold, here));
        }
        int from = 1;
        for (int p : place) from = Math.max(from, p);
        for (int k = from - 1; k >= 1; k--) {
            State next = moved(side(table, cold, k), s, place, k, false, cold);
            if (next != null) return next;
        }
        return State.OFF;
    }

    private static int place(Column[] table, boolean cold, State s, int setting, int here) {
        int best = 0;
        for (int k = 1; k <= max(cold); k++) {
            Column c = side(table, cold, k);
            if (!holds(c, s, setting)) continue;
            // A running machine's setpoint has no place on a mark without one.
            if (setting == TEMP && s.machine && !c.machine) continue;
            if (best == 0 || Math.abs(k - here) < Math.abs(best - here)) best = k;
        }
        if (best != 0 || setting != TEMP) return best;
        // A setpoint between two marks stands with the nearest one.
        float bestD = Float.MAX_VALUE;
        for (int k = 1; k <= max(cold); k++) {
            Column c = side(table, cold, k);
            if (!c.machine) continue;
            float d = Math.abs(c.setpointC - s.setpointC);
            if (d < bestD || (d == bestD && Math.abs(k - here) < Math.abs(best - here))) { bestD = d; best = k; }
        }
        return best;
    }

    /** The state with the settings that mark {@code c} (at place k) pulls
     * along, or null when it pulls none. */
    private static State moved(Column c, State s, int[] place, int k, boolean away, boolean cold) {
        boolean machine = s.machine, recirc = s.recirc;
        int fan = s.fan, direction = s.direction;
        float sp = s.setpointC;
        boolean any = false;
        for (int i = 0; i < SETTINGS; i++) {
            boolean pulled = place[i] == 0 || (away ? place[i] < k : place[i] > k);
            if (!pulled) continue;
            switch (i) {
                case MACHINE: if (machine != c.machine) { machine = c.machine; any = true; } break;
                case RECIRC:  if (recirc != c.recirc) { recirc = c.recirc; any = true; } break;
                case DIR:     if (direction != c.direction) { direction = c.direction; any = true; } break;
                case FAN:     if (fan != c.fan) { fan = c.fan; any = true; } break;
                default:
                    if (c.machine && s.machine && !sameTemp(sp, c.setpointC)) { sp = c.setpointC; any = true; }
            }
        }
        if (!any) return null;
        // A machine that runs must stay on its own side of MIDDLE_C, or the tap
        // would turn cooling into heating.
        // A machine this tap turns on takes the mark's setpoint.
        if (machine && c.machine && (!s.machine || Float.isNaN(sp) || (sp < MIDDLE_C) != cold)) sp = c.setpointC;
        return new State(true, machine, sp, fan, direction, recirc);
    }
}
