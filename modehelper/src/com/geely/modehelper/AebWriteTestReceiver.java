package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** One-off diagnostic: does modehelper's platform signature + uid system let it
 * WRITE the AEB master switch (557858874@0), not just read it? Hardcoded to this
 * single property on purpose — see ProbeReceiver's own comment on why this app
 * never grows a generic "write any id" path; a typo in a write is the risk that
 * comment exists to avoid, and AEB is exactly the property where that risk
 * matters most.
 *
 * Always restores the property to whatever it read as the baseline, regardless
 * of whether the write succeeded, so this is safe to fire from adb: worst case
 * it changes nothing, or it toggles AEB off for ~1.2s before putting it back.
 *
 * Invoke: adb shell am broadcast -n com.geely.modehelper/.AebWriteTestReceiver \
 *   -a com.geely.modehelper.AEBWRITETEST
 */
public class AebWriteTestReceiver extends BroadcastReceiver {
    private static final int AEB_PROP = 557858874;
    private static final int AEB_AREA = 0;

    @Override public void onReceive(Context ctx, Intent i) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            CarMode c = new CarMode();
            try {
                if (!c.connect(app)) { Log.w(CarMode.TAG, "aebwrite: no car"); return; }
                Boolean base = c.readBoolProp(AEB_PROP, AEB_AREA);
                Log.i(CarMode.TAG, "aebwrite: baseline = " + base);
                if (base == null) { Log.w(CarMode.TAG, "aebwrite: baseline unreadable, aborting"); return; }

                boolean target = !base;
                boolean wroteOk = c.writeBoolProp(AEB_PROP, AEB_AREA, target);
                Log.i(CarMode.TAG, "aebwrite: write(" + target + ") call ok=" + wroteOk);
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                Boolean after = c.readBoolProp(AEB_PROP, AEB_AREA);
                Log.i(CarMode.TAG, "aebwrite: readback after write = " + after
                    + " (actually changed=" + (after != null && !after.equals(base)) + ")");

                boolean restoreOk = c.writeBoolProp(AEB_PROP, AEB_AREA, base);
                Log.i(CarMode.TAG, "aebwrite: restore(" + base + ") call ok=" + restoreOk);
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                Boolean fin = c.readBoolProp(AEB_PROP, AEB_AREA);
                Log.i(CarMode.TAG, "aebwrite: final (should equal baseline " + base + ") = " + fin);
            } catch (Throwable t) { Log.w(CarMode.TAG, "aebwrite: " + t, t);
            } finally { c.disconnect(); pr.finish(); }
        }).start();
    }
}
