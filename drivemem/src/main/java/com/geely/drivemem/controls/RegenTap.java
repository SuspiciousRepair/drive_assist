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
 * On: Low -> Medium -> High -> Low. Either way the level comes from the hub
 * (car.regen_mode, a push watch), so the tap acts on what the car has now. */
public final class RegenTap {
    private RegenTap() {}

    /** Hub key: the car's regen level, a push watch (CarActor). */
    public static final String KEY = "car.regen_mode";

    /** Takes the level from the hub, writes the next level, and reports it
     * once applied (on the car thread: callers post to their own UI thread).
     * No real level in the hub (not connected yet, or the read failed): no
     * write, so a tap never acts on a guess. */
    public static void tap(Context ctx, IntConsumer onApplied) {
        final boolean cycle = Prefs.getRegenCycle(ctx);
        final int def = Prefs.getRegen(ctx, Modes.REGEN_MID);
        CarActor a = CarActor.get(ctx);
        final int cur = level(a.get(KEY));
        if (!known(cur)) return;
        final int target = target(cur, cycle, def);
        a.cast("regen_mode", target, r -> { if (r.applied) onApplied.accept(target); });
    }

    /** The car's level as the hub has it now (0 when unknown). */
    public static int current(Context ctx) {
        return level(CarActor.get(ctx).get(KEY));
    }

    /** The level in a hub reading, 0 when it holds no real level. */
    public static int level(CarActor.Reading r) {
        if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Integer)) return 0;
        int v = (Integer) r.value;
        return known(v) ? v : 0;
    }

    /** True for Low, Medium or High: a level a tap can act on. */
    public static boolean known(int level) {
        return segments(level) != 0;
    }

    /** The level a tap writes. Toggle mode: High, or back from High to
     * Config's level. Config's level can be High itself (Config > Drive):
     * then back to Medium, or the button could never leave High. */
    public static int target(int cur, boolean cycle, int configDefault) {
        if (cycle) return Modes.nextRegen(cur);
        if (cur != Modes.REGEN_HIGH) return Modes.REGEN_HIGH;
        return configDefault == Modes.REGEN_HIGH ? Modes.REGEN_MID : configDefault;
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
