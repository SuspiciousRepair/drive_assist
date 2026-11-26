package com.geely.drivemem.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.controls.AdbGate;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.SecurityLock;
import com.geely.drivemem.util.Style;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Security section of Config: PIN lock for Valet Mode & Settings, plus
 * Trusted Phone (paired Bluetooth device / owner's iPhone on the cable / Wi-Fi router) auto-unlock. */
public final class TelemetrySecuritySection extends LinearLayout {
    private final Activity activity;

    public TelemetrySecuritySection(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        addView(Style.header(activity, activity.getString(R.string.cfg_security_header)));

        LinearLayout left = ConfigCards.column(activity);
        LinearLayout right = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, left, right));

        ConfigCards.add(left, buildPinCard());
        ConfigCards.add(right, buildBluetoothCard());
    }

    private LinearLayout buildPinCard() {
        LinearLayout card = ConfigCards.card(activity, activity.getString(R.string.sec_pin_card_title));
        boolean hasPin = SecurityLock.hasPin(activity);

        TextView st = new TextView(activity);
        st.setText(hasPin ? R.string.sec_pin_status_set : R.string.sec_pin_status_none);
        st.setTextColor(hasPin ? Style.GOOD : Style.TEXT_DIM);
        st.setTextSize(18f);
        st.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(st);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(activity, 14);

        if (!hasPin) {
            btnRow.addView(Style.cardButton(activity, activity.getString(R.string.sec_pin_set_btn), true,
                () -> PinDialog.showSetPin(activity, this::rebuild)));
        } else {
            // Layer 1 recovery: when the trusted phone is connected, change/remove without old PIN.
            boolean trustedPresent = SecurityLock.isTrustedPhonePresent(activity);
            btnRow.addView(Style.cardButton(activity, activity.getString(R.string.sec_pin_change_btn), true, () -> {
                if (trustedPresent) {
                    PinDialog.showSetPin(activity, this::rebuild);
                } else {
                    PinDialog.requireUnlock(activity, R.string.sec_pin_verify_current,
                        () -> PinDialog.showSetPin(activity, this::rebuild));
                }
            }));
            TextView rm = Style.cardButton(activity, activity.getString(R.string.sec_pin_remove_btn), false, () -> {
                if (trustedPresent) {
                    SecurityLock.clearPin(activity);
                    Toast.makeText(activity, R.string.sec_pin_removed, Toast.LENGTH_SHORT).show();
                    rebuild();
                } else {
                    PinDialog.showRemovePin(activity, this::rebuild);
                }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.leftMargin = Style.dp(activity, 12);
            btnRow.addView(rm, rlp);
        }
        card.addView(btnRow, blp);

        if (hasPin) {
            View lockValet = Style.toggleRow(activity, activity.getString(R.string.sec_lock_valet_label),
                Prefs.getSecurityLockValet(activity),
                on -> Prefs.setSecurityLockValet(activity, on));
            LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t1.topMargin = Style.dp(activity, 12);
            card.addView(lockValet, t1);

            card.addView(Style.toggleRow(activity, activity.getString(R.string.sec_lock_settings_label),
                Prefs.getSecurityLockSettings(activity),
                on -> Prefs.setSecurityLockSettings(activity, on)));
        }

        // Layer 2 recovery: explicit Safe Reset Location
        View div = new View(activity);
        div.setBackgroundColor(Style.CARD_HI);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(activity, 1));
        dlp.topMargin = Style.dp(activity, 16);
        dlp.bottomMargin = Style.dp(activity, 12);
        card.addView(div, dlp);

        boolean hasSafe = Prefs.hasSecurityHome(activity);
        TextView safeSt = new TextView(activity);
        safeSt.setText(hasSafe ? R.string.sec_safe_loc_set : R.string.sec_safe_loc_none);
        safeSt.setTextColor(hasSafe ? Style.GOOD : Style.TEXT_DIM);
        safeSt.setTextSize(16f);
        safeSt.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(safeSt);

        LinearLayout safeBtns = new LinearLayout(activity);
        safeBtns.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = Style.dp(activity, 10);
        safeBtns.addView(Style.cardButton(activity, activity.getString(R.string.sec_safe_loc_set_btn), false, () -> {
            if (SecurityLock.setSafeLocationFromCurrentFix(activity)) {
                Toast.makeText(activity, R.string.sec_safe_loc_saved, Toast.LENGTH_SHORT).show();
                rebuild();
            } else {
                Toast.makeText(activity, R.string.sec_safe_loc_no_gps, Toast.LENGTH_SHORT).show();
            }
        }));
        if (hasSafe) {
            TextView clearLoc = Style.cardButton(activity, activity.getString(R.string.sec_bt_clear_btn), false, () -> {
                SecurityLock.clearSafeLocation(activity);
                rebuild();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Style.dp(activity, 12);
            safeBtns.addView(clearLoc, clp);
        }
        card.addView(safeBtns, sblp);

        card.addView(ConfigCards.hint(activity, activity.getString(R.string.sec_recovery_hint)));
        return card;
    }

    private LinearLayout buildBluetoothCard() {
        LinearLayout card = ConfigCards.card(activity, activity.getString(R.string.sec_bt_card_title));
        String btMac = Prefs.getSecurityBtMac(activity);
        String btName = Prefs.getSecurityBtName(activity);
        String beaconUuid = Prefs.getSecurityBeaconUuid(activity);
        String beaconName = Prefs.getSecurityBeaconName(activity);
        boolean hasBt = btMac != null && !btMac.isEmpty();
        boolean hasBeacon = beaconUuid != null && !beaconUuid.isEmpty();
        String unlockSource = SecurityLock.activeUnlockSource(activity);
        boolean present = !unlockSource.isEmpty();

        TextView liveSt = new TextView(activity);
        liveSt.setText(present
            ? activity.getString(R.string.sec_unlocked_now, unlockSource)
            : activity.getString(R.string.sec_locked_now));
        liveSt.setTextColor(present ? Style.GOOD : Style.TEXT_DIM);
        liveSt.setTextSize(18f);
        liveSt.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(liveSt);

        TextView devSt = new TextView(activity);
        if (hasBeacon && hasBt) {
            devSt.setText(btName + "  ·  " + beaconName);
            devSt.setTextColor(Style.TEXT);
        } else if (hasBeacon) {
            devSt.setText(beaconName);
            devSt.setTextColor(Style.TEXT);
        } else if (hasBt) {
            devSt.setText(btName);
            devSt.setTextColor(Style.TEXT);
        } else {
            devSt.setText(R.string.sec_bt_none);
            devSt.setTextColor(Style.TEXT_DIM);
        }
        devSt.setTextSize(15f);
        devSt.setPadding(0, Style.dp(activity, 4), 0, 0);
        card.addView(devSt);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Style.dp(activity, 12);

        btnRow.addView(Style.cardButton(activity, activity.getString(R.string.sec_bt_choose_btn), !hasBt && !hasBeacon,
            this::openDevicePicker));
        if (hasBt || hasBeacon) {
            TextView clear = Style.cardButton(activity, activity.getString(R.string.sec_bt_clear_btn), false, () -> {
                Prefs.setSecurityBtDevice(activity, "", "");
                Prefs.setSecurityBeacon(activity, "", "");
                SecurityLock.refreshBleMonitor(activity);
                rebuild();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Style.dp(activity, 12);
            btnRow.addView(clear, clp);
        }
        card.addView(btnRow, blp);

        View cpRow = Style.toggleRow(activity, activity.getString(R.string.sec_trust_carplay_label),
            Prefs.getSecurityTrustCarplay(activity) && !Prefs.getSecurityCarplaySerial(activity).isEmpty(),
            on -> {
                if (on) {
                    // Remember THIS iPhone: any other Apple device on the cable must not unlock.
                    java.util.Set<String> serials = com.geely.drivemem.state.CarplayState.cableIphones(activity);
                    if (serials.size() != 1) {
                        Toast.makeText(activity, R.string.sec_carplay_plug_first, Toast.LENGTH_LONG).show();
                        rebuild();
                        return;
                    }
                    Prefs.setSecurityCarplaySerial(activity, serials.iterator().next());
                } else {
                    Prefs.setSecurityCarplaySerial(activity, "");
                }
                Prefs.setSecurityTrustCarplay(activity, on);
                rebuild();
            });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Style.dp(activity, 12);
        card.addView(cpRow, tlp);

        card.addView(Style.toggleRow(activity, activity.getString(R.string.sec_trust_wifi_label),
            Prefs.getSecurityTrustWifi(activity),
            on -> {
                Prefs.setSecurityTrustWifi(activity, on);
                rebuild();
            }));

        List<String> ssids = SecurityLock.trustedWifiSsids(activity);
        TextView wifiSt = new TextView(activity);
        wifiSt.setText(ssids.isEmpty()
            ? activity.getString(R.string.sec_wifi_none)
            : activity.getString(R.string.sec_wifi_list, String.join(", ", ssids)));
        wifiSt.setTextColor(ssids.isEmpty() ? Style.TEXT_DIM : Style.TEXT);
        wifiSt.setTextSize(15f);
        wifiSt.setPadding(0, Style.dp(activity, 4), 0, 0);
        card.addView(wifiSt);

        LinearLayout wifiRow = new LinearLayout(activity);
        wifiRow.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Style.dp(activity, 8);
        wifiRow.addView(Style.cardButton(activity, activity.getString(R.string.sec_wifi_trust_current_btn), false, () -> {
            if (AdbGate.getCurrentSsid(activity).isEmpty()) {
                Toast.makeText(activity, R.string.sec_wifi_not_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            String added = SecurityLock.addTrustedWifi(activity);
            if (added.isEmpty()) {
                Toast.makeText(activity, R.string.sec_wifi_no_bssid, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(activity, activity.getString(R.string.sec_wifi_added, added), Toast.LENGTH_SHORT).show();
            rebuild();
        }));
        if (!Prefs.getSecurityWifiSsidsRaw(activity).isEmpty()) {
            TextView clearWifi = Style.cardButton(activity, activity.getString(R.string.sec_bt_clear_btn), false, () -> {
                SecurityLock.clearExtraTrustedWifis(activity);
                rebuild();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Style.dp(activity, 12);
            wifiRow.addView(clearWifi, clp);
        }
        card.addView(wifiRow, wlp);

        card.addView(Style.toggleRow(activity, activity.getString(R.string.sec_auto_end_valet_label),
            Prefs.getSecurityAutoEndValet(activity),
            on -> Prefs.setSecurityAutoEndValet(activity, on)));

        card.addView(ConfigCards.hint(activity, activity.getString(R.string.sec_bt_hint)));
        return card;
    }

    private void openDevicePicker() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(VERTICAL);
        box.setBackground(Style.card(Style.CARD, activity));
        int pad = Style.dp(activity, 24);
        box.setPadding(pad, pad, pad, pad);
        box.setLayoutParams(new ViewGroup.LayoutParams(
            Style.dp(activity, 560), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(activity);
        title.setText(R.string.sec_bt_picker_title);
        title.setTextColor(Style.TEXT);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView scanHint = new TextView(activity);
        scanHint.setText(R.string.sec_bt_picker_scan_hint);
        scanHint.setTextColor(Style.TEXT_DIM);
        scanHint.setTextSize(14f);
        scanHint.setPadding(0, Style.dp(activity, 6), 0, 0);
        box.addView(scanHint);

        ScrollView sc = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(VERTICAL);
        sc.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(activity, 340));
        slp.topMargin = Style.dp(activity, 12);
        box.addView(sc, slp);

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(box).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        final String curMac = Prefs.getSecurityBtMac(activity);
        final String curBeacon = Prefs.getSecurityBeaconUuid(activity);
        final Map<String, SecurityLock.BtDevice> seen = new LinkedHashMap<>();

        Runnable renderList = () -> {
            list.removeAllViews();
            if (seen.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText(R.string.sec_bt_scanning_empty);
                empty.setTextColor(Style.TEXT_DIM);
                empty.setTextSize(16f);
                empty.setPadding(0, Style.dp(activity, 12), 0, 0);
                list.addView(empty);
                return;
            }
            for (SecurityLock.BtDevice d : seen.values()) {
                boolean selected = d.isBeacon()
                    ? d.beaconUuid.equalsIgnoreCase(curBeacon)
                    : d.mac.equalsIgnoreCase(curMac);
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Style.card(selected ? Style.CARD_ON : Style.CARD_HI, activity));
                int rp = Style.dp(activity, 14);
                row.setPadding(rp, rp, rp, rp);

                LinearLayout txt = new LinearLayout(activity);
                txt.setOrientation(VERTICAL);
                TextView name = new TextView(activity);
                name.setText(d.name);
                name.setTextColor(selected ? Style.onFill(Style.CARD_ON) : Style.TEXT);
                name.setTextSize(19f);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                txt.addView(name);

                String subText;
                if (d.isBeacon()) {
                    subText = activity.getString(R.string.sec_bt_beacon_sub, d.rssi);
                } else if (d.paired && d.connected) {
                    subText = activity.getString(R.string.sec_bt_connected);
                } else if (d.paired) {
                    subText = d.mac;
                } else {
                    subText = activity.getString(R.string.sec_bt_nearby_sub, d.rssi, d.mac);
                }
                TextView sub = new TextView(activity);
                sub.setText(subText);
                sub.setTextColor((d.connected || d.isBeacon()) ? Style.GOOD
                    : (selected ? Style.onFill(Style.CARD_ON) : Style.TEXT_DIM));
                sub.setTextSize(14f);
                txt.addView(sub);

                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setOnClickListener(v -> {
                    if (d.isBeacon()) {
                        Prefs.setSecurityBeacon(activity, d.beaconUuid, d.name);
                    } else {
                        Prefs.setSecurityBtDevice(activity, d.mac, d.name);
                    }
                    SecurityLock.refreshBleMonitor(activity);
                    dialog.dismiss();
                    rebuild();
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Style.dp(activity, 8);
                list.addView(row, rlp);
            }
        };

        for (SecurityLock.BtDevice d : SecurityLock.listPairedDevices()) {
            seen.put("bt:" + d.mac, d);
        }
        renderList.run();

        SecurityLock.startNearbyScan(dev -> activity.runOnUiThread(() -> {
            String key = dev.isBeacon() ? ("beacon:" + dev.beaconUuid) : ("bt:" + dev.mac);
            if (!seen.containsKey(key) || dev.isBeacon()) {
                seen.put(key, dev);
                renderList.run();
            }
        }));

        dialog.setOnDismissListener(d -> SecurityLock.stopNearbyScan());

        LinearLayout foot = new LinearLayout(activity);
        foot.setOrientation(HORIZONTAL);
        foot.setGravity(Gravity.END);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Style.dp(activity, 10);
        foot.addView(Style.cardButton(activity, activity.getString(android.R.string.cancel), false, dialog::dismiss));
        box.addView(foot, flp);

        dialog.show();
    }
}
