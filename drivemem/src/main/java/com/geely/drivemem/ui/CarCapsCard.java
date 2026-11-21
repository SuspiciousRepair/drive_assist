package com.geely.drivemem.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.util.CarCaps;
import com.geely.drivemem.util.Style;

/** The "Car equipment" card: whether the car reports the 360 cameras the
 * dashcam needs, and the values behind that answer, for an owner to send in
 * an issue when the answer is wrong. On the System page always, and on the
 * Dashcam page in place of the recording settings when the dashcam is off
 * for this reason. Type is large because the owner photographs it. */
final class CarCapsCard {
    private CarCapsCard() { }

    static LinearLayout card(Context c, CarCaps caps) {
        LinearLayout card = ConfigCards.card(c, c.getString(R.string.caps_header));
        card.addView(line(c, c.getString(status(caps)), Style.TEXT, false));
        if (caps != null && !caps.config.isEmpty()) {
            card.addView(line(c, c.getString(R.string.caps_readings,
                caps.type, caps.variant, caps.avm, caps.rvc, caps.dvr,
                CarCaps.shown(caps.aeb), CarCaps.shown(caps.ambient), CarCaps.shown(caps.rhd)), Style.TEXT, true));
            card.addView(line(c, CarCaps.grouped(caps.config), Style.TEXT, true));
        }
        card.addView(line(c, c.getString(R.string.caps_report), Style.TEXT_DIM, false));
        return card;
    }

    static int status(CarCaps caps) {
        if (caps == null || caps.cameras == CarCaps.Cameras.UNKNOWN) return R.string.caps_cameras_unknown;
        return caps.cameras == CarCaps.Cameras.YES ? R.string.caps_cameras_yes : R.string.caps_cameras_no;
    }

    private static TextView line(Context c, String text, int color, boolean mono) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(17);
        if (mono) t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(0, 0, 0, Style.dp(c, 8));
        return t;
    }
}
