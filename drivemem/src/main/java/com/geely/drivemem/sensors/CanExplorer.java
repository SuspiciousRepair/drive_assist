package com.geely.drivemem.sensors;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.util.Prefs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** CAN explorer: a debug recorder, off by default, for finding values the
 * head unit receives but has no name for (61 % of the bits in the 111 frames
 * roadcastd reads; third_party/roadcast/docs/CATALOG_SURFACE.md, finding 4).
 *
 * It saves raw frame changes next to reference values from the hub (the
 * dongle's battery temperature, SoC, pack V/A, outside temperature, ...), so
 * an offline search can find unnamed bits that move with a known value.
 * First target: battery temperature, which today only the OBD2 dongle reads.
 *
 * Not a data source: nothing in the app consumes what it records, so it
 * reads raw frames straight from roadcastd (a debug capture, not car data
 * for consumers; CLAUDE.md rule 7 is about the latter). Its reference values
 * come from the hub.
 *
 * Cost control: each frame is written at most every {@link #FRAME_GAP_MS}
 * and only when its bytes changed; reference values every
 * {@link #REF_GAP_MS}. Stops by itself at the deadline set when it was
 * switched on, or at {@link #MAX_BYTES}.
 *
 * Switch (adb only): am broadcast -n com.geely.drivemem/.util.BootReceiver
 *   -a com.geely.drivemem.CANEXPLORE --ei minutes 120   (0 = stop now)
 * Output: Android/data/com.geely.drivemem/files/can-explore-*.tsv */
public final class CanExplorer {
    static final String TAG = "DriveMem";
    public static final String PREF_UNTIL = "can_explore_until";
    static final long FRAME_GAP_MS = 2_000;
    static final long REF_GAP_MS = 5_000;
    static final long MAX_BYTES = 200L * 1024 * 1024;
    public static final int DEFAULT_MINUTES = 360;

    /** Reference values written next to the frames, all from the hub. */
    static final String[] REF_KEYS = {
        ObdHub.BATT_TEMP_C, ObdHub.SOC, ObdHub.PACK_V, ObdHub.PACK_A, ObdHub.POWER_KW,
        CanHub.PACK_V, CanHub.PACK_A, CanHub.POWER_KW, CanHub.CLIMATE_KW, CanHub.DRIVE_KW,
        CanHub.CHARGER_W, "telemetry.outside_temp", "car.is_charging", "car.charge_a",
    };

    private static volatile boolean wanted;
    private static volatile Thread thread;
    private static volatile LocalSocket socket;

    private CanExplorer() {}

    /** Switch on for {@code minutes} (0 = off). Persisted, so a process
     * restart mid-drive keeps recording until the deadline. */
    public static void setMinutes(Context ctx, int minutes) {
        long until = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        Prefs.file(ctx).edit().putLong(PREF_UNTIL, until).apply();
        Log.i(TAG, "canexplore: " + (minutes > 0 ? "on for " + minutes + " min" : "off"));
        if (minutes > 0) ensureStarted(ctx); else stop();
    }

    public static synchronized void ensureStarted(Context ctx) {
        long until = Prefs.file(ctx).getLong(PREF_UNTIL, 0);
        if (until <= System.currentTimeMillis()) return;
        wanted = true;
        if (thread != null && thread.isAlive()) return;
        Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> loop(app, until), "can-explore");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        wanted = false;
        LocalSocket s = socket;
        socket = null;
        if (s != null) { try { s.shutdownInput(); s.close(); } catch (Throwable ignored) {} }
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private static void loop(Context ctx, long until) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        File f = new File(dir, "can-explore-" + stamp + ".tsv");
        Log.i(TAG, "canexplore: recording to " + f);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true), 64 * 1024)) {
            w.write("# CAN explorer. F = frame change: wall_ms, can_id, state, 8 data bytes hex."
                + " R = reference from the hub: wall_ms then key=value.\n");
            Recorder rec = new Recorder(w);
            while (wanted && System.currentTimeMillis() < until && rec.bytes < MAX_BYTES) {
                LocalSocket s = new LocalSocket();
                socket = s;
                try {
                    s.connect(new LocalSocketAddress("roadcast", LocalSocketAddress.Namespace.ABSTRACT));
                    RoadcastClient c = new RoadcastClient(s.getInputStream(), s.getOutputStream(),
                        Collections.singleton(CanPower.VOLT)).withFrames(rec);
                    c.run(new RoadcastClient.Listener() {
                        @Override public void onSignal(String n, double p, long raw, boolean v, boolean cal) {}
                        @Override public void onSubscribed() {}
                        @Override public void onHeartbeat(int sourceState, int milliHz) {
                            try {
                                rec.tick();
                                if (System.currentTimeMillis() >= until || rec.bytes >= MAX_BYTES) {
                                    LocalSocket cur = socket;
                                    if (cur != null) cur.shutdownInput();
                                }
                            } catch (IOException e) { throw new RuntimeException(e); }
                        }
                    });
                } catch (Throwable t) {
                    if (wanted && System.currentTimeMillis() < until) Log.w(TAG, "canexplore: " + t);
                } finally {
                    try { s.close(); } catch (Throwable ignored) {}
                    w.flush();
                }
                if (wanted && System.currentTimeMillis() < until && rec.bytes < MAX_BYTES) {
                    try { Thread.sleep(5_000); } catch (InterruptedException e) { break; }
                }
            }
            Log.i(TAG, "canexplore: stopped, " + rec.bytes + " bytes in " + f.getName());
        } catch (IOException e) {
            Log.w(TAG, "canexplore: " + e);
        }
    }

    /** Throttles and writes. Package-private for tests. */
    static final class Recorder implements RoadcastClient.FrameListener {
        private final java.io.Writer w;
        private final Map<Integer, byte[]> written = new HashMap<>();
        private final Map<Integer, byte[]> latest = new HashMap<>();
        private final Map<Integer, Integer> states = new HashMap<>();
        private final Map<Integer, Long> writtenAt = new HashMap<>();
        private long lastRefMono = 0;
        long bytes = 0;
        /** Clock and reference source, swappable for tests. */
        java.util.function.LongSupplier mono = SystemClock::elapsedRealtime;
        java.util.function.LongSupplier wall = System::currentTimeMillis;
        java.util.function.Function<String, Object> ref = k -> {
            CarActor a = CarActor.get();
            if (a == null) return null;
            CarActor.Reading r = a.get(k);
            return r.status == CarActor.Reading.Status.OK ? r.value : null;
        };

        Recorder(java.io.Writer w) { this.w = w; }

        @Override public void onFrame(int canId, int state, byte[] data, boolean snapshot) {
            latest.put(canId, data);
            states.put(canId, state);
            try { maybeWrite(canId, mono.getAsLong()); } catch (IOException e) { throw new RuntimeException(e); }
        }

        /** Once a second (heartbeat): flush frames held back by the gap, and
         * write the reference line when due. */
        void tick() throws IOException {
            long now = mono.getAsLong();
            for (Integer id : latest.keySet().toArray(new Integer[0])) maybeWrite(id, now);
            if (lastRefMono == 0 || now - lastRefMono >= REF_GAP_MS) {
                lastRefMono = now;
                StringBuilder b = new StringBuilder("R\t").append(wall.getAsLong());
                for (String k : REF_KEYS) {
                    Object v = ref.apply(k);
                    if (v != null) b.append('\t').append(k).append('=').append(v);
                }
                out(b.append('\n').toString());
                w.flush();   // every 5 s: a process death loses at most that much
            }
        }

        private void maybeWrite(int canId, long now) throws IOException {
            byte[] d = latest.get(canId);
            if (d == null || Arrays.equals(d, written.get(canId))) return;
            Long at = writtenAt.get(canId);
            if (at != null && now - at < FRAME_GAP_MS) return;
            written.put(canId, d);
            writtenAt.put(canId, now);
            StringBuilder b = new StringBuilder("F\t").append(wall.getAsLong()).append('\t')
                .append(String.format("%03X", canId)).append('\t').append(states.get(canId)).append('\t');
            for (byte x : d) b.append(String.format("%02X", x & 0xff));
            out(b.append('\n').toString());
        }

        private void out(String s) throws IOException {
            w.write(s);
            bytes += s.length();
        }
    }
}
