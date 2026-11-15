package com.geely.drivemem.controls;

import android.content.Context;

import com.geely.drivemem.R;
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

    /** How many of the icon's three ring segments are filled: 1 Low,
     * 2 Medium, 3 High, 0 when the level is not known. */
    public static int segments(int level) {
        return level == Modes.REGEN_LOW ? 1 : level == Modes.REGEN_MID ? 2
            : level == Modes.REGEN_HIGH ? 3 : 0;
    }

    /** The regen icon for a level: a ring in three segments, filled with
     * the level, a chevron at one tip and a bolt in the middle. */
    public static int icon(int level) {
        switch (segments(level)) {
            case 1:  return R.drawable.ic_regen_low;
            case 2:  return R.drawable.ic_regen_mid;
            case 3:  return R.drawable.ic_regen_high;
            default: return R.drawable.ic_regen_unknown;
        }
    }

    /** Toggle mode: lit only on High (the button means "Strong regen").
     * Cycle mode: always lit, the filled segments say the level. */
    public static boolean lit(int level, boolean cycle) {
        return cycle || level == Modes.REGEN_HIGH;
    }
}
