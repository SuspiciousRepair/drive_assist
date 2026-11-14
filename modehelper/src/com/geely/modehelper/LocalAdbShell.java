package com.geely.modehelper;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Runs one shell command through the head unit's own root adbd on
 * 127.0.0.1:5555, with ADB's "shell,v2" service.
 *
 * Ported to Java from Capy's LocalAdbShell.kt by Timoteo Sousa
 * (github.com/Timoteohss/capy, Apache-2.0). See THIRD-PARTY-NOTICES.md. It is the only way to get a
 * process into the su domain from an app: su from system_app keeps
 * system_app (third_party/roadcast/docs/ANDROID_DEPLOYMENT.md), and only a
 * su-domain process may read the vehicle HAL's memory.
 *
 * shell v2, not the legacy "shell:" service: on this unit the legacy service
 * kills a detached child when the session closes. The adbd here asks for no
 * key (ro.adb.secure is ignored), so A_AUTH means something changed and is
 * reported as an error, never answered. */
final class LocalAdbShell {
    static final int A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_AUTH = 0x48545541;
    static final int VERSION = 0x01000001, MAX_PAYLOAD = 4096, HEADER = 24;
    static final int SHELL_STDOUT = 1, SHELL_STDERR = 2, SHELL_EXIT = 3;
    private static final int MAX_OUTPUT = 64 * 1024;

    private final String host;
    private final int port;

    LocalAdbShell() { this("127.0.0.1", 5555); }
    LocalAdbShell(String host, int port) { this.host = host; this.port = port; }

    /** True if something accepts TCP on the port. Cheap, no ADB traffic. */
    boolean portOpen(int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs {@code command} with sh -c and returns stdout+stderr, trimmed. */
    String execute(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            socket.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            write(out, A_CNXN, VERSION, MAX_PAYLOAD,
                  "host::drive-assist-can\0".getBytes(StandardCharsets.UTF_8));
            Packet hs = read(in);
            if (hs.command == A_AUTH) throw new IOException("local adb asks for a key");
            if (hs.command != A_CNXN) throw new IOException("unexpected adb handshake " + hs.command);
            if (!new String(hs.payload, StandardCharsets.UTF_8).contains("shell_v2"))
                throw new IOException("local adb has no shell_v2");

            int localId = 1;
            write(out, A_OPEN, localId, 0, shellService(command).getBytes(StandardCharsets.UTF_8));

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            ShellV2 decoder = new ShellV2(result);
            while (true) {
                Packet p = read(in);
                if (p.command == A_OKAY) continue;
                if (p.command == A_WRTE) {
                    decoder.accept(p.payload);
                    write(out, A_OKAY, localId, p.arg0, new byte[0]);
                } else if (p.command == A_CLSE) {
                    write(out, A_CLSE, localId, p.arg0, new byte[0]);
                    return new String(result.toByteArray(), StandardCharsets.UTF_8).trim();
                } else {
                    throw new IOException("unexpected adb packet " + p.command);
                }
            }
        }
    }

    static String shellService(String command) { return "shell,v2,raw:" + command + "\0"; }

    /** shell v2 frames: 1 byte channel, 4 byte little-endian length, payload. */
    static final class ShellV2 {
        private final ByteArrayOutputStream sink;
        private byte[] pending = new byte[0];
        ShellV2(ByteArrayOutputStream sink) { this.sink = sink; }

        void accept(byte[] bytes) throws IOException {
            byte[] all = new byte[pending.length + bytes.length];
            System.arraycopy(pending, 0, all, 0, pending.length);
            System.arraycopy(bytes, 0, all, pending.length, bytes.length);
            int off = 0;
            while (all.length - off >= 5) {
                int channel = all[off] & 0xff;
                int len = ByteBuffer.wrap(all, off + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (len < 0 || len > 64 * 1024) throw new IOException("bad shell_v2 length " + len);
                if (all.length - off < 5 + len) break;
                if ((channel == SHELL_STDOUT || channel == SHELL_STDERR) && sink.size() < MAX_OUTPUT) {
                    sink.write(all, off + 5, Math.min(len, MAX_OUTPUT - sink.size()));
                }
                off += 5 + len;
            }
            pending = new byte[all.length - off];
            System.arraycopy(all, off, pending, 0, pending.length);
        }
    }

    static final class Packet {
        final int command, arg0, arg1;
        final byte[] payload;
        Packet(int command, int arg0, int arg1, byte[] payload) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.payload = payload;
        }
    }

    static void write(DataOutputStream out, int command, int arg0, int arg1, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("adb payload too big");
        int sum = 0;
        for (byte b : payload) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(sum).putInt(~command);
        out.write(h.array());
        out.write(payload);
        out.flush();
    }

    static Packet read(DataInputStream in) throws IOException {
        byte[] hb = new byte[HEADER];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        int command = h.getInt(), arg0 = h.getInt(), arg1 = h.getInt(), len = h.getInt();
        h.getInt();                       // checksum: ADB 1.0.1 peers may send 0
        int magic = h.getInt();
        if (magic != ~command) throw new IOException("bad adb magic");
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad adb length " + len);
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Packet(command, arg0, arg1, payload);
    }
}
