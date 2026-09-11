package com.geely.drivemem.car;

import com.geely.drivemem.controls.DoorWindow;
import com.geely.drivemem.controls.Purge;
import com.geely.drivemem.controls.TurboMode;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.sensors.OdoStats;
import com.geely.drivemem.services.OutTempService;
import com.geely.drivemem.state.CarplayState;
import com.geely.drivemem.state.ChargeSession;
import com.geely.drivemem.ui.ComfortActivity;
import com.geely.drivemem.ui.TelemetryActivity;
import com.geely.drivemem.util.Modes;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-threaded serializer for all car property reads and writes. Manages
 * a shared CarAccess connection, periodic polls, discrete property watches,
 * and publishes changes via EntityBus. All car I/O is serialized through one
 * thread to avoid race conditions when multiple components write.
 */
public final class CarActor {

    public interface ResultCallback { void onResult(CarDataHub.WriteResult r); }
    public interface ReadCallback { void onRead(Object value); }
    public interface BoolCallback { void onResult(boolean ok); }

    /** Cached reading with explicit status (not_available/loading/ok/error). */
    public static final class Reading {
        public enum Status { NOT_AVAILABLE, LOADING, OK, ERROR }
        public final Status status;
        public final Object value;
        public final String error;
        private Reading(Status s, Object v, String e) { status = s; value = v; error = e; }
        public static final Reading NOT_AVAILABLE = new Reading(Status.NOT_AVAILABLE, null, null);
        public static final Reading LOADING = new Reading(Status.LOADING, null, null);
        public static Reading ok(Object v) { return new Reading(Status.OK, v, null); }
        public static Reading error(String msg) { return new Reading(Status.ERROR, null, msg); }
    }

    // Per-key cached state and change-detection deadband.
    private static final class Cached {
        volatile Reading reading = Reading.LOADING;
        final double epsilon;
        Cached(double epsilon) { this.epsilon = epsilon; }
    }
    private final Map<String, Cached> state = new ConcurrentHashMap<>();

    private static volatile CarActor instance;

    public static CarActor get(Context ctx) {
        CarActor i = instance;
        if (i == null) {
            synchronized (CarActor.class) {
                if (instance == null) instance = new CarActor(ctx.getApplicationContext());
                i = instance;
            }
        }
        return i;
    }

    private final Context ctx;
    private final CarAccess car = new CarAccess();
    private final Handler h;

    private CarActor(Context ctx) {
        this.ctx = ctx;
        HandlerThread t = new HandlerThread("car-actor");
        t.start();
        h = new Handler(t.getLooper());
        h.post(this::tick);

        // Charging status from current flow (faster than main telemetry tick)
        // for ChargeSession card responsiveness.
        registerPoll("car.is_charging", 2000, c -> {
            // TODO: should we use CarAccess.isCharging() instead? It reads the same raw prop (605291008) but applies a 0.5A threshold.
            // TODO: should we name codes with meaningul constants instead of magic numbers? 605291008 is Telemetry.FIELDS.charge_a.prop.
            String v = c.readAny(605291008, 0, 'f');   // charge_a — same raw prop Telemetry.FIELDS reads
            if (v == null) return Reading.error("no reading");
            float a = Float.parseFloat(v);
            return Reading.ok((a > 0.5f) ? 1 : 0);
        });

        // Always-on baseline (independent of OutTempService lifecycle).
        registerPoll("telemetry.outside_temp", 15000, c -> {
            Float outC = c.readOutsideTempC();
            return (outC != null) ? Reading.ok(outC) : Reading.error("read failed");
        });

        // Always-on polling of ambient light (independent of screen focus).
        registerPoll("telemetry.ambient_color", 4000, c -> {
            Integer v = c.readAmbientColor();
            return (v != null) ? Reading.ok(v) : Reading.error("read failed");
        });
        registerPoll("telemetry.ambient_brightness", 4000, c -> {
            Integer v = c.readAmbientBrightness();
            return (v != null) ? Reading.ok(v) : Reading.error("read failed");
        });

        // Not a car property -- CarplayState owns the actual subscription (the
        // OEM CarPlay app's own broadcast) and the Bluetooth-recovery action.
        // This just puts its state on the same bus everything else reads from.
        registerPoll("car.carplay_connected", 4000, c ->
            Reading.ok(CarplayState.connected() ? 1 : 0));
    }

    // Main heartbeat: reads all Telemetry.FIELDS on cadence.
    private static final int TICK_INTERVAL_MS = 15000;
    private long lastTelemetryMs = -1;

    /** Callback for a registered periodic property poll. Runs on actor's thread. */
    public interface Poller { Reading poll(CarAccess car); }

    private static final class PollEntry {
        final String key; final int intervalMs; final Poller poller;
        volatile long lastRunMs = -1;
        PollEntry(String key, int intervalMs, Poller poller) {
            this.key = key; this.intervalMs = intervalMs; this.poller = poller;
        }
    }
    private final java.util.List<PollEntry> polls = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Loop tick interval adapts to shortest registered poll interval.
    private volatile int masterTickMs = TICK_INTERVAL_MS;

