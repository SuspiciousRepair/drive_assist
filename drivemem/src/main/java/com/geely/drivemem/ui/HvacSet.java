package com.geely.drivemem.ui;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

/** Diagnostic activity for HVAC fan direction. Reads current settings
 * across multiple areas. Runs on CarActor's shared thread. */
public class HvacSet extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView out;

    static final int DIR = 0x21401020;       // HVAC_FAN_DIRECTION
    static final int DIR_AUTO = 0x2140a682;  // HVAC_FAN_DIRECTION_AUTO
    static final int ZONED_DIR = 0x15400501; // ZONED_FAN_DIRECTION
    static final int[] AREAS = {0, 1, 4, 75, 31, 44};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        out = new TextView(this); out.setTextSize(15); out.setPadding(24,24,24,24);
        out.setText("..."); sv.addView(out); setContentView(sv);

        CarActor.get(this).runOnCarThread(() -> {
            CarAccess car = CarActor.get(this).rawAccess();
            if (!car.isReady() && !car.connect(getApplicationContext())) { post("sem car"); return; }
            StringBuilder sb = new StringBuilder("Direcao do ar (leia o modo atual da tela do carro):\n\n");
            probe(car, sb, "HVAC_FAN_DIRECTION", DIR);
            probe(car, sb, "ZONED_FAN_DIRECTION", ZONED_DIR);
            probe(car, sb, "HVAC_FAN_DIRECTION_AUTO", DIR_AUTO);
            post(sb.toString());
        });
    }

    private void probe(CarAccess car, StringBuilder sb, String name, int prop) {
        sb.append(name).append(" (0x").append(Integer.toHexString(prop)).append("): ");
        boolean any = false;
        for (int a : AREAS) {
            String v = car.readAny(prop, a, 'i');
            if (v != null) { sb.append("[a").append(a).append("=").append(v).append("] "); any = true; }
        }
        if (!any) sb.append("(sem leitura)");
        sb.append("\n\n");
    }

    private void post(String s) { ui.post(() -> out.setText(s)); }
}
