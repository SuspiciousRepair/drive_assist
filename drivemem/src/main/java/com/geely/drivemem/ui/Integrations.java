package com.geely.drivemem.ui;

import android.content.Context;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.OemTranslations;
import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.sensors.ObdHub;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.util.Prefs;

import java.util.Arrays;
import java.util.List;

/** Every integration the Config screen's Integrations page shows as a tile.
 *
 * An integration is anything that talks to an OUTSIDE device, service or
 * account: a broker, a Bluetooth dongle, a web API, a repo of files to
 * download (the car app translations). The car's own data
 * sources (CAN, the vehicle HAL) are not integrations -- the owner has
 * nothing to set up for them -- so they are not listed here.
 *
 * To add one: write its Telemetry*Section, give it a new SEC_* number in
 * TelemetryActivity (never reuse a retired one), add its case to
 * selectSection(), and add one line to ALL below. The sidebar does not
 * change: it has one "Integrations" item however many there are. */
final class Integrations {
    private Integrations() {}

    enum State { OFF, OK, BUSY, ERROR }

    static final class Status {
        final State state;
        final String text;
        Status(State state, String text) { this.state = state; this.text = text; }

        /** True when a tile that shows `o` already shows this: nothing to
         * redraw. The page asks every 2 s, and most answers are the same. */
        boolean sameAs(Status o) {
            return o != null && state == o.state && java.util.Objects.equals(text, o.text);
        }
    }

    interface StatusFn { Status of(Context c); }

    static final class Integration {
        final int section;      // TelemetryActivity SEC_* that holds its settings
        final int nameRes;
        final int descRes;
        final int iconRes;      // a white 24dp vector, drawn on `color`
        final int color;        // the tile's icon badge
        final StatusFn status;
        Integration(int section, int nameRes, int descRes, int iconRes, int color, StatusFn status) {
            this.section = section; this.nameRes = nameRes; this.descRes = descRes;
            this.iconRes = iconRes; this.color = color; this.status = status;
        }
    }

    /** Tile order on the page. */
    static final List<Integration> ALL = Arrays.asList(
        new Integration(TelemetryActivity.SEC_MQTT, R.string.int_ha_name, R.string.int_ha_desc,
            R.drawable.ic_home_assistant, 0xFF18BCF2, Integrations::homeAssistant),
        new Integration(TelemetryActivity.SEC_OBD, R.string.int_obd_name, R.string.int_obd_desc,
            R.drawable.ic_car_connected, 0xFFEF6C00, Integrations::obd),
        new Integration(TelemetryActivity.SEC_ABRP, R.string.int_abrp_name, R.string.int_abrp_desc,
            R.drawable.ic_route, 0xFF00897B, Integrations::abrp),
        new Integration(TelemetryActivity.SEC_MUSIC, R.string.int_music_name, R.string.int_music_desc,
            R.drawable.ic_lyrics, 0xFFD81B60, Integrations::music),
        new Integration(TelemetryActivity.SEC_OEM_TEXT, R.string.int_oem_name, R.string.int_oem_desc,
            R.drawable.ic_translate, 0xFF5E6AD2, Integrations::oemText)
    );

    /** The integration whose settings page is `section`, or null. */
    static Integration forSection(int section) {
        for (Integration i : ALL) if (i.section == section) return i;
        return null;
    }

    private static Status off(Context c) {
        return new Status(State.OFF, c.getString(R.string.cfg_status_off));
    }

    private static Status homeAssistant(Context c) {
        if (!Prefs.getTeleEnabled(c)) return off(c);
        TelemetryService svc = TelemetryService.getInstance();
        if (svc != null && svc.isRunning() && svc.isConnected())
            return new Status(State.OK, c.getString(R.string.int_status_connected));
        return new Status(State.BUSY, c.getString(R.string.cfg_connecting));
    }

    private static Status obd(Context c) {
        if (!Prefs.getObd2Enabled(c)) return off(c);
        return ObdHub.connected(com.geely.drivemem.car.CarActor.get())
            ? new Status(State.OK, c.getString(R.string.int_status_connected))
            : new Status(State.BUSY, c.getString(R.string.int_status_searching));
    }

    private static Status abrp(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("drivemem", 0);
        if (!p.getBoolean("abrp_enabled", false)) return off(c);
        if (p.getString("abrp_user_token", "").trim().isEmpty())
            return new Status(State.ERROR, c.getString(R.string.int_status_needs_setup));
        if (AbrpUploader.lastAttemptAtMs() > 0 && !AbrpUploader.lastAttemptOk())
            return new Status(State.ERROR, c.getString(R.string.int_status_failing));
        return new Status(State.OK, c.getString(R.string.int_status_on));
    }

    // modehelper owns this state. Each call asks it again (the page calls
    // every 2 s while open) and shows the answer it already has.
    private static Status oemText(Context c) {
        OemTranslations.refresh(c, null);
        OemTranslations.Status s = OemTranslations.last();
        if (s == null) return off(c);
        switch (s.state) {
            case OemTranslations.ON:
                return new Status(State.OK, c.getString(R.string.int_status_on));
            case OemTranslations.BUSY:
                return new Status(State.BUSY, c.getString(R.string.int_status_working));
            case OemTranslations.ERROR:
                return new Status(State.ERROR, c.getString(R.string.int_status_failing));
            case OemTranslations.NO_HELPER:
                return new Status(State.ERROR, c.getString(R.string.int_status_needs_setup));
            default:
                return off(c);
        }
    }

    // Follows the "Phone music" switch: it is what turns the card on.
    private static Status music(Context c) {
        return Prefs.getBtMusic(c) ? new Status(State.OK, c.getString(R.string.int_status_on)) : off(c);
    }
}
