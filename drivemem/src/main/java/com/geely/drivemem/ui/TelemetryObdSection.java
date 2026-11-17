package com.geely.drivemem.ui;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.content.SharedPreferences;

import com.geely.drivemem.R;
import com.geely.drivemem.sensors.CanEnergyReader;
import com.geely.drivemem.sensors.CanHub;
import com.geely.drivemem.sensors.EnergyHub;
import com.geely.drivemem.sensors.ObdHub;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.util.Style;

import java.util.ArrayList;
import java.util.List;

/** OBD2 dongle page of the Config screen -- connection status and switch on
 * the left, the live values it reads on the right. Updated via pushed
 * Obd2Reader callbacks, not a polling loop. ABRP used to share this page; it
 * has its own now (TelemetryAbrpSection), and the CAN switch moved to the
 * System page because CAN is the car's own data, not an integration.
 *
 * Extracted from TelemetryActivity.buildObd() to encapsulate a section with
 * live-updating UI; listener management (subscription/unsubscription) remains
 * in TelemetryActivity for lifecycle consistency. */
public final class TelemetryObdSection extends LinearLayout {
    private final TextView obdStatus;
    private final LinearLayout obdFields;
    private final LinearLayout canFields;

    public TelemetryObdSection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        // Two columns: the switch on the left, the live values on the right,
        // so a dongle problem does not need a logcat session to diagnose.
        LinearLayout leftCol = ConfigCards.column(activity);
        LinearLayout rightCol = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, leftCol, rightCol));

        // `left` is the OBD card; `right` is the live data it reads.
        LinearLayout left = ConfigCards.card(activity, activity.getString(R.string.obd_title));
        ConfigCards.add(leftCol, left);

        obdStatus = new TextView(activity);
        obdStatus.setTextColor(Style.TEXT); obdStatus.setTextSize(16);
        obdStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        left.addView(obdStatus);

        // Was a 2s self-rescheduling poll of Obd2Reader.isConnected() — a
        // snapshot read can't tell "still true" apart from "went false and
        // came back without me noticing," which is exactly how a real
        // disconnect once stayed on screen as "Connected" for minutes (see
        // ClassicChannel's own `alive` fix). Subscribing means the UI
        // updates the instant the state actually changes, from whichever
        // thread noticed — same edge-triggered idea, just pushed instead of
        // polled.
        SharedPreferences prefs = activity.getSharedPreferences("drivemem", 0);
        left.addView(Style.toggleRow(activity, activity.getString(R.string.obd_enable_label),
            prefs.getBoolean("obd2_enabled", false), on -> {
                Obd2Reader.setEnabled(activity, on);
                obdStatus.setText(activity.getString(on
                    ? R.string.obd_status_searching : R.string.obd_status_off));
            }));
        left.addView(ConfigCards.hint(activity, activity.getString(R.string.obd_hint)));

        // ---- right column: live debug, updated via pushed listener callbacks ----
        LinearLayout right = ConfigCards.card(activity, activity.getString(R.string.obd_live_header));
        ConfigCards.add(rightCol, right);

        obdFields = new LinearLayout(activity);
        obdFields.setOrientation(VERTICAL);
        right.addView(obdFields);

        // The car's own CAN values under the dongle's, to compare the two by
        // eye while driving. CAN is not an integration and has no page of its
        // own (its switch is in System); this is only the comparison.
        TextView canLabel = new TextView(activity);
        canLabel.setTextColor(Style.TEXT_DIM); canLabel.setTextSize(13);
        canLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        canLabel.setText(activity.getString(R.string.obd_compare_can));
        canLabel.setPadding(0, Style.dp(activity, 14), 0, 0);
        right.addView(canLabel);
        canFields = new LinearLayout(activity);
        canFields.setOrientation(VERTICAL);
        right.addView(canFields);
    }

    /** Updates the OBD status and connection display. Called when OBD2Reader
     * connection state changes. */
    public void updateObdDisplay() {
        // This method is called from a listener callback in TelemetryActivity.buildObd(),
        // which handles runOnUiThread().
        // Render OBD data display
        List<String> obdLines = new ArrayList<>();
        // All from the hub (CLAUDE.md rule 7).
        com.geely.drivemem.car.CarActor hub = com.geely.drivemem.car.CarActor.get();
        obdLines.add("Connected: " + (ObdHub.connected(hub) ? "Yes" : "No"));
        obdLines.add(fieldLine("SOC", ObdHub.get(ObdHub.SOC), "%"));
        obdLines.add(fieldLine("Voltage", ObdHub.get(ObdHub.PACK_V), "V"));
        obdLines.add(fieldLine("Current", ObdHub.get(ObdHub.PACK_A), "A"));
        obdLines.add(fieldLine("Power", ObdHub.get(ObdHub.POWER_KW), "kW"));
        obdLines.add(fieldLine("Battery temp", ObdHub.get(ObdHub.BATT_TEMP_C), "°C"));
        Object src = hub != null ? hub.get(EnergyHub.SOURCE).value : null;
        obdLines.add("Energy source: " + (src != null ? src : "estimate (SoC)"));
        setDebugLines(obdFields, obdLines);

        List<String> canLines = new ArrayList<>();
        canLines.add("Status: " + CanEnergyReader.status());
        canLines.add(fieldLine("Voltage", CanHub.get(CanHub.PACK_V), "V"));
        canLines.add(fieldLine("Current", CanHub.get(CanHub.PACK_A), "A"));
        canLines.add(fieldLine("Power", CanHub.get(CanHub.POWER_KW), "kW"));
        canLines.add(fieldLine("Drive", CanHub.get(CanHub.DRIVE_KW), "kW"));
        canLines.add(fieldLine("Climate", EnergyHub.get(EnergyHub.CLIMATE_KW), "kW"));
        canLines.add(fieldLine("Other (12 V, losses)", EnergyHub.get(EnergyHub.OTHER_KW), "kW"));
        setDebugLines(canFields, canLines);
    }

    /** Updates the OBD status text (Connected/Searching/Off). Called when
     * OBD2Reader connection state changes. */
    public void updateObdStatus(Activity activity, boolean enabled) {
        obdStatus.setText(activity.getString(!enabled ? R.string.obd_status_off
            : ObdHub.connected(com.geely.drivemem.car.CarActor.get()) ? R.string.obd_status_connected
            : R.string.obd_status_searching));
    }

    private static String fieldLine(String label, Float value, String unit) {
        return label + ": " + (value != null ? value + " " + unit : "—");
    }

    private static void setDebugLines(LinearLayout container, List<String> lines) {
        container.removeAllViews();
        for (String line : lines) {
            TextView t = new TextView(container.getContext());
            t.setTextColor(Style.TEXT); t.setTextSize(13);
            t.setText(line);
            container.addView(t);
        }
    }
}
