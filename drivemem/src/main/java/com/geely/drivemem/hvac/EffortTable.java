package com.geely.drivemem.hvac;

import com.geely.drivemem.util.Modes;

// A literal per-level table (one static row per -5..5) isn't possible here:
// fan/setpoint are literal per level (FAN_COLD/FAN_WARM, setpointFor()), but
// direction and recirc depend on `free` (freeColumns()), which slides with
// outside temperature — see COMFORT-TABLE.md. So the "table" is this file's
// rules, not a data grid; `tools/TableDump.java` is the human-readable view
// (run it: it prints the resolved table for a handful of representative
// outside temperatures) — it was left broken by the later package split
// (com.geely.drivemem.EffortTable -> .hvac.EffortTable) and is fixed now.

/**
 * Defines the HVAC effort table: a fixed 11-column grid mapping car HVAC state
 * to effort level (+5 full cooling through 0 off to -5 full heating). Provides
 * methods to construct columns for a given ambient temperature and fit car state
 * to the nearest column. See COMFORT-TABLE.md for design rationale.
 */
// The effort table defines HVAC columns from +5 (full cooling) through 0 (off)
// to -5 (full heating). POSITIVE means cooling. Ends are pinned (extremes don't
// vary with weather); the middle slides because outside air can do free work.
// Columns are declared statically (never enumerated or sorted); a car state fits
// to a column that exists. The effort number is absolute (immediately readable),
// not a delta from previous state. Touches no Android API for portability.
// See COMFORT-TABLE.md for design rationale and tools/TableDump.java for analysis.
public final class EffortTable {

    public static final int MAX_LEVEL = 5;
    public static final float TEMP_MIN = 17f, TEMP_MAX = 32f;
    // A comfortable cabin. Only decides how much of the middle is free — how far
    // outside air alone gets us — never what the human wants.
    public static final float CABIN_TARGET_C = 23f;

    // Cold-side cutoff for free (fan-only) cooling. Distinct from CABIN_TARGET_C
    // because moving air is comfortable on its own, not only when it's cooler
    // than the cabin. The warm side has no equivalent (only CABIN_TARGET_C) because
    // ambient heat requires an actual temperature delta to provide warmth.
    public static final float FRESH_LIMIT_C = 28f;

    // Air direction. The area is ignored on this unit and 356517121 is an alias
    // of the same value — see field-catalog.md.
    public static final int DIR_PROP = 557846560;
    // The AOSP bitmask. Confirmed in the car; 5 (face+defrost) is REFUSED by
    // this unit, so it is not in here. 6 (defrost+floor) is valid and used for
    // thermal comfort and windshield defogging.
    public static final int DIR_FACE = 1, DIR_FEET = 2, DIR_FACE_FEET = 3, DIR_GLASS = 4, DIR_GLASS_FEET = 6;
    public static final int DIR_DEFROST_FLOOR = DIR_GLASS_FEET;

    // Fan speed 8 (maximum), not Modes.FAN_MAX (7). The car reports and accepts
    // fan 8; Modes.FAN_MAX is the incorrect value elsewhere. Not changed here to
    // avoid impact on other code that reads the constant.
    static final int FAN_ALL_OUT = 8;
    private static final int[] FAN_COLD = {1, 2, 4, 6, 8};   // C1..C5
    private static final int[] FAN_WARM = {1, 2, 4, 6, 8};   // W1..W5

    /** Represents one column of the HVAC effort table with specific settings. */
    public static final class Column {
        public final int level;          // +5 = C5 ... 0 ... -5 = W5
        public final boolean machine;    // HVAC_AC_ON. TRUE ON BOTH SIDES: this is
                                         // an EV and the heat pump runs through the
                                         // same compressor.
        public final float setpointC;    // NaN when the machine is off
        public final int fan;
        public final int direction;
        public final boolean recirc;
        // True if power is on (HVAC_POWER_ON). Zero level means machine is off,
        // not "fan 1 at glass" (which is technically feasible but semantically
        // wrong since "nothing" should not be blowing air). Since nudgeFan() clamps
        // at Modes.FAN_MIN = 1, distinguishing off state by power rather than fan
        // speed is necessary.
        public final boolean power;

        Column(int level, boolean machine, float sp, int fan, int dir, boolean recirc) {
            this.level = level; this.machine = machine; this.setpointC = sp;
            this.fan = fan; this.direction = dir; this.recirc = recirc;
            this.power = level != 0;
        }

        /** Returns true if this is a cooling column (level > 0). */
        public boolean cold() { return level > 0; }

        /** Returns true if this is a heating column (level < 0). */
        public boolean warm() { return level < 0; }

        @Override public String toString() {
            if (!power) return name(level) + "@off";
            return name(level) + (machine ? "@" + (int) setpointC : "@--")
                 + "/f" + fan + (recirc ? "/rec" : "") + "/" + dirName(direction);
        }
    }

    /** Returns the string representation of an effort level (e.g., "C3", "W2", "0"). */
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

    private EffortTable() {}

    // ---------------------------------------------------------------- the slide