    /** Registers a periodic poll, replacing any existing entry for the key. */
    public void registerPoll(String key, int intervalMs, Poller poller) {
        h.post(() -> {
            polls.removeIf(p -> p.key.equals(key));
            PollEntry pe = new PollEntry(key, intervalMs, poller);
            polls.add(pe);
            recomputeMasterTick();
            // Read immediately instead of waiting for next loop tick.
            ensureConnected();
            Reading r;
            try { r = poller.poll(car); } catch (Throwable t) { r = Reading.error(String.valueOf(t)); }
            pe.lastRunMs = android.os.SystemClock.elapsedRealtime();
            ingest(key, r);
        });
    }

    /** Unregisters a periodic poll by key. */
    public void unregisterPoll(String key) {
        h.post(() -> { polls.removeIf(p -> p.key.equals(key)); recomputeMasterTick(); });
    }

    private void recomputeMasterTick() {
        int m = TICK_INTERVAL_MS;
        for (PollEntry p : polls) m = Math.min(m, p.intervalMs);
        masterTickMs = m;
    }

    private void tick() {
        try {
            ensureConnected();
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastTelemetryMs < 0 || now - lastTelemetryMs >= TICK_INTERVAL_MS) {
                lastTelemetryMs = now;
                java.util.LinkedHashMap<String, Object> data = Telemetry.read(car);
                if (!data.isEmpty()) {
                    for (Map.Entry<String, Object> e : data.entrySet())
                        ingest("telemetry." + e.getKey(), Reading.ok(e.getValue()));
                    // Full snapshot for subscribers that need all fields at once
                    // (e.g., MQTT, ChargeSession). Published on every tick
                    // regardless of changes (not subject to change-detection).
                    Cached c = state.computeIfAbsent("telemetry.tick", k -> new Cached(0));
                    c.reading = Reading.ok(data);
                    EntityBus.publish("telemetry.tick", c.reading);
                }
            }
            for (PollEntry p : polls) {
                if (p.lastRunMs < 0 || now - p.lastRunMs >= p.intervalMs) {
                    p.lastRunMs = now;
                    Reading r;
                    try { r = p.poller.poll(car); }
                    catch (Throwable t) { r = Reading.error(String.valueOf(t)); }
                    ingest(p.key, r);
                }
            }
        } catch (Throwable t) {
            Log.w(CarAccess.TAG, "car actor tick: " + t);
        }
        h.postDelayed(this::tick, masterTickMs);
    }

    /** Injects a synthetic value into the cache (for testing/emulation). */
    public void inject(String key, Object value) {
        h.post(() -> ingest(key, Reading.ok(value)));
    }

    /** Writes a property value asynchronously. */
    public void cast(String key, Object value) { cast(key, value, null); }

    /** Writes a property value asynchronously with a result callback. */
    public void cast(String key, Object value, ResultCallback cb) {
        h.post(() -> {
            ensureConnected();
            CarDataHub.WriteResult r = CarDataHub.apply(car, key, value);
            if (!r.applied) Log.w(CarAccess.TAG, "car actor: " + key + " rejected: " + r.error);
            if (cb != null) cb.onResult(r);
        });
    }

    /** Reads a property value, delivering it to the callback. */
    public void read(String key, ReadCallback cb) {
        h.post(() -> {
            ensureConnected();
            cb.onRead(CarDataHub.read(car, key));
        });
    }

    // For ordered sequences of I/O (e.g., ComfortRuler). Runs on actor's thread
    // and must never be called from any other thread. No ensureConnected()
    // wrapper: caller is responsible for checking car.isReady() first.
    public void runOnCarThread(Runnable r) { h.post(r); }
    /** Posts a runnable to be executed on the actor's thread after a delay. */
    public void runOnCarThreadDelayed(Runnable r, long delayMs) { h.postDelayed(r, delayMs); }

    /** Cancels a previously posted runnable. */
    public void cancelOnCarThread(Runnable r) { h.removeCallbacks(r); }

    /** Returns direct access to the shared CarAccess (for use only on actor's thread). */
    public CarAccess rawAccess() { return car; }

    /** Returns the cached reading for a key, or NOT_AVAILABLE if never registered. */
    public Reading get(String key) {
        Cached c = state.get(key);
        return (c != null) ? c.reading : Reading.NOT_AVAILABLE;
    }

    /** Ingests a reading into cache and publishes if changed. */
    private void ingest(String key, Reading next) {
        Cached c = state.computeIfAbsent(key, k -> new Cached(epsilonFor(k)));
        boolean changed = !sameReading(c.reading, next, c.epsilon);
        c.reading = next;
        if (changed) EntityBus.publish(key, next);
    }

    // Change-detection deadband: exact match for discrete properties or
    // pre-quantized floats; 0.05 for raw unrounded values (sensor jitter).
    private static double epsilonFor(String key) {
        Integer round = Telemetry.roundFor(key);
        return (round == null || round >= 0) ? 0 : 0.05;
    }

    private static boolean sameReading(Reading a, Reading b, double epsilon) {
        if (a.status != b.status) return false;
        if (a.status == Reading.Status.ERROR) return java.util.Objects.equals(a.error, b.error);
        if (a.status != Reading.Status.OK) return true;   // NOT_AVAILABLE/LOADING, no value to compare
        if (epsilon <= 0) return java.util.Objects.equals(a.value, b.value);
        double av = ((Number) a.value).doubleValue(), bv = ((Number) b.value).doubleValue();
        return Math.abs(av - bv) <= epsilon;
    }

    // --- raw layer: for what CarDataHub's closed registry doesn't cover ---

    /** Registers a continuous watch on a single-area property. */
    public void watchRaw(String key, int prop, int area) {
        h.post(() -> {
            ensureConnected();
            android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback cb =
                new android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback() {
                    @Override public void onChangeEvent(android.car.hardware.CarPropertyValue v) {
                        if (v.getAreaId() != area) return;
                        h.post(() -> ingest(key, Reading.ok(v.getValue())));
                    }
                    @Override public void onErrorEvent(int p, int a) {
                        if (a != area) return;
                        h.post(() -> ingest(key, Reading.error("prop " + p + " area " + a)));
                    }
                };
            if (!car.watch(prop, cb)) {
                ingest(key, Reading.error("watch failed"));
            } else {
                Integer initial = car.readIntRaw(prop, area);
                if (initial != null) {
                    ingest(key, Reading.ok(initial));
                } else {
                    Boolean boolInitial = car.readHvacFlag(prop);
                    if (boolInitial != null) ingest(key, Reading.ok(boolInitial));
                }
            }
        });
    }

    /** Registers a continuous watch on a multi-area property. Each area change
     * publishes to the same key as {areaId, value}. */
    public void watchRawMultiArea(String key, int prop) {
        h.post(() -> {
            ensureConnected();
            android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback cb =
                new android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback() {
                    @Override public void onChangeEvent(android.car.hardware.CarPropertyValue v) {
                        Object val = v.getValue();
                        if (!(val instanceof Integer)) return;
                        int area = v.getAreaId(); int iv = (Integer) val;
                        h.post(() -> ingest(key, Reading.ok(new int[]{area, iv})));
                    }
                    @Override public void onErrorEvent(int p, int a) {
                        h.post(() -> ingest(key, Reading.error("prop " + p + " area " + a)));
                    }
                };
            if (!car.watch(prop, cb)) ingest(key, Reading.error("watch failed"));
        });
    }

    /** Reads a property once without caching (debug/one-shot use). */
    // TODO: find spurious uses of this and replace with CarDataHub.read() or registerPoll(). Ok for debug. NOK for anything else.
    public void readRawOnce(int prop, int area, ReadCallback cb) {
        h.post(() -> { ensureConnected(); cb.onRead(car.readIntRaw(prop, area)); });
    }

    /** Reads a property of unknown type once, without caching (debug use). */
    // TODO: find spurious uses of this and replace with CarDataHub.read() or registerPoll(). Ok for debug. NOK for anything else.
    public void readAnyOnce(int prop, int area, char type, ReadCallback cb) {
        h.post(() -> { ensureConnected(); cb.onRead(car.readAny(prop, area, type)); });
    }

    /** Writes a property once without caching (debug/one-shot use). */
    // TODO: find spurious uses of this and replace with CarDataHub.apply() or cast(). Ok for debug. NOK for anything else.
    public void castRawOnce(int prop, int area, int value, BoolCallback cb) {
        h.post(() -> {
            ensureConnected();
            boolean ok = car.setIntRaw(prop, area, value);
            if (cb != null) cb.onResult(ok);
        });
    }

    // Connects if needed and re-arms discrete watches on reconnect.
    private void ensureConnected() {
        if (!car.isReady() && car.connect(ctx)) armDiscreteWatches();
    }

    private void armDiscreteWatches() {
        // Register push watches for properties that support it.
        watchRaw("car.gear", 289408001, 0);
        watchRaw("car.park_mode", CarAccess.PARK_MODE, 0);
        watchRaw("car.drive_mode", Modes.PROP_DRIVE, 0);
        watchRaw("car.regen_mode", Modes.PROP_REGEN, 0);
        watchRawMultiArea("car.door_pos", CarAccess.DOOR_POS);
        watchRawMultiArea("car.window_pos", CarAccess.WINDOW_POS);
        watchRaw("car.hvac_recirc", CarAccess.HVAC_RECIRC_ON, 75);
        watchRaw("car.hvac_direction", 557846560, 0);
        watchRaw("car.hvac_rear_defrost", CarAccess.HVAC_ELECTRIC_DEFROSTER_ON, 2);
    }
}
