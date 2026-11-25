package com.geely.drivemem.car;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** files/car-events.log: when the car was locked and unlocked.
 *
 * Why: on 2026-11-25 the head unit stayed awake for three hours after the
 * car was parked, and nothing was left to say whether the car was locked.
 * logcat keeps only minutes and is lost when the car cuts the power.
 *
 * One line per change of the whole car ("locked" = every door that reported
 * is locked), never per door and never per reading. 1 MB, then the file
 * starts over. */
public final class LockEventLog {
    private static final String TAG = "DriveMem";
    static final String FILE = "car-events.log";
    private static final long CAP_BYTES = 1024L * 1024;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static volatile boolean subscribed;

    private LockEventLog() {}

    /** Subscribes to the hub's door lock key; idempotent. */
    public static void ensureSubscribed(Context ctx) {
        if (subscribed) return;
        subscribed = true;
        final Context app = ctx.getApplicationContext();
        final Tracker t = new Tracker();
        EntityBus.subscribe("car.door_lock", (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK && reading.value instanceof int[]) {
                int[] av = (int[]) reading.value;
                String line = t.onDoor(av[0], av[1]);
                if (line != null) write(app, line);
            }
        });
    }

    /** Pure: per-door readings in, a line out when the car as a whole changes. */
    static final class Tracker {
        private final Map<Integer, Boolean> locked = new HashMap<>();
        private Boolean carLocked;

        /** "locked" / "unlocked" on a change, else null. The first reading
         * is written too ("locked (first reading)"), so the log says what
         * the state was when the app started. */
        String onDoor(int area, int value) {
            locked.put(area, value == 1);
            boolean all = true;
            for (boolean b : locked.values()) all &= b;
            if (carLocked != null && carLocked == all) return null;
            boolean first = carLocked == null;
            carLocked = all;
            return (all ? "locked" : "unlocked") + (first ? " (first reading)" : "");
        }
    }

    private static synchronized void write(Context c, String msg) {
        Log.i(TAG, "car: " + msg);
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            File f = new File(dir, FILE);
            if (f.exists() && f.length() > CAP_BYTES) f.delete();   // start over, never grow forever
            boolean fresh = !f.exists();
            try (FileWriter w = new FileWriter(f, true)) {
                if (fresh) w.write("wall\tevent\n");
                w.write(FMT.format(new Date()) + '\t' + msg + '\n');
            }
        } catch (Throwable t) {
            Log.w(TAG, "car: " + FILE + " write: " + t);
        }
    }
}
