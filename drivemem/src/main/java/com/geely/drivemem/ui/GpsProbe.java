package com.geely.drivemem.ui;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

/** Diagnostic activity for GPS and location properties. Reads location
 * data from various sources. Runs on CarActor's shared thread. */
public class GpsProbe extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView out;

    static final int GPS_INFO = 0x2140a007;
    static final int DVR_LOC = 0x21409016;
    static final int[] AREAS = {0, 1};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        out = new TextView(this); out.setTextSize(13); out.setPadding(24,24,24,24);
        out.setText("Lendo GPS..."); sv.addView(out); setContentView(sv);
        CarActor.get(this).runOnCarThread(() -> {
            CarAccess car = CarActor.get(this).rawAccess();
            if (!car.isReady() && !car.connect(getApplicationContext())) { post("sem car"); return; }
            StringBuilder sb = new StringBuilder("LOCALIZACAO:\n\n");
            probeAll(car, sb, "GPS_INFO", GPS_INFO);
            probeAll(car, sb, "DVR_CUR_LOCATION", DVR_LOC);
            // also tries it as an array (int[]/float[]) and as a string, via the raw getProperty
            post(sb.toString());
        });
    }

    private void probeAll(CarAccess car, StringBuilder sb, String name, int prop) {
        sb.append(name).append(" (0x").append(Integer.toHexString(prop)).append("):\n");
        for (int a : AREAS) {
            for (char t : new char[]{'i', 'f', 's', 'v'}) {
                String v = readTyped(car, prop, a, t);
                if (v != null) sb.append("  a").append(a).append(" [").append(t).append("] = ").append(v).append("\n");
            }
        }
        sb.append("\n");
    }

    // t: i=int, f=float, s=string (via getProperty), v=arrays
    private String readTyped(CarAccess car, int prop, int area, char t) {
        try {
            if (t == 'i') return String.valueOf(car.readAny(prop, area, 'i'));
            if (t == 'f') return car.readAny(prop, area, 'f');
            // s/v: uses the raw getProperty to see the unprocessed value
            Object cpv = car.rawGetProperty(prop, area);
            if (cpv == null) return null;
            Object val = car.cpvValue(cpv);
            if (val == null) return null;
            if (t == 's' && val instanceof String) return (String) val;
            if (t == 'v') {
                if (val instanceof Object[]) return java.util.Arrays.toString((Object[]) val);
                if (val instanceof byte[]) return java.util.Arrays.toString((byte[]) val);
                return val.getClass().getSimpleName() + ":" + val;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void post(String s) { ui.post(() -> out.setText(s)); }
}