    /**
     * Returns the number of free (fan-only, no compressor) columns available given
     * outside temperature and side. Returns 0 when outside air cannot contribute.
     * Cold side expands at 1 column per 4°C; warm side at 1 column per 2°C.
     */
    public static int freeColumns(float outC, boolean coldSide) {
        if (coldSide) {
            float past = FRESH_LIMIT_C - outC;
            if (past <= 0) return 0;
            return Math.min(3, (int) Math.floor(past / 4f) + 1);
        }
        float past = outC - CABIN_TARGET_C;
        if (past <= 0) return 0;
        return Math.min(3, (int) Math.floor(past / 2f) + 1);
    }

    // Where the machine reverses. A property of the CAR, measured, and it does
    // not move with the weather (historical/COMFORT.md). The nearest usable stop each side
    // is a whole degree clear of it, because the setpoint quantises to whole
    // degrees and landing on the boundary is what flips the machine.
    public static final float MIDDLE_C = (TEMP_MIN + TEMP_MAX) / 2f;   // 24.5
    public static final float COLD_NEAREST = 24f, HOT_NEAREST = 25f;

    // Initial setpoint for machine columns. Two constraints: (1) must be past
    // MIDDLE (24.5 C, fixed) so the machine is in the correct mode; (2) must be
    // past outside air temperature so the mode makes physical sense. Takes
    // whichever constraint is more stringent.
    static float setpointFor(float outC, int level, int free) {
        boolean coldSide = level > 0;
        int idx = Math.abs(level) - free;               // 1..n within the machine block
        int n = MAX_LEVEL - free;                       // how many machine columns
        float first = coldSide ? Math.min(COLD_NEAREST, outC - 1f)
                               : Math.max(HOT_NEAREST, outC + 1f);
        float last  = coldSide ? TEMP_MIN : TEMP_MAX;
        if (n <= 1) return last;
        // Ensure at least n distinct whole-degree setpoints so each machine column
        // produces a different temperature value (no duplicate setpoints).
        if (coldSide) first = Math.max(first, last + (n - 1));
        else          first = Math.min(first, last - (n - 1));
        float sp = first + (last - first) * ((idx - 1) / (float) (n - 1));
        // truncate rather than round: 29.5 -> 29 keeps the 26 C warm side exactly
        // as authored, and a cooler heat setpoint errs on the side of not cooking
        return (int) sp;
    }

    /** Constructs a Column for the given effort level and outside temperature. */
    public static Column column(float outC, int level) {
        if (level == 0) {
            // fan 0, not 1: this column is never WRITTEN lever by lever (apply()
            // just powers the HVAC off), and 0 is what a switched-off car reads
            // back — so matches() agrees with it instead of calling every parked
            // car approximate.
            return new Column(0, false, Float.NaN, 0, DIR_GLASS, false);
        }
        boolean coldSide = level > 0;
        int free = freeColumns(outC, coldSide);
        boolean machine = Math.abs(level) > free;
        float sp = machine ? setpointFor(outC, level, free) : Float.NaN;
        int fan = coldSide ? FAN_COLD[level - 1] : FAN_WARM[-level - 1];

        int dir;
        if (coldSide) {
            // Air direction depends on air source, not effort level. Free
            // (ambient) air goes to the face. Machine's first column goes to the
            // glass (it's cold enough that a person wouldn't want it direct).
            // Harder steps go to the face (that's what cooling directly means).
            dir = !machine ? DIR_FACE
                : (level == free + 1) ? DIR_GLASS
                : DIR_FACE;
        } else {
            // Thermal comfort & defogging physics:
            // - All-out (W5) uses face+feet for immediate full-body warmth.
            // - Gentle warming (W1) on cold days goes to glass (no direct blow).
            // - Intermediate heating (W2..W4) on cold days (outC < CABIN_TARGET_C)
            //   uses glass+feet (defrost+floor) to maintain footwell warmth while
            //   washing the windshield with a warm air boundary layer to prevent
            //   glass fogging from occupant respiration.
            // - On warm days (outC >= CABIN_TARGET_C, free heating), feet is used.
            if (level == -MAX_LEVEL) {
                dir = DIR_FACE_FEET;
            } else if (level == -1 && outC < CABIN_TARGET_C) {
                dir = DIR_GLASS;
            } else if (outC < CABIN_TARGET_C) {
                dir = DIR_GLASS_FEET;
            } else {
                dir = DIR_FEET;
            }
        }

        // Recirculation strategy differs by side. Cold side: enabled where
        // ambient is a liability (recirc cabin air is easier to cool than outside
        // air). Warm side: only at W1 (first machine column), and only when that
        // column is running the machine. W1's warm air benefits from recirc (cabin
        // air requires less heating to reach setpoint), and it's safe because W1
        // air is directed at the glass (clearing it). W2+ air goes to feet/face,
        // so recirc is not enabled (risk of fogging due to trapped moisture on cold
        // glass).
        boolean recirc = coldSide ? (machine && level > free + 1 && outC > CABIN_TARGET_C)
                                  : (machine && level == -1);

        return new Column(level, machine, sp, fan, dir, recirc);
    }

