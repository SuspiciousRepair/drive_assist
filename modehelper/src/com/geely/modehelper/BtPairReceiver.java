package com.geely.modehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Triggers Bluetooth pairing via broadcast. Delegates to ModeHelperService.pairByAddress(). */
public class BtPairReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String addr = intent.getStringExtra("addr");
        if (addr == null) { Log.w(ModeHelperService.TAG, "btpair: no addr extra given"); return; }
        String pin = intent.getStringExtra("pin");
        Intent svc = new Intent(ctx, ModeHelperService.class)
            .setAction(ModeHelperService.ACTION_BT_PAIR)
            .putExtra("addr", addr);
        if (pin != null) svc.putExtra("pin", pin);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) { Log.w(ModeHelperService.TAG, "btpair: could not start service: " + t); }
    }
}
