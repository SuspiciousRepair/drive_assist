package com.geely.drivemem.util;

import com.geely.drivemem.car.CarAccess;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assisted property discovery by snapshot diffing.
 *
 * Captures snapshots before and after user-initiated car control changes, then
 * compares them to identify which properties changed. Useful for finding new
 * function IDs without needing privileged READ_LOGS permission.
 */
public class Discovery {
    static final String TAG = CarAccess.TAG;

    // Snapshot A kept between the two broadcasts (the service keeps the process alive).
    public static Snapshot pendingA;

    /** A snapshot of all readable car properties at a point in time. */
    public static class Snapshot {
        final Map<String, Object> values = new LinkedHashMap<>();
        final long when = System.currentTimeMillis();
        int propsSeen, readOk, readFail;
    }

    // Reads everything getPropertyList() declares. If the list comes back empty
    // (the plan's premise fails on this unit), it falls back to the list of
    // known ids — that way the cable test works either way.
    public static Snapshot snapshot(CarAccess car) {
        Snapshot s = new Snapshot();
        List<int[]> targets = new ArrayList<>();   // {propId, area}

        int declared = 0, undeclared = 0;
        try {
            java.util.List<android.car.hardware.CarPropertyConfig> all = car.propertyList();
            if (all != null && !all.isEmpty()) {
                s.propsSeen = all.size();
                for (android.car.hardware.CarPropertyConfig cfg : all) {
                    int id = cfg.getPropertyId();
                    int[] areas = cfg.getAreaIds();
                    // A CONFIG THAT DECLARES NO AREAS IS NOT A GLOBAL PROPERTY.
                    // This used to read it at area 0 and move on, and area 0 is
                    // exactly where a per-area property answers null: DOOR_POS@0
                    // is null while DOOR_POS@1 is 2. So every such property was
                    // swept at the one area guaranteed to tell us nothing, which
                    // is the likeliest source of fail=1631 out of 1810 targets.
                    // Probing the standard area bits costs a handful of reads
                    // each and is bounded, because it only applies to configs
                    // that told us nothing.
                    if (areas == null || areas.length == 0) {
                        undeclared++;
                        for (int a : PROBE_AREAS) targets.add(new int[]{id, a});
                        continue;
                    }
                    declared++;
                    for (int a : areas) targets.add(new int[]{id, a});
                }
            }
        } catch (Throwable t) { Log.w(TAG, "disc: getPropertyList failed: " + t); }
        Log.i(TAG, "disc: configs with areas=" + declared + " without=" + undeclared);

        if (targets.isEmpty()) {
            Log.w(TAG, "disc: NO list from the VHAL — using known ids (the plan's premise FAILS)");
            for (int id : KNOWN) for (int a : new int[]{0, 1, 5, 75}) targets.add(new int[]{id, a});
        }

        for (int[] t : targets) {
            Object v = car.readGeneric(t[0], t[1]);
            if (v == null) { s.readFail++; continue; }
            s.readOk++;
            s.values.put(t[0] + "@" + t[1], v);
        }
        Log.i(TAG, "disc: snapshot props=" + s.propsSeen + " targets=" + targets.size()
                 + " ok=" + s.readOk + " fail=" + s.readFail);
        return s;
    }

    // The standard area bits, for configs that declare none. Covers the door
    // (1,4,16,64), window (16,64,256,1024) and seat (1,2,4,8,16,32,64) spaces,
    // which overlap, plus 0 for genuinely global properties and the two HVAC
    // masks this car actually uses (5 and 75, from Modes.FAN_AREAS).
    static final int[] PROBE_AREAS = {0, 1, 2, 4, 5, 8, 16, 32, 64, 75, 256, 1024};

    // ids Drive Assist already knows — only as a safety net
    private static final int[] KNOWN = {
        605028608, 605029888, 605291008, 605290752,   // charging
        557885165, 289407492, 289407752, 291504647,   // battery/odo/range/speed
        289408001, 354419984, 557884279,              // gear/ac/outside temp
        537528576, 704708864,                          // ambient light
    };

    // Compares two snapshots and logs what changed. This is the heart of the
    // mechanism: if it finds the right property on its own, the DiscoveryActivity
    // holds up; if it brings back 40 candidates, the screen will need ranking.
    // BYTE ARRAYS WERE INVISIBLE. String.valueOf(byte[]) is "[B@" plus an
    // identity hash, which is a NEW value on every read — so every byte-valued
    // property showed up as changed in every diff ever run here (two of them have
    // been flickering in the noise for weeks), and their actual CONTENTS were
    // never compared to anything. Anything the car packs into a bitfield —
    // per-door state is exactly that shape — could not be seen by this tool.
    static String render(Object v) {
        if (!(v instanceof byte[])) return String.valueOf(v);
        byte[] b = (byte[]) v;
        StringBuilder h = new StringBuilder(b.length * 2);
        for (byte x : b) h.append(String.format("%02x", x));
        return h.toString();
    }

    public static void diff(Snapshot a, Snapshot b, String label) {
        int changed = 0, appeared = 0, vanished = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : b.values.entrySet()) {
            Object before = a.values.get(e.getKey());
            Object after = e.getValue();
            if (before == null) { appeared++; sb.append("\n  + ").append(e.getKey()).append(" = ").append(render(after)); continue; }
            String bs = render(before), as = render(after);
            if (!bs.equals(as)) {
                changed++;
                sb.append("\n  ~ ").append(e.getKey()).append(": ").append(bs).append(" -> ").append(as);
            }
        }
        // Named, not just counted: a property that stops answering is a fact
        // about the car, and a bare "vanished=2" is unactionable.
        for (String k : a.values.keySet()) {
            if (!b.values.containsKey(k)) { vanished++; sb.append("\n  - ").append(k); }
        }

        Log.i(TAG, "disc: DIFF [" + label + "] changed=" + changed
                 + " appeared=" + appeared + " vanished=" + vanished + sb);
    }
}
