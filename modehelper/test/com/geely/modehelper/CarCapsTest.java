package com.geely.modehelper;

import java.io.File;
import java.nio.file.Files;

/** Small dependency-free test runner for {@link CarCaps}. The file text is a
 * contract with Drive Assist's CarCaps (CarCapsTest there reads the same
 * text), so it is pinned byte for byte. */
public final class CarCapsTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    // A 40-byte row with only the fields under test set.
    private static byte[] row(int type, int variant, int avm, int rvc, int dvr) {
        byte[] b = new byte[40];
        b[0] = (byte) type;
        b[1] = (byte) variant;
        b[27] = (byte) (avm | 0xA0);          // the other bits of the byte must not leak in
        b[22] = (byte) (rvc << 4 | 0x0F);
        b[23] = (byte) (dvr << 3 | 0x07);
        return b;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }

    public static void main(String[] args) throws Exception {
        // The fields come out of the right bits.
        CarCaps c = CarCaps.of(row(0x06, 0x23, 2, 0, 5), null);
        check(c != null, "a good row was refused");
        check(c.vehicleType() == 0x06 && c.variant() == 0x23, "type/variant");
        check(c.avm() == 2 && c.rvc() == 0 && c.dvr() == 5, "avm " + c.avm() + " rvc " + c.rvc() + " dvr " + c.dvr());
        check(c.cameras() == CarCaps.Cameras.YES, "avm 2 is the fitted value");

        // Every other value of the camera field keeps the dashcam off.
        for (int avm : new int[] {0, 1, 3}) {
            CarCaps n = CarCaps.of(row(0x06, 0x22, avm, 2, 0), null);
            check(n.cameras() == CarCaps.Cameras.NO, "avm " + avm + " must not turn the dashcam on");
        }

        // Nothing read: unknown, never a guess.
        check(CarCaps.of(null, null) == null, "nothing read must be null");
        check(CarCaps.of(new byte[40], "") == null, "all zeros is no answer");
        check(CarCaps.of(new byte[] {6, 0x23}, null) == null, "a short row has no camera field");
        check(CarCaps.of(null, "zz") == null && CarCaps.of(null, "123") == null, "bad hex");
        check(CarCaps.cameras(null) == CarCaps.Cameras.UNKNOWN, "null is unknown");

        // The car property wins; zeros there fall back to the system property.
        byte[] yes = row(0x06, 0x23, 2, 0, 0), no = row(0x06, 0x22, 3, 2, 0);
        check(CarCaps.of(yes, hex(no)).cameras() == CarCaps.Cameras.YES, "car property first");
        check(CarCaps.of(new byte[40], hex(no)).cameras() == CarCaps.Cameras.NO, "fallback to the system property");
        check(CarCaps.of(null, hex(yes).toUpperCase()).cameras() == CarCaps.Cameras.YES, "upper-case hex");

        // AEB, ambient light and steering side: one bit each, and the other
        // bits of the same byte must not leak in.
        byte[] eq = new byte[40];
        eq[0] = 0x06; eq[27] = 2;
        eq[37] = (byte) 0x80; eq[30] = 0x01; eq[13] = 0x20;
        CarCaps e = CarCaps.of(eq, null);
        check(e.aeb() == 1 && e.ambient() == 1 && e.rhd() == 1, "aeb/ambient/rhd set");
        check(!CarCaps.lacksAeb(e), "a car with AEB keeps its AEB switch");
        eq[37] = 0x7F; eq[30] = (byte) 0xFE; eq[13] = (byte) 0xDF;
        e = CarCaps.of(eq, null);
        check(e.aeb() == 0 && e.ambient() == 0 && e.rhd() == 0, "aeb/ambient/rhd clear");
        check(CarCaps.lacksAeb(e), "aeb 0 is a car without AEB");
        // Not read, or a row too short to have the field, is not "no AEB".
        check(!CarCaps.lacksAeb(null), "an unread row must not hide AEB");
        byte[] shortRow = new byte[28];
        shortRow[0] = 0x06; shortRow[27] = 2;
        CarCaps sh = CarCaps.of(shortRow, null);
        check(sh.cameras() == CarCaps.Cameras.YES && sh.aeb() == -1, "short row: cameras yes, aeb unknown");
        check(!CarCaps.lacksAeb(sh), "a short row must not hide AEB");

        // The file, byte for byte.
        byte[] plain = new byte[40];
        plain[0] = 0x06; plain[1] = 0x23; plain[27] = 2;
        File d = Files.createTempDirectory("caps").toFile();
        CarCaps.write(d, CarCaps.of(plain, null));
        String s = new String(Files.readAllBytes(new File(d, "car.caps").toPath()), "UTF-8");
        String zeros = new String(new char[25]).replace("\0", "00");
        check(s.equals("cameras=yes\nconfig=0623" + zeros + "02" + zeros.substring(0, 24)
            + "\ntype=06\nvariant=23\navm=2\nrvc=0\ndvr=0\naeb=0\nambient=0\nrhd=0\n"), "format: " + s);
        check(!new File(d, "car.caps.tmp").exists(), "temp file left behind");

        CarCaps.write(d, null);
        s = new String(Files.readAllBytes(new File(d, "car.caps").toPath()), "UTF-8");
        check(s.equals("cameras=unknown\n"), "unknown format: " + s);

        System.out.println("CarCapsTest OK");
    }
}
