package com.geely.drivemem.ui;

import com.geely.drivemem.R;

import com.geely.drivemem.util.SpotifyClient;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Spotify authentication via browser redirect. Handles two flows:
 * (1) Starting login with PKCE, sending authorization URL to browser.
 * (2) Handling the redirect callback to exchange code for token. */
public class SpotifyAuthActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SpotifyClient.init(this);

        Uri data = getIntent().getData();
        if (data != null) {
            handleRedirect(data);
        } else {
            startLogin();
        }
    }

    private void startLogin() {
        String[] pkce = SpotifyClient.newPkce();
        SpotifyClient.savePkceVerifier(this, pkce[0]);
        String url = SpotifyClient.authorizeUrl(this, pkce[1]);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.spotify_connect_failed), Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void handleRedirect(Uri uri) {
        String code = uri.getQueryParameter("code");
        String error = uri.getQueryParameter("error");
        if (code == null) {
            Toast.makeText(this, error == null ? getString(R.string.spotify_connect_failed)
                : getString(R.string.spotify_connect_failed) + ": " + error, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String verifier = SpotifyClient.readPkceVerifier(this);
        new Thread(() -> {
            boolean ok = SpotifyClient.exchangeCode(this, code, verifier);
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? getString(R.string.spotify_connected)
                                        : getString(R.string.spotify_connect_failed), Toast.LENGTH_LONG).show();
                finish();
            });
        }, "spotify-auth").start();
    }
}
