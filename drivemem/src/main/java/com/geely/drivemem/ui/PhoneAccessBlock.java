package com.geely.drivemem.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.web.PhoneAccess;

import io.nayuki.qrcodegen.QrCode;

/** "Phone access" card of the System section: when the phone page runs
 * (Off / Auto / On, like ADB), the QR code a phone scans to use it, and
 * "Forget paired phones". See web/PhoneAccess. */
final class PhoneAccessBlock {
    private PhoneAccessBlock() {}

    static LinearLayout card(Activity a) {
        LinearLayout card = ConfigCards.card(a, a.getString(R.string.phone_header));
        card.addView(ConfigCards.hint(a, a.getString(R.string.phone_card_hint)));

        LinearLayout modes = new LinearLayout(a);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        TextView modeHint = ConfigCards.hint(a, null);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            modes.removeAllViews();
            String now = PhoneAccess.mode(a);
            String[][] all = {
                {PhoneAccess.OFF, a.getString(R.string.phone_mode_off)},
                {PhoneAccess.AUTO, a.getString(R.string.phone_mode_auto)},
                {PhoneAccess.ON, a.getString(R.string.phone_mode_on)},
            };
            for (int i = 0; i < all.length; i++) {
                final String id = all[i][0];
                TextView b = Style.cardButton(a, all[i][1], id.equals(now), () -> {
                    PhoneAccess.setMode(a, id);
                    draw[0].run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.setMarginStart(Style.dp(a, 10));
                b.setLayoutParams(lp);
                modes.addView(b);
            }
            modeHint.setText(a.getString(PhoneAccess.ON.equals(now) ? R.string.phone_mode_on_hint
                : PhoneAccess.AUTO.equals(now) ? R.string.phone_mode_auto_hint : R.string.phone_mode_off_hint));
        };
        draw[0].run();
        card.addView(modes);
        card.addView(modeHint);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Style.button(a, a.getString(R.string.phone_show_qr), Style.ACCENT, () -> showQr(a)));
        android.view.View spacer = new android.view.View(a);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(a, 10), 1));
        row.addView(spacer);
        row.addView(Style.button(a, a.getString(R.string.phone_forget), 0xFF8A3A3A, () -> {
            PhoneAccess.forgetPhones(a);
            Toast.makeText(a, a.getString(R.string.phone_forgotten), Toast.LENGTH_LONG).show();
        }));
        card.addView(row);
        return card;
    }

    /** The QR code. The page runs while it is on screen, whatever the mode. */
    static void showQr(Activity a) {
        if (!CarState.isParked()) {
            Toast.makeText(a, a.getString(R.string.phone_park_first), Toast.LENGTH_LONG).show();
            return;
        }
        String host = PhoneAccess.localAddress();
        if (host == null) {
            Toast.makeText(a, a.getString(R.string.phone_no_network), Toast.LENGTH_LONG).show();
            return;
        }
        String url = PhoneAccess.url(host, PhoneAccess.token(a));

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Style.dp(a, 24);
        box.setPadding(pad, pad, pad, 0);
        ImageView img = new ImageView(a);
        int side = Style.dp(a, 480);
        img.setImageBitmap(qr(url, side));
        box.addView(img, new LinearLayout.LayoutParams(side, side));
        // The address without the token: a phone that already scanned once
        // can type it, and the token is not on screen as text.
        TextView addr = new TextView(a);
        addr.setText("http://" + host + ":" + PhoneAccess.PORT);
        addr.setTextSize(22);
        addr.setGravity(Gravity.CENTER);
        addr.setPadding(0, Style.dp(a, 12), 0, 0);
        box.addView(addr);

        AlertDialog d = new AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.phone_qr_title))
            .setMessage(a.getString(R.string.phone_qr_msg))
            .setView(box)
            .setPositiveButton(a.getString(R.string.phone_qr_done), null)
            .create();
        d.setOnShowListener(x -> PhoneAccess.hold(a, true));
        d.setOnDismissListener(x -> PhoneAccess.hold(a, false));
        d.show();
    }

    /** Black modules on white, with the 4-module quiet zone scanners need. */
    static Bitmap qr(String text, int side) {
        QrCode q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int modules = q.size + 8;
        int scale = Math.max(1, side / modules);
        int px = modules * scale;
        int[] pixels = new int[px * px];
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int mx = x / scale - 4, my = y / scale - 4;
                boolean dark = mx >= 0 && my >= 0 && mx < q.size && my < q.size && q.getModule(mx, my);
                pixels[y * px + x] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888);
    }
}
