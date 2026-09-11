package com.geely.drivemem.ui;

import com.geely.drivemem.R;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDataHub;
import com.geely.drivemem.car.CarDb;
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
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.services.OutTempService;
import com.geely.drivemem.services.SocIconService;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.services.WifiIconService;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.Modes;
import com.geely.drivemem.util.SpotifyClient;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.UsbExport;

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
    private SharedPreferences prefs;

    private LinearLayout content;             // right-hand panel (swapped per section)
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
    // Charging history window — a screen-local toggle (not persisted): 30 days
    // is the default, 365 available for the wider view.
    private int chargePeriodDays = 30;

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
    private EditText fSpotifyClientId;
    private EditText fTurbo;
    private EditText fSkylineSeed;
    private EditText fLockSec;
    private TextView spotifyStatus;
    // Drive mode. Two separate ideas, kept in separate fields on purpose —
    // conflating them into one used to mean a car that answered late (or
    // wrong, right after boot) silently overwrote the user's saved standard
    // in memory, so pressing "Salvar padrão" saved whatever the car
    // happened to currently be in, not what the user actually picked.
    //   selDrive/selRegen  — your standard: starts from prefs, changes only
    //                        when you tap a card. This is what "Salvar
    //                        padrão" persists. Always known (falls back to
    //                        Modes.DRIVE_ECO/REGEN_MID like before).
    //   liveDrive/liveRegen — what the car reports right now. Only
    //                        meaningful once driveKnown/regenKnown is true;
    //                        never written to by a tap or by saveDefault.
    private int selDrive, selRegen;
    private int liveDrive, liveRegen;
    private boolean driveKnown, regenKnown;   // is the LIVE value known — not the standard, which always is

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
        if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer) {
            liveDrive = (Integer) reading.value; driveKnown = true;
            ui.post(() -> { highlight(); updateCurrentStatus(); });
        }
    };
    private final EntityBus.Listener regenListener = (key, reading) -> {
        if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof Integer) {
            liveRegen = (Integer) reading.value; regenKnown = true;
            ui.post(() -> { highlight(); updateCurrentStatus(); });
        }
    };
    private final Map<Integer, LinearLayout> driveCards = new HashMap<>();
    private final Map<Integer, LinearLayout> regenCards = new HashMap<>();
    private boolean carActorSubscribed = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Style.load(this);                 // before any View
        prefs = getSharedPreferences("drivemem", MODE_PRIVATE);
        section = getIntent().getIntExtra("section", SEC_DRIVE);   // come back to the same section on recreate
        selDrive = prefs.getInt("drive", Modes.DRIVE_ECO);
        selRegen = prefs.getInt("regen", Modes.REGEN_MID);

        Style.edgeToEdge(this);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackground(Style.screenBg());

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

    private void selectSection(int idx) {
        section = idx;
        for (TextView t : navItems) {
            boolean sel = ((Integer) t.getTag() == idx);
            t.setBackground(sel ? Style.card(Style.CARD_ON, this) : null);
            t.setTextColor(sel ? Style.onFill(Style.CARD_ON) : Style.TEXT_DIM);
            t.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        content.removeAllViews();
        switch (idx) {
            case SEC_BAR:   buildBar();   break;
            case SEC_LOOK:  buildLook();  break;
            case SEC_MQTT:  buildMqtt();  break;
            case SEC_DOORS: buildDoors(); break;
            case SEC_CLIPS: buildClips(); break;
            case SEC_CHARGE: buildCharge(); break;
            case SEC_OBD: buildObd(); break;
            case SEC_SPOTIFY: buildSpotify(); break;
            case SEC_SYSTEM: buildSystem(); break;
            default:       buildDrive(); break;   // SEC_DRIVE, and the landing page
        }
    }

    // =====================================================================
    // Recordings panel — the dashcam gallery, living in the sidebar
    // =====================================================================
    // Rebuilt on every entry rather than cached: the recorder is a different
    // process and can close a segment or evict one at any moment, so anything
    // held here would be a guess about another app's directory.
    private void buildClips() {
        content.addView(Style.header(this, getString(R.string.clips_title)));

        List<Clips.Clip> clips = Clips.list(this);
        boolean on = Clips.recording(this);

        TextView usage = Style.label(this, getString(R.string.clips_usage,
            clips.size(), Clips.mb(Clips.usedBytes(this)), Clips.mb(Clips.heldBytes(this)),
            Clips.mb(new android.os.StatFs(Clips.dir(this).getAbsolutePath()).getAvailableBytes())));
        content.addView(usage);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setPadding(0, Style.dp(this, 12), 0, Style.dp(this, 4));
        ctl.addView(Style.cardButton(this,
            getString(on ? R.string.clips_stop : R.string.clips_record), on, () -> {
                // Drive Assist does not record — modehelper does. Ask over the same
                // broadcast adb uses, then re-read the directory rather than
                // assuming: a segment file takes a moment to appear.
                sendBroadcast(new Intent("com.geely.modehelper.DASHCAM")
                    .setClassName("com.geely.modehelper", "com.geely.modehelper.DashReceiver")
                    .putExtra("on", on ? 0 : 1));
                content.postDelayed(() -> { if (section == SEC_CLIPS) selectSection(SEC_CLIPS); }, 1500);
            }));
        content.addView(ctl);

        if (clips.isEmpty()) { content.addView(Style.label(this, getString(R.string.clips_none))); return; }
        for (Clips.Clip c : clips) content.addView(clipRow(c));
    }

    private View clipRow(final Clips.Clip c) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        // Held clips are outlined in the accent: the point of holding is seeing
        // at a glance which ones survive the ring buffer.
        card.setBackground(c.held ? Style.outlinedCard(Style.ACCENT, this)
                                  : Style.card(Style.CARD, this));
        int p = Style.dp(this, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(this, 10);
        card.setLayoutParams(lp);

        if (c.thumb.exists()) {
            android.widget.ImageView shot = new android.widget.ImageView(this);
            shot.setImageBitmap(android.graphics.BitmapFactory.decodeFile(c.thumb.getAbsolutePath()));
            shot.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ip =
                new LinearLayout.LayoutParams(Style.dp(this, 150), Style.dp(this, 62));
            ip.rightMargin = Style.dp(this, 14);
            shot.setLayoutParams(ip);
            card.addView(shot);
        }

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean pendingHold = c.kind == Clips.Kind.RECORDING && Clips.isPending(this, c);
        String tag = c.kind == Clips.Kind.RECORDING
                         ? "  ● " + getString(R.string.clips_recording) + (pendingHold ? "  ★" : "")
                   : c.kind == Clips.Kind.ORPHAN ? "  ⚠ " + getString(R.string.clips_orphan)
                   : c.held ? "  ★" : "";
        text.addView(Style.header(this, c.title() + tag));
        text.addView(Style.label(this, c.subtitle()));
        card.addView(text);

        // Play only for a finished clip: a live one has no moov atom and an
        // orphan needs remuxing before anything can open it. Hold works on
        // both a finished clip (moves it right away) and a recording one
        // (Clips.markPending — see its own comment for why a live file can't
        // just be moved; it gets swept into keep/ once the segment closes).
        if (c.playable()) {
            card.addView(Style.cardButton(this, getString(R.string.clips_play), false,
                () -> startActivity(new Intent(this, ClipPlayerActivity.class)
                        .putExtra(ClipPlayerActivity.EXTRA_PATH, c.mp4.getAbsolutePath()))));
            card.addView(Style.cardButton(this,
                getString(c.held ? R.string.clips_release : R.string.clips_hold), c.held,
                () -> { Clips.hold(this, c, !c.held); selectSection(SEC_CLIPS); }));
        } else if (c.kind == Clips.Kind.RECORDING) {
            final boolean pending = pendingHold;
            card.addView(Style.cardButton(this,
                getString(pending ? R.string.clips_release : R.string.clips_hold), pending,
                () -> {
                    if (pending) Clips.clearPending(this, c); else Clips.markPending(this, c);
                    selectSection(SEC_CLIPS);
                }));
        }
        if (c.kind != Clips.Kind.RECORDING) {
            card.addView(Style.cardButton(this, getString(R.string.clips_delete), false, () ->
                new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.clips_delete_q, c.title()))
                    .setMessage(c.subtitle())
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.clips_delete,
                        (d, w) -> { Clips.delete(c); selectSection(SEC_CLIPS); })
                    .show()));
        }
        return card;
    }

    // =====================================================================
    // Charging history
    // =====================================================================
    private void buildCharge() {
        content.addView(Style.header(this, getString(R.string.charge_title)));

        List<ChargeSession.Summary> sessions = ChargeSession.readLog(this);

        // Last N days/months, as its own card up top — same visual language
        // as the main page's cards (Clima/Turbo/etc.), not a settings line,
        // since this is the headline number for the whole screen. km driven
        // comes from OdoStats's own daily readings, not from charge sessions —
        // an odometer snapshot taken only when charging starts covers just
        // the gaps BETWEEN charges, silently missing any driving before the
        // first or after the last charge in the window. OdoStats has no
        // history before it started running, so with less than
        // chargePeriodDays of data on file this reads "since logging began"
        // rather than a true N-day figure — a smaller number, not a wrong
        // one, and it grows into accuracy on its own.
        long ltmCutoff = System.currentTimeMillis() - chargePeriodDays * 24L * 3600 * 1000;
        int ltmCount = 0; double ltmKwh = 0;
        for (ChargeSession.Summary s : sessions) {
            if (s.startWallMs < ltmCutoff) continue;
            ltmCount++;
            ltmKwh += s.kwh;
        }
        double kmDriven = OdoStats.kmSince(this, chargePeriodDays);

        LinearLayout ltmCard = new LinearLayout(this);
        ltmCard.setOrientation(LinearLayout.VERTICAL);
        int ltmPad = Style.dp(this, 26);
        ltmCard.setPadding(ltmPad, ltmPad, ltmPad, ltmPad);
        ltmCard.setBackground(Style.card(Style.cardFillColor(), this));
        ltmCard.addView(Style.header(this, getString(
            chargePeriodDays <= 30 ? R.string.charge_period_30d : R.string.charge_ltm_title)));

        LinearLayout periodRow = new LinearLayout(this);
        periodRow.setOrientation(LinearLayout.HORIZONTAL);
        periodRow.setPadding(0, Style.dp(this, 8), 0, 0);
        periodRow.addView(periodTile(30, getString(R.string.charge_period_30d)));
        periodRow.addView(periodTile(365, getString(R.string.charge_ltm_title)));
        ltmCard.addView(periodRow);

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, Style.dp(this, 10), 0, 0);
        statsRow.addView(statTile(String.valueOf(ltmCount), getString(R.string.charge_ltm_count_label)));
        statsRow.addView(statTile(String.format(java.util.Locale.US, "%.0f", ltmKwh), "kWh"));
        statsRow.addView(statTile(String.format(java.util.Locale.US, "%.0f", kmDriven), "km"));
        ltmCard.addView(statsRow);
        content.addView(ltmCard);

        double totalKwh = 0;
        for (ChargeSession.Summary s : sessions) totalKwh += s.kwh;
        content.addView(Style.label(this,
            getString(R.string.charge_summary, sessions.size(), totalKwh)));

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setPadding(0, Style.dp(this, 12), 0, Style.dp(this, 4));
        ctl.addView(Style.cardButton(this, getString(R.string.charge_export), false, () ->
            UsbExport.exportFiles((ok, drive, copied) -> runOnUiThread(() -> {
                    String msg = drive == null ? getString(R.string.charge_export_no_drive)
                               : !ok || copied == 0 ? getString(R.string.charge_export_nothing)
                               : getString(R.string.charge_export_ok, copied);
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                }),
                CarDb.file(this))));
        content.addView(ctl);

        if (sessions.isEmpty()) { content.addView(Style.label(this, getString(R.string.charge_none))); return; }
        // Most recent first — readLog() returns oldest-first.
        for (int i = sessions.size() - 1; i >= 0; i--) content.addView(chargeRow(sessions.get(i)));
    }

    // One of the two period-toggle buttons on the charge history card.
    private View periodTile(int days, String label) {
        boolean sel = chargePeriodDays == days;
        TextView b = Style.cardButton(this, label, sel, () -> {
            chargePeriodDays = days;
            selectSection(SEC_CHARGE);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Style.dp(this, 5);
        lp.leftMargin = m; lp.rightMargin = m;
        b.setLayoutParams(lp);
        return b;
    }

    // A big-number-over-small-label tile, same shape repeated three times in
    // the LTM card — count, kWh, km share one look rather than three ad hoc
    // layouts.
    private LinearLayout statTile(String value, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value); v.setTextColor(Style.TEXT); v.setTextSize(34);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(v);
        TextView l = new TextView(this);
        l.setText(label); l.setTextColor(Style.TEXT_DIM); l.setTextSize(14);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(l);
        return t;
    }

    private View chargeRow(ChargeSession.Summary s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Style.card(Style.CARD, this));
        int p = Style.dp(this, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Style.dp(this, 10);
        card.setLayoutParams(lp);
        card.addView(Style.header(this, s.title()));   // date + time range

        final android.widget.ImageView bar = new android.widget.ImageView(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(this, 26));   // tall enough to fit the duration label
        barLp.topMargin = Style.dp(this, 8);
        bar.setLayoutParams(barLp);
        // Drawn once, after layout gives it a real width — this row never
        // changes again, unlike the live card's bar, so no redraw-on-tick
        // machinery is needed here.
        bar.post(() -> {
            int w = bar.getWidth();
            if (w > 0) bar.setImageBitmap(Style.chargeRangeBar(this, w, bar.getHeight(),
                s.socStart / 100f, s.socEnd / 100f, Style.ACCENT, s.durationLabel()));
        });
        card.addView(bar);

        // Smaller than Style.label()'s usual 22sp: this line is now just
        // three numbers (SoC range, kWh, kW), not a sentence — it doesn't
        // need the same weight as a card's main text.
        TextView sub = new TextView(this);
        sub.setText(s.subtitle(this));
        sub.setTextColor(Style.TEXT_DIM);
        sub.setTextSize(16);
        card.addView(sub);
        return card;
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
            boolean enabled = prefs.getBoolean("obd2_enabled", false);
            obdStatus.setText(getString(!enabled ? R.string.obd_status_off
                : Obd2Reader.isConnected() ? R.string.obd_status_connected
                : R.string.obd_status_searching));
        };
        obdListener = connected -> runOnUiThread(updateStatus);
        Obd2Reader.subscribe(obdListener);
        updateStatus.run();

        left.addView(toggleRow(getString(R.string.obd_enable_label),
            prefs.getBoolean("obd2_enabled", false), on -> {
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
            prefs.getBoolean("abrp_enabled", false),
            on -> prefs.edit().putBoolean("abrp_enabled", on).apply()));

        left.addView(toggleRow(getString(R.string.abrp_location_label),
            prefs.getBoolean(AbrpUploader.PREF_SEND_LOCATION, true),
            on -> prefs.edit().putBoolean(AbrpUploader.PREF_SEND_LOCATION, on).apply()));

        TextView abrpLocHint = new TextView(this);
        abrpLocHint.setTextColor(Style.TEXT_DIM); abrpLocHint.setTextSize(13);
        abrpLocHint.setText(getString(R.string.abrp_location_hint));
        abrpLocHint.setPadding(0, 0, 0, Style.dp(this, 4));
        left.addView(abrpLocHint);

        fAbrpToken = field(left, getString(R.string.abrp_field_user_token),
            prefs.getString("abrp_user_token", ""), InputType.TYPE_CLASS_TEXT);

        TextView abrpStatus = new TextView(this);
        abrpStatus.setTextColor(Style.TEXT_DIM); abrpStatus.setTextSize(13);
        abrpStatus.setPadding(0, Style.dp(this, 8), 0, 0);
        left.addView(abrpStatus);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Style.dp(this, 4), 0, 0);
        row.addView(action(getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            prefs.edit()
                .putString("abrp_user_token", fAbrpToken.getText().toString().trim())
                .apply();
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
        boolean teleOn = prefs.getBoolean("tele_enabled", false);
        status.setText(teleOn
            ? getString(R.string.cfg_sending_every, prefs.getInt("tele_interval_s", 10))
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

        boolean cmdsOn = prefs.getBoolean("commands_enabled", true);
        rightToggle.addView(toggleRow(getString(R.string.cfg_commands_label),
            cmdsOn, allow -> {
                prefs.edit().putBoolean("commands_enabled", allow).apply();
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
        String hosts = prefs.getString("mqtt_uri", "tcp://homeassistant.local:1883");
        String legacyAlt = prefs.getString("mqtt_uri_alt", "");
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
        fUser = field(userCol, getString(R.string.cfg_field_user), prefs.getString("mqtt_user", "mosquitto"), InputType.TYPE_CLASS_TEXT);
        userPassRow.addView(userCol);

        LinearLayout passCol = new LinearLayout(this);
        passCol.setOrientation(LinearLayout.VERTICAL);
        passCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        passCol.setPadding(Style.dp(this, 6), 0, 0, 0);
        fPass = field(passCol, getString(R.string.cfg_field_pass), prefs.getString("mqtt_pass", ""),
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
        fInterval.setText(String.valueOf(prefs.getInt("tele_interval_s", 10)));
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
        String saved = prefs.getString("mqtt_tls_test_target", "");
        if (!saved.trim().isEmpty()) return saved.trim();

        String all = (fUri != null) ? fUri.getText().toString().trim() : prefs.getString("mqtt_uri", "");
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

        prefs.edit().putString("mqtt_tls_test_target", target).apply();

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

    // =====================================================================
    // Spotify panel — used to just be appended at the bottom of the MQTT
    // screen with no section of its own; pulled out as part of grouping
    // every section into CAR / DISPLAY / INTEGRATIONS. Separate from the
    // MQTT/HA link by nature, not just by screen: this one talks to
    // Spotify's own Web API straight from the car, no broker, no Home
    // Assistant involved (see SpotifyClient's header for why: the App
    // Remote SDK needs a Spotify app on THIS device, which the car does
    // not have — CarPlay plays through the phone. The Web API just
    // reflects the account, whichever device is actually making sound).
    // =====================================================================
    private void buildSpotify() {
        content.addView(Style.header(this, getString(R.string.cfg_spotify_header)));
        spotifyStatus = new TextView(this);
        spotifyStatus.setTextColor(Style.TEXT); spotifyStatus.setTextSize(16);
        spotifyStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        spotifyStatus.setText(getString(SpotifyClient.connected(this)
            ? R.string.cfg_spotify_status_on : R.string.cfg_spotify_status_off));
        content.addView(spotifyStatus);
        fSpotifyClientId = field(content, getString(R.string.cfg_spotify_client_id),
            SpotifyClient.clientId(this), InputType.TYPE_CLASS_TEXT);
        // Saved on blur — the Connect button below reads it fresh from prefs,
        // not from the field directly, so it always uses whatever was last
        // actually saved.
        fSpotifyClientId.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            SpotifyClient.setClientId(this, fSpotifyClientId.getText().toString());
        });
        LinearLayout spRow = new LinearLayout(this);
        spRow.setOrientation(LinearLayout.HORIZONTAL);
        spRow.addView(action(getString(R.string.cfg_spotify_connect), Style.ACCENT, () -> {
            SpotifyClient.setClientId(this, fSpotifyClientId.getText().toString());
            if (SpotifyClient.clientId(this).isEmpty()) {
                spotifyStatus.setText(getString(R.string.cfg_spotify_need_id));
                return;
            }
            startActivity(new Intent(this, SpotifyAuthActivity.class));
        }));
        spRow.addView(action(getString(R.string.cfg_spotify_disconnect), 0xFF8A3A3A, () -> {
            getSharedPreferences("drivemem", MODE_PRIVATE).edit()
                .remove("spotify_refresh_token").remove("spotify_access_token")
                .remove("spotify_token_expiry").apply();
            spotifyStatus.setText(getString(R.string.cfg_spotify_status_off));
        }));
        content.addView(spRow);
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
        devIdView.setPadding(0, Style.dp(this, 4), 0, Style.dp(this, 8));
        infoCard.addView(devIdView);

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
        fUpdateUrl.setText(prefs.getString("update_url", Updater.DEFAULT_URL));
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
            prefs.edit().putString("update_url", u).apply();
            Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        }));
        urlBtnRow.addView(button(getString(R.string.cfg_btn_reset_default), 0xFF3A5A7A, () -> {
            fUpdateUrl.setText(Updater.DEFAULT_URL);
            prefs.edit().putString("update_url", Updater.DEFAULT_URL).apply();
            Toast.makeText(this, getString(R.string.cfg_btn_reset_default), Toast.LENGTH_SHORT).show();
        }));
        updateCard.addView(urlBtnRow);

        Style.gap(updateCard, this, 14);

        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.addView(action(getString(R.string.cfg_btn_check_update), Style.ACCENT, () -> {
            String u = fUpdateUrl.getText().toString().trim();
            if (u.isEmpty()) { u = Updater.DEFAULT_URL; fUpdateUrl.setText(u); }
            prefs.edit().putString("update_url", u).apply();
            updateCheck();
        }));
        updateCard.addView(actRow);

        status = new TextView(this);
        status.setTextColor(Style.TEXT_DIM);
        status.setTextSize(13);
        status.setPadding(0, Style.dp(this, 10), 0, 0);
        updateCard.addView(status);

        content.addView(updateCard);

        // ---- Touch Controls & Cooldown Card ----
        content.addView(Style.header(this, getString(R.string.cfg_touch_header)));
        LinearLayout touchCard = new LinearLayout(this);
        touchCard.setOrientation(LinearLayout.VERTICAL);
        touchCard.setBackground(Style.card(Style.CARD, this));
        touchCard.setPadding(pad, pad, pad, pad);

        TextView cooldownHint = new TextView(this);
        cooldownHint.setTextColor(Style.TEXT_DIM);
        cooldownHint.setTextSize(13);
        cooldownHint.setText(getString(R.string.cfg_cooldown_hint));
        cooldownHint.setPadding(0, 0, 0, Style.dp(this, 8));
        touchCard.addView(cooldownHint);

        LinearLayout lockRow = new LinearLayout(this);
        lockRow.setOrientation(LinearLayout.HORIZONTAL);
        lockRow.setGravity(Gravity.CENTER_VERTICAL);
        lockRow.setPadding(0, Style.dp(this, 4), 0, Style.dp(this, 4));

        TextView lockLbl = Style.label(this, getString(R.string.cfg_cooldown_label) + ":");
        lockLbl.setTextSize(15);
        lockRow.addView(lockLbl);

        fLockSec = new EditText(this);
        fLockSec.setText(String.valueOf(prefs.getInt("switch_lock_s", GeelySwitch.DEFAULT_LOCK_S)));
        fLockSec.setInputType(InputType.TYPE_CLASS_NUMBER);
        fLockSec.setTextColor(Style.TEXT);
        fLockSec.setTextSize(16);
        fLockSec.setGravity(Gravity.CENTER);
        fLockSec.setBackground(Style.card(Style.CARD_HI, this));
        fLockSec.setPadding(Style.dp(this, 10), Style.dp(this, 8), Style.dp(this, 10), Style.dp(this, 8));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(
            Style.dp(this, 75), ViewGroup.LayoutParams.WRAP_CONTENT);
        lockLp.setMargins(Style.dp(this, 10), 0, Style.dp(this, 6), 0);
        fLockSec.setLayoutParams(lockLp);
        lockRow.addView(fLockSec);

        TextView lockUnit = new TextView(this);
        lockUnit.setText(getString(R.string.cfg_seconds_suffix));
        lockUnit.setTextColor(Style.TEXT_DIM);
        lockUnit.setTextSize(14);
        lockRow.addView(lockUnit);

        lockRow.addView(button(getString(R.string.cfg_btn_save), Style.ACCENT, () -> {
            int s;
            try { s = Integer.parseInt(fLockSec.getText().toString().trim()); }
            catch (NumberFormatException e) { s = GeelySwitch.DEFAULT_LOCK_S; }
            s = Math.max(1, Math.min(30, s));
            prefs.edit().putInt("switch_lock_s", s).apply();
            fLockSec.setText(String.valueOf(s));
            Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        }));
        touchCard.addView(lockRow);

        content.addView(touchCard);

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
        boolean teleOn = prefs.getBoolean("tele_enabled", false);

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
        int iv = prefs.getInt("tele_interval_s", 10);
        activeLastSentView.setText(teleOn
            ? getString(R.string.cfg_sending_every, iv)
            : getString(R.string.cfg_status_off));
    }

    private void save(boolean enable) {
        saveAll(enable);
    }

    private void saveAll(boolean enable) {
        int iv = prefs.getInt("tele_interval_s", 10);
        if (fInterval != null && fInterval.getText() != null) {
            try { iv = Integer.parseInt(fInterval.getText().toString().trim()); } catch (Exception ignored) {}
            iv = Math.max(5, iv);
        }

        SharedPreferences.Editor ed = prefs.edit()
            .putBoolean("tele_enabled", enable)
            .putInt("tele_interval_s", iv);

        if (fUri != null && fUri.getText() != null) {
            ed.putString("mqtt_uri", fUri.getText().toString().trim());
            ed.putString("mqtt_uri_alt", "");
        }
        if (fUser != null && fUser.getText() != null) {
            ed.putString("mqtt_user", fUser.getText().toString().trim());
        }
        if (fPass != null && fPass.getText() != null) {
            ed.putString("mqtt_pass", fPass.getText().toString());
        }
        if (fTlsTarget != null && fTlsTarget.getText() != null) {
            String val = fTlsTarget.getText().toString().trim();
            if (!val.isEmpty()) ed.putString("mqtt_tls_test_target", val);
        }

        if (fLockSec != null && fLockSec.getText() != null) {
            int s;
            try { s = Integer.parseInt(fLockSec.getText().toString().trim()); }
            catch (NumberFormatException e) { s = GeelySwitch.DEFAULT_LOCK_S; }
            s = Math.max(1, Math.min(30, s));
            ed.putInt("switch_lock_s", s);
            fLockSec.setText(String.valueOf(s));
        }

        if (fTrustedSsid != null && fTrustedSsid.getText() != null) {
            String val = fTrustedSsid.getText().toString().trim();
            AdbGate.setTrustedWifi(this, val);
        }

        ed.apply();

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
    }

    private void testOnce() {
        saveAll(prefs.getBoolean("tele_enabled", false));
        status.setText(getString(R.string.cfg_testing));
        final String uri = (fUri != null) ? fUri.getText().toString().trim() : prefs.getString("mqtt_uri", "");
        final String u = (fUser != null) ? fUser.getText().toString().trim() : prefs.getString("mqtt_user", "");
        final String pw = (fPass != null) ? fPass.getText().toString() : prefs.getString("mqtt_pass", "");
        String[] targets = uri.split("[,\\s]+");
        final String firstTarget = (targets.length > 0 && !targets[0].isEmpty()) ? targets[0] : uri;

        logMqtt("TEST", "Iniciando teste de conexão para: " + firstTarget);
        logMqtt("TEST", "Lendo dados dos sensores do veículo...");

        CarActor.get(this).runOnCarThread(() -> {
            CarAccess c = CarActor.get(this).rawAccess();
            boolean carOk = c.isReady() || c.connect(getApplicationContext());
            java.util.LinkedHashMap<String, Object> data =
                carOk ? Telemetry.read(c) : new java.util.LinkedHashMap<>();
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
        saveAll(prefs.getBoolean("tele_enabled", false));
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
        final String uri = (fUri != null) ? fUri.getText().toString().trim() : prefs.getString("mqtt_uri", "");
        final String u = (fUser != null) ? fUser.getText().toString().trim() : prefs.getString("mqtt_user", "");
        final String pw = (fPass != null) ? fPass.getText().toString() : prefs.getString("mqtt_pass", "");
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
    // Drive Mode panel (ported from the old TestActivity)
    // =====================================================================
    // DOORS AND GLASS. Its own section because everything in it moves part of the
    // car, sometimes while nobody is looking at the screen — which is a different
    // promise from anything else in Config, and it deserves a page rather than a
    // heading somebody scrolls past on the way to the MQTT host.
    //
    // Everything here stays OFF by default. See DoorWindow and Purge: the window
    // lock cannot be read on this car and is not enforced against our writes, so
    // nothing on this page is protected by it.
    private void buildDoors() {
        content.addView(Style.header(this, getString(R.string.cfg_doors_header)));
        content.addView(toggleRow(getString(R.string.cfg_window_on_door),
            DoorWindow.enabled(this), on -> {
                prefs.edit().putBoolean(DoorWindow.KEY, on).apply();
                // The watch itself is always registered; the pref only decides
                // whether an event acts. So nothing has to be started or stopped
                // here, and flipping it mid-session cannot make the next door
                // event look like the first one.
                ComfortHub.get(this);
            }));
        TextView winHint = new TextView(this);
        winHint.setTextColor(Style.TEXT_DIM); winHint.setTextSize(13);
        winHint.setPadding(0, 0, 0, Style.dp(this, 4));
        winHint.setText(getString(R.string.cfg_window_on_door_hint));
        content.addView(winHint);
    }

    private void buildDrive() {
        driveCards.clear(); regenCards.clear();
        content.addView(Style.header(this, getString(R.string.cfg_drive_header)));
        status = new TextView(this);
        status.setTextColor(Style.TEXT); status.setTextSize(16);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setText(getString(R.string.cfg_connecting));
        content.addView(status);

        // the glyphs "🍃 ☁ ⚡ ◦ ◉ ●" are icons, not text: they do not get translated
        //
        // Capped width, not MATCH_PARENT: content is the whole right-hand
        // panel (screen width minus the 240dp sidebar), and small tiles/
        // fields/rows stretched across all of it turn into oversized slabs.
        // Half the screen keeps everything on this page a sane, consistent
        // size regardless of how wide the panel is -- applied to every row
        // below (drive, regen, turbo, actions), not just the mode cards.
        int pageWidth = Style.dp(this, 960);
        LinearLayout driveRow = new LinearLayout(this);
        driveRow.setOrientation(LinearLayout.HORIZONTAL);
        driveRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        driveRow.addView(modeCard("🍃", getString(R.string.cfg_mode_eco), Modes.DRIVE_ECO, driveCards, () -> setDrive(Modes.DRIVE_ECO)));
        driveRow.addView(modeCard("☁", getString(R.string.cfg_mode_comfort), Modes.DRIVE_COMFORT, driveCards, () -> setDrive(Modes.DRIVE_COMFORT)));
        driveRow.addView(modeCard("⚡", getString(R.string.cfg_mode_sport), Modes.DRIVE_SPORT, driveCards, () -> setDrive(Modes.DRIVE_SPORT)));
        content.addView(driveRow);

        content.addView(Style.header(this, getString(R.string.cfg_regen_header)));
        LinearLayout regenRow = new LinearLayout(this);
        regenRow.setOrientation(LinearLayout.HORIZONTAL);
        regenRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        regenRow.addView(modeCard("◦", getString(R.string.cfg_regen_low), Modes.REGEN_LOW, regenCards, () -> setRegen(Modes.REGEN_LOW)));
        regenRow.addView(modeCard("◉", getString(R.string.cfg_regen_mid), Modes.REGEN_MID, regenCards, () -> setRegen(Modes.REGEN_MID)));
        regenRow.addView(modeCard("●", getString(R.string.cfg_regen_high), Modes.REGEN_HIGH, regenCards, () -> setRegen(Modes.REGEN_HIGH)));
        content.addView(regenRow);

        content.addView(Style.header(this, getString(R.string.turbo_header)));
        LinearLayout turboToggle = toggleRow(getString(R.string.turbo_enable_label),
            prefs.getBoolean("turbo_enabled", true),
            on -> prefs.edit().putBoolean("turbo_enabled", on).apply());
        turboToggle.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(turboToggle);
        fTurbo = field(content, getString(R.string.turbo_duration_label),
            String.valueOf(prefs.getInt("turbo_duration_s", TurboMode.DEFAULT_DURATION_S)),
            InputType.TYPE_CLASS_NUMBER);
        fTurbo.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Saved by "Salvar padrão" below, not on blur or every keystroke —
        // blur never reliably fired here (dismissing the on-screen number pad
        // hides the IME but does not necessarily move focus off the
        // EditText, so a save-on-blur listener could go the whole session
        // without firing once — reported as "edited it, it reverted to 30",
        // which was really "it never saved at all"). "Salvar padrão" already
        // means "persist my settings on this screen" for drive/regen mode;
        // Turbo's duration is just one more field it sweeps up on the same
        // press, with the same one clear save moment instead of a listener
        // whose firing conditions are easy to get wrong twice.

        content.addView(Style.header(this, getString(R.string.cfg_actions_header)));
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        actionRow.addView(action(getString(R.string.cfg_btn_apply), Style.ACCENT, this::applyNow));
        actionRow.addView(action(getString(R.string.cfg_btn_save_default), 0xFF6A4CFF, this::saveDefault));
        content.addView(actionRow);

        highlight();
        if (!carActorSubscribed) {
            carActorSubscribed = true;
            EntityBus.subscribe("car.drive_mode", driveListener);
            EntityBus.subscribe("car.regen_mode", regenListener);
        }
        refresh();
    }

    private LinearLayout modeCard(String symbol, String label, int key,
                                  Map<Integer, LinearLayout> reg, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int pad = Style.dp(this, 8);
        card.setPadding(pad, pad, pad, pad);
        TextView ic = new TextView(this);
        ic.setText(symbol); ic.setTextColor(Style.TEXT); ic.setTextSize(38);
        ic.setGravity(Gravity.CENTER);
        card.addView(ic);
        TextView lb = new TextView(this);
        lb.setText(label); lb.setTextColor(Style.TEXT); lb.setTextSize(19);
        lb.setTypeface(null, android.graphics.Typeface.BOLD);
        lb.setGravity(Gravity.CENTER);
        lb.setPadding(0, Style.dp(this, 8), 0, 0);
        card.addView(lb);
        card.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Style.dp(this, 120), 1f);
        int m = Style.dp(this, 4);
        lp.setMargins(m, Style.dp(this, 8), m, 0);
        card.setLayoutParams(lp);
        reg.put(key, card);
        return card;
    }

    private void setDrive(int v) { selDrive = v; highlight(); }
    private void setRegen(int v) { selRegen = v; highlight(); }

    // Fill = your standard (selDrive/selRegen, always known). Border = what
    // the car reports right now (liveDrive/liveRegen, only once driveKnown/
    // regenKnown). A card can carry both, one, or neither — that overlap (or
    // lack of it) is the whole point: it is what makes "car booted into
    // something other than your standard" visible instead of silent.
    private void highlight() {
        for (Map.Entry<Integer, LinearLayout> e : driveCards.entrySet())
            paintCard(e.getValue(), e.getKey() == selDrive, driveKnown && e.getKey() == liveDrive);
        for (Map.Entry<Integer, LinearLayout> e : regenCards.entrySet())
            paintCard(e.getValue(), e.getKey() == selRegen, regenKnown && e.getKey() == liveRegen);
    }

    // card background + text: on the light theme the selected fill is dark blue,
    // so the text has to turn light (otherwise it disappears). "isLive" adds a
    // thicker accent-coloured stroke on top, independent of the fill.
    private void paintCard(LinearLayout card, boolean selected, boolean isLive) {
        int fill = selected ? Style.CARD_ON : Style.CARD;
        android.graphics.drawable.GradientDrawable g = Style.card(fill, this);
        if (isLive) g.setStroke(Style.dp(this, Style.STROKE_DP + 2), Style.ACCENT);
        card.setBackground(g);
        int fg = Style.onFill(fill);
        for (int i = 0; i < card.getChildCount(); i++) {
            View ch = card.getChildAt(i);
            if (ch instanceof TextView) ((TextView) ch).setTextColor(fg);
        }
    }

    private void applyNow() {
        final int d = selDrive, r = selRegen;
        // A boost in progress owns drive_mode until it reverts on its own —
        // the Config picker must not undercut it mid-countdown. Regen has no
        // such conflict, so it still applies either way.
        boolean turboActive = TurboMode.get(this).active();
        if (!turboActive) CarActor.get(this).cast("drive_mode", d);
        CarActor.get(this).cast("regen_mode", r);
        // Trusted as sent, not waited on — see CarActor's own comment for
        // why a cast doesn't block here.
        liveRegen = r; regenKnown = true;
        if (!turboActive) { liveDrive = d; driveKnown = true; }
        highlight();
        status.setText(turboActive
            ? getString(R.string.cfg_applied_turbo_active, Modes.regenName(r))
            : getString(R.string.cfg_applied, Modes.driveName(d), Modes.regenName(r)));
    }

    private void saveDefault() {
        prefs.edit().putInt("drive", selDrive).putInt("regen", selRegen).apply();
        // tell the system helper (com.geely.modehelper) about the new default — it
        // is the one that, running as uid system, re-applies it when the car wakes
        // (here, as a normal app, we do not survive the suspend).
        try {
            android.content.Intent i = new android.content.Intent("com.geely.modehelper.SET_MODE");
            i.setPackage("com.geely.modehelper");
            i.putExtra("drive", selDrive).putExtra("regen", selRegen);
            sendBroadcast(i);
        } catch (Throwable ignored) {}
        // Turbo duration rides along here — same "persist my settings on
        // this screen" action, not a separate button. Clamped 5..120s (under
        // 5 is not a "boost", and TurboMode has no cap of its own to lean
        // on), and the field is rewritten to the clamped value so pressing
        // Save is also the confirmation that it took.
        if (fTurbo != null) {
            int turboSec;
            try { turboSec = Integer.parseInt(fTurbo.getText().toString().trim()); }
            catch (NumberFormatException e) { turboSec = TurboMode.DEFAULT_DURATION_S; }
            turboSec = Math.max(5, Math.min(120, turboSec));
            prefs.edit().putInt("turbo_duration_s", turboSec).apply();
            fTurbo.setText(String.valueOf(turboSec));
        }
        status.setText(getString(R.string.cfg_startup_default,
            Modes.driveName(selDrive), Modes.regenName(selRegen)));
    }

    // A fresh read (CarActor.read -> CarDataHub), not a cache lookup — this
    // is the one seed value the screen needs the instant it opens, and the
    // discrete watch above may not have delivered its first event yet.
    // NEVER touches selDrive/selRegen — that field is the user's standard,
    // and a stale/failed/booting-into-factory-default read here must not be
    // able to overwrite it. See the field comment.
    private void refresh() {
        CarActor a = CarActor.get(this);
        a.read("drive_mode", d -> {
            if (d instanceof Integer) { liveDrive = (Integer) d; driveKnown = true; }
            ui.post(this::afterRefresh);
        });
        a.read("regen_mode", r -> {
            if (r instanceof Integer) { liveRegen = (Integer) r; regenKnown = true; }
            ui.post(this::afterRefresh);
        });
    }

    private void afterRefresh() {
        highlight();
        if (!driveKnown || !regenKnown) status.setText(getString(R.string.cfg_mode_unknown));
        else updateCurrentStatus();
    }

    // Shared by refresh() and the live watch callbacks — one place that
    // knows how to render "what the car reports now" as text.
    private void updateCurrentStatus() {
        if (status == null || !driveKnown || !regenKnown) return;
        status.setText(getString(R.string.cfg_current, Modes.driveName(liveDrive), Modes.regenName(liveRegen)));
    }

    // =====================================================================
    // Menu bar panel (the toggles that moved out of the AC screen)
    // =====================================================================
    private void buildBar() {
        content.addView(Style.header(this, getString(R.string.cfg_bar_header)));

        content.addView(toggleRow(getString(R.string.cfg_bar_outtemp),
            prefs.getBoolean("outtemp_on", false), on -> {
                prefs.edit().putBoolean("outtemp_on", on).apply();
                Intent svc = new Intent(this, OutTempService.class);
                if (on) startService(svc); else stopService(svc);
            }));

        content.addView(toggleRow(getString(R.string.cfg_bar_wifi),
            prefs.getBoolean("wifiicon_on", false), on -> {
                prefs.edit().putBoolean("wifiicon_on", on).apply();
                Intent svc = new Intent(this, WifiIconService.class);
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                } else stopService(svc);
            }));

        content.addView(toggleRow(getString(R.string.cfg_bar_soc),
            prefs.getBoolean("soc_on", false), on -> {
                prefs.edit().putBoolean("soc_on", on).apply();
                Intent svc = new Intent(this, SocIconService.class);
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

        content.addView(Style.header(this, getString(R.string.cfg_appearance_header)));
        LinearLayout appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        appRow.addView(appearanceTile(Style.APPEARANCE_LIGHT, getString(R.string.cfg_appearance_light)));
        appRow.addView(appearanceTile(Style.APPEARANCE_DARK,  getString(R.string.cfg_appearance_dark)));
        appRow.addView(appearanceTile(Style.APPEARANCE_AUTO,  getString(R.string.cfg_appearance_auto)));
        content.addView(appRow);
        TextView appNote = new TextView(this);
        appNote.setTextColor(Style.TEXT_DIM); appNote.setTextSize(13);
        appNote.setPadding(0, Style.dp(this, 8), 0, 0);
        appNote.setText(getString(R.string.cfg_appearance_note));
        content.addView(appNote);

        // Noturno's own scene (VaporArtView) is a different art path entirely
        // from the skyline (SkylineArtView) every other theme uses -- this
        // toggle only ever affects the skyline, so Noturno always shows its
        // own art regardless of it. See ComfortActivity's art selection.
        //
        // Flipping this recreates the screen (not just saves the pref):
        // the seed config below must appear/disappear with it, not just sit
        // there disabled -- "the config for it" goes away along with the
        // skyline itself, not just the art.
        boolean skylineEnabled = prefs.getBoolean("skyline_enabled", true);
        LinearLayout skylineToggle = toggleRow(getString(R.string.cfg_skyline_label),
            skylineEnabled,
            on -> {
                prefs.edit().putBoolean("skyline_enabled", on).apply();
                getIntent().putExtra("section", SEC_LOOK);
                recreate();
            });
        skylineToggle.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(skylineToggle);

        if (skylineEnabled) {
            content.addView(Style.header(this, getString(R.string.cfg_skyline_seed_header)));

            boolean randomPerDrive = prefs.getBoolean("skyline_random_per_drive", false);

            fSkylineSeed = field(content, getString(R.string.cfg_skyline_seed_label),
                String.valueOf(prefs.getLong("skyline_seed", com.geely.drivemem.art.Skyline.DEFAULT_SEED)),
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
                prefs.edit().putLong("skyline_seed", seed).apply();
                fSkylineSeed.setText(String.valueOf(seed));
                Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
            }));
            skylineBtnRow.addView(action(getString(R.string.cfg_skyline_cycle), 0xFF6A4CFF, () -> {
                if (fSkylineSeed != null && !fSkylineSeed.isEnabled()) return;
                long seed = new java.util.Random().nextLong() & Long.MAX_VALUE;
                prefs.edit().putLong("skyline_seed", seed).apply();
                if (fSkylineSeed != null) fSkylineSeed.setText(String.valueOf(seed));
                Toast.makeText(this, getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
            }));
            content.addView(skylineBtnRow);

            LinearLayout randomToggle = toggleRow(getString(R.string.cfg_skyline_random_per_drive_label),
                randomPerDrive,
                on -> {
                    prefs.edit().putBoolean("skyline_random_per_drive", on).apply();
                    getIntent().putExtra("section", SEC_LOOK);
                    recreate();
                });
            randomToggle.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(randomToggle);
        }

        content.addView(Style.header(this, getString(R.string.cfg_theme_header)));
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
        sw.setLockSeconds(prefs.getInt("switch_lock_s", GeelySwitch.DEFAULT_LOCK_S));
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
        if (spotifyStatus != null) {
            spotifyStatus.setText(getString(SpotifyClient.connected(this)
                ? R.string.cfg_spotify_status_on : R.string.cfg_spotify_status_off));
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
