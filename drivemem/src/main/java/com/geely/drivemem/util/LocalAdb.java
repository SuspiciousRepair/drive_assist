package com.geely.drivemem.util;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Talks the raw ADB wire protocol to the car's OWN adbd over loopback, the
 * same channel a workstation uses over USB/Wi-Fi — nothing more privileged
 * than that. This only goes anywhere because the unlock procedure in
 * GUIA-ADB-IHU629G.md already sets ro.secure=0 on this class of head unit
 * (see CLAUDE.md "Access: over adb we are root"): adbd asks for no RSA key
 * confirmation, so any local process can open this socket and get a root
 * shell, same as a cabled workstation would. Used for exactly one purpose:
 * granting SYSTEM_ALERT_WINDOW to ourselves on first install, so the overlay
 * button works without a person ever touching a settings screen, since a
 * stranger's car has no developer sitting there to run an adb command by
 * hand. Every install already implies this unlock already happened — you
 * cannot sideload Drive Assist onto a stock, locked unit in the first place. */
final class LocalAdb {
    private static final String TAG = "DriveMem";
    private static final String HOST = "127.0.0.1";
    private static final int[] PORTS = {5555, 65010};
    private static final int TIMEOUT_MS = 4000;

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;
    private static final int A_VERSION = 0x01000000;
    private static final int A_MAXDATA = 4096;

    /** Grants ourselves the "draw over other apps" permission via the car's
     * own local adbd, if it isn't already granted. Safe to call on every
     * MY_PACKAGE_REPLACED: a no-op once granted, since the OS keeps the grant
     * across app updates. Blocking (socket I/O) — call off the main thread. */
    static boolean grantOverlayPermission(Context ctx) {
        if (Settings.canDrawOverlays(ctx)) return true;
        String pkg = ctx.getPackageName();
        IOException last = null;
        for (int port : PORTS) {
            try {
                String out = runShell(port, "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
                Log.i(TAG, "localadb: overlay grant via :" + port + " -> " + out);
                return Settings.canDrawOverlays(ctx);
            } catch (IOException e) {
                last = e;
            }
        }
        Log.w(TAG, "localadb: overlay grant failed on all ports: " + last);
        return false;
    }

    private static String runShell(int port, String cmd) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            send(out, A_CNXN, A_VERSION, A_MAXDATA, ascii("host::drivemem"));
            if (readUntil(in, A_CNXN).command != A_CNXN) {
                throw new IOException("no CNXN from local adbd");
            }

            send(out, A_OPEN, 1, 0, ascii("shell:" + cmd));
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            int remoteId = 0;
            boolean haveRemoteId = false;
            while (true) {
                Message m = read(in);
                if (m.command == A_AUTH) {
                    throw new IOException("adbd wants key auth — not this build");
                } else if (m.command == A_OKAY) {
                    if (m.arg1 == 1) { remoteId = m.arg0; haveRemoteId = true; }
                } else if (m.command == A_WRTE) {
                    if (!haveRemoteId) { remoteId = m.arg0; haveRemoteId = true; }
                    reply.write(m.payload);
                    send(out, A_OKAY, 1, m.arg0, new byte[0]);
                } else if (m.command == A_CLSE) {
                    send(out, A_CLSE, 1, remoteId, new byte[0]);
                    return reply.toString("UTF-8").trim();
                } else {
                    throw new IOException("unexpected adb message 0x" + Integer.toHexString(m.command));
                }
            }
        }
    }

    private static Message readUntil(DataInputStream in, int wantCommand) throws IOException {
        Message m;
        do {
            m = read(in);
            if (m.command == A_AUTH) throw new IOException("adbd wants key auth — not this build");
        } while (m.command != wantCommand);
        return m;
    }

    private static byte[] ascii(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 1];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1)
            .putInt(payload.length).putInt(checksum(payload)).putInt(~command);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buf.getInt(), arg0 = buf.getInt(), arg1 = buf.getInt();
        int dataLen = buf.getInt(), dataChecksum = buf.getInt(), magic = buf.getInt();
        if (magic != ~command) throw new IOException("bad adb header magic");
        byte[] payload = new byte[dataLen];
        if (dataLen > 0) in.readFully(payload);
        if (checksum(payload) != dataChecksum) throw new IOException("bad adb payload checksum");
        return new Message(command, arg0, arg1, payload);
    }

    private static int checksum(byte[] b) {
        int sum = 0;
        for (byte value : b) sum += value & 0xFF;
        return sum;
    }

    private static final class Message {
        final int command, arg0, arg1;
        final byte[] payload;
        Message(int command, int arg0, int arg1, byte[] payload) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.payload = payload;
        }
    }

    private LocalAdb() {}
}
