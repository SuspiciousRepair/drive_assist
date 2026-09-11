package com.geely.drivemem.ui;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

// TODO: are we done with this? Probing should be moved to another folder of its own.
/** Diagnostic activity for charging properties. Reads SOC, current limits,
 * and battery state. Runs on CarActor's shared thread. */
public class ChargeProbe extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView out;

    static final Object[][] P = {
        {0x214020cd, "PHEV_MAC_CHRG_SOC_SET (limite %?)", 'i'},
        {0x2140b00b, "VCU_ALLOW_CHARGE_MAX_SOC_ACT", 'i'},
        {0x2140b00c, "VCU_ODO_ALLOW_CHARGE_MAX_SOC", 'i'},
        {0x214020cc, "PHEV_CHRG_IMAX_LIMIT (corrente A?)", 'i'},
        {0x214020c4, "PHEV_CHRG_DIS_MODE", 'i'},
        {0x214020b1, "PHEV_CHRG_DIS_SET", 'i'},
        {0x2140a156, "SOC_BALANCE_POINT_SETTING", 'i'},
        {0x1120030b, "EV_CHARGE_PORT_CONNECTED", 'i'},
        {0x2160b055, "DCHA_CHARGE_ACDC_CURRENT", 'f'},
        {0x2160b056, "DCHA_CHARGE_ACDC_VOLT", 'f'},
        {0x1160030c, "EV_BATTERY_INSTANT_CHARGE_RATE", 'f'},
        {557885165, "BATTERY_PCT (atual)", 'f'},
    };
    static final int[] AREAS = {0, 1};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        out = new TextView(this); out.setTextSize(14); out.setPadding(24,24,24,24);
        out.setText("Lendo carga..."); sv.addView(out); setContentView(sv);
        CarActor.get(this).runOnCarThread(() -> {
            CarAccess car = CarActor.get(this).rawAccess();
            if (!car.isReady() && !car.connect(getApplicationContext())) { post("sem car"); return; }
            StringBuilder sb = new StringBuilder("CARGA (so leitura):\n\n");
            for (Object[] p : P) {
                int prop = (Integer) p[0]; String name = (String) p[1]; char t = (Character) p[2];
                String got = null;
                for (int a : AREAS) {
                    String v = car.readAny(prop, a, t);
                    if (v == null) { v = car.readAny(prop, a, t == 'f' ? 'i' : 'f'); }
                    if (v != null) { got = "a" + a + "=" + v; break; }
                }
                sb.append(got != null ? "OK  " : "--  ").append(name).append(got != null ? "  " + got : "").append("\n\n");
            }
            post(sb.toString());
        });
    }
    private void post(String s) { ui.post(() -> out.setText(s)); }
}
