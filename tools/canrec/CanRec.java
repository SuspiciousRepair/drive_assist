package com.geely.canrec;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import com.geely.drivemem.sensors.RoadcastClient;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Standalone CAN recorder: the CAN explorer without the app.
 *
 * Runs as a root process from app_process (tools/canrec/canrec.sh), so
 * installing or killing drivemem does not stop it. It reads roadcastd's
 * socket like any other client and writes the same format as drivemem's
 * CanExplorer:
 *   F wall_ms can_id state 16hex     a frame's bytes changed (max every 2 s per frame)
 *   R wall_ms key=value ...          roadcastd's own decoded signals, every 5 s
 * It has no hub, so the dongle's values (battery temperature, SoC) are not
 * here: tools/can-explore-analyze.py --obd2 joins drivemem's
 * obd2-reading.log by time.
 *
 *   app_process -Djava.class.path=canrec.dex /system/bin com.geely.canrec.CanRec DIR MINUTES */
public final class CanRec {
    static final long FRAME_GAP_MS = 2_000;
    static final long REF_GAP_MS = 5_000;
    static final long MAX_BYTES = 300L * 1024 * 1024;
    static final String[] SIGNALS = {
        "BMSH_BattVolt", "BMSH_BattCurr", "VCU_ThermalPwrAct", "VCU_DrvPwrAct", "OBC_uInAct", "OBC_iInAct",
    };

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "/data/local/tmp/drive_assist/canrec");
        long minutes = args.length > 1 ? Long.parseLong(args[1]) : 600;
        long until = System.currentTimeMillis() + minutes * 60_000L;
        dir.mkdirs();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        File f = new File(dir, "can-explore-" + stamp + ".tsv");
        Set<String> wanted = new HashSet<>(Arrays.asList(SIGNALS));
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true), 64 * 1024)) {
            w.write("# CAN explorer (standalone canrec). F = frame change: wall_ms, can_id, state, 8 data bytes hex."
                + " R = roadcastd signals: wall_ms then key=value.\n");
            w.flush();
            Rec rec = new Rec(w);
            while (System.currentTimeMillis() < until && rec.bytes < MAX_BYTES) {
                LocalSocket s = new LocalSocket();
                try {
                    s.connect(new LocalSocketAddress("roadcast", LocalSocketAddress.Namespace.ABSTRACT));
                    RoadcastClient c = new RoadcastClient(s.getInputStream(), s.getOutputStream(), wanted).withFrames(rec);
                    c.run(new RoadcastClient.Listener() {
                        @Override public void onSignal(String n, double p, long raw, boolean v, boolean cal) {
                            if (v) rec.signals.put(n, p);
                        }
                        @Override public void onSubscribed() {}
                        @Override public void onHeartbeat(int sourceState, int milliHz) {
                            try {
                                rec.tick();
                                if (System.currentTimeMillis() >= until || rec.bytes >= MAX_BYTES) s.shutdownInput();
                            } catch (IOException e) { throw new RuntimeException(e); }
                        }
                    });
                } catch (Throwable t) {
                    System.err.println("canrec: " + t);
                } finally {
                    try { s.close(); } catch (Throwable ignored) {}
                    w.flush();
                }
                if (System.currentTimeMillis() < until && rec.bytes < MAX_BYTES) Thread.sleep(5_000);
            }
            w.write("# stopped\n");
        }
    }

    static final class Rec implements RoadcastClient.FrameListener {
        private final java.io.Writer w;
        private final Map<Integer, byte[]> written = new HashMap<>();
        private final Map<Integer, byte[]> latest = new HashMap<>();
        private final Map<Integer, Integer> states = new HashMap<>();
        private final Map<Integer, Long> writtenAt = new HashMap<>();
        final Map<String, Double> signals = new TreeMap<>();
        private long lastRef = 0;
        long bytes = 0;

        Rec(java.io.Writer w) { this.w = w; }

        static long mono() { return System.nanoTime() / 1_000_000; }

        @Override public void onFrame(int canId, int state, byte[] data, boolean snapshot) {
            latest.put(canId, data);
            states.put(canId, state);
            try { maybeWrite(canId, mono()); } catch (IOException e) { throw new RuntimeException(e); }
        }

        void tick() throws IOException {
            long now = mono();
            for (Integer id : latest.keySet().toArray(new Integer[0])) maybeWrite(id, now);
            if (lastRef == 0 || now - lastRef >= REF_GAP_MS) {
                lastRef = now;
                StringBuilder b = new StringBuilder("R\t").append(System.currentTimeMillis());
                for (Map.Entry<String, Double> e : signals.entrySet()) {
                    b.append("\tcan.").append(e.getKey()).append('=').append(e.getValue());
                }
                out(b.append('\n').toString());
                w.flush();
            }
        }

        private void maybeWrite(int canId, long now) throws IOException {
            byte[] d = latest.get(canId);
            if (d == null || Arrays.equals(d, written.get(canId))) return;
            Long at = writtenAt.get(canId);
            if (at != null && now - at < FRAME_GAP_MS) return;
            written.put(canId, d);
            writtenAt.put(canId, now);
            StringBuilder b = new StringBuilder("F\t").append(System.currentTimeMillis()).append('\t')
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
