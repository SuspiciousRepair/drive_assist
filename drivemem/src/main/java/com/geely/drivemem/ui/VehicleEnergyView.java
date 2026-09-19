package com.geely.drivemem.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.sensors.EnergyIntegrator;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.util.Style;

import java.util.Collections;
import java.util.Map;

/** Home energy overview, refreshed from the existing telemetry and OBD2 streams. */
public final class VehicleEnergyView extends LinearLayout {
    private static final long STALE_MS = 45000;
    private static final String[] KEYS = {"telemetry.tick", "car.plug_connected", "car.is_charging"};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CarActor actor;
    private final TextView battery, chargeState, range, power, temperature, recovered;
    private final ProgressBar batteryBar;
    private boolean started;
    private long lastTelemetryAt;
    private Map<?, ?> telemetry = Collections.emptyMap();

    private final EntityBus.Listener listener = (key, reading) -> ui.post(() -> {
        if (!started) return;
        if ("telemetry.tick".equals(key)) acceptTelemetry(reading);
        refresh();
    });
    private final Obd2Reader.Listener obdListener = new Obd2Reader.Listener() {
        @Override public void onObd2ConnectedChanged(boolean connected) { requestRefresh(); }
        @Override public void onObd2Reading(Obd2Reader.Reading reading) { requestRefresh(); }
    };
    // Expire missing readings even when the source stops sending events.
    private final Runnable freshnessCheck = new Runnable() {
        @Override public void run() {
            if (!started) return;
            refresh();
            ui.postDelayed(this, 5000);
        }
    };

    public VehicleEnergyView(Context context) {
        super(context);
        actor = CarActor.get(context);
        setOrientation(VERTICAL);
        setPadding(dp(24), dp(20), dp(24), dp(20));
        setBackground(Style.card(Style.CARD, context, 24));

        TextView title = text(context.getString(R.string.ui_home_energy), 32, Style.TEXT);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout headline = new LinearLayout(context);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = icon(R.drawable.ic_dashboard_battery, Style.GOOD);
        headline.addView(icon, new LayoutParams(dp(40), dp(40)));
        battery = text("—", 58, Style.TEXT);
        battery.setPadding(dp(14), 0, 0, 0);
        headline.addView(battery, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams headlineLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        headlineLp.topMargin = dp(4);
        addView(headline, headlineLp);
        batteryBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        batteryBar.setMax(100);
        batteryBar.setProgressTintList(ColorStateList.valueOf(Style.GOOD));
        batteryBar.setProgressBackgroundTintList(ColorStateList.valueOf(Style.CARD_HI));
        batteryBar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams barLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(12));
        barLp.topMargin = dp(4); barLp.bottomMargin = dp(10);
        addView(batteryBar, barLp);
        chargeState = text(context.getString(R.string.ui_energy_waiting), 20, Style.TEXT_DIM);
        addView(chargeState, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout metrics = new LinearLayout(context);
        metrics.setOrientation(VERTICAL);
        LayoutParams gridLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        gridLp.topMargin = dp(20);
        addView(metrics, gridLp);
        LinearLayout top = new LinearLayout(context);
        LinearLayout bottom = new LinearLayout(context);
        metrics.addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams bottomLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = dp(12);
        metrics.addView(bottom, bottomLp);
        range = metric(top, R.drawable.ic_dashboard_road, R.string.ui_energy_range, false);
        power = metric(top, R.drawable.ic_tesla_power, R.string.ui_energy_charge_power, true);
        temperature = metric(bottom, R.drawable.ic_dashboard_battery, R.string.ui_battery_temp, false);
        recovered = metric(bottom, R.drawable.ic_dashboard_leaf, R.string.ui_energy_recovered, true);
    }

    private int dp(int value) { return Style.dp(getContext(), value); }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(getContext());
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setTypeface(Style.font(getContext()));
        return text;
    }

    private ImageView icon(int resource, int tint) {
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(resource); icon.setColorFilter(tint);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private TextView metric(LinearLayout row, int icon, int label, boolean withGap) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(Style.card(Style.CARD_HI, getContext(), 16));
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        if (withGap) lp.leftMargin = dp(12);
        row.addView(card, lp);
        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(icon(icon, Style.ACCENT), new LayoutParams(dp(24), dp(24)));
        TextView name = text(getContext().getString(label), 20, Style.TEXT_DIM);
        // Reserve two lines for translated labels without truncating larger text.
        name.setMinLines(2);
        LayoutParams nameLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = dp(8);
        heading.addView(name, nameLp);
        card.addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        TextView value = text("—", 32, Style.TEXT);
        LayoutParams valueLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        valueLp.topMargin = dp(4);
        card.addView(value, valueLp);
        return value;
    }

    private Object readingValue(String key) {
        CarActor.Reading r = actor.get(key);
        return r.status == CarActor.Reading.Status.OK ? r.value : null;
    }

    private void acceptTelemetry(CarActor.Reading reading) {
        telemetry = reading.status == CarActor.Reading.Status.OK && reading.value instanceof Map
            ? (Map<?, ?>) reading.value : Collections.emptyMap();
        lastTelemetryAt = SystemClock.elapsedRealtime();
    }

    private void requestRefresh() {
        ui.post(() -> { if (started) refresh(); });
    }

    private void refresh() {
        boolean fresh = SystemClock.elapsedRealtime() - lastTelemetryAt <= STALE_MS;
        VehicleEnergy values = new VehicleEnergy(fresh ? telemetry : Collections.emptyMap(),
            fresh ? readingValue("car.plug_connected") : null,
            fresh ? readingValue("car.is_charging") : null);
        Double soc = values.battery;
        if (soc == null && Obd2Reader.isConnected()) {
            soc = VehicleEnergy.number(Obd2Reader.freshSoc(15000), 0, 100);
        }
        battery.setText(soc == null ? "—" : getContext().getString(R.string.ui_energy_percent, soc));
        batteryBar.setProgress(soc == null ? 0 : (int) Math.round(soc));
        batteryBar.setAlpha(soc == null ? 0.35f : 1f);
        batteryBar.setProgressTintList(ColorStateList.valueOf(
            soc != null && soc <= 20 ? Style.HEAT : Style.GOOD));
        chargeState.setText(values.charging == null ? R.string.ui_energy_waiting
            : values.charging ? R.string.ui_energy_charging : R.string.ui_energy_not_charging);
        show(range, values.range, R.string.ui_energy_km);
        show(power, values.chargePower, R.string.ui_energy_kw);
        Double temp = Obd2Reader.isConnected()
            ? VehicleEnergy.number(Obd2Reader.freshBattTempC(15000), -40, 100) : null;
        show(temperature, temp, R.string.ui_energy_celsius);
        EnergyIntegrator.TripSnapshot trip = EnergyIntegrator.currentTrip();
        show(recovered, trip.sampleCount > 0 ? trip.regenKwh : null, R.string.ui_energy_kwh);
    }

    private void show(TextView view, Double value, int format) {
        view.setText(value == null ? "—" : getContext().getString(format, value));
    }

    public void start() {
        if (started) return;
        started = true;
        for (String key : KEYS) EntityBus.subscribe(key, listener);
        Obd2Reader.subscribe(obdListener);
        acceptTelemetry(actor.get("telemetry.tick"));
        ui.post(freshnessCheck);
    }

    public void stop() {
        started = false;
        for (String key : KEYS) EntityBus.unsubscribe(key, listener);
        Obd2Reader.unsubscribe(obdListener);
        ui.removeCallbacksAndMessages(null);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); start(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
