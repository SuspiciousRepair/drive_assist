package com.geely.drivemem.ui;

import com.geely.drivemem.R;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDataHub;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.controls.AdbGate;
import com.geely.drivemem.controls.DoorWindow;
import com.geely.drivemem.controls.GeelySwitch;
import com.geely.drivemem.controls.Purge;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.net.CertImporter;
import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.net.MqttTls;
import com.geely.drivemem.net.Updater;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.services.OutTempService;
import com.geely.drivemem.services.SocIconService;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.services.WifiIconService;
import com.geely.drivemem.util.Modes;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.SpotifyClient;
import com.geely.drivemem.util.Style;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Drive Assist's settings, in the style of the native ones: the left sidebar picks the
// section and the right-hand panel swaps in-place (no screen change).
//   MQTT | Drive Mode | Menu bar
public class TelemetryActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;             // right-hand panel (swapped per section)
    private LinearLayout outer;               // whole-screen root -- background swapped per section, see selectSection()
    // THE ORDER OF THE NAV LIVES HERE AND NOWHERE ELSE. The index is not just a
    // position: it is passed through the "section" intent extra to survive the
    // recreate() a theme change causes, so a renumber done in one place and not
    // the other lands you on the wrong page with no error. Named, it cannot drift.
    // Order = how often you actually touch it while using the car.
    private static final int SEC_DRIVE = 0;   // driving mode
    private static final int SEC_BAR   = 1;   // what shows in the menu bar
    private static final int SEC_LOOK  = 2;   // appearance
    private static final int SEC_MQTT  = 3;   // set once and forgotten
    // Doors and glass. Its own section rather than a header buried at the bottom
    // of MQTT: everything here MOVES THE CAR, which is a different kind of
    // setting from a broker address. Appended rather than inserted, because the
    // open section survives a theme change as an intent extra and renumbering
    // would land somebody on the wrong page.
    private static final int SEC_DOORS = 4;
    // The dashcam's recordings. A SECTION and not its own screen, so the sidebar
    // is always there to leave by — which is why this one needs no back button
    // while a standalone Activity did.
    private static final int SEC_CLIPS = 5;
    // Charging session history — same "appended, never renumbered" rule.
    private static final int SEC_CHARGE = 6;
    // Driving + charging stats at a glance — same rule again.
    private static final int SEC_STATS = 7;
    // OBD2 dongle + ABRP upload — same rule again.
    private static final int SEC_OBD = 8;
    // Pulled out of buildMqtt(), where it used to just be appended at the
    // bottom with no section of its own — same rule again.
    private static final int SEC_SPOTIFY = 9;
    private static final int SEC_SYSTEM = 10;

    private final List<TextView> navItems = new ArrayList<>();
    private TextView status;                  // recreated by each panel that needs it

    // MQTT
    private EditText fUri, fUser, fPass, fInterval, fTlsTarget;
    private EditText fTrustedSsid;
    private static final int REQ_CODE_PICK_CERT = 4201;
    private TextView clientCertStatus, caCertStatus, tlsTestStatus;
    private LinearLayout clientBtnRow, caBtnRow;
    private TextView mqttLogView;
    private ScrollView mqttLogScroll;
    private TextView activeBrokerView, activeClientView, activeLastSentView;
    private LinearLayout mqttConfigContainer;
    private TelemetrySpotifySection spotifySection;
    private TelemetryClipsSection clipsSection;
    private TelemetryChargeSection chargeSection;
    private TelemetryDriveSection driveSection;
    private EditText fSkylineSeed;

    // Keeps liveDrive/liveRegen actually LIVE. Without this the border was a
    // one-time snapshot (taken on screen-open or right after Apply) that went
    // stale the moment the car changed on its own — reported live: saved
    // Sport, watched the car settle back to Eco a few seconds later, and the
    // card kept showing Sport with fill AND border because nothing was
    // listening. Event, not poll — CarActor's own "car.drive_mode"/
    // "car.regen_mode" watch (registered once, in its constructor) now,
    // not a CarPropertyManager callback this screen registers itself —
    // that used to leak a duplicate watch every time the Drive section was
    // re-visited, since it was only ever unregistered in onDestroy().
    // carActorSubscribed below is what fixes that: subscribe once, ever.
    private final EntityBus.Listener driveListener = (key, reading) -> {
        if (driveSection != null && reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer) {
            driveSection.updateModeFromCar((Integer) reading.value, -1, true, false);
        }
    };
    private final EntityBus.Listener regenListener = (key, reading) -> {
        if (driveSection != null && reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer) {
            driveSection.updateModeFromCar(-1, (Integer) reading.value, false, true);
        }
    };
    private boolean carActorSubscribed = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);                 // before any View
        section = getIntent().getIntExtra("section", SEC_DRIVE);   // come back to the same section on recreate

        Style.edgeToEdge(this);
        outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        // Background itself is set per-section in selectSection(), once the
        // initial section is known -- not here.

        // ---- sidebar ----
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setLayoutParams(new LinearLayout.LayoutParams(Style.dp(this, 240), ViewGroup.LayoutParams.MATCH_PARENT));
        int sp = Style.dp(this, 16);
        side.setPadding(sp, Style.dp(this, 22) + Style.statusBarHeight(this), sp, sp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Style.backButton(this, this::finish));
        TextView title = Style.title(this, getString(R.string.cfg_title));
        title.setTextSize(20);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Style.dp(this, 12);
        title.setLayoutParams(tlp);
        head.addView(title);
        side.addView(head);
        Style.gap(side, this, 10);

        // The list itself scrolls independently of the fixed title/back
        // row above it — CAR/DISPLAY/INTEGRATIONS plus a 10th item finally
        // ran past what the old unscrollable sidebar could reliably fit.
        ScrollView navScroll = new ScrollView(this);
        navScroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        navScroll.addView(nav);
        side.addView(navScroll);

        // Grouped by what a section actually IS, not the order it was
        // added — CAR (controls/reads the car itself), DISPLAY (the app's
        // own look, no external dependency), INTEGRATIONS (talks to an
        // outside device/service/account: a broker, a Bluetooth dongle, a
        // web API). Spotify used to just be appended at the bottom of the
        // MQTT screen with no section of its own, which is what prompted
        // sorting the rest of this out too.
        nav.addView(sideGroupLabel(getString(R.string.cfg_group_car)));
        nav.addView(navItem(getString(R.string.cfg_nav_drive), SEC_DRIVE));
        nav.addView(navItem(getString(R.string.cfg_nav_doors), SEC_DOORS));
        nav.addView(navItem(getString(R.string.cfg_nav_clips), SEC_CLIPS));

        nav.addView(sideGroupLabel(getString(R.string.cfg_group_display)));
        nav.addView(navItem(getString(R.string.cfg_nav_bar),   SEC_BAR));
        nav.addView(navItem(getString(R.string.cfg_nav_look),  SEC_LOOK));

        nav.addView(sideGroupLabel(getString(R.string.cfg_group_integrations)));
        nav.addView(navItem(getString(R.string.cfg_nav_mqtt),  SEC_MQTT));
        nav.addView(navItem(getString(R.string.cfg_nav_obd), SEC_OBD));
        nav.addView(navItem(getString(R.string.cfg_nav_spotify), SEC_SPOTIFY));
        nav.addView(sideGroupLabel(getString(R.string.cfg_group_system)));
        nav.addView(navItem(getString(R.string.cfg_nav_system), SEC_SYSTEM));
        outer.addView(side);

        // ---- right-hand panel (scrollable) ----
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int cp = Style.dp(this, 22);
        content.setPadding(cp, cp + Style.statusBarHeight(this), cp, cp);
        scroll.addView(content);
        outer.addView(scroll);

        setContentView(outer);
        selectSection(section);
    }

    private int section = 0;   // open section (survives the recreate caused by a theme change)

    // Small caption above a cluster of nav items — same idea as Style.header()
    // but sized for the 240dp sidebar rather than the content panel.
    private TextView sideGroupLabel(String label) {
        TextView t = new TextView(this);
        t.setText(label.toUpperCase(java.util.Locale.getDefault()));
        t.setTextColor(Style.blend(Style.TEXT_DIM, Style.TEXT, 0.3f));
        t.setTextSize(12);
        t.setLetterSpacing(0.05f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(this, 14);
        lp.bottomMargin = Style.dp(this, 4);
        t.setLayoutParams(lp);
        return t;
    }

    // Selected sidebar row: a thin accent bar on the left edge plus a faint
    // tinted row background, matching this car's own OEM settings menu
    // (thin blue rail + tinted label, sharp -- no glow) instead of the
    // solid filled pill this used before.
    private android.graphics.drawable.Drawable selectedNavBg() {
        android.graphics.drawable.GradientDrawable row = new android.graphics.drawable.GradientDrawable();
        row.setColor(Style.blend(Style.ACCENT, Style.cardFillColor(), 0.88f));
        android.graphics.drawable.GradientDrawable bar = new android.graphics.drawable.GradientDrawable();
        bar.setColor(Style.ACCENT);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{row, bar});
        ld.setLayerGravity(1, Gravity.LEFT | Gravity.FILL_VERTICAL);
        ld.setLayerWidth(1, Style.dp(this, 3));
        return ld;
    }

    // sidebar item: stays highlighted while it is the selected one
    private TextView navItem(String label, final int idx) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(18);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(Style.dp(this, 60));   // taller buttons, easy to hit
        t.setPadding(Style.dp(this, 16), Style.dp(this, 16), Style.dp(this, 16), Style.dp(this, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Style.dp(this, 8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> selectSection(idx));
        t.setTag(idx);   // the section this button opens — NOT its position in
                          // navItems. Doors was appended after Mqtt to keep
                          // SEC_MQTT's value stable, so list order and SEC_*
                          // order differ; the highlight has to key off this,
                          // not off the loop index below.
        navItems.add(t);
        return t;
    }

    // Only these three sections get the OEM car render as their background
    // (see Style.configScreenBg) -- everything else gets a flat fill
    // (Style.configScreenBgSolid), per the owner's explicit choice of which
    // pages should carry it.
    private boolean sectionHasCarBg(int idx) {
        return idx == SEC_DRIVE || idx == SEC_DOORS || idx == SEC_BAR;
    }

    private void selectSection(int idx) {
        section = idx;
        outer.setBackground(sectionHasCarBg(idx) ? Style.configScreenBg(this) : Style.configScreenBgSolid());
        for (TextView t : navItems) {
            boolean sel = ((Integer) t.getTag() == idx);
            t.setBackground(sel ? selectedNavBg() : null);
            t.setTextColor(sel ? Style.ACCENT : Style.TEXT_DIM);
            t.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        content.removeAllViews();
        switch (idx) {
            case SEC_BAR:   buildBar();   break;
            case SEC_LOOK:  buildLook();  break;
            case SEC_MQTT:  buildMqtt();  break;
            case SEC_DOORS: content.addView(new TelemetryDoorsSection(this)); break;
            case SEC_CLIPS:
                clipsSection = new TelemetryClipsSection(this, () -> selectSection(SEC_CLIPS));
                content.addView(clipsSection);
                break;
            case SEC_CHARGE:
                chargeSection = new TelemetryChargeSection(this);
                content.addView(chargeSection);
                break;
            case SEC_OBD: buildObd(); break;
            case SEC_SPOTIFY:
                spotifySection = new TelemetrySpotifySection(this);
                content.addView(spotifySection);
                break;
            case SEC_SYSTEM: buildSystem(); break;
            default:       // SEC_DRIVE, and the landing page
                driveSection = new TelemetryDriveSection(this);
                content.addView(driveSection);
                if (!carActorSubscribed) {
                    carActorSubscribed = true;
                    EntityBus.subscribe("car.drive_mode", driveListener);
                    EntityBus.subscribe("car.regen_mode", regenListener);
                }
                break;
        }
    }




    // =====================================================================
    // OBD2 dongle + ABRP panel
    // =====================================================================
    private EditText fAbrpToken;
    // See onDestroy() — unsubscribed there, and re-subscribed fresh each
    // time buildObd() runs (the section can be rebuilt without the Activity
    // being destroyed, same reason driveListener/regenListener need this).
    private Obd2Reader.Listener obdListener;
    private Obd2Reader.Listener obdDebugListener;
    private AbrpUploader.Listener abrpDebugListener;

    private void buildObd() {
        // Two columns: controls on the left (unchanged), a live debug panel
        // on the right showing exactly what's being sent to ABRP, when, and
        // how it went -- requested live while chasing the wrong-request-
        // format bug, so the next problem doesn't need a logcat session to
        // diagnose. Local `left`/`right` columns, NOT a reassignment of the
        // shared `content` field -- this section is the only one that
        // splits into columns, and every other buildXxx() still expects
        // `content` to mean the whole right-hand panel.
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.setPadding(0, 0, Style.dp(this, 16), 0);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.setPadding(Style.dp(this, 16), 0, 0, 0);
        cols.addView(left);
        cols.addView(right);
        content.addView(cols);

        left.addView(Style.header(this, getString(R.string.obd_title)));

        TextView obdStatus = new TextView(this);
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
        if (obdListener != null) Obd2Reader.unsubscribe(obdListener);
        Runnable updateStatus = () -> {
            boolean enabled = Prefs.getObd2Enabled(this);
            obdStatus.setText(getString(!enabled ? R.string.obd_status_off
                : Obd2Reader.isConnected() ? R.string.obd_status_connected
                : R.string.obd_status_searching));
        };
        obdListener = connected -> runOnUiThread(updateStatus);
        Obd2Reader.subscribe(obdListener);
        updateStatus.run();

        left.addView(toggleRow(getString(R.string.obd_enable_label),
            Prefs.getObd2Enabled(this), on -> {
                Obd2Reader.setEnabled(this, on);
                obdStatus.setText(getString(on
                    ? R.string.obd_status_searching : R.string.obd_status_off));
            }));
        TextView obdHint = new TextView(this);
        obdHint.setTextColor(Style.TEXT_DIM); obdHint.setTextSize(13);
        obdHint.setText(getString(R.string.obd_hint));
        obdHint.setPadding(0, 0, 0, Style.dp(this, 4));
        left.addView(obdHint);

        left.addView(Style.header(this, getString(R.string.abrp_header)));
        left.addView(toggleRow(getString(R.string.abrp_enable_label),
            Prefs.getAbrpEnabled(this),
            on -> Prefs.setAbrpEnabled(this, on)));

        left.addView(toggleRow(getString(R.string.abrp_location_label),
            Prefs.getAbrpSendLocation(this),
            on -> Prefs.setAbrpSendLocation(this, on)));

        TextView abrpLocHint = new TextView(this);
        abrpLocHint.setTextColor(Style.TEXT_DIM); abrpLocHint.setTextSize(13);
        abrpLocHint.setText(getString(R.string.abrp_location_hint));
        abrpLocHint.setPadding(0, 0, 0, Style.dp(this, 4));
        left.addView(abrpLocHint);

        fAbrpToken = field(left, getString(R.string.abrp_field_user_token),
            Prefs.getAbrpUserToken(this), InputType.TYPE_CLASS_TEXT);

        TextView abrpStatus = new TextView(this);
        abrpStatus.setTextColor(Style.TEXT_DIM); abrpStatus.setTextSize(13);
        abrpStatus.setPadding(0, Style.dp(this, 8), 0, 0);
        left.addView(abrpStatus);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Style.dp(this, 4), 0, 0);
        row.addView(action(getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            Prefs.setAbrpUserToken(this, fAbrpToken.getText().toString().trim());
            // Saving is the moment to actually PROVE the credentials work,
            // not wait for a real drive/charge -- see AbrpUploader
            // .testConnect()'s own header for why ABRP's token page looked
            // like it kept "creating a new id" (it was just staying
            // pending, never having received a real post yet).
            abrpStatus.setText(getString(R.string.abrp_test_sending));
            AbrpUploader.testConnect(this, (ok, detail) -> runOnUiThread(() ->
                abrpStatus.setText(getString(ok ? R.string.abrp_test_ok : R.string.abrp_test_fail, detail))));
        }));
        left.addView(row);

        // ---- right column: live debug, refreshed every 2s while this
        // section is open (a plain poll is fine here -- this is a
        // snapshot-in-time debug display, not a status that can mislead the
        // way a stale "Connected" elsewhere in this app once did) ----
        right.addView(Style.header(this, getString(R.string.abrp_debug_header)));

        TextView obdDataLabel = sectionLabel(getString(R.string.obd_title));
        right.addView(obdDataLabel);
        LinearLayout obdFields = new LinearLayout(this);
        obdFields.setOrientation(LinearLayout.VERTICAL);
        right.addView(obdFields);

        TextView abrpDataLabel = sectionLabel(getString(R.string.abrp_debug_sent_header));
        abrpDataLabel.setPadding(0, Style.dp(this, 14), 0, 0);
        right.addView(abrpDataLabel);
        LinearLayout abrpFields = new LinearLayout(this);
        abrpFields.setOrientation(LinearLayout.VERTICAL);
        right.addView(abrpFields);

        TextView abrpMeta = new TextView(this);
        abrpMeta.setTextColor(Style.TEXT_DIM); abrpMeta.setTextSize(13);
        abrpMeta.setPadding(0, Style.dp(this, 6), 0, 0);
        right.addView(abrpMeta);

        TextView logHeader = sectionLabel(getString(R.string.abrp_debug_log_header));
        logHeader.setPadding(0, Style.dp(this, 14), 0, 4);
        right.addView(logHeader);

        ScrollView logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 260)));
        TextView logText = new TextView(this);
        logText.setTextColor(Style.TEXT_DIM); logText.setTextSize(11);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logText);
        right.addView(logScroll);

        // PUSHED, not polled -- both Obd2Reader and AbrpUploader now notify
        // subscribers the moment they have something new (see their own
        // Listener/Reading additions), so this panel just renders on
        // demand instead of re-reading everything on a timer.
        Runnable renderObd = () -> {
            java.util.List<String> obdLines = new java.util.ArrayList<>();
            obdLines.add(getString(R.string.abrp_debug_connected,
                getString(Obd2Reader.isConnected() ? R.string.abrp_debug_yes : R.string.abrp_debug_no)));
            obdLines.add(fieldLine("SOC", Obd2Reader.freshSoc(600_000), "%"));
            obdLines.add(fieldLine("Voltage", Obd2Reader.freshVoltage(600_000), "V"));
            obdLines.add(fieldLine("Current", Obd2Reader.freshCurrent(600_000), "A"));
            obdLines.add(fieldLine("Power", Obd2Reader.freshPowerKw(600_000), "kW"));
            obdLines.add(fieldLine("Battery temp", Obd2Reader.freshBattTempC(600_000), "°C"));
            setDebugLines(obdFields, obdLines);
        };

        Runnable renderAbrp = () -> {
            java.util.List<String> abrpLines = new java.util.ArrayList<>();
            JSONObject tlm = AbrpUploader.lastTlmSent();
            if (tlm != null) {
                java.util.Iterator<String> keys = tlm.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    abrpLines.add(k + ": " + tlm.opt(k) + abrpUnit(k));
                }
            } else {
                abrpLines.add(getString(R.string.abrp_debug_no_data));
            }
            setDebugLines(abrpFields, abrpLines);

            long lastAt = AbrpUploader.lastAttemptAtMs();
            if (lastAt > 0) {
                String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(lastAt));
                abrpMeta.setText(AbrpUploader.lastAttemptOk()
                    ? getString(R.string.abrp_debug_last_ok, ts)
                    : getString(R.string.abrp_debug_last_fail, ts, String.valueOf(AbrpUploader.lastErrorDetail())));
            } else {
                abrpMeta.setText(getString(R.string.abrp_debug_no_data));
            }

            StringBuilder sb = new StringBuilder();
            for (String line : AbrpUploader.recentDebugLog()) sb.append(line).append('\n');
            logText.setText(sb.length() > 0 ? sb.toString() : "—");
        };

        if (obdDebugListener != null) Obd2Reader.unsubscribe(obdDebugListener);
        obdDebugListener = new Obd2Reader.Listener() {
            @Override public void onObd2ConnectedChanged(boolean connected) { runOnUiThread(renderObd); }
            @Override public void onObd2Reading(Obd2Reader.Reading r) { runOnUiThread(renderObd); }
        };
        Obd2Reader.subscribe(obdDebugListener);

        if (abrpDebugListener != null) AbrpUploader.unsubscribe(abrpDebugListener);
        abrpDebugListener = () -> runOnUiThread(renderAbrp);
        AbrpUploader.subscribe(abrpDebugListener);

        renderObd.run();
        renderAbrp.run();
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

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setTextColor(Style.TEXT_DIM); t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setText(text);
        return t;
    }

    private static String fieldLine(String label, Float value, String unit) {
        return label + ": " + (value != null ? value + " " + unit : "—");
    }

    private void setDebugLines(LinearLayout container, java.util.List<String> lines) {
        container.removeAllViews();
        for (String line : lines) {
            TextView t = new TextView(this);
            t.setTextColor(Style.TEXT); t.setTextSize(13);
            t.setText(line);
            container.addView(t);
        }
    }

    // =====================================================================
    // =====================================================================
    // MQTT panel
    // =====================================================================
    private void buildMqtt() {
        content.addView(Style.header(this, getString(R.string.cfg_mqtt_header)));
        status = new TextView(this);
        status.setTextColor(Style.TEXT); status.setTextSize(16);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        boolean teleOn = Prefs.getTeleEnabled(this);
        status.setText(teleOn
            ? getString(R.string.cfg_sending_every, Prefs.getTeleIntervalS(this))
            : getString(R.string.cfg_status_off));
        status.setPadding(0, 0, 0, Style.dp(this, 8));
        content.addView(status);

        // Security / Master Toggles Card at Top
        LinearLayout secCard = new LinearLayout(this);
        secCard.setOrientation(LinearLayout.VERTICAL);
        secCard.setBackground(Style.card(Style.CARD, this));
        int scPad = Style.dp(this, 12);
        secCard.setPadding(scPad, scPad, scPad, scPad);

        LinearLayout togglesRow = new LinearLayout(this);
        togglesRow.setOrientation(LinearLayout.HORIZONTAL);

        // Master Toggle: Enable MQTT
        LinearLayout leftToggle = new LinearLayout(this);
        leftToggle.setOrientation(LinearLayout.VERTICAL);
        leftToggle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        leftToggle.setPadding(0, 0, Style.dp(this, 8), 0);

        leftToggle.addView(toggleRow(getString(R.string.cfg_mqtt_enable_label),
            teleOn, on -> {
                saveAll(on);
                if (mqttConfigContainer != null) {
                    mqttConfigContainer.setVisibility(on ? View.VISIBLE : View.GONE);
                }
                logMqtt("MASTER", on ? "Telemetria MQTT HABILITADA" : "Telemetria MQTT DESABILITADA");
            }));

        TextView teleHint = new TextView(this);
        teleHint.setTextColor(Style.TEXT_DIM); teleHint.setTextSize(12);
        teleHint.setText(getString(R.string.cfg_mqtt_enable_hint));
        teleHint.setPadding(Style.dp(this, 44), 0, 0, 0);
        leftToggle.addView(teleHint);
        togglesRow.addView(leftToggle);

        // Secondary Toggle: Accept Commands from HA
        LinearLayout rightToggle = new LinearLayout(this);
        rightToggle.setOrientation(LinearLayout.VERTICAL);
        rightToggle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rightToggle.setPadding(Style.dp(this, 8), 0, 0, 0);

        boolean cmdsOn = Prefs.getCommandsEnabled(this);
        rightToggle.addView(toggleRow(getString(R.string.cfg_commands_label),
            cmdsOn, allow -> {
                Prefs.setCommandsEnabled(this, allow);
                Intent svc = new Intent(this, TelemetryService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                logMqtt("SECURITY", allow ? "Comandos remotos do HA HABILITADOS" : "Comandos remotos BLOQUEADOS");
            }));

        TextView cmdHint = new TextView(this);
        cmdHint.setTextColor(Style.TEXT_DIM); cmdHint.setTextSize(12);
        cmdHint.setText(getString(R.string.cfg_commands_hint));
        cmdHint.setPadding(Style.dp(this, 44), 0, 0, 0);
        rightToggle.addView(cmdHint);
        togglesRow.addView(rightToggle);

        secCard.addView(togglesRow);
        content.addView(secCard);

        Style.gap(content, this, 14);

        // Two-column container for all settings and live feedback
        mqttConfigContainer = new LinearLayout(this);
        mqttConfigContainer.setOrientation(LinearLayout.HORIZONTAL);
        mqttConfigContainer.setVisibility(teleOn ? View.VISIBLE : View.GONE);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.setPadding(0, 0, Style.dp(this, 12), 0);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.setPadding(Style.dp(this, 12), 0, 0, 0);

        mqttConfigContainer.addView(left);
        mqttConfigContainer.addView(right);
        content.addView(mqttConfigContainer);

        // ---- Left Column: Broker & Controls ----
        left.addView(Style.header(this, getString(R.string.cfg_broker_header)));
        String hosts = Prefs.getMqttUri(this, "tcp://homeassistant.local:1883");
        String legacyAlt = Prefs.getMqttUriAlt(this);
        if (!legacyAlt.isEmpty()) hosts = hosts + "\n" + legacyAlt;
        fUri = field(left, getString(R.string.cfg_field_hosts), hosts,
                InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_CLASS_TEXT);
        fUri.setSingleLine(false);
        fUri.setMaxLines(4);

        LinearLayout userPassRow = new LinearLayout(this);
        userPassRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout userCol = new LinearLayout(this);
        userCol.setOrientation(LinearLayout.VERTICAL);
        userCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        userCol.setPadding(0, 0, Style.dp(this, 6), 0);
        fUser = field(userCol, getString(R.string.cfg_field_user), Prefs.getMqttUser(this, "mosquitto"), InputType.TYPE_CLASS_TEXT);
        userPassRow.addView(userCol);

        LinearLayout passCol = new LinearLayout(this);
        passCol.setOrientation(LinearLayout.VERTICAL);
        passCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        passCol.setPadding(Style.dp(this, 6), 0, 0, 0);
        fPass = field(passCol, getString(R.string.cfg_field_pass), Prefs.getMqttPass(this),
                InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_CLASS_TEXT);
        userPassRow.addView(passCol);
        left.addView(userPassRow);

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalRow.setPadding(0, Style.dp(this, 8), 0, Style.dp(this, 6));

        TextView ivLbl = Style.label(this, getString(R.string.cfg_field_interval) + ":");
        ivLbl.setTextColor(Style.TEXT_DIM);
        intervalRow.addView(ivLbl);

        fInterval = new EditText(this);
        fInterval.setText(String.valueOf(Prefs.getTeleIntervalS(this)));
        fInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        fInterval.setTextColor(Style.TEXT);
        fInterval.setTextSize(16);
        fInterval.setGravity(Gravity.CENTER);
        fInterval.setBackground(Style.card(Style.CARD, this));
        int ivPadH = Style.dp(this, 12);
        int ivPadV = Style.dp(this, 8);
        fInterval.setPadding(ivPadH, ivPadV, ivPadH, ivPadV);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
            Style.dp(this, 75), ViewGroup.LayoutParams.WRAP_CONTENT);
        ivLp.setMargins(Style.dp(this, 10), 0, Style.dp(this, 6), 0);
        fInterval.setLayoutParams(ivLp);
        intervalRow.addView(fInterval);

        TextView ivUnit = new TextView(this);
        ivUnit.setText(getString(R.string.cfg_seconds_suffix));
        ivUnit.setTextColor(Style.TEXT_DIM);
        ivUnit.setTextSize(14);
        intervalRow.addView(ivUnit);
        left.addView(intervalRow);

        buildCertSection(left);

        left.addView(Style.header(this, getString(R.string.cfg_security_header)));

        final TextView adbHint = new TextView(this);
        adbHint.setTextColor(Style.TEXT_DIM); adbHint.setTextSize(12);
        adbHint.setPadding(0, 0, 0, Style.dp(this, 4));
        left.addView(toggleRow(getString(R.string.cfg_adb_label),
            AdbGate.isEnabled(this), on -> {
                if (!AdbGate.request(this, on, AdbGate.DEFAULT_MINUTES)) {
                    adbHint.setText(getString(R.string.cfg_adb_no_helper));
                    logMqtt("ADB", getString(R.string.cfg_adb_no_helper));
                    return;
                }
                logMqtt("ADB", on ? "ADB habilitado por " + AdbGate.DEFAULT_MINUTES + " min" : "ADB desabilitado");
                adbHint.postDelayed(() -> adbHint.setText(getString(
                    AdbGate.isEnabled(this)
                        ? R.string.cfg_adb_on : R.string.cfg_adb_off,
                    AdbGate.DEFAULT_MINUTES)), 900);
            }));
        adbHint.setText(getString(AdbGate.isEnabled(this)
            ? R.string.cfg_adb_on : R.string.cfg_adb_off, AdbGate.DEFAULT_MINUTES));
        left.addView(adbHint);

        fTrustedSsid = field(left, getString(R.string.cfg_trusted_wifi_label),
            AdbGate.getTrustedWifi(this), InputType.TYPE_CLASS_TEXT);

        final TextView wifiHint = new TextView(this);
        wifiHint.setTextColor(Style.TEXT_DIM); wifiHint.setTextSize(12);
        wifiHint.setText(getString(R.string.cfg_trusted_wifi_hint));
        wifiHint.setPadding(0, 0, 0, Style.dp(this, 4));
        left.addView(wifiHint);

        LinearLayout wifiBtnRow = new LinearLayout(this);
        wifiBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        wifiBtnRow.addView(button(getString(R.string.cfg_btn_use_current_wifi), 0xFF3A5A7A, () -> {
            String current = AdbGate.getCurrentSsid(this);
            if (!current.isEmpty()) {
                fTrustedSsid.setText(current);
                AdbGate.setTrustedWifi(this, current);
                Toast.makeText(this, getString(R.string.cfg_trusted_wifi_saved), Toast.LENGTH_SHORT).show();
                logMqtt("WIFI", "Wi-Fi Privilegiado salvo: " + current);
            }
        }));
        left.addView(wifiBtnRow);

        Style.gap(left, this, 14);
        left.addView(button(getString(R.string.cfg_btn_save_all), Style.ACCENT, () -> saveAll(true)));

        // ---- Right Column: Status, Actions & Console ----
        right.addView(Style.header(this, getString(R.string.cfg_active_status_title)));
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(Style.card(Style.CARD, this));
        int cardPad = Style.dp(this, 12);
        statusCard.setPadding(cardPad, cardPad, cardPad, cardPad);

        activeBrokerView = new TextView(this);
        activeBrokerView.setTextColor(Style.TEXT); activeBrokerView.setTextSize(14);
        statusCard.addView(activeBrokerView);

        activeClientView = new TextView(this);
        activeClientView.setTextColor(Style.TEXT_DIM); activeClientView.setTextSize(13);
        activeClientView.setPadding(0, Style.dp(this, 4), 0, 0);
        statusCard.addView(activeClientView);

        activeLastSentView = new TextView(this);
        activeLastSentView.setTextColor(Style.TEXT_DIM); activeLastSentView.setTextSize(13);
        activeLastSentView.setPadding(0, Style.dp(this, 4), 0, 0);
        statusCard.addView(activeLastSentView);
        right.addView(statusCard);
        updateStatusCard();

        right.addView(Style.header(this, getString(R.string.cfg_actions_header)));
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.addView(action(getString(R.string.cfg_btn_test), 0xFF3A6B4A, this::testOnce));
        actRow.addView(action(getString(R.string.cfg_btn_discovery), 0xFF4A6B82, this::forceDiscoveryNow));
        right.addView(actRow);

        right.addView(Style.header(this, getString(R.string.cfg_log_title)));
        mqttLogScroll = new ScrollView(this);
        mqttLogScroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 280)));
        mqttLogScroll.setBackground(Style.card(0xFF14171A, this));
        int lPad = Style.dp(this, 10);
        mqttLogScroll.setPadding(lPad, lPad, lPad, lPad);

        mqttLogView = new TextView(this);
        mqttLogView.setTextColor(0xFFCCCCCC);
        mqttLogView.setTextSize(11);
        mqttLogView.setTypeface(android.graphics.Typeface.MONOSPACE);
        mqttLogScroll.addView(mqttLogView);
        right.addView(mqttLogScroll);

        logMqtt("INIT", "Painel MQTT inicializado. ID: " + MqttReporter.getClientId());
    }

    // =====================================================================
    // Certificates & TLS Panel
    // =====================================================================
    private void buildCertSection(LinearLayout parent) {
        parent.addView(Style.header(this, getString(R.string.cfg_certs_header)));

        LinearLayout certCard = new LinearLayout(this);
        certCard.setOrientation(LinearLayout.VERTICAL);
        certCard.setBackground(Style.card(Style.CARD, this));
        int pad = Style.dp(this, 14);
        certCard.setPadding(pad, pad, pad, pad);

        // Client Certificate
        TextView clientLbl = Style.label(this, getString(R.string.cfg_client_cert_label));
        certCard.addView(clientLbl);

        clientCertStatus = new TextView(this);
        clientCertStatus.setTextSize(14);
        clientCertStatus.setPadding(0, Style.dp(this, 4), 0, Style.dp(this, 6));
        certCard.addView(clientCertStatus);

        clientBtnRow = new LinearLayout(this);
        clientBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        certCard.addView(clientBtnRow);

        Style.gap(certCard, this, 14);

        // CA Certificate
        TextView caLbl = Style.label(this, getString(R.string.cfg_ca_cert_label));
        certCard.addView(caLbl);

        caCertStatus = new TextView(this);
        caCertStatus.setTextSize(14);
        caCertStatus.setPadding(0, Style.dp(this, 4), 0, Style.dp(this, 6));
        certCard.addView(caCertStatus);

        caBtnRow = new LinearLayout(this);
        caBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        certCard.addView(caBtnRow);

        Style.gap(certCard, this, 14);

        // TLS Handshake Test
        TextView tlsLbl = Style.label(this, getString(R.string.cfg_tls_test_target_label));
        tlsLbl.setTextSize(14);
        tlsLbl.setTextColor(Style.TEXT_DIM);
        tlsLbl.setPadding(0, Style.dp(this, 6), 0, Style.dp(this, 2));
        certCard.addView(tlsLbl);

        fTlsTarget = new EditText(this);
        fTlsTarget.setText(getTlsTestTarget());
        fTlsTarget.setHint("ssl://homeassistant.example.com:8883");
        fTlsTarget.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fTlsTarget.setTextColor(Style.TEXT);
        fTlsTarget.setTextSize(15);
        fTlsTarget.setBackground(Style.card(Style.CARD_HI, this));
        int padTls = Style.dp(this, 10);
        fTlsTarget.setPadding(padTls, padTls, padTls, padTls);
        certCard.addView(fTlsTarget);

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.addView(button(getString(R.string.cfg_btn_test_tls), 0xFF2E6B8A, this::testTlsHandshake));
        certCard.addView(testRow);

        tlsTestStatus = new TextView(this);
        tlsTestStatus.setTextSize(13);
        tlsTestStatus.setPadding(0, Style.dp(this, 8), 0, 0);
        tlsTestStatus.setVisibility(View.GONE);
        certCard.addView(tlsTestStatus);

        parent.addView(certCard);

        refreshCertUi();
    }

    private void refreshCertUi() {
        if (clientCertStatus == null || caCertStatus == null) return;

        // Client Certificate
        CertImporter.CertInfo clientInfo = CertImporter.getClientCertInfo(this);
        clientBtnRow.removeAllViews();
        if (clientInfo != null) {
            clientCertStatus.setTextColor(clientInfo.isExpired ? 0xFFE57373 : Style.TEXT);
            clientCertStatus.setText(getString(R.string.cfg_cert_status_installed,
                    clientInfo.getCommonName(), clientInfo.getFormattedExpiry()) +
                    (clientInfo.isExpired ? " [EXPIRED]" : ""));
            clientBtnRow.addView(button(getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(false)));
            clientBtnRow.addView(button(getString(R.string.cfg_btn_remove_cert), 0xFF8A3A3A, this::confirmRemoveClientCert));
        } else {
            clientCertStatus.setTextColor(Style.TEXT_DIM);
            clientCertStatus.setText(getString(R.string.cfg_cert_status_none));
            clientBtnRow.addView(button(getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(false)));
        }

        // CA Certificate
        CertImporter.CertInfo caInfo = CertImporter.getCaCertInfo(this);
        boolean isCustom = CertImporter.hasCustomCa(this);
        caBtnRow.removeAllViews();
        if (isCustom && caInfo != null) {
            caCertStatus.setTextColor(Style.TEXT);
            caCertStatus.setText(getString(R.string.cfg_ca_status_custom,
                    caInfo.getCommonName(), caInfo.getFormattedExpiry()));
            caBtnRow.addView(button(getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(true)));
            caBtnRow.addView(button(getString(R.string.cfg_btn_reset_default), 0xFF8A3A3A, this::confirmResetCustomCa));
        } else {
            caCertStatus.setTextColor(Style.TEXT_DIM);
            String name = (caInfo != null) ? " (" + caInfo.getCommonName() + ")" : "";
            caCertStatus.setText(getString(R.string.cfg_ca_status_default) + name);
            caBtnRow.addView(button(getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(true)));
        }
    }

    private void showImportDialog(boolean forCa) {
        String[] options = new String[] {
            getString(R.string.cfg_cert_import_usb),
            getString(R.string.cfg_cert_import_url),
            getString(R.string.cfg_cert_import_file),
            getString(R.string.cfg_cert_import_paste)
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_cert_import_title))
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: importFromUsb(); break;
                    case 1: promptUrlImport(); break;
                    case 2: launchStoragePicker(); break;
                    case 3: promptPasteImport(); break;
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void importFromUsb() {
        new Thread(() -> {
            List<File> files = CertImporter.scanUsbForCerts();
            ui.post(() -> {
                if (files.isEmpty()) {
                    new android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.cfg_cert_import_usb))
                        .setMessage(getString(R.string.cfg_cert_usb_no_files))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }

                String[] items = new String[files.size()];
                for (int i = 0; i < files.size(); i++) {
                    File f = files.get(i);
                    long kb = Math.max(1, f.length() / 1024);
                    items[i] = f.getName() + " (" + kb + " KB)\n" + f.getParent();
                }

                new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.cfg_cert_usb_pick_file))
                    .setItems(items, (dialog, which) -> {
                        File chosen = files.get(which);
                        try {
                            byte[] bytes = CertImporter.readFileBytes(chosen);
                            tryImportBytes(bytes, null);
                        } catch (Throwable t) {
                            showErrorDialog("Erro: " + t.getMessage());
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    private void promptUrlImport() {
        final EditText input = new EditText(this);
        input.setHint("http://homeassistant.local:8123/local/cert.p12");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        int pad = Style.dp(this, 14);
        input.setPadding(pad, pad, pad, pad);

        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_cert_import_url))
            .setMessage(getString(R.string.cfg_cert_url_prompt))
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) return;
                status.setText("Baixando certificado...");
                CertImporter.fetchUrl(url, (ok, data, error) -> {
                    status.setText("");
                    if (ok && data != null) {
                        tryImportBytes(data, null);
                    } else {
                        showErrorDialog("Download falhou: " + (error != null ? error : "desconhecido"));
                    }
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void launchStoragePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, getString(R.string.cfg_cert_import_file)), REQ_CODE_PICK_CERT);
        } catch (Throwable t) {
            showErrorDialog("Não foi possível abrir o seletor de arquivos: " + t.getMessage());
        }
    }

    private void promptPasteImport() {
        final EditText input = new EditText(this);
        input.setHint("-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----");
        input.setLines(8);
        input.setMaxLines(15);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextSize(12);
        int pad = Style.dp(this, 12);
        input.setPadding(pad, pad, pad, pad);

        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_cert_import_paste))
            .setMessage(getString(R.string.cfg_cert_paste_prompt))
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) return;
                try {
                    tryImportBytes(text.getBytes("UTF-8"), null);
                } catch (Throwable t) {
                    showErrorDialog("Erro: " + t.getMessage());
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void tryImportBytes(final byte[] bytes, String password) {
        CertImporter.ImportResult res = CertImporter.importData(this, bytes, password);
        if (res.passwordRequired) {
            promptPasswordAndImport(bytes);
            return;
        }

        if (res.success) {
            refreshCertUi();
            String details = res.certInfo != null ?
                "\n\nNome: " + res.certInfo.getCommonName() +
                "\nEmissor: " + CertImporter.extractCN(res.certInfo.issuer) +
                "\nVálido até: " + res.certInfo.getFormattedExpiry() : "";
            new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.cfg_certs_header))
                .setMessage(res.message + details)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(getString(R.string.cfg_btn_test_tls), (d, w) -> testTlsHandshake())
                .show();
        } else {
            showErrorDialog(res.message);
        }
    }

    private void promptPasswordAndImport(final byte[] bytes) {
        final EditText pwdInput = new EditText(this);
        pwdInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = Style.dp(this, 14);
        pwdInput.setPadding(pad, pad, pad, pad);

        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_cert_password_prompt))
            .setView(pwdInput)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String pwd = pwdInput.getText().toString();
                tryImportBytes(bytes, pwd);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmRemoveClientCert() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_client_cert_label))
            .setMessage(getString(R.string.cfg_cert_remove_confirm, MqttTls.CLIENT_P12))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                CertImporter.removeClientCert(this);
                refreshCertUi();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmResetCustomCa() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_ca_cert_label))
            .setMessage(getString(R.string.cfg_cert_remove_confirm, MqttTls.CUSTOM_CA_CRT))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                CertImporter.removeCustomCaCert(this);
                refreshCertUi();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showErrorDialog(String message) {
        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cfg_certs_header))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private String getTlsTestTarget() {
        String saved = Prefs.getMqttTlsTestTarget(this);
        if (!saved.trim().isEmpty()) return saved.trim();

        String all = (fUri != null) ? fUri.getText().toString().trim() : Prefs.getMqttUri(this, "");
        if (!all.isEmpty()) {
            String[] tokens = all.split("[,\\s\n]+");
            for (String t : tokens) {
                String tl = t.trim().toLowerCase();
                if (tl.startsWith("ssl://") || tl.startsWith("tls://") || tl.startsWith("wss://")
                        || tl.contains(":8883") || tl.contains(":8884")) {
                    return t.trim();
                }
            }
        }
        return "ssl://homeassistant.example.com:8883";
    }

    private void testTlsHandshake() {
        if (tlsTestStatus == null) return;
        String target = (fTlsTarget != null) ? fTlsTarget.getText().toString().trim() : "";
        if (target.isEmpty()) target = getTlsTestTarget();
        if (target.isEmpty()) return;

        Prefs.setMqttTlsTestTarget(this, target);

        tlsTestStatus.setVisibility(View.VISIBLE);
        tlsTestStatus.setTextColor(Style.TEXT_DIM);
        tlsTestStatus.setText(getString(R.string.cfg_cert_testing_tls) + " (" + target + ")...");

        logMqtt("TLS", "Testando handshake TLS para: " + target);
        CertImporter.testTls(this, target, (ok, msg) -> {
            tlsTestStatus.setTextColor(ok ? 0xFF81C784 : 0xFFE57373);
            tlsTestStatus.setText(msg);
            logMqtt("TLS", (ok ? "SUCESSO: " : "ERRO: ") + msg);
        });
    }

    /** Current Wi-Fi IPv4 address, dotted-quad, or "—" if not connected/available. */
    private String wifiIpAddress() {
        try {
            android.net.wifi.WifiManager wm =
                (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return "—";
            return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
        } catch (Throwable t) { return "—"; }
    }

    // =====================================================================
    // System panel — application version, APK SHA-256, OTA update URL and
    // installer, universal switch cooldown, and maintenance/cleanup.
    // =====================================================================
    private void buildSystem() {
        content.addView(Style.header(this, getString(R.string.cfg_system_header)));

        int pad = Style.dp(this, 14);

        // ---- App Info Card ----
        content.addView(Style.header(this, getString(R.string.cfg_app_info_header)));
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackground(Style.card(Style.CARD, this));
        infoCard.setPadding(pad, pad, pad, pad);

        String verName = "Unknown";
        long verCode = 0;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName;
            verCode = pi.getLongVersionCode();
        } catch (Throwable ignored) {}

        TextView verView = new TextView(this);
        verView.setText(getString(R.string.cfg_version_label, verName, verCode));
        verView.setTextColor(Style.TEXT);
        verView.setTextSize(15);
        verView.setTypeface(null, android.graphics.Typeface.BOLD);
        infoCard.addView(verView);

        TextView devIdView = new TextView(this);
        devIdView.setText("Device ID: " + MqttReporter.getDevId());
        devIdView.setTextColor(Style.TEXT_DIM);
        devIdView.setTextSize(13);
        devIdView.setPadding(0, Style.dp(this, 4), 0, 0);
        infoCard.addView(devIdView);

        // For connecting over adb without guessing the car's address --
        // this head unit's IP moves around DHCP, and asking the driver to
        // dig through Android's own network settings mid-task isn't
        // reasonable when this screen already shows every other identifier.
        TextView ipView = new TextView(this);
        ipView.setText(getString(R.string.cfg_ip_label, wifiIpAddress()));
        ipView.setTextColor(Style.TEXT_DIM);
        ipView.setTextSize(13);
        ipView.setTextIsSelectable(true);
        ipView.setPadding(0, Style.dp(this, 2), 0, Style.dp(this, 8));
        infoCard.addView(ipView);

        TextView shaLbl = Style.label(this, getString(R.string.cfg_sha_label) + ":");
        shaLbl.setTextSize(14);
        shaLbl.setTextColor(Style.TEXT_DIM);
        infoCard.addView(shaLbl);

        final TextView shaView = new TextView(this);
        shaView.setText("...");
        shaView.setTextColor(Style.TEXT);
        shaView.setTextSize(12);
        shaView.setTypeface(android.graphics.Typeface.MONOSPACE);
        shaView.setPadding(0, Style.dp(this, 4), 0, 0);
        shaView.setTextIsSelectable(true);
        infoCard.addView(shaView);

        new Thread(() -> {
            String sha = Updater.sha256(new File(getPackageCodePath()));
            ui.post(() -> shaView.setText(sha != null ? sha : "N/A"));
        }).start();

        content.addView(infoCard);

        // ---- Software Update Card ----
        content.addView(Style.header(this, getString(R.string.cfg_update_header)));
        LinearLayout updateCard = new LinearLayout(this);
        updateCard.setOrientation(LinearLayout.VERTICAL);
        updateCard.setBackground(Style.card(Style.CARD, this));
        updateCard.setPadding(pad, pad, pad, pad);

        TextView urlLbl = Style.label(this, getString(R.string.cfg_update_url_label));
        urlLbl.setTextSize(14);
        urlLbl.setTextColor(Style.TEXT_DIM);
        urlLbl.setPadding(0, 0, 0, Style.dp(this, 4));
        updateCard.addView(urlLbl);

        EditText fUpdateUrl = new EditText(this);
        fUpdateUrl.setText(Prefs.getUpdateUrl(this, Updater.DEFAULT_URL));
        fUpdateUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fUpdateUrl.setHint(Updater.DEFAULT_URL);
        fUpdateUrl.setTextColor(Style.TEXT);
        fUpdateUrl.setTextSize(15);
        fUpdateUrl.setBackground(Style.card(Style.CARD_HI, this));
        fUpdateUrl.setPadding(pad, Style.dp(this, 10), pad, Style.dp(this, 10));
        updateCard.addView(fUpdateUrl);

        LinearLayout urlBtnRow = new LinearLayout(this);
        urlBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        urlBtnRow.addView(button(getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            String u = fUpdateUrl.getText().toString().trim();
            if (u.isEmpty()) { u = Updater.DEFAULT_URL; fUpdateUrl.setText(u); }
            Prefs.setUpdateUrl(this, u);
            Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        }));
        urlBtnRow.addView(button(getString(R.string.cfg_btn_reset_default), 0xFF3A5A7A, () -> {
            fUpdateUrl.setText(Updater.DEFAULT_URL);
            Prefs.setUpdateUrl(this, Updater.DEFAULT_URL);
            Toast.makeText(this, getString(R.string.cfg_btn_reset_default), Toast.LENGTH_SHORT).show();
        }));
        updateCard.addView(urlBtnRow);

        Style.gap(updateCard, this, 14);

        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.addView(action(getString(R.string.cfg_btn_check_update), Style.ACCENT, () -> {
            String u = fUpdateUrl.getText().toString().trim();
            if (u.isEmpty()) { u = Updater.DEFAULT_URL; fUpdateUrl.setText(u); }
            Prefs.setUpdateUrl(this, u);
            updateCheck();
        }));
        updateCard.addView(actRow);

        status = new TextView(this);
        status.setTextColor(Style.TEXT_DIM);
        status.setTextSize(13);
        status.setPadding(0, Style.dp(this, 10), 0, 0);
        updateCard.addView(status);

        content.addView(updateCard);

        // ---- Maintenance Card ----
        content.addView(Style.header(this, getString(R.string.cfg_maintenance_header)));
        LinearLayout maintCard = new LinearLayout(this);
        maintCard.setOrientation(LinearLayout.VERTICAL);
        maintCard.setBackground(Style.card(Style.CARD, this));
        maintCard.setPadding(pad, pad, pad, pad);

        LinearLayout maintRow = new LinearLayout(this);
        maintRow.setOrientation(LinearLayout.HORIZONTAL);
        maintRow.addView(button(getString(R.string.cfg_cleanup), 0xFF8A3A3A, () -> {
            startActivity(new Intent(this, CleanupActivity.class));
        }));
        maintCard.addView(maintRow);

        content.addView(maintCard);
    }

    // Same Updater the MQTT command uses, so it inherits the same locks: https
    // only, signature must match, and the helper does the install. Progress lands
    // in the status line because there is no other place to watch it from the
    // driver's seat — and there will be no success message, since a successful
    // install kills this process.
    private void logMqtt(String tag, String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        String line = "[" + time + "] [" + tag + "] " + msg + "\n";
        ui.post(() -> {
            if (mqttLogView != null) {
                mqttLogView.append(line);
                if (mqttLogView.getText().length() > 10000) {
                    mqttLogView.setText(mqttLogView.getText().subSequence(2500, mqttLogView.getText().length()));
                }
                if (mqttLogScroll != null) {
                    mqttLogScroll.post(() -> mqttLogScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private void updateStatusCard() {
        if (activeBrokerView == null || activeClientView == null || activeLastSentView == null) return;
        TelemetryService svc = TelemetryService.getInstance();
        boolean svcRunning = svc != null && svc.isRunning();
        boolean teleOn = Prefs.getTeleEnabled(this);

        if (!teleOn) {
            activeBrokerView.setText(getString(R.string.cfg_active_broker, getString(R.string.cfg_status_off)));
            activeBrokerView.setTextColor(Style.TEXT_DIM);
        } else if (svcRunning && svc.isConnected()) {
            String broker = svc.getConnectedBroker();
            activeBrokerView.setText(getString(R.string.cfg_active_broker, (broker != null ? broker : "Online")));
            activeBrokerView.setTextColor(0xFF81C784);
        } else if (svcRunning) {
            activeBrokerView.setText(getString(R.string.cfg_active_broker, getString(R.string.cfg_connecting)));
            activeBrokerView.setTextColor(0xFFFFB74D);
        } else {
            activeBrokerView.setText(getString(R.string.cfg_active_broker, getString(R.string.cfg_status_off)));
            activeBrokerView.setTextColor(Style.TEXT_DIM);
        }

        activeClientView.setText(getString(R.string.cfg_client_id, MqttReporter.getClientId()));
        int iv = Prefs.getTeleIntervalS(this);
        activeLastSentView.setText(teleOn
            ? getString(R.string.cfg_sending_every, iv)
            : getString(R.string.cfg_status_off));
    }

    private void save(boolean enable) {
        saveAll(enable);
    }

    private void saveAll(boolean enable) {
        int iv = Prefs.getTeleIntervalS(this);
        if (fInterval != null && fInterval.getText() != null) {
            try { iv = Integer.parseInt(fInterval.getText().toString().trim()); } catch (Exception ignored) {}
            iv = Math.max(5, iv);
        }

        Prefs.setTeleEnabled(this, enable);
        Prefs.setTeleIntervalS(this, iv);

        if (fUri != null && fUri.getText() != null) {
            Prefs.setMqttUri(this, fUri.getText().toString().trim());
            Prefs.setMqttUriAlt(this, "");
        }
        if (fUser != null && fUser.getText() != null) {
            Prefs.setMqttUser(this, fUser.getText().toString().trim());
        }
        if (fPass != null && fPass.getText() != null) {
            Prefs.setMqttPass(this, fPass.getText().toString());
        }
        if (fTlsTarget != null && fTlsTarget.getText() != null) {
            String val = fTlsTarget.getText().toString().trim();
            if (!val.isEmpty()) Prefs.setMqttTlsTestTarget(this, val);
        }

        if (fTrustedSsid != null && fTrustedSsid.getText() != null) {
            String val = fTrustedSsid.getText().toString().trim();
            AdbGate.setTrustedWifi(this, val);
        }

        Intent svc = new Intent(this, TelemetryService.class);
        if (enable) {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        } else {
            stopService(svc);
        }

        if (status != null) {
            status.setText(enable
                ? getString(R.string.cfg_sending_every, iv)
                : getString(R.string.cfg_status_off));
        }

        updateStatusCard();
        Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        logMqtt("CONFIG", "Configurações salvas e aplicadas.");
    }

    private void updateCheck() {
        logMqtt("UPDATE", "Verificando atualização OTA...");
        if (status != null) status.setText(getString(R.string.update_checking));
        Updater.check(getApplicationContext(), null, new Updater.CheckCallback() {
            @Override
            public void onUpdateAvailable(Updater.UpdateInfo info) {
                ui.post(() -> {
                    if (status != null) status.setText("");
                    logMqtt("UPDATE", "Atualização encontrada: " + info.versionName);
                    if (!com.geely.drivemem.state.CarState.isParked()) {
                        logMqtt("UPDATE", "Veículo em movimento / fora de Park. Atualização bloqueada por segurança.");
                        if (status != null) status.setText(getString(R.string.update_not_parked));
                        Toast.makeText(TelemetryActivity.this, getString(R.string.update_not_parked), Toast.LENGTH_LONG).show();
                        return;
                    }
                    UpdateDialog.show(TelemetryActivity.this, info, () -> {
                        if (status != null) status.setText(getString(R.string.update_downloading, info.versionName));
                        logMqtt("UPDATE", "Atualização aceita pelo usuário. Baixando e instalando...");
                        Updater.update(getApplicationContext(), info.apkUrl, s -> ui.post(() -> {
                            if (status != null) status.setText(s);
                            logMqtt("UPDATE", s);
                        }));
                    });
                });
            }

            @Override
            public void onAlreadyUpToDate(String currentVer) {
                ui.post(() -> {
                    if (status != null) status.setText(getString(R.string.update_up_to_date, currentVer));
                    logMqtt("UPDATE", "Aplicativo já está na versão mais recente: " + currentVer);
                });
            }

            @Override
            public void onError(String error) {
                ui.post(() -> {
                    if (status != null) status.setText(getString(R.string.update_check_failed, error));
                    logMqtt("UPDATE", "Erro na verificação: " + error);
                });
            }
        });
        updateCheckHelper();
    }

    // Same button, second independent check -- modehelper never shows its
    // own UI, so there's no separate "check modehelper" affordance; this is
    // the one place a manual check can reach it. Fired alongside the
    // drivemem check above, not chained after it -- neither depends on the
    // other's outcome. Derives modehelper's URL by swapping the filename on
    // whatever drivemem's own configured update URL resolves to (same HA
    // /local server, sibling file) rather than adding a second Settings
    // field for one more URL to keep in sync.
    private void updateCheckHelper() {
        String base = Updater.resolveUrl(getApplicationContext(), null);
        int slash = base.lastIndexOf('/');
        if (slash < 0) return;
        String helperUrl = base.substring(0, slash + 1) + "modehelper.apk";
        Updater.check(getApplicationContext(), helperUrl, Updater.HELPER_PKG, new Updater.CheckCallback() {
            @Override
            public void onUpdateAvailable(Updater.UpdateInfo info) {
                ui.post(() -> {
                    logMqtt("UPDATE", "Atualização do ModeHelper encontrada: " + info.versionName);
                    if (!com.geely.drivemem.state.CarState.isParked()) {
                        logMqtt("UPDATE", "ModeHelper: veículo em movimento, atualização bloqueada.");
                        return;
                    }
                    UpdateDialog.show(TelemetryActivity.this, info, () -> {
                        logMqtt("UPDATE", "Atualização do ModeHelper aceita. Baixando e instalando...");
                        Updater.updateHelper(getApplicationContext(), info.apkUrl,
                            s -> ui.post(() -> logMqtt("UPDATE", "ModeHelper: " + s)));
                    });
                });
            }

            @Override
            public void onAlreadyUpToDate(String currentVer) {
                ui.post(() -> logMqtt("UPDATE", "ModeHelper já está na versão mais recente: " + currentVer));
            }

            @Override
            public void onError(String error) {
                ui.post(() -> {
                    logMqtt("UPDATE", "ModeHelper: erro na verificação: " + error);
                    if (error != null && error.contains("predates self-update")) {
                        Toast.makeText(TelemetryActivity.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void testOnce() {
        saveAll(Prefs.getTeleEnabled(this));
        status.setText(getString(R.string.cfg_testing));
        final String uri = (fUri != null) ? fUri.getText().toString().trim() : Prefs.getMqttUri(this, "");
        final String u = (fUser != null) ? fUser.getText().toString().trim() : Prefs.getMqttUser(this, "");
        final String pw = (fPass != null) ? fPass.getText().toString() : Prefs.getMqttPass(this);
        String[] targets = uri.split("[,\\s]+");
        final String firstTarget = (targets.length > 0 && !targets[0].isEmpty()) ? targets[0] : uri;

        logMqtt("TEST", "Iniciando teste de conexão para: " + firstTarget);
        logMqtt("TEST", "Lendo dados dos sensores do veículo...");

        CarActor.get(this).runOnCarThread(() -> {
            CarActor actor = CarActor.get(this);
            CarAccess c = actor.rawAccess();
            boolean carOk = c.isReady() || c.connect(getApplicationContext());
            java.util.LinkedHashMap<String, Object> data = carOk
                ? Telemetry.snapshot(c, CarActor.chargingFrom(actor.get("car.is_charging")))
                : new java.util.LinkedHashMap<>();
            logMqtt("TEST", "Sensores lidos: " + data.size() + " campos (CarAccess ok=" + carOk + ")");
            logMqtt("TEST", "Publicando em '" + MqttReporter.getBaseTopic() + "/state'...");

            final MqttReporter r = new MqttReporter(uri, "", u, pw, getApplicationContext());
            r.testConnection(data, (ok, detail) -> {
                ui.post(() -> {
                    status.setText(detail);
                    if (ok) {
                        logMqtt("TEST", "SUCESSO: " + detail);
                        logMqtt("TEST", "Tópico de estado atualizado no broker!");
                    } else {
                        logMqtt("TEST", "ERRO: " + detail);
                    }
                    updateStatusCard();
                });
                r.close();
            });
        });
    }

    private void forceDiscoveryNow() {
        saveAll(Prefs.getTeleEnabled(this));
        status.setText(getString(R.string.cfg_discovery_sending));
        logMqtt("DISCOVERY", "Solicitando envio de descoberta MQTT (47 entidades)...");
        TelemetryService svc = TelemetryService.getInstance();
        if (svc != null && svc.isRunning()) {
            svc.triggerDiscovery((ok, detail) -> ui.post(() -> {
                String msg = ok ? getString(R.string.cfg_discovery_ok) : getString(R.string.cfg_discovery_failed, detail);
                status.setText(msg);
                if (ok) {
                    logMqtt("DISCOVERY", "SUCESSO: " + detail);
                    logMqtt("DISCOVERY", "Entidades recriadas em 'homeassistant/.../" + MqttReporter.getDevId() + "/...'");
                } else {
                    logMqtt("DISCOVERY", "FALHA: " + detail);
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                updateStatusCard();
            }));
            return;
        }
        final String uri = (fUri != null) ? fUri.getText().toString().trim() : Prefs.getMqttUri(this, "");
        final String u = (fUser != null) ? fUser.getText().toString().trim() : Prefs.getMqttUser(this, "");
        final String pw = (fPass != null) ? fPass.getText().toString() : Prefs.getMqttPass(this);
        logMqtt("DISCOVERY", "Conectando cliente direto para publicar descoberta...");
        CarActor.get(this).runOnCarThread(() -> {
            final MqttReporter r = new MqttReporter(uri, "", u, pw, getApplicationContext());
            r.forceDiscovery((ok, detail) -> {
                ui.post(() -> {
                    String msg = ok ? getString(R.string.cfg_discovery_ok) : getString(R.string.cfg_discovery_failed, detail);
                    status.setText(msg);
                    if (ok) {
                        logMqtt("DISCOVERY", "SUCESSO: " + detail);
                    } else {
                        logMqtt("DISCOVERY", "FALHA: " + detail);
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    updateStatusCard();
                });
                r.close();
            });
        });
    }

    // =====================================================================
    // Menu bar panel (the toggles that moved out of the AC screen)
    // =====================================================================
    private void buildBar() {
        content.addView(Style.header(this, getString(R.string.cfg_bar_header)));

        content.addView(Style.header(this, getString(R.string.cfg_bar_topbar_header)));

        content.addView(toggleRow(getString(R.string.cfg_bar_outtemp),
            Prefs.getOutTempOn(this), on -> {
                Prefs.setOutTempOn(this, on);
                Intent svc = new Intent(this, OutTempService.class);
                if (on) startService(svc); else stopService(svc);
            }));

        content.addView(toggleRow(getString(R.string.cfg_bar_wifi),
            Prefs.getWifiIconOn(this), on -> {
                Prefs.setWifiIconOn(this, on);
                Intent svc = new Intent(this, WifiIconService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                } else stopService(svc);
            }));

        content.addView(toggleRow(getString(R.string.cfg_bar_soc),
            Prefs.getSocOn(this), on -> {
                Prefs.setSocOn(this, on);
                Intent svc = new Intent(this, SocIconService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                } else stopService(svc);
            }));

        content.addView(Style.header(this, getString(R.string.cfg_bar_home_header)));

        content.addView(toggleRow(getString(R.string.cfg_drive_card),
            Prefs.getDriveCardEnabled(this), on ->
                Prefs.setDriveCardEnabled(this, on)));

        content.addView(toggleRow(getString(R.string.cfg_overlay_label),
            Prefs.getOverlayOn(this), on -> {
                Prefs.setOverlayOn(this, on);
                Intent svc = new Intent(this, com.geely.drivemem.services.OverlayService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                } else stopService(svc);
            }));
    }

    // =====================================================================
    // Appearance panel — theme picker
    // =====================================================================
    private void buildLook() {
        int pageWidth = Style.dp(this, 960);   // same half-screen cap as the drive/regen/turbo page

        // Noturno's own scene (VaporArtView) is a different art path entirely
        // from the skyline (SkylineArtView) every other theme uses -- this
        // toggle only ever affects the skyline, so Noturno always shows its
        // own art regardless of it. See ComfortActivity's art selection.
        //
        // Flipping this recreates the screen (not just saves the pref):
        // the seed config below must appear/disappear with it, not just sit
        // there disabled -- "the config for it" goes away along with the
        // skyline itself, not just the art.
        content.addView(Style.header(this, getString(R.string.cfg_skyline_header)));
        boolean skylineEnabled = Prefs.getSkylineEnabled(this);
        LinearLayout skylineToggle = toggleRow(getString(R.string.cfg_skyline_label),
            skylineEnabled,
            on -> {
                Prefs.setSkylineEnabled(this, on);
                getIntent().putExtra("section", SEC_LOOK);
                recreate();
            });
        skylineToggle.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(skylineToggle);

        if (skylineEnabled) {
            content.addView(Style.header(this, getString(R.string.cfg_skyline_seed_header)));

            boolean randomPerDrive = Prefs.getSkylineRandomPerDrive(this);

            fSkylineSeed = field(content, getString(R.string.cfg_skyline_seed_label),
                String.valueOf(Prefs.getSkylineSeed(this, com.geely.drivemem.art.Skyline.DEFAULT_SEED)),
                InputType.TYPE_CLASS_NUMBER);
            fSkylineSeed.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Locked while "random every drive" is on: that toggle is the one
            // writing skyline_seed now, on every P->D, so a value typed here
            // would just be overwritten by the next drive anyway.
            fSkylineSeed.setEnabled(!randomPerDrive);

            LinearLayout skylineBtnRow = new LinearLayout(this);
            skylineBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            skylineBtnRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            skylineBtnRow.addView(action(getString(R.string.cfg_skyline_seed_save), Style.ACCENT, () -> {
                if (!fSkylineSeed.isEnabled()) return;   // random-per-drive owns the seed right now
                long seed;
                try { seed = Long.parseLong(fSkylineSeed.getText().toString().trim()); }
                catch (NumberFormatException e) { seed = com.geely.drivemem.art.Skyline.DEFAULT_SEED; }
                Prefs.setSkylineSeed(this, seed);
                fSkylineSeed.setText(String.valueOf(seed));
                Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
            }));
            skylineBtnRow.addView(action(getString(R.string.cfg_skyline_cycle), 0xFF6A4CFF, () -> {
                if (fSkylineSeed != null && !fSkylineSeed.isEnabled()) return;
                long seed = new java.util.Random().nextLong() & Long.MAX_VALUE;
                Prefs.setSkylineSeed(this, seed);
                if (fSkylineSeed != null) fSkylineSeed.setText(String.valueOf(seed));
                Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
            }));
            content.addView(skylineBtnRow);

            LinearLayout randomToggle = toggleRow(getString(R.string.cfg_skyline_random_per_drive_label),
                randomPerDrive,
                on -> {
                    Prefs.setSkylineRandomPerDrive(this, on);
                    getIntent().putExtra("section", SEC_LOOK);
                    recreate();
                });
            randomToggle.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(randomToggle);
        }

        content.addView(Style.header(this, getString(R.string.cfg_theme_header)));
        content.addView(sectionLabel(getString(R.string.cfg_dark_mode_header)));
        LinearLayout appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        appRow.addView(appearanceTile(Style.APPEARANCE_LIGHT, getString(R.string.cfg_appearance_light)));
        appRow.addView(appearanceTile(Style.APPEARANCE_DARK,  getString(R.string.cfg_appearance_dark)));
        appRow.addView(appearanceTile(Style.APPEARANCE_AUTO,  getString(R.string.cfg_appearance_auto)));
        content.addView(appRow);
        TextView appNote = new TextView(this);
        appNote.setTextColor(Style.TEXT_DIM); appNote.setTextSize(13);
        appNote.setPadding(0, Style.dp(this, 8), 0, Style.dp(this, 12));
        appNote.setText(getString(R.string.cfg_appearance_note));
        content.addView(appNote);

        content.addView(sectionLabel(getString(R.string.cfg_theme_selection_header)));
        TextView sub = new TextView(this);
        sub.setTextColor(Style.TEXT_DIM); sub.setTextSize(14);
        sub.setText(getString(R.string.cfg_theme_sub));
        content.addView(sub);

        // A secret theme still gets a tile while it is the SAVED choice. That is
        // not a loophole in the secret: without it, someone who chose Noturno
        // before it was hidden would open this screen and find nothing selected,
        // with no way to describe the theme they are looking at. BORROWED for the
        // trip it stays hidden — the highlight belongs to the saved choice, which
        // is what comes back when the process dies.
        List<Style.Theme> shown = new ArrayList<>();
        for (Style.Theme t : Style.THEMES)
            if (!Style.secret(t.id) || t.id.equals(Style.savedId(this))) shown.add(t);

        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(row);
            }
            row.addView(themeTile(shown.get(i)));
        }
        // odd number of themes: fill the empty column so the last one is not stretched
        if (shown.size() % 2 == 1 && row != null) {
            View filler = new View(this);
            filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(filler);
        }

        TextView note = new TextView(this);
        note.setTextColor(Style.TEXT_DIM); note.setTextSize(13);
        note.setPadding(0, Style.dp(this, 14), 0, 0);
        note.setText(getString(Style.FOLLOW_AMBIENT
            ? R.string.cfg_theme_ambient_on : R.string.cfg_theme_ambient_off));
        content.addView(note);

    }

    private View themeTile(final Style.Theme t) {
        // SAVED, not current(). During a transient Noturno current() is Noturno,
        // which has no tile — so the grid would highlight nothing. The grid
        // describes what is written to disk; the borrowed theme is what you are
        // looking at, and those are allowed to disagree for one trip.
        final boolean sel = t.id.equals(Style.savedId(this));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(sel ? Style.outlinedCard(Style.ACCENT, this) : Style.card(Style.CARD, this));
        int p = Style.dp(this, 10);
        col.setPadding(p, p, p, p);

        // thumbnail drawn at the slot's real size (no stretching)
        final android.widget.ImageView sw = new android.widget.ImageView(this);
        sw.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        sw.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 104)));
        sw.post(() -> sw.setImageBitmap(Style.themeSwatch(this, t, sw.getWidth(), sw.getHeight())));
        col.addView(sw);

        TextView name = new TextView(this);
        // t.name is a proper name ("Geely", "Noturno"…): not translated, it only gets the selected marker
        name.setText(sel ? getString(R.string.cfg_theme_selected, t.name) : t.name);
        name.setTextColor(sel ? Style.ACCENT : Style.TEXT); name.setTextSize(18);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setPadding(0, Style.dp(this, 8), 0, 0);
        col.addView(name);

        TextView blurb = new TextView(this);
        blurb.setText(getString(t.blurbRes));
        blurb.setTextColor(Style.TEXT_DIM); blurb.setTextSize(13);
        col.addView(blurb);

        col.setOnClickListener(v -> pickTheme(t));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(this, 5);
        lp.setMargins(m, Style.dp(this, 10), m, 0);
        col.setLayoutParams(lp);
        return col;
    }

    // Saves and recreates the screen: the palette is only read at draw time, so
    // repainting = rebuilding the Views. Comes back on the Appearance section so
    // the user can see the effect.
    private void pickTheme(Style.Theme t) {
        // Compare against the SAVED theme, not the current one. While the Konami
        // code has us transiently in Noturno, Style.current() IS Noturno — so
        // picking Noturno here would return early and neither persist the choice
        // nor clear the override, leaving the user stuck in an easter egg with no
        // way out through the UI.
        if (t.id.equals(Style.savedId(this)) && !Style.isTransient()) return;
        Style.save(this, t.id);
        getIntent().putExtra("section", SEC_LOOK);
        recreate();
    }

    private View appearanceTile(String mode, String label) {
        boolean sel = mode.equals(Style.appearance(this));
        TextView b = Style.cardButton(this, label, sel, () -> {
            Style.setAppearance(this, mode);
            getIntent().putExtra("section", SEC_LOOK);
            recreate();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(this, 5);
        lp.setMargins(m, Style.dp(this, 10), m, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout toggleRow(String label, boolean on, GeelySwitch.OnToggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Style.dp(this, 8), 0, Style.dp(this, 8));
        GeelySwitch sw = new GeelySwitch(this);
        sw.setLockSeconds(Prefs.getSwitchLockS(this, GeelySwitch.DEFAULT_LOCK_S));
        sw.setCheckedSilently(on);
        sw.setOnToggle(cb);
        row.addView(sw);
        TextView lbl = Style.label(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Style.dp(this, 14);
        lbl.setLayoutParams(lp);
        row.addView(lbl);
        return row;
    }

    // =====================================================================
    private EditText field(LinearLayout parent, String label, String val, int type) {
        TextView l = new TextView(this);
        l.setText(label); l.setTextColor(Style.TEXT_DIM); l.setTextSize(14);
        l.setPadding(0, Style.dp(this, 10), 0, Style.dp(this, 2));
        parent.addView(l);
        EditText e = new EditText(this);
        e.setText(val); e.setInputType(type);
        e.setTextColor(Style.TEXT); e.setTextSize(17);
        e.setBackground(Style.card(Style.CARD, this));
        int p = Style.dp(this, 12);
        e.setPadding(p, p, p, p);
        parent.addView(e);
        return e;
    }

    private TextView action(String label, int color, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(Style.onFill(color)); t.setTextSize(18);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Style.card(color, this));
        int pv = Style.dp(this, 16);
        t.setPadding(pv, pv, pv, pv);
        t.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(this, 4);
        lp.setMargins(m, Style.dp(this, 8), m, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView button(String label, int color, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(Style.onFill(color)); t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Style.card(color, this));
        int ph = Style.dp(this, 18);
        int pv = Style.dp(this, 12);
        t.setPadding(ph, pv, ph, pv);
        t.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Style.dp(this, 4);
        lp.setMargins(m, Style.dp(this, 8), m, 0);
        t.setLayoutParams(lp);
        return t;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_PICK_CERT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                if (is != null) {
                    byte[] bytes = CertImporter.readStreamBytes(is);
                    tryImportBytes(bytes, null);
                }
            } catch (Throwable t) {
                showErrorDialog("Erro ao carregar arquivo selecionado: " + t.getMessage());
            }
        }
    }

    // Refreshes status lines after returning from external activities
    @Override protected void onResume() {
        super.onResume();
        if (spotifySection != null) {
            spotifySection.refreshStatus(this);
        }
        refreshCertUi();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (carActorSubscribed) {
            EntityBus.unsubscribe("car.drive_mode", driveListener);
            EntityBus.unsubscribe("car.regen_mode", regenListener);
        }
        if (obdListener != null) Obd2Reader.unsubscribe(obdListener);
        if (obdDebugListener != null) Obd2Reader.unsubscribe(obdDebugListener);
        if (abrpDebugListener != null) AbrpUploader.unsubscribe(abrpDebugListener);
    }
}
