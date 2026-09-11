package com.geely.drivemem.ui;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.Telemetry;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

/** Diagnostic activity for telemetry properties. Reads and displays available
 * car properties and their types. Runs on CarActor's shared thread. */
public class TeleProbe extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView out;

    // {propId, name, type} — tries float and int
    static final Object[][] P = {
        {291504647, "PERF_VEHICLE_SPEED", 'f'},
        {291504648, "PERF_SPEED_DISPLAY", 'i'},
        {289408001, "CURRENT_GEAR", 'i'},
        {291504644, "PERF_ODOMETER", 'f'},
        {289407752, "RANGE_REMAINING", 'f'},
        {557885165, "EV_BATTERY_PCT", 'f'},
        {559982316, "AUX_12V_VOLTAGE", 'f'},
        {559982315, "BATT_ENERGY_FLOW", 'f'},
        {559982313, "DRIVING_ENERGY_FLOW", 'f'},
        {557885176, "AVG_CONSUMPTION", 'f'},
        {557884279, "AMBIENT_TEMP(ext)", 'i'},
        {557884281, "INSIDE_TEMP", 'i'},
        {658548345, "OUTSIDE_TEMP_V", 'i'},
        {557884439, "AC_FAN_LEVEL", 'i'},
        {557884965, "AC_POWER", 'i'},
    };
    static final int[] AREAS = {0, 1, 16777216};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        out = new TextView(this); out.setTextSize(13); out.setPadding(20,20,20,20);
        out.setText("Lendo telemetria..."); sv.addView(out); setContentView(sv);

        CarActor.get(this).runOnCarThread(() -> {
            CarAccess car = CarActor.get(this).rawAccess();
            if (!car.isReady() && !car.connect(getApplicationContext())) { post("sem car"); return; }
            StringBuilder sb = new StringBuilder("TELEMETRIA (o que responde):\n\n");
            for (Object[] p : P) {
                int prop = (Integer) p[0]; String name = (String) p[1]; char t = (Character) p[2];
                String got = null;
                for (int a : AREAS) {
                    String v = car.readAny(prop, a, t);
                    if (v == null) { char t2 = (t == 'f') ? 'i' : 'f'; v = car.readAny(prop, a, t2); }
                    if (v != null) { got = "a" + a + "=" + v; break; }
                }
                sb.append(got != null ? "OK  " : "--  ").append(name).append(got != null ? "  " + got : "").append("\n");
            }
            post(sb.toString());
        });
    }
    private void post(String s) { ui.post(() -> out.setText(s)); }
}
