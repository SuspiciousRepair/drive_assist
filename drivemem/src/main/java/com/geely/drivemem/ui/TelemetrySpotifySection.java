package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.SpotifyClient;
import com.geely.drivemem.util.Style;

/** Spotify panel of the Config screen -- client ID, connect/disconnect, and
 * the home-screen large-card toggle. Separate from the MQTT/HA link by
 * nature, not just by screen: this one talks to Spotify's own Web API
 * straight from the car, no broker, no Home Assistant involved (see
 * SpotifyClient's header for why: the App Remote SDK needs a Spotify app on
 * THIS device, which the car does not have -- CarPlay plays through the
 * phone. The Web API just reflects the account, whichever device is
 * actually making sound).
 *
 * Extracted from TelemetryActivity.buildSpotify() so that 2800+ line file
 * stops growing with every new Config section -- one View subclass per
 * section, same pattern ChargeStatsView/DailyStatsView already use. */
public final class TelemetrySpotifySection extends LinearLayout {
    private final TextView spotifyStatus;
    private final EditText fSpotifyClientId;

    public TelemetrySpotifySection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        addView(Style.header(activity, activity.getString(R.string.cfg_spotify_header)));
        spotifyStatus = new TextView(activity);
        spotifyStatus.setTextColor(Style.TEXT); spotifyStatus.setTextSize(16);
        spotifyStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        spotifyStatus.setText(activity.getString(SpotifyClient.connected(activity)
            ? R.string.cfg_spotify_status_on : R.string.cfg_spotify_status_off));
        addView(spotifyStatus);
        fSpotifyClientId = Style.field(activity, this, activity.getString(R.string.cfg_spotify_client_id),
            SpotifyClient.clientId(activity), InputType.TYPE_CLASS_TEXT);
        // Saved on blur -- the Connect button below reads it fresh from prefs,
        // not from the field directly, so it always uses whatever was last
        // actually saved.
        fSpotifyClientId.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            SpotifyClient.setClientId(activity, fSpotifyClientId.getText().toString());
        });

        LinearLayout spRow = new LinearLayout(activity);
        spRow.setOrientation(LinearLayout.HORIZONTAL);
        spRow.addView(Style.button(activity, activity.getString(R.string.cfg_spotify_connect), Style.ACCENT, () -> {
            SpotifyClient.setClientId(activity, fSpotifyClientId.getText().toString());
            if (SpotifyClient.clientId(activity).isEmpty()) {
                spotifyStatus.setText(activity.getString(R.string.cfg_spotify_need_id));
                return;
            }
            activity.startActivity(new Intent(activity, SpotifyAuthActivity.class));
        }));
        spRow.addView(Style.button(activity, activity.getString(R.string.cfg_spotify_disconnect), 0xFF8A3A3A, () -> {
            Prefs.file(activity).edit()
                .remove("spotify_refresh_token").remove("spotify_access_token")
                .remove("spotify_token_expiry").apply();
            spotifyStatus.setText(activity.getString(R.string.cfg_spotify_status_off));
        }));
        addView(spRow);
        Style.gap(this, activity, 20);

        // Home screen card style -- read once at ComfortActivity's own
        // onCreate (musicLargeCardAtBuild), same pattern as turbo_enabled
        // and skyline_enabled, so this only needs the plain pref written
        // here, no live-update plumbing back to a screen that isn't open.
        addView(Style.toggleRow(activity, activity.getString(R.string.cfg_spotify_large_card),
            Prefs.getSpotifyLargeCard(activity),
            on -> Prefs.setSpotifyLargeCard(activity, on)));
        TextView largeCardHint = new TextView(activity);
        largeCardHint.setTextColor(Style.TEXT_DIM); largeCardHint.setTextSize(13);
        largeCardHint.setPadding(0, 0, 0, Style.dp(activity, 4));
        largeCardHint.setText(activity.getString(R.string.cfg_spotify_large_card_hint));
        addView(largeCardHint);
    }

    /** Refreshes the connection status text -- called after returning from
     * the Spotify auth flow (onActivityResult), which may complete well
     * after this section was built and even after the user navigated to a
     * different Config section; harmless if this view is no longer attached. */
    public void refreshStatus(Activity activity) {
        spotifyStatus.setText(activity.getString(SpotifyClient.connected(activity)
            ? R.string.cfg_spotify_status_on : R.string.cfg_spotify_status_off));
    }
}
