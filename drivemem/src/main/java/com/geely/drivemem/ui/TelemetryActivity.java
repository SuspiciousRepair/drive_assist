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

    // Section instances kept for post-construction updates (refreshStatus, refreshCertUi)
    private TelemetryMqttSection mqttSection;
    private static final int REQ_CODE_PICK_CERT = 4201;  // used by mqttSection file picker
    private TelemetrySpotifySection spotifySection;
    private TelemetryClipsSection clipsSection;
    private TelemetryChargeSection chargeSection;
    private TelemetryDriveSection driveSection;
    private TelemetryObdSection obdSection;
    private TelemetrySystemSection systemSection;
    private TelemetryBarSection barSection;
    private TelemetryLookSection lookSection;

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
            case SEC_BAR:   barSection = new TelemetryBarSection(this); content.addView(barSection); break;
            case SEC_LOOK:  lookSection = new TelemetryLookSection(this); content.addView(lookSection); break;
            case SEC_MQTT:
                mqttSection = new TelemetryMqttSection(this);
                content.addView(mqttSection);
                break;
            case SEC_DOORS: content.addView(new TelemetryDoorsSection(this)); break;
            case SEC_CLIPS:
                clipsSection = new TelemetryClipsSection(this, () -> selectSection(SEC_CLIPS));
                content.addView(clipsSection);
                break;
            case SEC_CHARGE:
                chargeSection = new TelemetryChargeSection(this);
                content.addView(chargeSection);
                break;
            case SEC_OBD:
                obdSection = new TelemetryObdSection(this);
                content.addView(obdSection);
                buildObdListeners();
                break;
            case SEC_SPOTIFY:
                spotifySection = new TelemetrySpotifySection(this);
                content.addView(spotifySection);
                break;
            case SEC_SYSTEM: systemSection = new TelemetrySystemSection(this); content.addView(systemSection); break;
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
    // See onDestroy() — unsubscribed there, and re-subscribed fresh each
    // time buildObdListeners() runs (the section can be rebuilt without the
    // Activity being destroyed, same reason driveListener/regenListener need this).
    private Obd2Reader.Listener obdListener;
    private Obd2Reader.Listener obdDebugListener;
    private AbrpUploader.Listener abrpDebugListener;

    // Subscribe listeners for the OBD section — UI building happens in
    // TelemetryObdSection.constructor. Listeners are unsubscribed in onDestroy().
    private void buildObdListeners() {
        if (obdSection == null) return;  // Section not created (shouldn't happen)

        // OBD status listener: updates when connection state changes
        if (obdListener != null) Obd2Reader.unsubscribe(obdListener);
        obdListener = connected -> runOnUiThread(() -> {
            boolean enabled = Prefs.getObd2Enabled(this);
            obdSection.updateObdStatus(this, enabled);
        });
        Obd2Reader.subscribe(obdListener);
        obdSection.updateObdStatus(this, Prefs.getObd2Enabled(this));

        // OBD debug display listener: updates when OBD data changes
        if (obdDebugListener != null) Obd2Reader.unsubscribe(obdDebugListener);
        obdDebugListener = new Obd2Reader.Listener() {
            @Override public void onObd2ConnectedChanged(boolean connected) {
                runOnUiThread(obdSection::updateObdDisplay);
            }
            @Override public void onObd2Reading(Obd2Reader.Reading r) {
                runOnUiThread(obdSection::updateObdDisplay);
            }
        };
        Obd2Reader.subscribe(obdDebugListener);

        // ABRP debug display listener: updates when ABRP data changes
        if (abrpDebugListener != null) AbrpUploader.unsubscribe(abrpDebugListener);
        abrpDebugListener = () -> runOnUiThread(obdSection::updateAbrpDisplay);
        AbrpUploader.subscribe(abrpDebugListener);

        // Initial render of debug displays
        obdSection.updateObdDisplay();
        obdSection.updateAbrpDisplay();
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setTextColor(Style.TEXT_DIM); t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setText(text);
        return t;
    }

    // =====================================================================
    // MQTT panel (extracted to TelemetryMqttSection)
    // =====================================================================
    // Same Updater the MQTT command uses, so it inherits the same locks: https
    // only, signature must match, and the helper does the install. Progress lands
    // in the status line because there is no other place to watch it from the
    // driver's seat — and there will be no success message, since a successful
    // install kills this process.
    // logMqtt stub: MQTT logging moved to TelemetryMqttSection, but updateCheck()
    // and updateCheckHelper() in TelemetrySystemSection still call this; we keep it as a no-op.
    private void logMqtt(String tag, String msg) {
        // No-op: MQTT-specific logging is now handled by TelemetryMqttSection.
        // System logging (updateCheck, updateCheckHelper) simply doesn't log to MQTT log.
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
                if (is != null && mqttSection != null) {
                    mqttSection.importCertFromFile(is);
                }
            } catch (Throwable t) {
                Toast.makeText(this, "Erro ao carregar arquivo selecionado: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Refreshes status lines after returning from external activities
    @Override protected void onResume() {
        super.onResume();
        if (spotifySection != null) {
            spotifySection.refreshStatus(this);
        }
        if (mqttSection != null) {
            mqttSection.refreshCertUi();
        }
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
