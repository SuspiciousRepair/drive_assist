package com.geely.drivemem.util;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** What the car is fitted with, as ModeHelper read it from the car's own
 * equipment row and wrote to `car.caps` (its CarCaps has the format and the
 * reason). ModeHelper decides; this only reads the answer, to hide the
 * dashcam settings on a car without the 360 cameras, to hide the AEB switch
 * on a car without AEB, and to show the values an owner can send in an
 * issue. */
public final class CarCaps {
    public static final String FILE = "car.caps";

    public enum Cameras { YES, NO, UNKNOWN }

    public final Cameras cameras;
    /** The whole row as hex; empty when it was not read. */
    public final String config;
    public final String type, variant, avm, rvc, dvr;
    /** Emergency braking, cabin ambient light, steering wheel on the right:
     * "1" or "0"; empty when an older ModeHelper wrote the file. */
    public final String aeb, ambient, rhd;

    private CarCaps(Cameras cameras, String config, java.util.Map<String, String> m) {
        this.cameras = cameras; this.config = config;
        type = or(m.get("type")); variant = or(m.get("variant"));
        avm = or(m.get("avm")); rvc = or(m.get("rvc")); dvr = or(m.get("dvr"));
        aeb = or(m.get("aeb")); ambient = or(m.get("ambient")); rhd = or(m.get("rhd"));
    }

    /** null when ModeHelper has written nothing: an older ModeHelper, or none. */
    public static CarCaps read(Context c) {
        return read(new File(c.getExternalFilesDir(null), FILE));
    }

    static CarCaps read(File f) {
        if (!f.isFile()) return null;
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1).trim());
            }
        } catch (Exception e) { return null; }
        String cam = m.get("cameras");
        if (cam == null) return null;
        Cameras cameras = cam.equals("yes") ? Cameras.YES : cam.equals("no") ? Cameras.NO : Cameras.UNKNOWN;
        return new CarCaps(cameras, or(m.get("config")), m);
    }

    private static String or(String s) { return s == null ? "" : s; }

    /** True when ModeHelper keeps the dashcam off: it said the cameras are
     * not there, or that it could not read the row. No file at all is not a
     * refusal (an older ModeHelper records without asking). */
    public static boolean blocksDashcam(CarCaps c) {
        return c != null && c.cameras != Cameras.YES;
    }

    /** True only when the car's row says it has no AEB. No file, an older
     * file without the value, or a row not read yet is not a "no": the AEB
     * switch stays, as before. */
    public static boolean lacksAeb(CarCaps c) {
        return c != null && c.aeb.equals("0");
    }

    /** A value for the readings line: "?" when the file does not have it. */
    public static String shown(String v) { return v.isEmpty() ? "?" : v; }

    /** The row in groups of eight characters, five groups to a line, so it
     * can be read back from a photo of the screen. */
    public static String grouped(String hex) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 8) {
            if (i > 0) b.append(i % 40 == 0 ? '\n' : ' ');
            b.append(hex, i, Math.min(hex.length(), i + 8));
        }
        return b.toString();
    }
}
