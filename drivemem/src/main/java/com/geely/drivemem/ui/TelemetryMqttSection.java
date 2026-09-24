package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.controls.AdbGate;
import com.geely.drivemem.net.CertImporter;
import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.services.TelemetryService;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

import java.io.File;
import java.io.InputStream;
import java.util.List;

/** MQTT and TLS certificate configuration panel of the Config screen -- Home Assistant
 * broker settings, MQTT credentials, trusted Wi-Fi for ADB, certificate management,
 * and live testing controls. Includes the live log display and connection test buttons,
 * plus the full certificate import/management UI (client cert, CA cert, TLS tests).
 *
 * Extracted from TelemetryActivity.buildMqtt() and buildCertSection() following the same
 * pattern as TelemetrySpotifySection and TelemetryDoorsSection. */
public final class TelemetryMqttSection extends LinearLayout {
    private final Activity activity;
    private final TextView status;  // shared with Activity (reused by sections)

    // Log display
    private TextView mqttLogView;
    private ScrollView mqttLogScroll;

    // Status display
    private TextView activeBrokerView, activeClientView, activeLastSentView;
    private LinearLayout mqttConfigContainer;

    // Configuration fields
    private EditText fUri, fUser, fPass, fInterval, fTrustedSsid, fTlsTarget;

    // Certificate UI
    private TextView clientCertStatus, caCertStatus, tlsTestStatus;
    private LinearLayout clientBtnRow, caBtnRow;

    public TelemetryMqttSection(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);

        // Reuse the Activity's shared status field (recreated by each section that needs it)
        this.status = new TextView(activity);
        this.status.setTextColor(Style.TEXT);
        this.status.setTextSize(16);
        this.status.setTypeface(null, android.graphics.Typeface.BOLD);
        boolean teleOn = Prefs.getTeleEnabled(activity);
        this.status.setText(teleOn
            ? activity.getString(R.string.cfg_sending_every, Prefs.getTeleIntervalS(activity))
            : activity.getString(R.string.cfg_status_off));
        this.status.setPadding(0, 0, 0, Style.dp(activity, 8));
        addView(Style.header(activity, activity.getString(R.string.cfg_mqtt_header)));
        addView(this.status);

