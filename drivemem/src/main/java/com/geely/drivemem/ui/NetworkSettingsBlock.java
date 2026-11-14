package com.geely.drivemem.ui;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.AdbGate;
import com.geely.drivemem.controls.WifiKeep;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

/** "Wi-Fi & ADB" block of the System section: Keep Wi-Fi on, the ADB switch,
 * and the home (privileged) Wi-Fi where ADB stays open.
 *
 * These used to sit at the bottom of the MQTT screen, which is not where
 * anyone looks for them (issues #11, #14). A block inside System, not a new
 * sidebar section — the sidebar is long enough. */
final class NetworkSettingsBlock {
    private NetworkSettingsBlock() {}

    /** The whole block as one Config card, ready for ConfigCards.add(). */
    static LinearLayout card(Activity a) {
        LinearLayout parent = ConfigCards.card(a, a.getString(R.string.cfg_network_header));

        // ---- Keep Wi-Fi on ----
        final TextView keepHint = hint(a);
        parent.addView(Style.toggleRow(a, a.getString(R.string.cfg_wifi_keep_label),
            Prefs.getWifiKeepOn(a), on -> {
                WifiKeep.set(a, on);
                keepHint.setText(a.getString(on ? R.string.cfg_wifi_keep_on : R.string.cfg_wifi_keep_off));
            }));
        keepHint.setText(a.getString(Prefs.getWifiKeepOn(a)
            ? R.string.cfg_wifi_keep_on : R.string.cfg_wifi_keep_off));
        parent.addView(keepHint);

        // ---- ADB ----
        final TextView adbHint = hint(a);
        parent.addView(Style.toggleRow(a, a.getString(R.string.cfg_adb_label),
            AdbGate.isEnabled(a), on -> {
                if (!AdbGate.request(a, on, AdbGate.DEFAULT_MINUTES)) {
                    adbHint.setText(a.getString(R.string.cfg_adb_no_helper));
                    return;
                }
                adbHint.postDelayed(() -> adbHint.setText(a.getString(
                    AdbGate.isEnabled(a) ? R.string.cfg_adb_on : R.string.cfg_adb_off,
                    AdbGate.DEFAULT_MINUTES)), 900);
            }));
        adbHint.setText(a.getString(AdbGate.isEnabled(a)
            ? R.string.cfg_adb_on : R.string.cfg_adb_off, AdbGate.DEFAULT_MINUTES));
        parent.addView(adbHint);

        // ---- Home (privileged) Wi-Fi ----
        final EditText fSsid = Style.field(a, parent, a.getString(R.string.cfg_trusted_wifi_label),
            AdbGate.getTrustedWifi(a), InputType.TYPE_CLASS_TEXT);
        TextView ssidHint = hint(a);
        ssidHint.setText(a.getString(R.string.cfg_trusted_wifi_hint));
        parent.addView(ssidHint);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Style.button(a, a.getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            AdbGate.setTrustedWifi(a, fSsid.getText() != null ? fSsid.getText().toString() : "");
            Toast.makeText(a, a.getString(R.string.cfg_trusted_wifi_saved), Toast.LENGTH_SHORT).show();
        }));
        android.view.View spacer = new android.view.View(a);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(a, 10), 1));
        row.addView(spacer);
        row.addView(Style.button(a, a.getString(R.string.cfg_btn_use_current_wifi), 0xFF3A5A7A, () -> {
            String current = AdbGate.getCurrentSsid(a);
            if (current.isEmpty()) return;
            fSsid.setText(current);
            AdbGate.setTrustedWifi(a, current);
            Toast.makeText(a, a.getString(R.string.cfg_trusted_wifi_saved), Toast.LENGTH_SHORT).show();
        }));
        parent.addView(row);
        return parent;
    }

    private static TextView hint(Activity a) {
        return ConfigCards.hint(a, null);
    }
}
