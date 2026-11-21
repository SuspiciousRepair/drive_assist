package com.geely.modehelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** What this car is fitted with, from the car's own equipment bytes.
 *
 * The head unit holds one row of bytes that says which options the factory
 * built into this car. The car's own apps read it to show or hide their
 * screens. The dashcam needs the 360 cameras, so it asks the same row and
 * stays off on a car that does not report them.
 *
 * Two ways to the same bytes, in this order: the car property
 * {@link #PROP}, then the system property {@link #SYS_PROP} (the same bytes
 * as hex text), which the car itself falls back to when the first one
 * answers with zeros.
 *
 * {@link #FILE} carries the result to Drive Assist, a different process:
 * plain `key=value` lines, replaced by rename like recorder.state.
 *
 *   cameras=yes|no|unknown
 *   config=<the whole row, hex>      (absent when unknown)
 *   type=06  variant=23  avm=2  rvc=0  dvr=0   (one per line)
 *
 * The values are shown on the System page so an owner can send them in an
 * issue: only one car has confirmed what a value means so far. */
final class CarCaps {
    /** VENDOR_OFF_LINE_STATUS: the equipment bytes. */
    static final int PROP = 561025077;
    static final String SYS_PROP = "sys.ecarx.local_config";
    static final String FILE = "car.caps";

    /** The value of the 360-camera field on the car where the dashcam is
     * known to work. Every other value keeps the dashcam off. */
    static final int AVM_FITTED = 2;

    // The last byte read below, plus one.
    private static final int MIN_BYTES = 28;

    enum Cameras { YES, NO, UNKNOWN }

    private final byte[] bytes;

    private CarCaps(byte[] bytes) { this.bytes = bytes; }

    /** The row from the car property, else from the system property's hex
     * text. null when neither is usable: nothing is known yet. */
    static CarCaps of(byte[] fromCar, String fromProp) {
        if (usable(fromCar)) return new CarCaps(fromCar.clone());
        byte[] p = parseHex(fromProp);
        return usable(p) ? new CarCaps(p) : null;
    }

    // All zeros is what the car property gives before it is filled; the car's
    // own code treats that as "no answer" too.
    private static boolean usable(byte[] b) {
        if (b == null || b.length < MIN_BYTES) return false;
        for (byte x : b) if (x != 0) return true;
        return false;
    }

    static byte[] parseHex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    // Same arithmetic as the car's own decoder: `length` bits of one byte,
    // starting at `startBit` from the low end.
    private int bits(int index, int startBit, int length) {
        return ((bytes[index] & 0xFF) >> startBit) & ((1 << length) - 1);
    }

    int vehicleType() { return bytes[0] & 0xFF; }
    int variant()     { return bytes[1] & 0xFF; }
    /** 360 cameras (around view monitor). */
    int avm()         { return bits(27, 0, 2); }
    /** Rear camera alone. */
    int rvc()         { return bits(22, 4, 2); }
    /** Factory video recorder. */
    int dvr()         { return bits(23, 3, 3); }

    Cameras cameras() { return avm() == AVM_FITTED ? Cameras.YES : Cameras.NO; }

    /** The answer for a row that may not have been read yet. */
    static Cameras cameras(CarCaps c) { return c == null ? Cameras.UNKNOWN : c.cameras(); }

    String hex() {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) b.append(String.format("%02X", x & 0xFF));
        return b.toString();
    }

    /** The text of {@link #FILE}. */
    static String body(CarCaps c) {
        if (c == null) return "cameras=unknown\n";
        return "cameras=" + (c.cameras() == Cameras.YES ? "yes" : "no")
            + "\nconfig=" + c.hex()
            + "\ntype=" + String.format("%02X", c.vehicleType())
            + "\nvariant=" + String.format("%02X", c.variant())
            + "\navm=" + c.avm()
            + "\nrvc=" + c.rvc()
            + "\ndvr=" + c.dvr() + "\n";
    }

    static void write(File dir, CarCaps c) {
        File tmp = new File(dir, FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body(c).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(new File(dir, FILE));
    }
}
