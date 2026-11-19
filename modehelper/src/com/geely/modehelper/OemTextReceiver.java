package com.geely.modehelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Drive Assist's "OEM translations" page talks to {@link OemText} here.
 *
 *  - {@code OEM_TEXT_INSTALL}: get the newest text, patch and mount the known apps;
 *  - {@code OEM_TEXT_REMOVE}: take every copy away, delete the folder;
 *  - {@code OEM_TEXT_STATUS}: no change.
 *
 * Install and Remove only start the job; it runs on {@link OemText}'s
 * worker. Send them ordered: the result data is "started" or "busy", and
 * the result extras are {@link OemText#status} (STATUS answers the same way,
 * with the state as result data). The page asks STATUS again while it is
 * open.
 *
 * Exported because Drive Assist is another app. A caller can only apply the
 * text from the fixed repo or remove it; it cannot name a URL, a file or an
 * app. */
public class OemTextReceiver extends BroadcastReceiver {
    static final String INSTALL = "com.geely.modehelper.OEM_TEXT_INSTALL";
    static final String REMOVE = "com.geely.modehelper.OEM_TEXT_REMOVE";
    static final String STATUS = "com.geely.modehelper.OEM_TEXT_STATUS";

    @Override public void onReceive(Context ctx, Intent i) {
        String a = i.getAction();
        String code;
        if (INSTALL.equals(a) || REMOVE.equals(a)) {
            if (OemText.busy()) {
                code = "busy";
            } else {
                if (INSTALL.equals(a)) OemText.install(ctx); else OemText.remove(ctx);
                code = "started";
            }
        } else if (STATUS.equals(a)) {
            code = null;
        } else {
            return;
        }
        if (!isOrderedBroadcast()) return;
        Bundle b = OemText.status(ctx);
        // A job just queued may not have set its step yet: say busy now, so
        // the page does not flash the old state.
        if ("started".equals(code)) b.putString("state", "busy");
        setResult(Activity.RESULT_OK, code != null ? code : b.getString("state"), b);
    }
}
