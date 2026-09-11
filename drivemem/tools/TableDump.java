import com.geely.drivemem.hvac.EffortTable;
import com.geely.drivemem.hvac.EffortTable.Column;

/** Diagnostic tool: prints the HVAC effort table and tests the fitting rule against
 * various states. See COMFORT-TABLE.md for documentation. */
public class TableDump {

    public static void main(String[] a) {
        float[] days = {32f, 26f, 21f, 16f};
        for (float out : days) print(out);

        System.out.println();
        System.out.println("=== fitting: what the car is doing -> what we call it (26.0C) ===");
        Column[] t = EffortTable.build(26f);
        fit(t, 26f, "all-out heat, exactly",        true, true,  32f, 8, EffortTable.DIR_FACE_FEET, false);
        fit(t, 26f, "all-out heat but fan 5",       true, true,  32f, 5, EffortTable.DIR_FACE_FEET, false);
        fit(t, 26f, "all-out heat, feet only",      true, true,  32f, 8, EffortTable.DIR_FEET,      false);
        fit(t, 26f, "heating gently",               true, true,  27f, 5, EffortTable.DIR_FEET,      false);
        fit(t, 26f, "nothing at all",               false, false, Float.NaN, 1, EffortTable.DIR_GLASS, false);
        fit(t, 26f, "ambient, feet, low flow",      true, false, Float.NaN, 1, EffortTable.DIR_FEET,  false);
        fit(t, 26f, "cooling hard",                 true, true,  17f, 8, EffortTable.DIR_FACE,      true);
        fit(t, 26f, "cooling hard, no recirc",      true, true,  17f, 8, EffortTable.DIR_FACE,      false);

        // Test case: switched-off car (all HVAC off). Common on initial start.
        System.out.println();
        System.out.println("=== a car that is switched off (35.5C example) ===");
        Column[] hot = EffortTable.build(35.5f);
        fit(hot, 35.5f, "blower stopped, all off",  false, false, Float.NaN, 0, EffortTable.DIR_FACE,  false);
        fit(hot, 35.5f, "off, but fan still 6",     true, false, Float.NaN, 6, EffortTable.DIR_FACE,  false);
        fit(hot, 35.5f, "off, fan 0, at the glass", false, false, Float.NaN, 0, EffortTable.DIR_GLASS, false);
        fit(hot, 35.5f, "all-out cooling",          true, true,  17f, 8, EffortTable.DIR_FACE,       true);
    }

    static void print(float out) {
        System.out.printf("%n=== outside %.1fC   (free columns: cold %d, warm %d)%n", out,
            EffortTable.freeColumns(out, true), EffortTable.freeColumns(out, false));
        System.out.println("  lvl  power  machine  setpoint  fan  direction   recirc");
        for (int lv = EffortTable.MAX_LEVEL; lv >= -EffortTable.MAX_LEVEL; lv--) {
            Column c = EffortTable.column(out, lv);
            System.out.printf("  %-3s  %-5s  %-7s  %-8s  %-3d  %-10s  %s%n",
                EffortTable.name(lv),
                c.power ? "on" : "OFF",
                c.machine ? "on" : "off",
                c.machine ? String.format("%.0f", c.setpointC) : "--",
                c.fan,
                EffortTable.dirName(c.direction),
                c.recirc ? "on" : "off");
        }
    }

    /** Test fitting rule. Power (HVAC_POWER_ON) controls compressor and blower.
     * Switched-off state is power=false with no other settings. */
    static void fit(Column[] t, float out, String what, boolean power,
                    boolean machine, float sp, int fan, int dir, boolean recirc) {
        int lv = EffortTable.fit(t, out, power, machine, sp, fan, dir, recirc);
        System.out.printf("  %-28s -> %s%n", what, EffortTable.name(lv));
    }
}