    /** Builds the full 11-column table for the given outside temperature. */
    public static Column[] build(float outC) {
        Column[] cols = new Column[2 * MAX_LEVEL + 1];
        for (int i = 0; i < cols.length; i++) cols[i] = column(outC, MAX_LEVEL - i);
        return cols;
    }

    /** Returns the column at the given effort level from the table. */
    public static Column at(Column[] table, int level) { return table[MAX_LEVEL - level]; }

    // ---------------------------------------------------------------- the fitting

    /**
     * Maps current car HVAC state to the nearest effort level.
     * Returns the level (-5..5) where the car's state best fits,
     * measured as maximum lever distance (not sum) to all-out.
     */
    public static int fit(Column[] table, float outC, boolean power, boolean machine,
                          float setpointC, int fan, int direction, boolean recirc) {
        // Zero level means HVAC is off. This is unambiguous, so it's a direct check.
        if (!power) return 0;

        boolean coldSide = sideIsCold(outC, machine, setpointC, direction);
        int allOut = coldSide ? MAX_LEVEL : -MAX_LEVEL;
        Column top = at(table, allOut);

        int worst = 0;
        worst = Math.max(worst, fanPresses(table, coldSide, fan));
        worst = Math.max(worst, presses(table, coldSide, Lever.DIR, direction, top.direction));
        worst = Math.max(worst, presses(table, coldSide, Lever.RECIRC, recirc ? 1 : 0, top.recirc ? 1 : 0));
        worst = Math.max(worst, presses(table, coldSide, Lever.MACHINE, machine ? 1 : 0, top.machine ? 1 : 0));
        if (machine && !Float.isNaN(setpointC)) {
            worst = Math.max(worst, setpointPresses(table, coldSide, setpointC));
        }

        int level = MAX_LEVEL - worst;
        if (level < 0) level = 0;

        // The machine (compressor) state gates which columns apply. A machine-on
        // column cannot describe a machine-off car (and vice versa) regardless of
        // how close other parameters are. Without this gate, an idle car on a hot
        // day with no free cold columns would fit to a high cooling level.
        int free = freeColumns(outC, coldSide);
        if (!machine && level > free) level = free;        // can only be a free column
        if (machine && level < free + 1) level = free + 1; // cannot be a free column

        return coldSide ? level : -level;
    }

    private enum Lever { FAN, DIR, RECIRC, MACHINE }

    private static int valueOf(Column c, Lever l) {
        switch (l) {
            case FAN:     return c.fan;
            case DIR:     return c.direction;
            case RECIRC:  return c.recirc ? 1 : 0;
            default:      return c.machine ? 1 : 0;
        }
    }

    // Returns presses needed for this lever to reach all-out. p = furthest
    // column from all-out still holding current value; m = nearest column to
    // zero already holding all-out value. An unsupported value (e.g., driver
    // picked direction not in table) counts as one press. See COMFORT-TABLE.md.
    private static int presses(Column[] table, boolean coldSide, Lever lever, int cur, int allOutValue) {
        if (cur == allOutValue) return 0;
        int p = -1, m = -1;
        for (int k = 1; k <= MAX_LEVEL; k++) {
            Column c = at(table, coldSide ? k : -k);
            int v = valueOf(c, lever);
            // p is the highest column still holding the current value (where the
            // lever currently stands). Must take highest, not lowest, to correctly
            // measure distance when the lever changes partway through a range.
            if (v == cur) p = k;
            if (v == allOutValue && m < 0) m = k;
        }
        if (p < 0) return 1;
        if (m < 0) return MAX_LEVEL - p;
        return Math.max(0, m - p);
    }

    // Fan is a continuous scale (graded), not discrete positions. Measures
    // presses by magnitude: where it stands is the highest column whose flow it
    // meets or beats. Below the lowest column, it's at zero (no flow requirement).
    private static int fanPresses(Column[] table, boolean coldSide, int fan) {
        int p = 0;
        for (int k = 1; k <= MAX_LEVEL; k++) {
            if (fan >= at(table, coldSide ? k : -k).fan) p = k;
        }
        return MAX_LEVEL - p;
    }

    // The setpoint is continuous, so it gets its own: which column's setpoint is
    // nearest, then the climb from there to the rail.
    private static int setpointPresses(Column[] table, boolean coldSide, float sp) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int k = 1; k <= MAX_LEVEL; k++) {
            Column c = at(table, coldSide ? k : -k);
            if (!c.machine) continue;
            float d = Math.abs(c.setpointC - sp);
            if (d < bestD) { bestD = d; best = k; }
        }
        return best < 0 ? 0 : MAX_LEVEL - best;
    }

    // Determines which side of the effort table the car belongs to (cold or warm).
    // With machine on: compare setpoint to outside temperature (not to middle of
    // scale). With machine off: infer from air direction (feet = warm side, glass
    // = cold side).
    private static boolean sideIsCold(float outC, boolean machine, float sp, int direction) {
        if (machine && !Float.isNaN(sp)) return sp < outC;
        return direction != DIR_FEET && direction != DIR_FACE_FEET && direction != DIR_GLASS_FEET;
    }
}
