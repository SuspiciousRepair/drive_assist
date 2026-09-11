package com.geely.drivemem.controls;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.net.MqttReporter;
import com.geely.drivemem.state.GateState;
import com.geely.drivemem.ui.TelemetryActivity;
import com.geely.drivemem.util.Modes;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/** Timed Sport-mode boost. Switches to Sport for a configured duration
 * (turbo_duration_s, default DEFAULT_DURATION_S seconds), then reverts to the
 * mode that was running at the moment of press (read fresh from car, not the
 * saved startup default). Process-wide singleton so countdown survives screen/
 * app lifecycle. */
public final class TurboMode {
    public static final int DEFAULT_DURATION_S = 30;
    private static final long TICK_MS = 100L;

    public interface Listener { void onTurbo(boolean active, float fraction); }

    private static volatile TurboMode instance;

    public static TurboMode get(Context ctx) {
        TurboMode i = instance;
        if (i == null) {
            synchronized (TurboMode.class) {
                if (instance == null) instance = new TurboMode(ctx.getApplicationContext());
                i = instance;
            }
        }
        return i;
    }

    private final Context ctx;
    private final Handler h;                                  // Turbo thread for countdown; car I/O via CarActor.
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean active = false;
    private volatile float fraction = 0f;   // 1.0 = just started, 0.0 = idle/done
    private volatile Listener listener;
    private int previousDrive = Modes.DRIVE_ECO;
    // Fixed for the life of one boost, read fresh from prefs at start() — a
    // change to the Config field mid-countdown must not retarget a boost
    // already in flight, only the next one.
    private volatile long durationMs = DEFAULT_DURATION_S * 1000L;
    private volatile long startMs;

    private TurboMode(Context ctx) {
        this.ctx = ctx;
        HandlerThread t = new HandlerThread("turbo");
        t.start();
        h = new Handler(t.getLooper());
    }

    public void setListener(Listener l) { listener = l; }
    public boolean active() { return active; }
    public float fraction() { return fraction; }

    /** Starts a boost, or, if one is already active, refreshes its timer back
     * to the full duration without re-reading or re-writing drive mode. */
    public void start() {
        android.content.SharedPreferences p =
            ctx.getSharedPreferences("drivemem", Context.MODE_PRIVATE);
        if (!p.getBoolean("turbo_enabled", true)) return;   // Config: card off
        durationMs = Math.max(1, p.getInt("turbo_duration_s", DEFAULT_DURATION_S)) * 1000L;
        if (active) {
            startMs = System.currentTimeMillis();
            fraction = 1f;
            notifyUi();
            return;
        }
        active = true;
        fraction = 1f;
        notifyUi();
        // Read current drive mode; use saved default if unavailable.
        // Both read and write go through CarActor for serialized access.
        CarActor.get(ctx).read("drive_mode", cur -> {
            previousDrive = (cur instanceof Integer) ? (Integer) cur : p.getInt("drive", Modes.DRIVE_ECO);
            CarActor.get(ctx).cast("drive_mode", Modes.DRIVE_SPORT);
            startMs = System.currentTimeMillis();
            h.post(this::tick);
        });
    }

    // Reads startMs fresh each call (not a captured parameter) so a refresh
    // from start() takes effect on the next tick already in flight.
    private void tick() {
        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed >= durationMs) {
            CarActor.get(ctx).cast("drive_mode", previousDrive);
            active = false;
            fraction = 0f;
            notifyUi();
            return;
        }
        fraction = 1f - (elapsed / (float) durationMs);
        notifyUi();
        h.postDelayed(this::tick, TICK_MS);
    }

    private void notifyUi() {
        final boolean a = active; final float f = fraction;
        ui.post(() -> { Listener l = listener; if (l != null) l.onTurbo(a, f); });
    }
}
