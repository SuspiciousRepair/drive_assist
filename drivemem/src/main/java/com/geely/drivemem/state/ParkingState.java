package com.geely.drivemem.state;

import android.content.Context;
import android.content.SharedPreferences;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;

import java.util.Map;

/** Persistent baseline for the current continuous stay in Park, including
 * charging. Start/clear follow CarState's parked notifications, not a direct
 * car.gear subscription of its own — see CarState's class comment for why. */
public final class ParkingState {
    private static final String PREFS = "drivemem";
    private static final String START = "parking_start_ms";
    private static final String SOC = "parking_start_soc";
    private static final String TEMP = "parking_start_temp";
    private static volatile boolean subscribed;

    public static final class Snapshot {
        public final long startMs;
        public final Integer startSoc, currentSoc;
        public final Float startTemp, currentTemp;
        Snapshot(long startMs, Integer startSoc, Integer currentSoc, Float startTemp, Float currentTemp) {
            this.startMs = startMs; this.startSoc = startSoc; this.currentSoc = currentSoc;
            this.startTemp = startTemp; this.currentTemp = currentTemp;
        }
    }

    public static synchronized void ensureSubscribed(Context context) {
        if (subscribed) return;
        subscribed = true;
        Context app = context.getApplicationContext();
        CarState.addListener(parked -> {
            if (parked) ensureBaseline(app, null);
            else clear(app);
        });
        EntityBus.subscribe("telemetry.tick", (key, reading) -> {
            if (reading.status != CarActor.Reading.Status.OK || !CarState.isParked()) return;
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) reading.value;
            ensureBaseline(app, data);
        });
    }

    private static void ensureBaseline(Context c, Map<String, Object> data) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean isNew = p.getLong(START, 0) <= 0;
        SharedPreferences.Editor e = p.edit();
        if (isNew) e.putLong(START, System.currentTimeMillis());
        Number soc = number(data, "battery", CarActor.get(c).get("telemetry.battery"));
        Number temp = number(data, "outside_temp", CarActor.get(c).get("telemetry.outside_temp"));
        if (!p.contains(SOC) && soc != null) e.putInt(SOC, soc.intValue());
        if (!p.contains(TEMP) && temp != null) e.putFloat(TEMP, temp.floatValue());
        e.apply();
        if (isNew || (!p.contains(SOC) && soc != null) || (!p.contains(TEMP) && temp != null))
            EntityBus.publish("parking.changed", CarActor.Reading.ok(true));
    }

    private static Number number(Map<String, Object> data, String key, CarActor.Reading cached) {
        Object value = data == null ? null : data.get(key);
        if (value instanceof Number) return (Number) value;
        return cached != null && cached.status == CarActor.Reading.Status.OK && cached.value instanceof Number
            ? (Number) cached.value : null;
    }

    private static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(START).remove(SOC).remove(TEMP).apply();
        EntityBus.publish("parking.changed", CarActor.Reading.ok(false));
    }

    public static Snapshot snapshot(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long start = p.getLong(START, 0);
        CarActor.Reading socR = CarActor.get(c).get("telemetry.battery");
        CarActor.Reading tempR = CarActor.get(c).get("telemetry.outside_temp");
        Integer nowSoc = socR.status == CarActor.Reading.Status.OK && socR.value instanceof Number
            ? ((Number) socR.value).intValue() : null;
        Float nowTemp = tempR.status == CarActor.Reading.Status.OK && tempR.value instanceof Number
            ? ((Number) tempR.value).floatValue() : null;
        return new Snapshot(start, p.contains(SOC) ? p.getInt(SOC, 0) : null, nowSoc,
            p.contains(TEMP) ? p.getFloat(TEMP, 0) : null, nowTemp);
    }

    private ParkingState() {}
}
