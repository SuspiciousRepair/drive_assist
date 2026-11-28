package com.geely.sysprobe;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;

/** Diagnostic tool: tests TBox network interface as uid system. Enumerates networks,
 * binds to tbox0, and makes a minimal HTTP request (generate_204) to verify
 * connectivity and permission levels. */
public class SysProbeActivity extends Activity {
    static final String T = "SysProbe";
    private final StringBuilder ui = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setTextSize(13f); tv.setPadding(24, 40, 24, 24);
        ScrollView sv = new ScrollView(this); sv.addView(tv);
        setContentView(sv);
        line(tv, "uid=" + Process.myUid() + "  system=" + (Process.myUid() == 1000));
        new Thread(() -> probe(tv)).start();
    }

    private void probe(TextView tv) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network tbox = null;
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                String iff = (lp != null) ? lp.getInterfaceName() : null;
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                line(tv, "net " + n + " if=" + iff + "\n   caps=" + nc);
                if ("tbox0".equals(iff)) tbox = n;
            }
            if (tbox == null) { line(tv, ">> tbox0 NAO aparece como Network"); return; }

            line(tv, "tbox0 = " + tbox + " — vinculando e testando HTTP...");
            boolean bound = cm.bindProcessToNetwork(tbox);
            line(tv, "bindProcessToNetwork=" + bound);
            long t0 = System.currentTimeMillis();
            try {
                HttpURLConnection c = (HttpURLConnection)
                    new URL("https://connectivitycheck.gstatic.com/generate_204").openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "sysprobe");
                int code = c.getResponseCode();
                long dt = System.currentTimeMillis() - t0;
                line(tv, ">> HTTP generate_204 = " + code + " em " + dt + "ms  (204 = INTERNET OK pela tbox0)");
            } catch (Throwable e) {
                line(tv, ">> HTTP falhou pela tbox0: " + e);
            } finally {
                cm.bindProcessToNetwork(null);
            }
        } catch (Throwable t) {
            line(tv, "ERRO: " + t);
            Log.w(T, "probe", t);
        }
    }

    private void line(final TextView tv, final String s) {
        Log.i(T, s);
        ui.append(s).append('\n');
        runOnUiThread(() -> tv.setText(ui.toString()));
    }
}
