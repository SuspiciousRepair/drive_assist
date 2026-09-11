package com.geely.drivemem.ui;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

/** Diagnostic activity for HVAC properties. Reads temperature and air
 * settings across multiple zones. Runs on CarActor's shared thread. */
public class HvacProbe extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView out;

    // {propId, "name", type: 'f'=float 'i'=int}
    static final Object[][] PROPS = {
        {0x15600503, "ZONED_TEMP_SETPOINT", 'f'},
        {0x21401021, "HVAC_TEMPERATURE_SET", 'f'},
        {0x21601022, "HVAC_TEMPERATURE_LV_SET", 'i'},
        {0x2140101d, "HVAC_IN_OUT_TEMP", 'i'},
        {0x2140a377, "AC_AMBIENT_TEMP", 'f'},
        {0x2140a376, "AC_AMBIENT_TEMP_INVALID", 'i'},
        {0x2140a373, "AC_BLOWER_AUTO_CONTROL_STATUS", 'i'},
        {0x2140a368, "AC_PTC_HEAT", 'i'},
        {0x2140a353, "AC_SWHEAT", 'i'},
        {0x2140a348, "AC_AIRINQLEVEL", 'i'},
        {0x2140a349, "AC_AIROUTQLEVEL", 'i'},
        {0x2140100f, "HVAC_ENG_HEAT_TEMP_CFG", 'i'},
        {0x15400500, "ZONED_FAN_SPEED_SETPOINT", 'i'},
        {0x2140105c, "HVAC_WORK_MODE", 'i'},
    };
    static final int[] AREAS = {0, 1, 4, 31, 44, 75};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        out = new TextView(this);
        out.setTextSize(13);
        out.setPadding(24,24,24,24);
        out.setText("Lendo HVAC...");
        sv.addView(out);
        setContentView(sv);

        CarActor.get(this).runOnCarThread(() -> {
            CarAccess car = CarActor.get(this).rawAccess();
            if (!car.isReady() && !car.connect(getApplicationContext())) { post("FALHA conectar Car"); return; }
            StringBuilder sb = new StringBuilder();
            for (Object[] p : PROPS) {
                int prop = (Integer) p[0]; String name = (String) p[1]; char t = (Character) p[2];
                String line = name + " (0x" + Integer.toHexString(prop) + "): ";
                boolean any = false;
                for (int a : AREAS) {
                    String v = car.readAny(prop, a, t);
                    if (v != null) { line += "[a" + a + "=" + v + "] "; any = true; }
                }
                if (!any) line += "(sem leitura)";
                sb.append(line).append("\n\n");
            }
            post(sb.toString());
        });
    }

    private void post(String s) { ui.post(() -> out.setText(s)); }
}
