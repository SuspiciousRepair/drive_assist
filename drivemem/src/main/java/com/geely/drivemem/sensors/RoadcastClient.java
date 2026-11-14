package com.geely.drivemem.sensors;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Minimal client for roadcastd's wire protocol, version 3
 * (third_party/roadcast/docs/PROTOCOL.md). Pure Java, no JNI: the daemon's
 * own C client library would add a native .so to an apk that has none.
 *
 * It only does what this app needs: learn the schema, find the indices of a
 * few named CAN signals, pull the snapshot the daemon insists on before a
 * subscription, then follow the update stream and hand every change of a
 * wanted signal to a {@link Listener}. Everything else is read and dropped.
 *
 * Blocking. One instance per connection; run {@link #run} on its own thread
 * and close the streams to stop it. Any protocol surprise throws, and the
 * caller reconnects: that is also how RESYNC_REQUIRED is handled. */
public final class RoadcastClient {
    public static final int PROTOCOL_VERSION = 3;
    static final int MAGIC = 0x52444354;
    static final int HEADER = 32;
    static final int MAX_PAYLOAD = 65536;

    static final int HELLO = 1, WELCOME = 2, GET_SNAPSHOT = 3, SNAPSHOT = 4,
            SUBSCRIBE_ALL = 5, UPDATE_BATCH = 6, HEARTBEAT = 7, RESYNC_REQUIRED = 8,
            SUBSCRIBED = 9, PONG = 11, GET_SCHEMA = 12, SCHEMA_CHUNK = 13,
            GET_SIGNAL_SNAPSHOT = 14, SIGNAL_SNAPSHOT_CHUNK = 15, SIGNAL_UPDATE_BATCH = 16;

    /** Observation state 2: observed and valid. Anything else is not a value. */
    public static final int STATE_VALID = 2;

    public interface Listener {
        /** A wanted signal has a (new) value. {@code physical} is already
         * scaled by the daemon's catalog; {@code valid} is false for states
         * other than "observed and valid". */
        void onSignal(String name, double physical, long raw, boolean valid, boolean calibrated);
        /** Once a second from the daemon; {@code sourceState} 1 = reading the HAL. */
        void onHeartbeat(int sourceState, int effectiveMilliHz);
        /** The snapshot is in and live updates follow. */
        void onSubscribed();
    }

    private final DataInputStream in;
    private final OutputStream out;
    private final Set<String> wanted;
    private final Map<Integer, String> nameByIndex = new HashMap<>();
    private int signalCount = -1;
    private int frameCount = -1;
    private long schemaHash;

    public RoadcastClient(InputStream in, OutputStream out, Set<String> wanted) {
        this.in = new DataInputStream(in);
        this.out = out;
        this.wanted = wanted;
    }

    /** Names that the schema did not contain (empty once {@link #run} subscribed). */
    public Set<String> missing() {
        java.util.HashSet<String> m = new java.util.HashSet<>(wanted);
        m.removeAll(nameByIndex.values());
        return m;
    }

    /** Handshake, schema, snapshot, then follows the stream until it fails. */
    public void run(Listener l) throws IOException {
        handshake();
        readSchema();
        if (nameByIndex.isEmpty()) throw new IOException("roadcast: none of the wanted signals is in the schema");
        readFrameSnapshot();
        readSignalSnapshot(l);
        send(SUBSCRIBE_ALL, new byte[0]);
        follow(l);
    }

    /** Only the handshake: true if a protocol-3 daemon answers. For probes. */
    public void handshake() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) PROTOCOL_VERSION).putShort((short) PROTOCOL_VERSION).putInt(3);
        send(HELLO, b.array());
        Msg m = expect(WELCOME);
        ByteBuffer w = m.payload();
        if (m.len != 36) throw new IOException("roadcast: bad WELCOME size " + m.len);
        w.getInt();                       // hz
        frameCount = w.getInt();
        signalCount = w.getInt();
        w.getInt(); w.getInt(); w.getInt(); w.getInt(); // limits, caps, schema version
        schemaHash = w.getLong();
        if (frameCount <= 0 || signalCount <= 0) throw new IOException("roadcast: empty catalog");
    }

    public long schemaHash() { return schemaHash; }

    private void readSchema() throws IOException {
        int next = 0;
        while (next < signalCount) {
            send(GET_SCHEMA, index(next));
            ByteBuffer p = expect(SCHEMA_CHUNK).payload();
            p.getInt();                   // schema version
            int total = p.getInt(), start = p.getInt(), count = p.getInt();
            p.getLong();                  // hash
            if (total != signalCount || start != next || count <= 0) throw new IOException("roadcast: bad schema page");
            for (int i = 0; i < count; i++) {
                int base = p.position();
                p.position(base + 8);
                int idx = p.getInt();
                p.position(base + 22);
                int nameLen = p.getShort() & 0xffff, unitLen = p.getShort() & 0xffff;
                if (nameLen > 63 || unitLen > 15) throw new IOException("roadcast: bad schema entry");
                byte[] name = new byte[nameLen];
                p.position(base + 44);
                p.get(name);
                p.position(p.position() + unitLen);
                String n = new String(name, StandardCharsets.UTF_8);
                if (wanted.contains(n)) nameByIndex.put(idx, n);
            }
            next = start + count;
        }
    }

    /** The daemon refuses a signal snapshot until the frame snapshot was
     * pulled (that is what freezes both), so page through it and drop it. */
    private void readFrameSnapshot() throws IOException {
        int next = 0;
        while (next < frameCount) {
            send(GET_SNAPSHOT, index(next));
            ByteBuffer p = expect(SNAPSHOT).payload();
            p.getLong();
            int total = p.getInt(), start = p.getInt(), count = p.getShort() & 0xffff;
            if (total != frameCount || start != next || count <= 0) throw new IOException("roadcast: bad frame page");
            next = start + count;
        }
    }

    private void readSignalSnapshot(Listener l) throws IOException {
        int next = 0;
        while (next < signalCount) {
            send(GET_SIGNAL_SNAPSHOT, index(next));
            Msg m = expect(SIGNAL_SNAPSHOT_CHUNK);
            int[] page = signalBatch(m.payload(), m.len, l);
            if (page[0] != next || page[1] <= 0) throw new IOException("roadcast: bad signal page");
            next += page[1];
        }
    }

    private void follow(Listener l) throws IOException {
        while (true) {
            Msg m = read();
            switch (m.type) {
                case SIGNAL_UPDATE_BATCH: signalBatch(m.payload(), m.len, l); break;
                case SUBSCRIBED: l.onSubscribed(); break;
                case HEARTBEAT: {
                    ByteBuffer p = m.payload();
                    if (m.len != 40) throw new IOException("roadcast: bad heartbeat");
                    p.position(32);
                    int milliHz = p.getInt();
                    l.onHeartbeat(p.get() & 0xff, milliHz);
                    break;
                }
                case RESYNC_REQUIRED: throw new IOException("roadcast: resync required");
                default: break;       // frame batches, pongs: not needed
            }
        }
    }

    /** Decodes a signal batch and reports wanted entries. Returns {start, count}. */
    private int[] signalBatch(ByteBuffer p, int len, Listener l) throws IOException {
        if (len < 20) throw new IOException("roadcast: short batch");
        p.getLong();
        p.getInt();                       // total
        int start = p.getInt(), count = p.getShort() & 0xffff;
        p.getShort();
        if (20 + count * 40 != len) throw new IOException("roadcast: bad batch size");
        for (int i = 0; i < count; i++) {
            int base = 20 + i * 40;
            int idx = p.getInt(base);
            String name = nameByIndex.get(idx);
            if (name == null) continue;
            int state = p.get(base + 4) & 0xff;
            boolean calibrated = p.get(base + 5) != 0;
            long raw = p.getLong(base + 8);
            double physical = p.getDouble(base + 16);
            l.onSignal(name, physical, raw, state == STATE_VALID, calibrated);
        }
        return new int[]{start, count};
    }

    private static byte[] index(int i) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(i).array();
    }

    private void send(int type, byte[] payload) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC).putShort((short) PROTOCOL_VERSION).putShort((short) type)
         .putInt(0).putInt(payload.length).putLong(0).putLong(0).put(payload);
        out.write(b.array());
        out.flush();
    }

    static final class Msg {
        final int type, len;
        final byte[] data;
        Msg(int type, byte[] data) { this.type = type; this.data = data; this.len = data.length; }
        ByteBuffer payload() { return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN); }
    }

    /** Reads until a message of {@code type}; heartbeats and stray updates
     * that arrive meanwhile are skipped. */
    private Msg expect(int type) throws IOException {
        for (int guard = 0; guard < 10_000; guard++) {
            Msg m = read();
            if (m.type == type) return m;
            if (m.type == RESYNC_REQUIRED) throw new IOException("roadcast: resync required");
        }
        throw new IOException("roadcast: no reply " + type);
    }

    private Msg read() throws IOException {
        byte[] h = new byte[HEADER];
        try { in.readFully(h); } catch (EOFException e) { throw new IOException("roadcast: closed by daemon", e); }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC) throw new IOException("roadcast: bad magic");
        int version = b.getShort() & 0xffff;
        int type = b.getShort() & 0xffff;
        b.getInt();
        int len = b.getInt();
        if (version != PROTOCOL_VERSION) throw new IOException("roadcast: protocol " + version);
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("roadcast: bad length " + len);
        byte[] data = new byte[len];
        in.readFully(data);
        return new Msg(type, data);
    }
}
