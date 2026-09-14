package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** One-off diagnostic: does modehelper's platform signature let it call
 * CarAudioManager.setAVASMode — the same call the reference app (CentralEXAuto)
 * uses to mute AVAS (the low-speed pedestrian warning sound)? The OEM Settings
 * UI's Sound screen never exposes a button for this at all — only the sound
 * *style* (Clássico / Tom Galático / Caminhada Espacial, a separate property,
 * already in the catalog) is selectable there. Not to be confused with ADAS
 * (collision warning / AEB, see AebWriteTestReceiver) — different system,
 * different acronym, easy to mix up.
 *
 * Hardcoded to this single call for the same reason AebWriteTestReceiver is
 * hardcoded to one property: see ProbeReceiver's own comment on why this app
 * never grows a generic writer. Always restores the original AVAS mode before
 * finishing.
 *
 * Invoke: adb shell am broadcast -n com.geely.modehelper/.AvasMuteTestReceiver \
 *   -a com.geely.modehelper.AVASMUTETEST
 */
public class AvasMuteTestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            CarMode c = new CarMode();
            try {
                if (!c.connect(app)) { Log.w(CarMode.TAG, "avasmute: no car"); return; }

                Object supported = c.audioCall("isAVASModeSupported");
                Log.i(CarMode.TAG, "avasmute: isAVASModeSupported = " + supported);

                Object base = c.audioCall("getAVASMode");
                Log.i(CarMode.TAG, "avasmute: baseline mode = " + base);
                if (!(base instanceof Integer)) {
                    Log.w(CarMode.TAG, "avasmute: baseline unreadable, aborting");
                    return;
                }
                int baseMode = (Integer) base;

                Object muteResult = c.audioCall("setAVASMode", 0);
                Log.i(CarMode.TAG, "avasmute: setAVASMode(0) result = " + muteResult);
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                Object after = c.audioCall("getAVASMode");
                Log.i(CarMode.TAG, "avasmute: mode after mute = " + after);

                int restoreTarget = Math.max(baseMode, 1);
                Object restoreResult = c.audioCall("setAVASMode", restoreTarget);
                Log.i(CarMode.TAG, "avasmute: setAVASMode(" + restoreTarget + ") restore result = " + restoreResult);
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                Object fin = c.audioCall("getAVASMode");
                Log.i(CarMode.TAG, "avasmute: final mode (should be " + restoreTarget + ") = " + fin);
            } catch (Throwable t) { Log.w(CarMode.TAG, "avasmute: " + t, t);
            } finally { c.disconnect(); pr.finish(); }
        }).start();
    }
}
