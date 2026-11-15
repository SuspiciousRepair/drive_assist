package com.geely.drivemem.controls;

import android.content.Context;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.util.Modes;
import com.geely.drivemem.util.Prefs;

import java.util.function.IntConsumer;

/** The Regen quick button, shared by the home screen's Turbo card and the
 * floating panel, so both follow Config > Drive > "Regen button steps
 * through Low, Medium and High" (issue #15).
 *
 * Off (default): Strong regen on/off, back to Config's own regen level.
 * On: Low -> Medium -> High -> Low. Either way the car is read first, so the
 * tap acts on what the car has now, not on a stale guess. */
public final class RegenTap {
    private RegenTap() {}

    /** Reads the car, writes the next level, and reports it once applied
     * (on the car thread: callers post to their own UI thread). */
    public static void tap(Context ctx, IntConsumer onApplied) {
        final boolean cycle = Prefs.getRegenCycle(ctx);
        final int def = Prefs.getRegen(ctx, Modes.REGEN_MID);
        CarActor a = CarActor.get(ctx);
        a.read("regen_mode", cur -> {
            int target = target(cur instanceof Integer ? (Integer) cur : 0, cycle, def);
            a.cast("regen_mode", target, r -> { if (r.applied) onApplied.accept(target); });
        });
    }

    /** Reads the car's level and reports it (0 when unknown). */
    public static void read(Context ctx, IntConsumer onLevel) {
        CarActor.get(ctx).read("regen_mode", cur -> onLevel.accept(cur instanceof Integer ? (Integer) cur : 0));
    }

    /** The level a tap writes. */
    public static int target(int cur, boolean cycle, int configDefault) {
        if (cycle) return Modes.nextRegen(cur);
        return cur == Modes.REGEN_HIGH ? configDefault : Modes.REGEN_HIGH;
    }

    /** Toggle mode: the High mark. Cycle mode: the mark Config's regen
     * picker uses for each level. */
    public static String mark(int level, boolean cycle) {
        if (!cycle) return "●";
        return level == Modes.REGEN_LOW ? "◦" : level == Modes.REGEN_MID ? "◉"
            : level == Modes.REGEN_HIGH ? "●" : "?";
    }

    /** Toggle mode: lit only on High. Cycle mode: always lit, the mark says the level. */
    public static boolean lit(int level, boolean cycle) {
        return cycle || level == Modes.REGEN_HIGH;
    }
}
