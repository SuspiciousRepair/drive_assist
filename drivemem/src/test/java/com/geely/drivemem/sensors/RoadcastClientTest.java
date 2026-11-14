package com.geely.drivemem.sensors;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

/** RoadcastClient against a scripted daemon: byte layouts from
 * third_party/roadcast/src/protocol.c (protocol 3, big-endian). */
public class RoadcastClientTest {

    private static byte[] msg(int type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(32 + payload.length);
        b.putInt(0x52444354).putShort((short) 3).putShort((short) type).putInt(0)
         .putInt(payload.length).putLong(0).putLong(0).put(payload);
        return b.array();
    }

    private static byte[] welcome(int frames, int signals) {
        ByteBuffer b = ByteBuffer.allocate(36);
        b.putInt(10).putInt(frames).putInt(signals).putInt(4064).putInt(2016).putInt(3).putInt(1).putLong(42);
        return b.array();
    }

    private static byte[] schema(int total, int start, String... names) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteBuffer pre = ByteBuffer.allocate(24);
        pre.putInt(1).putInt(total).putInt(start).putInt(names.length).putLong(42);
        o.write(pre.array(), 0, 24);
        for (int i = 0; i < names.length; i++) {
            byte[] n = names[i].getBytes(StandardCharsets.UTF_8);
            byte[] u = "V".getBytes(StandardCharsets.UTF_8);
            ByteBuffer e = ByteBuffer.allocate(44);
            e.putLong(7).putInt(start + i).putInt(-1).putShort((short) 0x178)
             .put((byte) 2).put((byte) 1).put((byte) 13).put((byte) 2)
             .putShort((short) n.length).putShort((short) u.length).putShort((short) 0)
             .putDouble(0.1).putDouble(0);
            o.write(e.array(), 0, 44);
            o.write(n, 0, n.length);
            o.write(u, 0, u.length);
        }
        return o.toByteArray();
    }

    private static byte[] frames(int total, int start, int count) {
        ByteBuffer b = ByteBuffer.allocate(20 + count * 28);
        b.putLong(1).putInt(total).putInt(start).putShort((short) count).putShort((short) 0);
        return b.array();
    }

    private static byte[] signals(int total, int start, int[] idx, double[] phys, int state) {
        ByteBuffer b = ByteBuffer.allocate(20 + idx.length * 40);
        b.putLong(1).putInt(total).putInt(start).putShort((short) idx.length).putShort((short) 0);
        for (int i = 0; i < idx.length; i++) {
            b.putInt(idx[i]).put((byte) state).put((byte) 1).putShort((short) 0)
             .putLong(123).putDouble(phys[i]).putLong(0).putLong(0);
        }
        return b.array();
    }

    @Test public void fullSessionReportsWantedSignalsOnly() throws Exception {
        ByteArrayOutputStream daemon = new ByteArrayOutputStream();
        for (byte[] m : new byte[][]{
                msg(2, welcome(1, 3)),
                msg(13, schema(3, 0, "BMSH_BattVolt", "Other")),
                msg(13, schema(3, 2, "BMSH_BattCurr")),
                msg(4, frames(1, 0, 1)),
                msg(7, new byte[40]),                                      // heartbeat mid-setup: skipped
                msg(15, signals(3, 0, new int[]{0, 1, 2}, new double[]{404.0, 1.0, -16.0}, 2)),
                msg(9, new byte[0]),                                       // SUBSCRIBED
                msg(16, signals(3, -1, new int[]{2}, new double[]{-15.5}, 2)),
                msg(16, signals(3, -1, new int[]{1}, new double[]{9.0}, 2)), // unwanted
        }) daemon.write(m);

        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        RoadcastClient c = new RoadcastClient(new ByteArrayInputStream(daemon.toByteArray()), sent,
            new HashSet<>(Arrays.asList("BMSH_BattVolt", "BMSH_BattCurr", "Missing")));
        List<String> got = new ArrayList<>();
        final boolean[] subscribed = {false};
        try {
            c.run(new RoadcastClient.Listener() {
                @Override public void onSignal(String n, double p, long raw, boolean v, boolean cal) { got.add(n + "=" + p + (v ? "" : "!")); }
                @Override public void onHeartbeat(int s, int mhz) {}
                @Override public void onSubscribed() { subscribed[0] = true; }
            });
            fail("run only ends with the stream");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("closed"));
        }
        assertTrue(subscribed[0]);
        assertEquals(Arrays.asList("BMSH_BattVolt=404.0", "BMSH_BattCurr=-16.0", "BMSH_BattCurr=-15.5"), got);
        assertEquals(new HashSet<>(Arrays.asList("Missing")), c.missing());

        // Requests, in order: HELLO, GET_SCHEMA 0, GET_SCHEMA 2, GET_SNAPSHOT 0, GET_SIGNAL_SNAPSHOT 0, SUBSCRIBE_ALL.
        ByteBuffer s = ByteBuffer.wrap(sent.toByteArray());
        int[] types = new int[6];
        for (int i = 0; i < 6; i++) {
            assertEquals(0x52444354, s.getInt());
            s.getShort();
            types[i] = s.getShort();
            s.getInt();
            int len = s.getInt();
            s.position(s.position() + 16 + len);
        }
        assertArrayEquals(new int[]{1, 12, 12, 3, 14, 5}, types);
        assertFalse(s.hasRemaining());
    }

    @Test(expected = IOException.class)
    public void resyncRequiredEndsTheSession() throws Exception {
        ByteArrayOutputStream daemon = new ByteArrayOutputStream();
        daemon.write(msg(2, welcome(1, 1)));
        daemon.write(msg(8, new byte[0]));
        new RoadcastClient(new ByteArrayInputStream(daemon.toByteArray()), new ByteArrayOutputStream(),
            new HashSet<>(Arrays.asList("X"))).run(null);
    }
}
