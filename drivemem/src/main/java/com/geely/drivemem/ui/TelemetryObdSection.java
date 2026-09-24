package com.geely.drivemem.ui;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.content.SharedPreferences;

import com.geely.drivemem.R;
import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.util.Style;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** OBD2 dongle + ABRP panel of the Config screen -- OBD2 connection status,
 * ABRP upload configuration, and a live debug panel showing exactly what's
 * being sent to ABRP, when, and how it went. The debug display is updated
 * via pushed listener callbacks from Obd2Reader and AbrpUploader, not a
 * polling loop.
 *
 * Extracted from TelemetryActivity.buildObd() to encapsulate a section with
 * live-updating UI; listener management (subscription/unsubscription) remains
 * in TelemetryActivity for lifecycle consistency. */
public final class TelemetryObdSection extends LinearLayout {
    private final TextView obdStatus;
    private final EditText fAbrpToken;
    private final LinearLayout obdFields;
    private final LinearLayout abrpFields;
    private final TextView abrpMeta;
    private final TextView logText;

    public TelemetryObdSection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        // Two columns: controls on the left (unchanged), a live debug panel
        // on the right showing exactly what's being sent to ABRP, when, and
        // how it went -- requested live while chasing the wrong-request-
        // format bug, so the next problem doesn't need a logcat session to
        // diagnose. Local `left`/`right` columns, NOT a reassignment of the
        // shared `content` field -- this section is the only one that
        // splits into columns, and every other buildXxx() still expects
        // `content` to mean the whole right-hand panel.
        LinearLayout cols = new LinearLayout(activity);
        cols.setOrientation(HORIZONTAL);
        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.setPadding(0, 0, Style.dp(activity, 16), 0);
        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.setPadding(Style.dp(activity, 16), 0, 0, 0);
        cols.addView(left);
        cols.addView(right);
        addView(cols);

        left.addView(Style.header(activity, activity.getString(R.string.obd_title)));

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
        TextView obdHint = new TextView(activity);
        obdHint.setTextColor(Style.TEXT_DIM); obdHint.setTextSize(13);
        obdHint.setText(activity.getString(R.string.obd_hint));
        obdHint.setPadding(0, 0, 0, Style.dp(activity, 4));
        left.addView(obdHint);

        left.addView(Style.header(activity, activity.getString(R.string.abrp_header)));
        left.addView(Style.toggleRow(activity, activity.getString(R.string.abrp_enable_label),
            prefs.getBoolean("abrp_enabled", false),
            on -> prefs.edit().putBoolean("abrp_enabled", on).apply()));

        left.addView(Style.toggleRow(activity, activity.getString(R.string.abrp_location_label),
            prefs.getBoolean(AbrpUploader.PREF_SEND_LOCATION, true),
            on -> prefs.edit().putBoolean(AbrpUploader.PREF_SEND_LOCATION, on).apply()));

        TextView abrpLocHint = new TextView(activity);
        abrpLocHint.setTextColor(Style.TEXT_DIM); abrpLocHint.setTextSize(13);
        abrpLocHint.setText(activity.getString(R.string.abrp_location_hint));
        abrpLocHint.setPadding(0, 0, 0, Style.dp(activity, 4));
        left.addView(abrpLocHint);

        fAbrpToken = Style.field(activity, left, activity.getString(R.string.abrp_field_user_token),
            prefs.getString("abrp_user_token", ""), InputType.TYPE_CLASS_TEXT);

