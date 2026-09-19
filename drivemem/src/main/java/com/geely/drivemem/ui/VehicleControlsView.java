package com.geely.drivemem.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.util.Modes;
import com.geely.drivemem.util.Style;
import com.geely.drivemem.util.VehicleProfile;

import java.util.HashMap;
import java.util.Map;

/** Shared home/settings controls. Mode taps apply and persist immediately. */
public final class VehicleControlsView extends LinearLayout {
    private final Context context;
    private final SharedPreferences prefs;
    private final boolean compact;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, LinearLayout> driveCards = new HashMap<>();
    private final Map<Integer, LinearLayout> regenCards = new HashMap<>();
    private int selDrive, selRegen, liveDrive, liveRegen;
    private boolean driveKnown, regenKnown, subscribed;
    private TextView status;
    private TextView vehicleName;
    private TextView vehicleNickname;
    private LinearLayout vehicleIdentity;
    private android.app.AlertDialog nameDialog;
    private final SharedPreferences.OnSharedPreferenceChangeListener profileListener = (preferences, key) -> {
        if (key == null || VehicleProfile.NAME_KEY.equals(key)
                || VehicleProfile.MODEL_KEY.equals(key)) updateVehicleName();
        if (key == null || "drive".equals(key) || "regen".equals(key)) {
            loadSelection();
            highlight();
        }
    };

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

    public VehicleControlsView(Context context) {
        this(context, false);
    }

