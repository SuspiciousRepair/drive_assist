package com.geely.drivemem.sensors;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.net.Updater;

/** Tells modehelper's dashcam the brake pedal, for the subtitle line.
 *
 * The dashcam records in modehelper, a different process, and the pedal is
 * only on the CAN bus, which this app reads. So the hub's {@link CanHub#BRAKE}
 * (CLAUDE.md rule 7: consumers read the hub) is passed on with a broadcast,
 * {@link #ACTION} with {@code pressed} = 1, 0, or -1 for not known.
 *
 * Sent when the pedal changes, a few times a minute. While it is pressed the
 * same message goes again every {@link #REPEAT_MS}: modehelper believes
 * "pressed" only for a few seconds after it last heard it, so a line can
 * never go on saying BRAKE because this app died with the pedal down.
 * "Released" and "not known" need no repeat: both show nothing.
 *
 * Each message carries {@link #EXTRA_FROM}, a PendingIntent this app made.
 * modehelper reads who made it and drops a message from any other app, so
 * no other app can put BRAKE in a clip.
 *
 * Nothing is sent while the CAN reader is off, because the key then never
 * changes. */
public final class DashBrake {
    static final String ACTION = "com.geely.modehelper.DASH_BRAKE";
    static final String EXTRA = "pressed";
    static final String EXTRA_FROM = "from";
    static final long REPEAT_MS = 2_000L;
    static final int PRESSED = 1, RELEASED = 0, UNKNOWN = -1;

    private DashBrake() {}

    /** A hub reading as 1 pressed, 0 released, -1 not known. */
    static int state(CarActor.Reading r) {
        if (r == null || r.status != CarActor.Reading.Status.OK || !(r.value instanceof Number)) return UNKNOWN;
        return ((Number) r.value).intValue() == 1 ? PRESSED : RELEASED;
    }

    /** What to send and when. No Android in it, so it is unit tested. */
    static final class Core {
        interface Out {
            void send(int state);
            void repeatIn(long ms);
            void cancelRepeat();
        }

        private boolean sent;
        private int last;

        /** The hub's value, now. Sends it if it is new. */
        void onState(int state, Out out) {
            if (sent && state == last) return;
            sent = true;
            last = state;
            out.cancelRepeat();
            out.send(state);
            if (state == PRESSED) out.repeatIn(REPEAT_MS);
        }

        /** The repeat timer fired. */
        void onRepeat(Out out) {
            if (!sent || last != PRESSED) return;
            out.send(PRESSED);
            out.repeatIn(REPEAT_MS);
        }
    }

    private static boolean started;

    /** Idempotent. Call once from the application; it costs nothing while
     * the CAN reader is off. */
    public static synchronized void ensureSubscribed(Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        // The hub runs its listeners on its own thread and they must be
        // cheap: the broadcast is sent from the main thread instead.
        final Handler main = new Handler(Looper.getMainLooper());
        final Core core = new Core();
        final Runnable[] repeat = new Runnable[1];
        final PendingIntent[] from = new PendingIntent[1];
        final Core.Out out = new Core.Out() {
            @Override public void send(int state) {
                try {
                    if (from[0] == null) {
                        // Made once. It is never sent, only read for its maker.
                        from[0] = PendingIntent.getBroadcast(app, 0,
                            new Intent(ACTION + ".FROM").setPackage(app.getPackageName()),
                            PendingIntent.FLAG_IMMUTABLE);
                    }
                    app.sendBroadcast(new Intent(ACTION).setPackage(Updater.HELPER_PKG)
                        .putExtra(EXTRA, state).putExtra(EXTRA_FROM, from[0]));
                } catch (Throwable t) { Log.w(CarAccess.TAG, "dash brake: " + t); }
            }
            @Override public void repeatIn(long ms) { main.postDelayed(repeat[0], ms); }
            @Override public void cancelRepeat() { main.removeCallbacks(repeat[0]); }
        };
        repeat[0] = () -> core.onRepeat(out);
        EntityBus.subscribe(CanHub.BRAKE, (key, reading) -> {
            final int state = state(reading);
            main.post(() -> core.onState(state, out));
        });
        // The bus does not replay: a pedal already down when this starts
        // (the app restarted mid-drive) is read from the hub once.
        CarActor a = CarActor.get(app);
        final int now = a == null ? UNKNOWN : state(a.get(CanHub.BRAKE));
        if (now != UNKNOWN) main.post(() -> core.onState(now, out));
    }
}