        TextView abrpStatus = new TextView(activity);
        abrpStatus.setTextColor(Style.TEXT_DIM); abrpStatus.setTextSize(13);
        abrpStatus.setPadding(0, Style.dp(activity, 8), 0, 0);
        left.addView(abrpStatus);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, Style.dp(activity, 4), 0, 0);
        row.addView(Style.action(activity, activity.getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            prefs.edit()
                .putString("abrp_user_token", fAbrpToken.getText().toString().trim())
                .apply();
            // Saving is the moment to actually PROVE the credentials work,
            // not wait for a real drive/charge -- see AbrpUploader
            // .testConnect()'s own header for why ABRP's token page looked
            // like it kept "creating a new id" (it was just staying
            // pending, never having received a real post yet).
            abrpStatus.setText(activity.getString(R.string.abrp_test_sending));
            AbrpUploader.testConnect(activity, (ok, detail) -> {
                // Update abrpStatus on UI thread (caller handles that via runOnUiThread)
                abrpStatus.setText(activity.getString(ok ? R.string.abrp_test_ok : R.string.abrp_test_fail, detail));
            });
        }));
        left.addView(row);

        // ---- right column: live debug, updated via pushed listener callbacks ----
        right.addView(Style.header(activity, activity.getString(R.string.abrp_debug_header)));

        TextView obdDataLabel = sectionLabel(activity, activity.getString(R.string.obd_title));
        right.addView(obdDataLabel);
        obdFields = new LinearLayout(activity);
        obdFields.setOrientation(VERTICAL);
        right.addView(obdFields);

        TextView abrpDataLabel = sectionLabel(activity, activity.getString(R.string.abrp_debug_sent_header));
        abrpDataLabel.setPadding(0, Style.dp(activity, 14), 0, 0);
        right.addView(abrpDataLabel);
        abrpFields = new LinearLayout(activity);
        abrpFields.setOrientation(VERTICAL);
        right.addView(abrpFields);

        abrpMeta = new TextView(activity);
        abrpMeta.setTextColor(Style.TEXT_DIM); abrpMeta.setTextSize(13);
        abrpMeta.setPadding(0, Style.dp(activity, 6), 0, 0);
        right.addView(abrpMeta);

        TextView logHeader = sectionLabel(activity, activity.getString(R.string.abrp_debug_log_header));
        logHeader.setPadding(0, Style.dp(activity, 14), 0, 4);
        right.addView(logHeader);

        ScrollView logScroll = new ScrollView(activity);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(activity, 260)));
        logText = new TextView(activity);
        logText.setTextColor(Style.TEXT_DIM); logText.setTextSize(11);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logText);
        right.addView(logScroll);
    }

    /** Updates the OBD status and connection display. Called when OBD2Reader
     * connection state changes. */
    public void updateObdDisplay() {
        // This method is called from a listener callback in TelemetryActivity.buildObd(),
        // which handles runOnUiThread().
        // Render OBD data display
        List<String> obdLines = new ArrayList<>();
        obdLines.add("Connected: " + (Obd2Reader.isConnected() ? "Yes" : "No"));
        obdLines.add(fieldLine("SOC", Obd2Reader.freshSoc(600_000), "%"));
        obdLines.add(fieldLine("Voltage", Obd2Reader.freshVoltage(600_000), "V"));
        obdLines.add(fieldLine("Current", Obd2Reader.freshCurrent(600_000), "A"));
        obdLines.add(fieldLine("Power", Obd2Reader.freshPowerKw(600_000), "kW"));
        obdLines.add(fieldLine("Battery temp", Obd2Reader.freshBattTempC(600_000), "°C"));
        setDebugLines(obdFields, obdLines);
    }

    /** Updates the ABRP debug display. Called when ABRP sends telemetry or
     * when the uploader state changes. */
    public void updateAbrpDisplay() {
        // This method is called from a listener callback in TelemetryActivity.buildObd(),
        // which handles runOnUiThread().
        List<String> abrpLines = new ArrayList<>();
        JSONObject tlm = AbrpUploader.lastTlmSent();
        if (tlm != null) {
            java.util.Iterator<String> keys = tlm.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                abrpLines.add(k + ": " + tlm.opt(k) + abrpUnit(k));
            }
        } else {
            abrpLines.add("—");
        }
        setDebugLines(abrpFields, abrpLines);

        long lastAt = AbrpUploader.lastAttemptAtMs();
        if (lastAt > 0) {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(lastAt));
            abrpMeta.setText(AbrpUploader.lastAttemptOk()
                ? ("Last OK: " + ts)
                : ("Last FAIL: " + ts + " (" + String.valueOf(AbrpUploader.lastErrorDetail()) + ")"));
        } else {
            abrpMeta.setText("—");
        }

        StringBuilder sb = new StringBuilder();
        for (String line : AbrpUploader.recentDebugLog()) sb.append(line).append('\n');
        logText.setText(sb.length() > 0 ? sb.toString() : "—");
    }

    /** Updates the OBD status text (Connected/Searching/Off). Called when
     * OBD2Reader connection state changes. */
    public void updateObdStatus(Activity activity, boolean enabled) {
        obdStatus.setText(activity.getString(!enabled ? R.string.obd_status_off
            : Obd2Reader.isConnected() ? R.string.obd_status_connected
            : R.string.obd_status_searching));
    }

    // ABRP field name -> display unit, for the raw key:value dump in the
    // debug panel's "Sent to ABRP" section (its own OBD2/Style-formatted
    // fields elsewhere already carry units via fieldLine()).
    private static String abrpUnit(String key) {
        switch (key) {
            case "soc": return "%";
            case "power": return " kW";
            case "speed": return " km/h";
            case "voltage": return " V";
            case "current": return " A";
            case "batt_temp":
            case "ext_temp": return " °C";
            case "odometer":
            case "est_battery_range": return " km";
            case "elevation": return " m";
            case "heading": return "°";
            default: return "";
        }
    }

    private static TextView sectionLabel(Activity activity, String text) {
        TextView t = new TextView(activity);
        t.setTextColor(Style.TEXT_DIM); t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setText(text);
        return t;
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
