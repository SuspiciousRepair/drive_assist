package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Reads car properties by ID for diagnostic purposes. Invoked via adb broadcast.
 * Example: am broadcast -a com.geely.modehelper.READPROP --es ids 0x2140a173
 * Reads only (no writes): property IDs are numerous, and typos in writes risk
 * car damage. */
public class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        final String ids = i.getStringExtra("ids");
        final int area = i.getIntExtra("area", 0);
        if (ids == null) { Log.w(CarMode.TAG, "probe: no ids"); return; }
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            CarMode c = new CarMode();
            try {
                if (!c.connect(app)) { Log.w(CarMode.TAG, "probe: no car"); return; }
                for (String raw : ids.split(",")) {
                    String t = raw.trim();
                    if (t.isEmpty()) continue;
                    int id;
                    try {
                        id = t.startsWith("0x") || t.startsWith("0X")
                            ? (int) Long.parseLong(t.substring(2), 16)
                            : (int) Long.parseLong(t);
                    } catch (Throwable bad) { Log.w(CarMode.TAG, "probe: bad id " + t); continue; }
                    // Try int, then float, then bool — the config says the type
                    // but reading all three is cheaper than parsing dumpsys and
                    // says plainly which one answered.
                    Integer iv = c.readIntProp(id, area);
                    Float   fv = c.readFloatProp(id, area);
                    Boolean bv = c.readBoolProp(id, area);
                    Log.i(CarMode.TAG, "probe " + t + " area=" + area
                        + " int=" + iv + " float=" + fv + " bool=" + bv);
                }
            } catch (Throwable t) { Log.w(CarMode.TAG, "probe: " + t);
            } finally { c.disconnect(); pr.finish(); }
        }).start();
    }
}