    private VehicleControlsView(Context context, boolean compact) {
        super(context);
        this.context = context;
        this.compact = compact;
        prefs = context.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        loadSelection();
        setOrientation(VERTICAL);
        int pageWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(Style.dp(context, 24), Style.dp(context, compact ? 12 : 20),
            Style.dp(context, 24), Style.dp(context, compact ? 12 : 20));
        controls.setBackground(Style.card(Style.CARD, context, 24));
        addView(controls);

        LinearLayout driving = new LinearLayout(context);
        driving.setOrientation(LinearLayout.VERTICAL);
        driving.addView(sectionHeader(R.string.cfg_drive_header));
        driving.addView(modeCard(getString(R.string.cfg_mode_eco), R.drawable.ic_dashboard_leaf,
            Modes.DRIVE_ECO, driveCards, () -> setDrive(Modes.DRIVE_ECO)));
        driving.addView(modeCard(getString(R.string.cfg_mode_comfort), R.drawable.ic_tesla_steering,
            Modes.DRIVE_COMFORT, driveCards, () -> setDrive(Modes.DRIVE_COMFORT)));
        driving.addView(modeCard(getString(R.string.cfg_mode_sport), R.drawable.ic_tesla_power,
            Modes.DRIVE_SPORT, driveCards, () -> setDrive(Modes.DRIVE_SPORT)));
        controls.addView(driving, new LinearLayout.LayoutParams(Style.dp(context, 245), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout vehicle = new LinearLayout(context);
        vehicle.setOrientation(LinearLayout.VERTICAL);
        vehicle.setGravity(Gravity.CENTER);
        vehicleIdentity = new LinearLayout(context);
        vehicleIdentity.setOrientation(VERTICAL);
        vehicleIdentity.setGravity(Gravity.CENTER);
        vehicleIdentity.setFocusable(true);
        vehicleIdentity.setOnClickListener(v -> {
            if (nameDialog == null || !nameDialog.isShowing()) nameDialog = VehicleNameDialog.show(context, prefs);
        });
        vehicleName = Style.title(context, VehicleProfile.modelName(prefs));
        vehicleName.setGravity(Gravity.CENTER);
        vehicleName.setSingleLine(true);
        vehicleName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vehicleName.setMaxWidth(Style.dp(context, 720));
        vehicleName.setMinHeight(Style.dp(context, 48));
        vehicleName.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.graphics.drawable.Drawable edit = context.getDrawable(R.drawable.ic_vehicle_edit).mutate();
        edit.setTint(Style.TEXT_DIM);
        edit.setBounds(0, 0, Style.dp(context, 24), Style.dp(context, 24));
        vehicleName.setCompoundDrawablesRelative(null, null, edit, null);
        vehicleName.setCompoundDrawablePadding(Style.dp(context, 12));
        vehicleIdentity.addView(vehicleName, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vehicleNickname = new TextView(context);
        vehicleNickname.setTextSize(22);
        vehicleNickname.setTextColor(Style.TEXT_DIM);
        vehicleNickname.setGravity(Gravity.CENTER);
        vehicleNickname.setSingleLine(true);
        vehicleNickname.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vehicleNickname.setMinHeight(Style.dp(context, 32));
        vehicleNickname.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        vehicleIdentity.addView(vehicleNickname, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateVehicleName();
        vehicle.addView(vehicleIdentity, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        com.geely.drivemem.art.VehicleArtView vehicleImage =
            new com.geely.drivemem.art.VehicleArtView(context, true);
        vehicle.addView(vehicleImage,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(context, compact ? 144 : 248)));
        TextView colorCaption = new TextView(context);
        colorCaption.setTextSize(20);
        colorCaption.setTextColor(Style.TEXT_DIM);
        colorCaption.setGravity(Gravity.CENTER);
        colorCaption.setText(vehicleImage.colorLabel());
        vehicleImage.enableColorSelection(() -> colorCaption.setText(vehicleImage.colorLabel()));
        vehicle.addView(colorCaption);
        status = new TextView(context);
        status.setTextColor(Style.TEXT_DIM); status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setText(getString(R.string.cfg_connecting));
        vehicle.addView(status);
        LinearLayout.LayoutParams vehicleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vehicleLp.setMargins(Style.dp(context, 24), 0, Style.dp(context, 24), 0);
        controls.addView(vehicle, vehicleLp);

        LinearLayout regeneration = new LinearLayout(context);
        regeneration.setOrientation(LinearLayout.VERTICAL);
        regeneration.addView(sectionHeader(R.string.cfg_regen_header));
        regeneration.addView(modeCard(getString(R.string.cfg_regen_low), R.drawable.ic_tesla_power,
            Modes.REGEN_LOW, regenCards, () -> setRegen(Modes.REGEN_LOW)));
        regeneration.addView(modeCard(getString(R.string.cfg_regen_mid), R.drawable.ic_tesla_power,
            Modes.REGEN_MID, regenCards, () -> setRegen(Modes.REGEN_MID)));
        regeneration.addView(modeCard(getString(R.string.cfg_regen_high), R.drawable.ic_tesla_power,
            Modes.REGEN_HIGH, regenCards, () -> setRegen(Modes.REGEN_HIGH)));
        controls.addView(regeneration, new LinearLayout.LayoutParams(Style.dp(context, 245), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView modeNote = Style.label(context, getString(R.string.ui_mode_selection_hint));
        modeNote.setTextSize(18); modeNote.setTextColor(Style.TEXT_DIM);
        modeNote.setPadding(0, Style.dp(context, compact ? 4 : 16), 0, Style.dp(context, 4));
        addView(modeNote);
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setLayoutParams(new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        actionRow.addView(action(getString(R.string.ui_restore_drive_defaults), Style.CARD_HI, this::restoreDefaults));
        addView(actionRow);

        highlight();
    }

    private String getString(int id, Object... args) { return context.getString(id, args); }

    private TextView sectionHeader(int label) {
        TextView title = Style.header(context, getString(label));
        if (compact) title.setPadding(0, 0, 0, Style.dp(context, 8));
        return title;
    }

    private TextView action(String label, int color, Runnable onClick) {
        TextView button = Style.cardButton(context, label, false, onClick);
        button.setBackground(Style.card(color, context));
        button.setTextColor(Style.onFill(color));
        button.setTextSize(22);
        button.setMinHeight(Style.dp(context, compact ? 56 : 72));
        if (compact) button.setPadding(Style.dp(context, 16), Style.dp(context, 8),
            Style.dp(context, 16), Style.dp(context, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, Style.dp(context, 8), 0, 0);
        button.setLayoutParams(lp);
        return button;
    }

    public void start() {
        updateVehicleName();
        loadSelection();
        highlight();
        if (!subscribed) {
            subscribed = true;
            prefs.registerOnSharedPreferenceChangeListener(profileListener);
            EntityBus.subscribe("car.drive_mode", driveListener);
            EntityBus.subscribe("car.regen_mode", regenListener);
        }
        refresh();
    }

    public void stop() {
        if (nameDialog != null) {
            nameDialog.dismiss();
            nameDialog = null;
        }
        if (subscribed) {
            prefs.unregisterOnSharedPreferenceChangeListener(profileListener);
            EntityBus.unsubscribe("car.drive_mode", driveListener);
            EntityBus.unsubscribe("car.regen_mode", regenListener);
            subscribed = false;
        }
        ui.removeCallbacksAndMessages(null);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); start(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    private void updateVehicleName() {
        String nickname = VehicleProfile.customName(prefs);
        vehicleName.setText(VehicleProfile.modelName(prefs));
        vehicleNickname.setText(nickname.isEmpty() ? getString(R.string.ui_vehicle_name_add) : nickname);
        vehicleIdentity.setContentDescription(getString(R.string.ui_vehicle_name_edit,
            VehicleProfile.displayName(prefs)));
    }

    private LinearLayout modeCard(String label, int iconRes, int key,
                                  Map<Integer, LinearLayout> reg, Runnable onClick) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Style.dp(context, compact ? 12 : 18);
        card.setPadding(pad, pad, pad, pad);
        android.widget.ImageView icon = new android.widget.ImageView(context);
        icon.setImageResource(iconRes);
        card.addView(icon, new LinearLayout.LayoutParams(Style.dp(context, 32), Style.dp(context, 32)));
        TextView lb = new TextView(context);
        lb.setText(label); lb.setTextColor(Style.TEXT); lb.setTextSize(23);
        lb.setTypeface(lb.getTypeface(), android.graphics.Typeface.BOLD);
        lb.setPadding(Style.dp(context, 16), 0, 0, 0);
        card.addView(lb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setContentDescription(label);
        card.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Style.dp(context, compact ? 60 : 88));
        lp.bottomMargin = Style.dp(context, compact ? 8 : 14);
        card.setLayoutParams(lp);
        reg.put(key, card);
        return card;
    }

    private void loadSelection() {
        selDrive = prefs.getInt("drive", Modes.DEFAULT_DRIVE);
        selRegen = prefs.getInt("regen", Modes.DEFAULT_REGEN);
    }

    private void setDrive(int v) {
        selDrive = v;
        persistSelection(true, false);
        TurboMode.get(context).selectDriveMode(v);
    }

    private void setRegen(int v) {
        selRegen = v;
        persistSelection(false, true);
        CarActor.get(context).cast("regen_mode", v);
    }

    private void restoreDefaults() {
        selDrive = Modes.DEFAULT_DRIVE;
        selRegen = Modes.DEFAULT_REGEN;
        persistSelection(true, true);
        TurboMode.get(context).selectDriveMode(selDrive);
        CarActor.get(context).cast("regen_mode", selRegen);
        String message = getString(R.string.ui_drive_defaults_restored,
            Modes.driveName(context, Modes.DEFAULT_DRIVE),
            Modes.regenName(context, Modes.DEFAULT_REGEN));
        // Confirm the saved defaults; live vehicle state still comes from its readings.
        showRestoreFeedback(message);
    }

    // The platform's small toast font is difficult to read on a head unit.
    // Keep the native, automatically dismissing popup with our locale font and palette.
    @SuppressWarnings("deprecation")
    private void showRestoreFeedback(String message) {
        int success = Style.LIGHT ? 0xFF187C56 : Style.GOOD;
        android.text.SpannableString caption = new android.text.SpannableString(message);
        int titleEnd = message.indexOf('\n');
        if (titleEnd < 0) titleEnd = message.length();
        caption.setSpan(new android.text.style.ForegroundColorSpan(success), 0, titleEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, titleEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView text = Style.label(context, "");
        text.setText(caption);
        text.setTextSize(22);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setMaxWidth(Style.dp(context, 720));
        text.setPadding(Style.dp(context, 28), Style.dp(context, 18),
            Style.dp(context, 28), Style.dp(context, 18));
        android.graphics.drawable.Drawable check = context.getDrawable(R.drawable.ic_language_check).mutate();
        check.setTint(success);
        check.setBounds(0, 0, Style.dp(context, 32), Style.dp(context, 32));
        text.setCompoundDrawablesRelative(check, null, null, null);
        text.setCompoundDrawablePadding(Style.dp(context, 16));
        text.setBackground(Style.accentCard(success, context));
        Toast popup = new Toast(context);
        popup.setView(text);
        popup.setDuration(Toast.LENGTH_LONG);
        popup.show();
    }

    // Fill = your saved choice (selDrive/selRegen, always known). Border = what
    // the car reports right now (liveDrive/liveRegen, only once driveKnown/
    // regenKnown). A card can carry both, one, or neither — that overlap (or
    // lack of it) is the whole point: it is what makes "car booted into
    // something other than your standard" visible instead of silent.
    private void highlight() {
        for (Map.Entry<Integer, LinearLayout> e : driveCards.entrySet()) {
            boolean eco = e.getKey() == Modes.DRIVE_ECO;
            boolean comfort = e.getKey() == Modes.DRIVE_COMFORT;
            int fill = Style.LIGHT
                ? eco ? 0xFF187C56 : comfort ? 0xFFF3C64D : 0xFFC63C3C
                : eco ? 0xFF2F604B : comfort ? 0xFFB79856 : 0xFF854B4D;
            int accent = Style.LIGHT
                ? comfort ? 0xFF966000 : fill
                : eco ? 0xFF91B8A1 : comfort ? 0xFFC4AB79 : 0xFFCE9694;
            paintCard(e.getValue(), e.getKey() == selDrive, driveKnown && e.getKey() == liveDrive,
                fill, accent);
        }
        for (Map.Entry<Integer, LinearLayout> e : regenCards.entrySet())
            paintCard(e.getValue(), e.getKey() == selRegen, regenKnown && e.getKey() == liveRegen, 0, 0);
    }

    // Each driving mode keeps its colour. Selection fills the pill; the live
    // outline remains independent: a sent command is not a confirmed reading.
    private void paintCard(LinearLayout card, boolean selected, boolean isLive, int modeColor, int modeAccent) {
        int fill = modeColor == 0 ? (selected ? Style.CARD_ON : Style.CARD_HI)
            : selected ? modeColor : Style.blend(Style.CARD_HI, modeColor, Style.LIGHT ? 0.12f : 0.10f);
        android.graphics.drawable.GradientDrawable g = Style.card(fill, context, 44);
        if (isLive) g.setStroke(Style.dp(context, Style.STROKE_DP + 2),
            modeColor == 0 ? Style.ACCENT : selected ? Style.TEXT : Style.LIGHT ? modeColor : modeAccent);
        card.setBackground(g);
        card.setSelected(selected);
        int fg = Style.onFill(fill);
        for (int i = 0; i < card.getChildCount(); i++) {
            View ch = card.getChildAt(i);
            if (ch instanceof TextView) ((TextView) ch).setTextColor(fg);
            if (ch instanceof android.widget.ImageView) ((android.widget.ImageView) ch).setColorFilter(
                !selected && modeColor != 0 ? modeAccent : fg);
        }
    }

    private void persistSelection(boolean drive, boolean regen) {
        SharedPreferences.Editor editor = prefs.edit();
        if (drive) editor.putInt("drive", selDrive);
        if (regen) editor.putInt("regen", selRegen);
        editor.apply();
        // Keep the helper's wake-up preferences in sync. A single-property tap
        // leaves the other preference untouched in both processes.
        try {
            android.content.Intent i = new android.content.Intent("com.geely.modehelper.SET_MODE");
            i.setPackage("com.geely.modehelper");
            if (drive) i.putExtra("drive", selDrive);
            if (regen) i.putExtra("regen", selRegen);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {}
        highlight();
    }

    private void refresh() {
        CarActor a = CarActor.get(context);
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
        status.setText(getString(R.string.cfg_current, Modes.driveName(context, liveDrive), Modes.regenName(context, liveRegen)));
    }

}
