package com.geely.drivemem.ui;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
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

    static void addTo(Activity a, LinearLayout parent) {
        parent.addView(Style.header(a, a.getString(R.string.cfg_network_header)));

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

        // ---- Advanced: keep ADB always on ----
        // Folded away on purpose: it turns off the auto-close that is the
        // whole safety model of the switch above.
        final LinearLayout advanced = new LinearLayout(a);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        final String advLabel = a.getString(R.string.cfg_advanced);
        final TextView advToggle = Style.header(a, advLabel + "  \u25B8");
        advToggle.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText(advLabel + (show ? "  \u25BE" : "  \u25B8"));
        });
        advanced.addView(Style.toggleRow(a, a.getString(R.string.cfg_adb_always_label),
            Prefs.getAdbAlwaysOn(a), on -> {
                AdbGate.setAlwaysOn(a, on);
                adbHint.postDelayed(() -> adbHint.setText(a.getString(
                    AdbGate.isEnabled(a) ? R.string.cfg_adb_on : R.string.cfg_adb_off,
                    AdbGate.DEFAULT_MINUTES)), 900);
            }));
        TextView alwaysHint = hint(a);
        alwaysHint.setText(a.getString(R.string.cfg_adb_always_hint));
        advanced.addView(alwaysHint);
        parent.addView(advToggle);
        parent.addView(advanced);
    }

    private static TextView hint(Activity a) {
        TextView t = new TextView(a);
        t.setTextColor(Style.TEXT_DIM);
        t.setTextSize(12);
        t.setPadding(0, 0, 0, Style.dp(a, 4));
        return t;
    }
}
