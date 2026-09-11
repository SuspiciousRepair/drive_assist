package com.geely.drivemem.car;

import com.geely.drivemem.ui.TelemetryActivity;
import com.geely.drivemem.util.Modes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Closed registry of writable car properties by name. Each entity delegates
 * to an existing CarAccess method with validation (Range clamping or ValueSet
 * constraint). The registry is compile-time static; unknown keys are rejected. */
public final class CarDataHub {

    public interface Read { Object read(CarAccess car); }
    public interface Write { boolean write(CarAccess car, Object value); }

    /** Numeric range validator: out-of-range requests are clamped to min/max,
     * never rejected, so sliders remain in sync with applied values. */
    public static final class Range {
        public final double min, max;
        public Range(double min, double max) { this.min = min; this.max = max; }
        public Object clamp(Object requested) {
            double d = ((Number) requested).doubleValue();
            if (d < min) d = min;
            if (d > max) d = max;
            return d;
        }
    }

    /** Closed set validator: resolve() accepts a name or an already-composed
     * raw int, returning it only if in the set. Unknown values return null. */
    public static final class ValueSet {
        private final Map<String, Integer> byName;
        private ValueSet(Map<String, Integer> byName) { this.byName = byName; }

        public static ValueSet of(Object... nameValuePairs) {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < nameValuePairs.length; i += 2)
                m.put((String) nameValuePairs[i], (Integer) nameValuePairs[i + 1]);
            return new ValueSet(m);
        }