        buildMqtt();
    }

    private void buildMqtt() {
        // Security / Master Toggles Card at Top
        LinearLayout secCard = new LinearLayout(activity);
        secCard.setOrientation(LinearLayout.VERTICAL);
        secCard.setBackground(Style.card(Style.CARD, activity));
        int scPad = Style.dp(activity, 12);
        secCard.setPadding(scPad, scPad, scPad, scPad);

        LinearLayout togglesRow = new LinearLayout(activity);
        togglesRow.setOrientation(LinearLayout.HORIZONTAL);

        // Master Toggle: Enable MQTT
        LinearLayout leftToggle = new LinearLayout(activity);
        leftToggle.setOrientation(LinearLayout.VERTICAL);
        leftToggle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        leftToggle.setPadding(0, 0, Style.dp(activity, 8), 0);

        boolean teleOn = Prefs.getTeleEnabled(activity);
        leftToggle.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_mqtt_enable_label),
            teleOn, on -> {
                saveAll(on);
                if (mqttConfigContainer != null) {
                    mqttConfigContainer.setVisibility(on ? View.VISIBLE : View.GONE);
                }
                activity.getApplicationContext();  // logMqtt will be called via Activity
            }));

        TextView teleHint = new TextView(activity);
        teleHint.setTextColor(Style.TEXT_DIM);
        teleHint.setTextSize(12);
        teleHint.setText(activity.getString(R.string.cfg_mqtt_enable_hint));
        teleHint.setPadding(Style.dp(activity, 44), 0, 0, 0);
        leftToggle.addView(teleHint);
        togglesRow.addView(leftToggle);

        // Secondary Toggle: Accept Commands from HA
        LinearLayout rightToggle = new LinearLayout(activity);
        rightToggle.setOrientation(LinearLayout.VERTICAL);
        rightToggle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rightToggle.setPadding(Style.dp(activity, 8), 0, 0, 0);

        boolean cmdsOn = Prefs.getCommandsEnabled(activity);
        rightToggle.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_commands_label),
            cmdsOn, allow -> {
                Prefs.setCommandsEnabled(activity, allow);
                Intent svc = new Intent(activity, TelemetryService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
            }));

        TextView cmdHint = new TextView(activity);
        cmdHint.setTextColor(Style.TEXT_DIM);
        cmdHint.setTextSize(12);
        cmdHint.setText(activity.getString(R.string.cfg_commands_hint));
        cmdHint.setPadding(Style.dp(activity, 44), 0, 0, 0);
        rightToggle.addView(cmdHint);
        togglesRow.addView(rightToggle);

        secCard.addView(togglesRow);
        addView(secCard);

        Style.gap(this, activity, 14);

        // Two-column container for all settings and live feedback
        mqttConfigContainer = new LinearLayout(activity);
        mqttConfigContainer.setOrientation(LinearLayout.HORIZONTAL);
        mqttConfigContainer.setVisibility(teleOn ? View.VISIBLE : View.GONE);

        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.setPadding(0, 0, Style.dp(activity, 12), 0);

        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.setPadding(Style.dp(activity, 12), 0, 0, 0);

        mqttConfigContainer.addView(left);
        mqttConfigContainer.addView(right);
        addView(mqttConfigContainer);

        // ---- Left Column: Broker & Controls ----
        left.addView(Style.header(activity, activity.getString(R.string.cfg_broker_header)));
        String hosts = Prefs.getMqttUri(activity, "tcp://homeassistant.local:1883");
        String legacyAlt = Prefs.getMqttUriAlt(activity);
        if (!legacyAlt.isEmpty()) hosts = hosts + "\n" + legacyAlt;
        fUri = Style.field(activity, left, activity.getString(R.string.cfg_field_hosts), hosts,
                InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_CLASS_TEXT);
        fUri.setSingleLine(false);
        fUri.setMaxLines(4);

        LinearLayout userPassRow = new LinearLayout(activity);
        userPassRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout userCol = new LinearLayout(activity);
        userCol.setOrientation(LinearLayout.VERTICAL);
        userCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        userCol.setPadding(0, 0, Style.dp(activity, 6), 0);
        fUser = Style.field(activity, userCol, activity.getString(R.string.cfg_field_user), Prefs.getMqttUser(activity, "mosquitto"), InputType.TYPE_CLASS_TEXT);
        userPassRow.addView(userCol);

        LinearLayout passCol = new LinearLayout(activity);
        passCol.setOrientation(LinearLayout.VERTICAL);
        passCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        passCol.setPadding(Style.dp(activity, 6), 0, 0, 0);
        fPass = Style.field(activity, passCol, activity.getString(R.string.cfg_field_pass), Prefs.getMqttPass(activity),
                InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_CLASS_TEXT);
        userPassRow.addView(passCol);
        left.addView(userPassRow);

        LinearLayout intervalRow = new LinearLayout(activity);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        intervalRow.setPadding(0, Style.dp(activity, 8), 0, Style.dp(activity, 6));

        TextView ivLbl = Style.label(activity, activity.getString(R.string.cfg_field_interval) + ":");
        ivLbl.setTextColor(Style.TEXT_DIM);
        intervalRow.addView(ivLbl);

        fInterval = new EditText(activity);
        fInterval.setText(String.valueOf(Prefs.getTeleIntervalS(activity)));
        fInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        fInterval.setTextColor(Style.TEXT);
        fInterval.setTextSize(16);
        fInterval.setGravity(Gravity.CENTER);
        fInterval.setBackground(Style.card(Style.CARD, activity));
        int ivPadH = Style.dp(activity, 12);
        int ivPadV = Style.dp(activity, 8);
        fInterval.setPadding(ivPadH, ivPadV, ivPadH, ivPadV);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
            Style.dp(activity, 75), ViewGroup.LayoutParams.WRAP_CONTENT);
        ivLp.setMargins(Style.dp(activity, 10), 0, Style.dp(activity, 6), 0);
        fInterval.setLayoutParams(ivLp);
        intervalRow.addView(fInterval);

        TextView ivUnit = new TextView(activity);
        ivUnit.setText(activity.getString(R.string.cfg_seconds_suffix));
        ivUnit.setTextColor(Style.TEXT_DIM);
        ivUnit.setTextSize(14);
        intervalRow.addView(ivUnit);
        left.addView(intervalRow);

        buildCertSection(left);

        left.addView(Style.header(activity, activity.getString(R.string.cfg_security_header)));

        final TextView adbHint = new TextView(activity);
        adbHint.setTextColor(Style.TEXT_DIM);
        adbHint.setTextSize(12);
        adbHint.setPadding(0, 0, 0, Style.dp(activity, 4));
        left.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_adb_label),
            AdbGate.isEnabled(activity), on -> {
                if (!AdbGate.request(activity, on, AdbGate.DEFAULT_MINUTES)) {
                    adbHint.setText(activity.getString(R.string.cfg_adb_no_helper));
                    return;
                }
                adbHint.postDelayed(() -> adbHint.setText(activity.getString(
                    AdbGate.isEnabled(activity)
                        ? R.string.cfg_adb_on : R.string.cfg_adb_off,
                    AdbGate.DEFAULT_MINUTES)), 900);
            }));
        adbHint.setText(activity.getString(AdbGate.isEnabled(activity)
            ? R.string.cfg_adb_on : R.string.cfg_adb_off, AdbGate.DEFAULT_MINUTES));
        left.addView(adbHint);

        fTrustedSsid = Style.field(activity, left, activity.getString(R.string.cfg_trusted_wifi_label),
            AdbGate.getTrustedWifi(activity), InputType.TYPE_CLASS_TEXT);

        final TextView wifiHint = new TextView(activity);
        wifiHint.setTextColor(Style.TEXT_DIM);
        wifiHint.setTextSize(12);
        wifiHint.setText(activity.getString(R.string.cfg_trusted_wifi_hint));
        wifiHint.setPadding(0, 0, 0, Style.dp(activity, 4));
        left.addView(wifiHint);

        LinearLayout wifiBtnRow = new LinearLayout(activity);
        wifiBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        wifiBtnRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_use_current_wifi), 0xFF3A5A7A, () -> {
            String current = AdbGate.getCurrentSsid(activity);
            if (!current.isEmpty()) {
                fTrustedSsid.setText(current);
                AdbGate.setTrustedWifi(activity, current);
                Toast.makeText(activity, activity.getString(R.string.cfg_trusted_wifi_saved), Toast.LENGTH_SHORT).show();
            }
        }));
        left.addView(wifiBtnRow);

        Style.gap(left, activity, 14);
        left.addView(Style.button(activity, activity.getString(R.string.cfg_btn_save_all), Style.ACCENT, () -> saveAll(true)));

        // ---- Right Column: Status, Actions & Console ----
        right.addView(Style.header(activity, activity.getString(R.string.cfg_active_status_title)));
        LinearLayout statusCard = new LinearLayout(activity);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(Style.card(Style.CARD, activity));
        int cardPad = Style.dp(activity, 12);
        statusCard.setPadding(cardPad, cardPad, cardPad, cardPad);

        activeBrokerView = new TextView(activity);
        activeBrokerView.setTextColor(Style.TEXT);
        activeBrokerView.setTextSize(14);
        statusCard.addView(activeBrokerView);

        activeClientView = new TextView(activity);
        activeClientView.setTextColor(Style.TEXT_DIM);
        activeClientView.setTextSize(13);
        activeClientView.setPadding(0, Style.dp(activity, 4), 0, 0);
        statusCard.addView(activeClientView);

        activeLastSentView = new TextView(activity);
        activeLastSentView.setTextColor(Style.TEXT_DIM);
        activeLastSentView.setTextSize(13);
        activeLastSentView.setPadding(0, Style.dp(activity, 4), 0, 0);
        statusCard.addView(activeLastSentView);
        right.addView(statusCard);
        updateStatusCard();

        right.addView(Style.header(activity, activity.getString(R.string.cfg_actions_header)));
        LinearLayout actRow = new LinearLayout(activity);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.addView(Style.action(activity, activity.getString(R.string.cfg_btn_test), 0xFF3A6B4A, this::testOnce));
        actRow.addView(Style.action(activity, activity.getString(R.string.cfg_btn_discovery), 0xFF4A6B82, this::forceDiscoveryNow));
        right.addView(actRow);

        right.addView(Style.header(activity, activity.getString(R.string.cfg_log_title)));
        mqttLogScroll = new ScrollView(activity);
        mqttLogScroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(activity, 280)));
        mqttLogScroll.setBackground(Style.card(0xFF14171A, activity));
        int lPad = Style.dp(activity, 10);
        mqttLogScroll.setPadding(lPad, lPad, lPad, lPad);

        mqttLogView = new TextView(activity);
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
        parent.addView(Style.header(activity, activity.getString(R.string.cfg_certs_header)));

        LinearLayout certCard = new LinearLayout(activity);
        certCard.setOrientation(LinearLayout.VERTICAL);
        certCard.setBackground(Style.card(Style.CARD, activity));
        int pad = Style.dp(activity, 14);
        certCard.setPadding(pad, pad, pad, pad);

        // Client Certificate
        TextView clientLbl = Style.label(activity, activity.getString(R.string.cfg_client_cert_label));
        certCard.addView(clientLbl);

        clientCertStatus = new TextView(activity);
        clientCertStatus.setTextSize(14);
        clientCertStatus.setPadding(0, Style.dp(activity, 4), 0, Style.dp(activity, 6));
        certCard.addView(clientCertStatus);

        clientBtnRow = new LinearLayout(activity);
        clientBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        certCard.addView(clientBtnRow);

        Style.gap(certCard, activity, 14);

        // CA Certificate
        TextView caLbl = Style.label(activity, activity.getString(R.string.cfg_ca_cert_label));
        certCard.addView(caLbl);

        caCertStatus = new TextView(activity);
        caCertStatus.setTextSize(14);
        caCertStatus.setPadding(0, Style.dp(activity, 4), 0, Style.dp(activity, 6));
        certCard.addView(caCertStatus);

        caBtnRow = new LinearLayout(activity);
        caBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        certCard.addView(caBtnRow);

        Style.gap(certCard, activity, 14);

        // TLS Handshake Test
        TextView tlsLbl = Style.label(activity, activity.getString(R.string.cfg_tls_test_target_label));
        tlsLbl.setTextSize(14);
        tlsLbl.setTextColor(Style.TEXT_DIM);
        tlsLbl.setPadding(0, Style.dp(activity, 6), 0, Style.dp(activity, 2));
        certCard.addView(tlsLbl);

        fTlsTarget = new EditText(activity);
        fTlsTarget.setText(getTlsTestTarget());
        fTlsTarget.setHint("ssl://homeassistant.example.com:8883");
        fTlsTarget.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fTlsTarget.setTextColor(Style.TEXT);
        fTlsTarget.setTextSize(15);
        fTlsTarget.setBackground(Style.card(Style.CARD_HI, activity));
        int padTls = Style.dp(activity, 10);
        fTlsTarget.setPadding(padTls, padTls, padTls, padTls);
        certCard.addView(fTlsTarget);

        LinearLayout testRow = new LinearLayout(activity);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_test_tls), 0xFF2E6B8A, this::testTlsHandshake));
        certCard.addView(testRow);

        tlsTestStatus = new TextView(activity);
        tlsTestStatus.setTextSize(13);
        tlsTestStatus.setPadding(0, Style.dp(activity, 8), 0, 0);
        tlsTestStatus.setVisibility(View.GONE);
        certCard.addView(tlsTestStatus);

        parent.addView(certCard);

        refreshCertUi();
    }

    /** Handles certificate file import from onActivityResult (file picker).
     * Called by TelemetryActivity.onActivityResult when REQ_CODE_PICK_CERT completes. */
    public void importCertFromFile(InputStream is) {
        try {
            byte[] bytes = CertImporter.readStreamBytes(is);
            tryImportBytes(bytes, null);
        } catch (Throwable t) {
            showErrorDialog("Erro ao carregar arquivo selecionado: " + t.getMessage());
        }
    }

    /** Refreshes certificate UI -- called from onResume after returning from file
     * picker or other cert-related activities. */
    public void refreshCertUi() {
        if (clientCertStatus == null || caCertStatus == null) return;

        // Client Certificate
        CertImporter.CertInfo clientInfo = CertImporter.getClientCertInfo(activity);
        clientBtnRow.removeAllViews();
        if (clientInfo != null) {
            clientCertStatus.setTextColor(clientInfo.isExpired ? 0xFFE57373 : Style.TEXT);
            clientCertStatus.setText(activity.getString(R.string.cfg_cert_status_installed,
                    clientInfo.getCommonName(), clientInfo.getFormattedExpiry()) +
                    (clientInfo.isExpired ? " [EXPIRED]" : ""));
            clientBtnRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(false)));
            clientBtnRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_remove_cert), 0xFF8A3A3A, this::confirmRemoveClientCert));
        } else {
            clientCertStatus.setTextColor(Style.TEXT_DIM);
            clientCertStatus.setText(activity.getString(R.string.cfg_cert_status_none));
            clientBtnRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(false)));
        }

        // CA Certificate
        CertImporter.CertInfo caInfo = CertImporter.getCaCertInfo(activity);
        boolean isCustom = CertImporter.hasCustomCa(activity);
        caBtnRow.removeAllViews();
        if (isCustom && caInfo != null) {
            caCertStatus.setTextColor(Style.TEXT);
            caCertStatus.setText(activity.getString(R.string.cfg_ca_status_custom,
                    caInfo.getCommonName(), caInfo.getFormattedExpiry()));
            caBtnRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(true)));
            caBtnRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_reset_default), 0xFF8A3A3A, this::confirmResetCustomCa));
        } else {
            caCertStatus.setTextColor(Style.TEXT_DIM);
            String name = (caInfo != null) ? " (" + caInfo.getCommonName() + ")" : "";
            caCertStatus.setText(activity.getString(R.string.cfg_ca_status_default) + name);
            caBtnRow.addView(Style.button(activity, activity.getString(R.string.cfg_btn_import_cert), Style.ACCENT, () -> showImportDialog(true)));
        }
    }

    private void showImportDialog(boolean forCa) {
        String[] options = new String[] {
            activity.getString(R.string.cfg_cert_import_usb),
            activity.getString(R.string.cfg_cert_import_url),
            activity.getString(R.string.cfg_cert_import_file),
            activity.getString(R.string.cfg_cert_import_paste)
        };
        new android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cfg_cert_import_title))
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
            activity.runOnUiThread(() -> {
                if (files.isEmpty()) {
                    new android.app.AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.cfg_cert_import_usb))
                        .setMessage(activity.getString(R.string.cfg_cert_usb_no_files))
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

                new android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.cfg_cert_import_usb))
                    .setItems(items, (dialog, which) -> {
                        try {
                            File f = files.get(which);
                            byte[] bytes = CertImporter.readStreamBytes(new java.io.FileInputStream(f));
                            promptPasswordIfNeeded(bytes);
                        } catch (Throwable t) {
                            showErrorDialog("Erro ao carregar arquivo: " + t.getMessage());
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        }).start();
    }

    private void promptUrlImport() {
        final EditText input = new EditText(activity);
        input.setHint("https://...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cfg_cert_import_url))
            .setView(input)
            .setPositiveButton("Baixar", (dialog, which) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) return;
                new Thread(() -> {
                    try {
                        InputStream is = new java.net.URL(url).openStream();
                        byte[] bytes = CertImporter.readStreamBytes(is);
                        activity.runOnUiThread(() -> promptPasswordIfNeeded(bytes));
                    } catch (Throwable t) {
                        activity.runOnUiThread(() -> showErrorDialog("Erro ao baixar: " + t.getMessage()));
                    }
                }).start();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void launchStoragePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(Intent.createChooser(intent, activity.getString(R.string.cfg_cert_import_file)), 4201);  // REQ_CODE_PICK_CERT
        } catch (Throwable t) {
            showErrorDialog("Erro ao abrir seletor de arquivos: " + t.getMessage());
        }
    }

    private void promptPasteImport() {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        new android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cfg_cert_import_paste))
            .setView(input)
            .setPositiveButton("Importar", (dialog, which) -> {
                String pem = input.getText().toString().trim();
                if (pem.isEmpty()) return;
                try {
                    byte[] bytes = pem.getBytes("UTF-8");
                    promptPasswordIfNeeded(bytes);
                } catch (Throwable t) {
                    showErrorDialog("Erro ao processar PEM: " + t.getMessage());
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void promptPasswordIfNeeded(byte[] bytes) {
        // Try without password first
        tryImportBytes(bytes, null);
    }

    private void tryImportBytes(final byte[] bytes, String password) {
        new Thread(() -> {
            try {
                CertImporter.ImportResult res = CertImporter.importData(activity, bytes, password);
                activity.runOnUiThread(() -> {
                    if (res.passwordRequired) {
                        promptPasswordDialog(bytes);
                        return;
                    }
                    if (res.success) {
                        refreshCertUi();
                        String details = res.certInfo != null ?
                            "\n\nNome: " + res.certInfo.getCommonName() +
                            "\nEmissor: " + CertImporter.extractCN(res.certInfo.issuer) +
                            "\nVálido até: " + res.certInfo.getFormattedExpiry() : "";
                        new android.app.AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.cfg_certs_header))
                            .setMessage(res.message + details)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton(activity.getString(R.string.cfg_btn_test_tls), (d, w) -> testTlsHandshake())
                            .show();
                    } else {
                        showErrorDialog(res.message);
                    }
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> showErrorDialog("Erro na importação: " + t.getMessage()));
            }
        }).start();
    }

    private void promptPasswordDialog(byte[] bytes) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cfg_cert_password_title))
            .setView(input)
            .setPositiveButton("Importar", (dialog, which) -> {
                String password = input.getText().toString();
                tryImportBytes(bytes, password);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmRemoveClientCert() {
        new android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cfg_cert_remove_title))
            .setMessage(activity.getString(R.string.cfg_cert_remove_confirm))
            .setPositiveButton("Remover", (dialog, which) -> {
                CertImporter.removeClientCert(activity);
                refreshCertUi();
                Toast.makeText(activity, activity.getString(R.string.cfg_cert_removed), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmResetCustomCa() {
        new android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cfg_ca_reset_title))
            .setMessage(activity.getString(R.string.cfg_ca_reset_confirm))
            .setPositiveButton("Restaurar Padrão", (dialog, which) -> {
                CertImporter.removeCustomCaCert(activity);
                refreshCertUi();
                Toast.makeText(activity, activity.getString(R.string.cfg_ca_reset_ok), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private String getTlsTestTarget() {
        return Prefs.getMqttTlsTestTarget(activity);
    }

    private void testTlsHandshake() {
        if (tlsTestStatus == null) return;
        String target = (fTlsTarget != null) ? fTlsTarget.getText().toString().trim() : "";
        if (target.isEmpty()) target = getTlsTestTarget();
        if (target.isEmpty()) return;

        Prefs.setMqttTlsTestTarget(activity, target);

        tlsTestStatus.setVisibility(View.VISIBLE);
        tlsTestStatus.setTextColor(Style.TEXT_DIM);
        tlsTestStatus.setText(activity.getString(R.string.cfg_cert_testing_tls) + " (" + target + ")...");

        logMqtt("TLS", "Testando handshake TLS para: " + target);
        CertImporter.testTls(activity, target, (ok, msg) -> {
            tlsTestStatus.setTextColor(ok ? 0xFF81C784 : 0xFFE57373);
            tlsTestStatus.setText(msg);
            logMqtt("TLS", (ok ? "SUCESSO: " : "ERRO: ") + msg);
        });
    }

    private void testOnce() {
        saveAll(Prefs.getTeleEnabled(activity));
        status.setText(activity.getString(R.string.cfg_testing));
        final String uri = (fUri != null) ? fUri.getText().toString().trim() : Prefs.getMqttUri(activity, "");
        final String u = (fUser != null) ? fUser.getText().toString().trim() : Prefs.getMqttUser(activity, "");
        final String pw = (fPass != null) ? fPass.getText().toString() : Prefs.getMqttPass(activity);
        String[] targets = uri.split("[,\\s]+");
        final String firstTarget = (targets.length > 0 && !targets[0].isEmpty()) ? targets[0] : uri;

        logMqtt("TEST", "Iniciando teste de conexão para: " + firstTarget);
        logMqtt("TEST", "Lendo dados dos sensores do veículo...");

        CarActor.get(activity).runOnCarThread(() -> {
            CarActor actor = CarActor.get(activity);
            CarAccess c = actor.rawAccess();
            boolean carOk = c.isReady() || c.connect(activity.getApplicationContext());
            java.util.LinkedHashMap<String, Object> data = carOk
                ? Telemetry.snapshot(c, CarActor.chargingFrom(actor.get("car.is_charging")))
                : new java.util.LinkedHashMap<>();
            logMqtt("TEST", "Sensores lidos: " + data.size() + " campos (CarAccess ok=" + carOk + ")");
            logMqtt("TEST", "Publicando em '" + MqttReporter.getBaseTopic() + "/state'...");

            final MqttReporter r = new MqttReporter(uri, "", u, pw, activity.getApplicationContext());
            r.testConnection(data, (ok, detail) -> {
                activity.runOnUiThread(() -> {
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
        saveAll(Prefs.getTeleEnabled(activity));
        status.setText(activity.getString(R.string.cfg_discovery_sending));
        logMqtt("DISCOVERY", "Solicitando envio de descoberta MQTT (47 entidades)...");
        TelemetryService svc = TelemetryService.getInstance();
        if (svc != null && svc.isRunning()) {
            svc.triggerDiscovery((ok, detail) -> activity.runOnUiThread(() -> {
                String msg = ok ? activity.getString(R.string.cfg_discovery_ok) : activity.getString(R.string.cfg_discovery_failed, detail);
                status.setText(msg);
                if (ok) {
                    logMqtt("DISCOVERY", "SUCESSO: " + detail);
                    logMqtt("DISCOVERY", "Entidades recriadas em 'homeassistant/.../" + MqttReporter.getDevId() + "/...'");
                } else {
                    logMqtt("DISCOVERY", "FALHA: " + detail);
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                updateStatusCard();
            }));
            return;
        }
        final String uri = (fUri != null) ? fUri.getText().toString().trim() : Prefs.getMqttUri(activity, "");
        final String u = (fUser != null) ? fUser.getText().toString().trim() : Prefs.getMqttUser(activity, "");
        final String pw = (fPass != null) ? fPass.getText().toString() : Prefs.getMqttPass(activity);
        logMqtt("DISCOVERY", "Conectando cliente direto para publicar descoberta...");
        CarActor.get(activity).runOnCarThread(() -> {
            final MqttReporter r = new MqttReporter(uri, "", u, pw, activity.getApplicationContext());
            r.forceDiscovery((ok, detail) -> {
                activity.runOnUiThread(() -> {
                    String msg = ok ? activity.getString(R.string.cfg_discovery_ok) : activity.getString(R.string.cfg_discovery_failed, detail);
                    status.setText(msg);
                    if (ok) {
                        logMqtt("DISCOVERY", "SUCESSO: " + detail);
                    } else {
                        logMqtt("DISCOVERY", "FALHA: " + detail);
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                    updateStatusCard();
                });
                r.close();
            });
        });
    }

    private void updateStatusCard() {
        if (activeBrokerView == null || activeClientView == null || activeLastSentView == null) return;
        TelemetryService svc = TelemetryService.getInstance();
        boolean svcRunning = svc != null && svc.isRunning();
        boolean teleOn = Prefs.getTeleEnabled(activity);

        if (!teleOn) {
            activeBrokerView.setText(activity.getString(R.string.cfg_active_broker, activity.getString(R.string.cfg_status_off)));
            activeBrokerView.setTextColor(Style.TEXT_DIM);
        } else if (svcRunning && svc.isConnected()) {
            String broker = svc.getConnectedBroker();
            activeBrokerView.setText(activity.getString(R.string.cfg_active_broker, (broker != null ? broker : "Online")));
            activeBrokerView.setTextColor(0xFF81C784);
        } else if (svcRunning) {
            activeBrokerView.setText(activity.getString(R.string.cfg_active_broker, activity.getString(R.string.cfg_connecting)));
            activeBrokerView.setTextColor(0xFFFFB74D);
        } else {
            activeBrokerView.setText(activity.getString(R.string.cfg_active_broker, activity.getString(R.string.cfg_status_off)));
            activeBrokerView.setTextColor(Style.TEXT_DIM);
        }

        activeClientView.setText(activity.getString(R.string.cfg_client_id, MqttReporter.getClientId()));
        int iv = Prefs.getTeleIntervalS(activity);
        activeLastSentView.setText(teleOn
            ? activity.getString(R.string.cfg_sending_every, iv)
            : activity.getString(R.string.cfg_status_off));
    }

    private void saveAll(boolean enable) {
        int iv = Prefs.getTeleIntervalS(activity);
        if (fInterval != null && fInterval.getText() != null) {
            try { iv = Integer.parseInt(fInterval.getText().toString().trim()); } catch (Exception ignored) {}
            iv = Math.max(5, iv);
        }

        Prefs.setTeleEnabled(activity, enable);
        Prefs.setTeleIntervalS(activity, iv);

        if (fUri != null && fUri.getText() != null) {
            Prefs.setMqttUri(activity, fUri.getText().toString().trim());
            Prefs.setMqttUriAlt(activity, "");
        }
        if (fUser != null && fUser.getText() != null) {
            Prefs.setMqttUser(activity, fUser.getText().toString().trim());
        }
        if (fPass != null && fPass.getText() != null) {
            Prefs.setMqttPass(activity, fPass.getText().toString());
        }
        if (fTlsTarget != null && fTlsTarget.getText() != null) {
            String val = fTlsTarget.getText().toString().trim();
            if (!val.isEmpty()) Prefs.setMqttTlsTestTarget(activity, val);
        }

        if (fTrustedSsid != null && fTrustedSsid.getText() != null) {
            String val = fTrustedSsid.getText().toString().trim();
            AdbGate.setTrustedWifi(activity, val);
        }

        Intent svc = new Intent(activity, TelemetryService.class);
        if (enable) {
            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(svc); else activity.startService(svc);
        } else {
            activity.stopService(svc);
        }

        if (status != null) {
            status.setText(enable
                ? activity.getString(R.string.cfg_sending_every, iv)
                : activity.getString(R.string.cfg_status_off));
        }

        updateStatusCard();
        Toast.makeText(activity, activity.getString(R.string.cfg_saved), Toast.LENGTH_SHORT).show();
        logMqtt("CONFIG", "Configurações salvas e aplicadas.");
    }

    private void logMqtt(String tag, String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        String line = "[" + time + "] [" + tag + "] " + msg + "\n";
        activity.runOnUiThread(() -> {
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

    private void showErrorDialog(String message) {
        new android.app.AlertDialog.Builder(activity)
            .setTitle("Erro")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
