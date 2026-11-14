package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.util.Style;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** ABRP page of the Config screen: the upload settings on the left, and a
 * live debug panel on the right showing exactly what is sent to ABRP, when,
 * and how it went. It used to share a page with the OBD2 dongle; split out
 * when the Integrations page gave every integration its own tile.
 *
 * The debug panel is updated by pushed AbrpUploader callbacks, subscribed in
 * TelemetryActivity (same lifecycle rule as the other sections). */
public final class TelemetryAbrpSection extends LinearLayout {
    private final EditText fAbrpToken;
    private final LinearLayout abrpFields;
    private final TextView abrpMeta;
    private final TextView logText;

    public TelemetryAbrpSection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        LinearLayout leftCol = ConfigCards.column(activity);
        LinearLayout rightCol = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, leftCol, rightCol));
        SharedPreferences prefs = activity.getSharedPreferences("drivemem", 0);

        LinearLayout left = ConfigCards.card(activity, activity.getString(R.string.abrp_header));
        ConfigCards.add(leftCol, left);
        left.addView(Style.toggleRow(activity, activity.getString(R.string.abrp_enable_label),
            prefs.getBoolean("abrp_enabled", false),
            on -> prefs.edit().putBoolean("abrp_enabled", on).apply()));

        left.addView(Style.toggleRow(activity, activity.getString(R.string.abrp_location_label),
            prefs.getBoolean(AbrpUploader.PREF_SEND_LOCATION, true),
            on -> prefs.edit().putBoolean(AbrpUploader.PREF_SEND_LOCATION, on).apply()));

        left.addView(ConfigCards.hint(activity, activity.getString(R.string.abrp_location_hint)));

        fAbrpToken = Style.field(activity, left, activity.getString(R.string.abrp_field_user_token),
            prefs.getString("abrp_user_token", ""), InputType.TYPE_CLASS_TEXT);

        TextView abrpStatus = new TextView(activity);
        abrpStatus.setTextColor(Style.TEXT_DIM); abrpStatus.setTextSize(13);
        abrpStatus.setPadding(0, Style.dp(activity, 8), 0, 0);
        left.addView(abrpStatus);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, Style.dp(activity, 4), 0, 0);
        row.addView(Style.button(activity, activity.getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
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
        LinearLayout right = ConfigCards.card(activity, activity.getString(R.string.abrp_debug_header));
        ConfigCards.add(rightCol, right);

        TextView abrpDataLabel = sectionLabel(activity, activity.getString(R.string.abrp_debug_sent_header));
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