        public Integer resolve(Object requested) {
            if (requested instanceof Integer) return byName.containsValue(requested) ? (Integer) requested : null;
            return byName.get(String.valueOf(requested));
        }
    }

    /** A writable/readable car property with optional read and write accessors
     * and optional validation (Range or ValueSet). */
    public static final class Entity {
        public final String key;
        public final Read read;     // null if write-only
        public final Write write;   // null if read-only
        public final Range range;   // mutually exclusive with valueSet
        public final ValueSet valueSet;

        private Entity(String key, Read read, Write write, Range range, ValueSet valueSet) {
            this.key = key; this.read = read; this.write = write;
            this.range = range; this.valueSet = valueSet;
        }
    }

    /** Result of a write operation: indicates success or failure with the
     * applied value (after clamping/resolving) or an error message. */
    public static final class WriteResult {
        public final boolean applied;
        public final Object value;     // the value actually sent, after clamp/resolve
        public final String error;     // null when applied

        private WriteResult(boolean applied, Object value, String error) {
            this.applied = applied; this.value = value; this.error = error;
        }
        private static WriteResult ok(Object value) { return new WriteResult(true, value, null); }
        private static WriteResult rejected(String error) { return new WriteResult(false, null, error); }
    }

    /** One of the 7 durations the car's native parking-mode menu offers,
     * beyond plain off/on: the raw low byte the ECU expects, the machine key
     * used to write it (CarDataHub/MQTT commands), and the human label used
     * to display it (MQTT/HA state, Telemetry.parkTimerLabel). Single source
     * of truth for both, so they cannot drift apart the way they used to
     * when each kept its own copy of the same 7 codes. */
    private static final class ParkDuration {
        final int code; final String key, label;
        ParkDuration(int code, String key, String label) { this.code = code; this.key = key; this.label = label; }
    }
    private static final ParkDuration[] PARK_DURATIONS = {
        new ParkDuration(0x02, "30min", "30 min"),
        new ParkDuration(0x04, "1h",    "1 h"),
        new ParkDuration(0x06, "2h",    "2 h"),
        new ParkDuration(0x08, "3h",    "3 h"),
        new ParkDuration(0x0A, "4h",    "4 h"),
        new ParkDuration(0x0C, "5h",    "5 h"),
        new ParkDuration(0x13, "unlimited", "Ilimitado"),
    };

    /** Decodes parking mode duration from the low byte to a human-readable
     * label (e.g., "1 h", "Ilimitado"). */
    public static String parkLabel(int raw) {
        int code = raw & 0xFF;
        for (ParkDuration d : PARK_DURATIONS) if (d.code == code) return d.label;
        return String.format(java.util.Locale.US, "cod 0x%02X", code);
    }

    /** Converts a parking mode label back to its low-byte code. Default
     * (null or unrecognized) is 0x13, "Ilimitado". */
    public static int parkCode(String label) {
        if (label != null) {
            String trimmed = label.trim();
            for (ParkDuration d : PARK_DURATIONS) if (d.label.equals(trimmed)) return d.code;
        }
        return 0x13;
    }

    private static Object[] parkValueSetPairs() {
        Object[] pairs = new Object[2 + PARK_DURATIONS.length * 2];
        pairs[0] = "off"; pairs[1] = 0;
        for (int i = 0; i < PARK_DURATIONS.length; i++) {
            pairs[2 + i * 2] = PARK_DURATIONS[i].key;
            pairs[2 + i * 2 + 1] = CarAccess.PARK_ON_BASE | PARK_DURATIONS[i].code;
        }
        return pairs;
    }

    private static final Map<String, Entity> REGISTRY = buildRegistry();

    /** Returns the Entity for a key, or null if unknown. */
    public static Entity get(String key) { return REGISTRY.get(key); }

    /** Reads the current value of a property by key, or null if the key is
     * unknown or read-only. */
    public static Object read(CarAccess car, String key) {
        Entity e = REGISTRY.get(key);
        return (e == null || e.read == null) ? null : e.read.read(car);
    }

    /** Applies a write: validates the key, clamps/resolves the value,
     * and delegates to the entity's write method. Returns a WriteResult
     * indicating success (with the clamped/resolved value) or failure. */
    public static WriteResult apply(CarAccess car, String key, Object requested) {
        Entity e = REGISTRY.get(key);
        if (e == null) return WriteResult.rejected("unknown entity: " + key);
        if (e.write == null) return WriteResult.rejected("read-only entity: " + key);

        Object toWrite;
        if (e.range != null) {
            toWrite = e.range.clamp(requested);
        } else if (e.valueSet != null) {
            Integer resolved = e.valueSet.resolve(requested);
            if (resolved == null) return WriteResult.rejected("value not in closed set: " + requested);
            toWrite = resolved;
        } else {
            toWrite = requested;
        }

        boolean ok = e.write.write(car, toWrite);
        return ok ? WriteResult.ok(toWrite) : WriteResult.rejected("write failed");
    }

    private static Map<String, Entity> buildRegistry() {
        Map<String, Entity> m = new LinkedHashMap<>();

        m.put("drive_mode", new Entity("drive_mode",
            car -> car.readDrive(),
            (car, v) -> car.writeDrive((Integer) v),
            null,
            ValueSet.of("eco", Modes.DRIVE_ECO, "comfort", Modes.DRIVE_COMFORT, "sport", Modes.DRIVE_SPORT)));

        // Not in the original six — added because TelemetryActivity's Config
        // picker writes drive AND regen from the same call site, and
        // writeRegen has exactly the same "no clamp at all today" gap as
        // writeDrive.
        m.put("regen_mode", new Entity("regen_mode",
            car -> car.readRegen(),
            (car, v) -> car.writeRegen((Integer) v),
            null,
            ValueSet.of("low", Modes.REGEN_LOW, "mid", Modes.REGEN_MID, "high", Modes.REGEN_HIGH)));

        // All 8 states the car's native menu offers (off + 7 durations, see
        // PARK_DURATIONS above). Callers compose the raw int themselves
        // (PARK_ON_BASE | code); this validates that only known codes reach
        // setParkMode.
        m.put("park_mode", new Entity("park_mode",
            car -> car.readParkMode(),
            (car, v) -> car.setParkMode((Integer) v),
            null,
            ValueSet.of(parkValueSetPairs())));

        // Returns success immediately after write is sent, without waiting for
        // ECU acknowledgement (same pattern as all other entities). The ECU's
        // internal area-sweep-and-remember behavior runs unchanged; confirmation
        // is left to independent reads. Earlier versions attempted immediate
        // read-back verification but found it unreliable due to ECU response latency.
        m.put("ambient_color", new Entity("ambient_color",
            car -> car.readAmbientColor(),
            (car, v) -> { car.setAmbientColor((Integer) v); return true; },
            null, null));

        m.put("ambient_brightness", new Entity("ambient_brightness",
            car -> car.readAmbientBrightness(),
            (car, v) -> car.setAmbientBrightness(((Number) v).intValue()),
            new Range(0, CarAccess.AMBIENT_BRIGHT_MAX), null));

        m.put("charging", new Entity("charging",
            car -> car.readCharging(),
            (car, v) -> car.setCharging((Integer) v),
            null,
            ValueSet.of("on", CarAccess.CHARGE_ON, "off", CarAccess.CHARGE_OFF)));

        // No dedicated getter exists for the limit itself (CarAccess only
        // reads the CURRENT FLOWING amperage, a different property) — read
        // the raw property directly, same area the write sweeps first.
        m.put("charge_current_limit", new Entity("charge_current_limit",
            car -> car.readIntRaw(CarAccess.CHARGE_CURRENT_LIMIT, 0),
            (car, v) -> car.setChargeCurrentLimit(((Number) v).intValue()) > 0,
            new Range(CarAccess.CHARGE_MIN_A, CarAccess.CHARGE_MAX_A), null));

        return Collections.unmodifiableMap(m);
    }

    private CarDataHub() {}
}
