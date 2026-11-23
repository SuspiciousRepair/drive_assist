package com.geely.drivemem.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Opens the car's own sound settings with the equalizer, from the music
 * card.
 *
 * The OEM settings app (com.flyme.auto.settings) exports
 * fragment.volume.SoundSpaceActivity, the sound-effects screen with the EQ
 * presets (Standard, Pop, Rock, ...) and, where the car has it, the custom
 * sliders; its intent filter takes the action below. Found by decompiling
 * that app (version 26012820). The fallback is the Settings Volume page,
 * whose action the main SettingsActivity routes (RouterUtils.getActIndex).
 * Both are exported, so no helper or permission is needed. Neither there:
 * null, and the button is not shown. */
public final class OemSound {
    static final String PKG = "com.flyme.auto.settings";
    static final String ACTION_EQ = "com.flyme.auto.settings.action.space_effect";
    static final String ACTION_VOLUME = "com.flyme.auto.settings.action.VOLUME";

    private OemSound() {}

    /** The first of the two screens this car has, or null. */
    public static Intent intent(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String action : new String[]{ACTION_EQ, ACTION_VOLUME}) {
            Intent i = new Intent(action).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (pm.resolveActivity(i, 0) != null) return i;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Opens it; false when the car has neither screen or it would not start. */
    public static boolean open(Context ctx) {
        Intent i = intent(ctx);
        if (i == null) return false;
        try { ctx.startActivity(i); return true; }
        catch (Throwable t) { android.util.Log.w(com.geely.drivemem.car.CarAccess.TAG, "oem eq: " + t); return false; }
    }
}
