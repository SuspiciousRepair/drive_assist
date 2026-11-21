package com.geely.drivemem.util;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** What the car is fitted with, as ModeHelper read it from the car's own
 * equipment row and wrote to `car.caps` (its CarCaps has the format and the
 * reason). ModeHelper decides; this only reads the answer, to hide the
 * dashcam settings on a car without the 360 cameras and to show the values
 * an owner can send in an issue. */
public final class CarCaps {
    public static final String FILE = "car.caps";

    public enum Cameras { YES, NO, UNKNOWN }

    public final Cameras cameras;
    /** The whole row as hex; empty when it was not read. */
    public final String config;
    public final String type, variant, avm, rvc, dvr;

    private CarCaps(Cameras cameras, String config, String type, String variant,
                    String avm, String rvc, String dvr) {
        this.cameras = cameras; this.config = config; this.type = type; this.variant = variant;
        this.avm = avm; this.rvc = rvc; this.dvr = dvr;
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
        return new CarCaps(cameras, or(m.get("config")), or(m.get("type")), or(m.get("variant")),
            or(m.get("avm")), or(m.get("rvc")), or(m.get("dvr")));
    }

    private static String or(String s) { return s == null ? "" : s; }

    /** True when ModeHelper keeps the dashcam off: it said the cameras are
     * not there, or that it could not read the row. No file at all is not a
     * refusal (an older ModeHelper records without asking). */
    public static boolean blocksDashcam(CarCaps c) {
        return c != null && c.cameras != Cameras.YES;
    }

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
