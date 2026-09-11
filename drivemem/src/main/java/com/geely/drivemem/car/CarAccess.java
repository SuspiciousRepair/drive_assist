package com.geely.drivemem.car;

import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.util.Modes;

import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Wrapper for vehicle property access via Android Automotive CarPropertyManager.
 * Manages connection lifecycle and provides read/write/watch methods for properties.
 */
public class CarAccess {
    public static final String TAG = "DriveMem";

    private volatile Car car;
    private volatile CarPropertyManager cpm;   // volatile: read/written on different threads
    private volatile int failStreak = 0;       // reads failing in a row => dead binder

    public boolean connect(Context ctx) {
        // always release the previous connection — otherwise every attempt leaks a Car
        disconnect();
        try {
            car = Car.createCar(ctx, new android.content.ServiceConnection() {
                public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) {}
                public void onServiceDisconnected(android.content.ComponentName n) {
                    // Unit suspends and binder dies; invalidate or isReady() lies.
                    Log.i(TAG, "car disconnected — invalidating cpm");
                    cpm = null;
                }
            });
            car.connect();
            for (int i = 0; i < 60 && !car.isConnected(); i++) {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
            if (!car.isConnected()) { Log.w(TAG, "car did not connect"); return false; }
            cpm = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            failStreak = 0;
            return cpm != null;
        } catch (Throwable t) {
            Log.w(TAG, "connect failed: " + t, t);
            return false;
        }
    }

    public void disconnect() {
        try { if (car != null) car.disconnect(); } catch (Throwable ignored) {}
        car = null; cpm = null;
    }

    public boolean isReady() { return cpm != null; }

    /** Registers a push watch (event-driven, not polled). */
    public boolean watch(int prop, android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback cb) {
        if (cpm == null) return false;
        try {
            return cpm.registerCallback(cb, prop,
                android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE);
        } catch (Throwable t) { Log.w(TAG, "watch " + prop + ": " + t); return false; }
    }

    public void unwatch(android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback cb) {
        try { if (cpm != null) cpm.unregisterCallback(cb); } catch (Throwable ignored) {}
    }

    // Door and window position properties. 1 = open, 2 = closed.
    public static final int DOOR_POS   = 373295872;   // 0x16400B00
    public static final int WINDOW_POS = 322964416;   // 0x13400BC0
    public static final int DOOR_OPEN = 1, DOOR_CLOSED = 2;

    // called by the loops: reads failing in a row == dead connection
    void noteFailure() { if (++failStreak >= 5) { Log.i(TAG, "5 failures in a row — dropping the connection"); disconnect(); } }
    void noteSuccess() { failStreak = 0; }

    public Integer readGear() {
        try { return cpm.getIntProperty(Modes.GEAR_SELECTION, 0); }
        catch (Throwable t) { return null; }
    }

    // vehicle speed in km/h (for the wave animation). null on failure.
    public static final int PERF_VEHICLE_SPEED = 291504647;
    public Float readSpeed() {
        try { return cpm.getFloatProperty(PERF_VEHICLE_SPEED, 0); }
        catch (Throwable t) { return null; }
    }

    // Parking Mode (timer that keeps the vehicle powered after locking it).
    // Prop found by diffing snapshots: 0 = off; otherwise 0x20180000 | code,
    // where the low byte is the duration (0x02=30min, 0x04=1h, 0x06=2h,
    // 0x13=unlimited). Returns the RAW int (null on failure); interpreting it is
    // Telemetry's job.
    public static final int PARK_MODE = 557885463;
    public Integer readParkMode() {
        try { return cpm.getIntProperty(PARK_MODE, 0); }
        catch (Throwable t) { return null; }
    }

    // raw int write (debug / write experiments). returns whether it did not throw.
    public boolean setIntRaw(int prop, int area, int val) {
        try { cpm.setIntProperty(prop, area, val); Log.i(TAG, "setInt " + prop + "@" + area + " = " + val); return true; }
        catch (Throwable t) { Log.w(TAG, "setInt " + prop + " failed: " + t); return false; }
    }

    public Integer readIntRaw(int prop, int area) {
        try { return cpm.getIntProperty(prop, area); }
        catch (Throwable t) { return null; }
    }

    // raw FLOAT write/read (debug / write experiments) — the int-only pair
    // above silently truncates a decimal write to a whole number instead of
    // failing, which is exactly the kind of quiet data loss the method
    // warns about (field-catalog.md step 6): 557884450 (cruise ARMED/ACTIVE)
    // is a float property and could not be write-tested at all without this.
    public boolean setFloatRaw(int prop, int area, float val) {
        try { cpm.setFloatProperty(prop, area, val); Log.i(TAG, "setFloat " + prop + "@" + area + " = " + val); return true; }
        catch (Throwable t) { Log.w(TAG, "setFloat " + prop + " failed: " + t); return false; }
    }

    public Float readFloatRaw(int prop, int area) {
        try { return cpm.getFloatProperty(prop, area); }
        catch (Throwable t) { return null; }
    }

    // arms/disarms Parking Mode. value=0 turns it off; otherwise PARK_ON_BASE|code.
    // Base = 0x201B0100 (checked: |0x04 = 538640644 = "1h", which WRITEPROP proved).
    public static final int PARK_ON_BASE = 0x201B0100;
    public boolean setParkMode(int value) { return setIntRaw(PARK_MODE, 0, value); }

    public Integer readDrive() {
        for (int a : Modes.DRIVE_AREAS) {
            try { return cpm.getIntProperty(Modes.PROP_DRIVE, a); } catch (Throwable ignored) {}
        }
        return null;
    }

    public Integer readRegen() {
        for (int a : Modes.REGEN_AREAS) {
            try { return cpm.getIntProperty(Modes.PROP_REGEN, a); } catch (Throwable ignored) {}
        }
        return null;
    }

    // reads the temperature of a specific area (float C); NaN on failure
    public float readHvacTempArea(int area) {
        try { return cpm.getFloatProperty(Modes.HVAC_TEMP, area); }
        catch (Throwable t) { return Float.NaN; }
    }

    // current setpoint of the driver zone (area 1); NaN on failure
    public float readSetpoint() { return readHvacTempArea(1); }

    // moves the setpoint by a delta (in C, steps of 0.5). applies it to both zones.
    // returns the new target, or NaN if the current one could not be read.
    public float nudgeSetpoint(float deltaC) {
        float cur = readHvacTempArea(1);
        if (Float.isNaN(cur)) return Float.NaN;
        float target = Math.round((cur + deltaC) * 2f) / 2f; // snap to 0.5
        if (target < 17f) target = 17f; if (target > 32f) target = 32f;
        writeHvacTemp(target, 1);
        writeHvacTemp(target, 4); // ghost zone, harmless
        return target;
    }

    // fan: reads the current level (1..7) or -1
    public int readFan() {
        for (int a : Modes.FAN_AREAS) {
            try { return cpm.getIntProperty(Modes.HVAC_FAN, a); } catch (Throwable ignored) {}
        }
        return -1;
    }

    // moves the fan by a delta (whole steps), clamped 1..7. returns the new level or -1
    public int nudgeFan(int delta) {
        int cur = readFan();
        if (cur < 0) return -1;
        int target = Math.max(Modes.FAN_MIN, Math.min(Modes.FAN_MAX, cur + delta));
        for (int a : Modes.FAN_AREAS) {
            try { cpm.setIntProperty(Modes.HVAC_FAN, a, target); Log.i(TAG, "fan set area=" + a + " = " + target); return target; }
            catch (Throwable ignored) {}
        }
        return -1;
    }

    // --- charging ---
    public static final int CHARGE_CURRENT_LIMIT = 605029888; // functionId, value = Amperes
    public static final int CHARGE_WORK_CURRENT  = 605291008; // A flowing (read)
    public static final int CHARGE_WORK_VOLTAGE  = 605290752; // V (read)
    public static final int CHARGE_MIN_A = 5, CHARGE_MAX_A = 32;

    // writes the charge current limit, CLAMPED to 5..32A. returns the applied value or -1
    public int setChargeCurrentLimit(int amps) {
        // CLAMP (does not reject): an out-of-range command becomes the nearest
        // valid end, and the applied value is echoed back — that way the HA
        // slider never falls out of sync
        if (amps < CHARGE_MIN_A) { Log.i(TAG, "charge: " + amps + "A -> clamp " + CHARGE_MIN_A); amps = CHARGE_MIN_A; }
        if (amps > CHARGE_MAX_A) { Log.i(TAG, "charge: " + amps + "A -> clamp " + CHARGE_MAX_A); amps = CHARGE_MAX_A; }
        for (int a : new int[]{0, 1}) {
            try { cpm.setIntProperty(CHARGE_CURRENT_LIMIT, a, amps); Log.i(TAG, "charge limit=" + amps + "A (area " + a + ")"); return amps; }
            catch (Throwable ignored) {}
        }
        return -1;
    }

    // --- cabin ambient light ---
    // value = plain 0xRRGGBB integer (captured from Geely's MyCar app)
    public static final int AMBIENT_COLOR = 537528576; // SETTING_FUNC_AMBIENCE_LIGHT_COLOR_SET
    public static final int AMBIENT_AREA = 5;

    // A WRITE THAT DOES NOT THROW HAS NOT NECESSARILY DONE ANYTHING. setIntProperty
    // hands the value to the VHAL and returns; whether the ECU acts on it is not in
    // the return value. That is how "the colour is not changing" got reported as
    // success while the strip did not move — and it is why this VERIFIES, by
    // reading the property back and comparing. It is also what a whole colour
    // CALIBRATION feature got built on top of: the panel and the cabin looked
    // different, the obvious culprit was the LEDs, and it was this all along.
    //
    // It also sweeps areas, the way brightness always has. Brightness needed
    // {5, 0, 1} to work on this unit, which is already evidence that area 5 is not
    // universally the right zone here, and colour had no such fallback. The sweep
    // stops at the first area whose read-back agrees, and that area is REMEMBERED
    // and tried first from then on — so a later restore writes to the same place,
    // and an area that was probed but did not verify never changed anything to undo.
    private volatile int colorArea = AMBIENT_AREA;
    // The last thing the strip actually did, in words — for the log, and for
    // whoever next has to work out whether a write landed.
    public volatile String ambientNote = "";

    public boolean setAmbientColor(int rgb) {
        int v = rgb & 0xFFFFFF;
        int[] order = (colorArea == AMBIENT_AREA)
            ? new int[]{ AMBIENT_AREA, 0, 1 }
            : new int[]{ colorArea, AMBIENT_AREA, 0, 1 };
        boolean accepted = false;
        for (int a : order) {
            try { cpm.setIntProperty(AMBIENT_COLOR, a, v); }
            catch (Throwable t) { continue; }
            accepted = true;
            Integer back = readAmbientColorArea(a);
            if (back == null) {                       // cannot check; take the write
                colorArea = a;
                ambientNote = String.format("#%06X sent to area %d (no read-back)", v, a);
                Log.i(TAG, "ambient light: " + ambientNote);
                return true;
            }
            if ((back & 0xFFFFFF) == v) {
                colorArea = a;
                ambientNote = String.format("#%06X confirmed on area %d", v, a);
                Log.i(TAG, "ambient light: " + ambientNote);
                return true;
            }
            ambientNote = String.format("area %d took #%06X but still reads #%06X",
                                        a, v, back & 0xFFFFFF);
            Log.w(TAG, "ambient light: " + ambientNote);
        }
        if (!accepted) {
            ambientNote = String.format("no area accepted #%06X", v);
            Log.w(TAG, "ambient light: " + ambientNote);
        }
        return false;
    }

    private Integer readAmbientColorArea(int area) {
        try { return cpm.getIntProperty(AMBIENT_COLOR, area); }
        catch (Throwable t) { return null; }
    }

    // colorArea is only ever DISCOVERED by a WRITE Drive Assist itself made
    // (setAmbientColor's own read-back sweep, above) — a session where the
    // light was set some other way (the OEM app, a physical control, or
    // just whatever it booted with) never earns that discovery, and a
    // single fixed-area read then fails forever even though the light
    // genuinely has a colour right now. Sweep the same candidates
    // setAmbientColor tries, same as readAmbientBrightness already does
    // below, and remember whichever one answers so read and write agree
    // on it from here on.
    public Integer readAmbientColor() {
        Integer v = readAmbientColorArea(colorArea);
        if (v != null) return v;
        for (int a : new int[]{AMBIENT_AREA, 0, 1}) {
            if (a == colorArea) continue;
            v = readAmbientColorArea(a);
            if (v != null) { colorArea = a; return v; }
        }
        return null;
    }

    // BRIGHTNESS of the ambient light: 0 = off (there is no separate on/off).
    // Scale observed in Geely's app: 0..20 (the slider goes up to ~20).
    public static final int AMBIENT_BRIGHT = 704708864; // SETTING_FUNC_AMBIENCE_LIGHT_INTENSITY_SET
    public static final int AMBIENT_BRIGHT_MAX = 20;

    public boolean setAmbientBrightness(int level) {
        int v = Math.max(0, Math.min(AMBIENT_BRIGHT_MAX, level));
        for (int a : new int[]{AMBIENT_AREA, 0, 1}) {
            try { cpm.setIntProperty(AMBIENT_BRIGHT, a, v);
                  Log.i(TAG, "light brightness = " + v + " (area " + a + ")"); return true; }
            catch (Throwable ignored) {}
        }
        Log.w(TAG, "light brightness failed");
        return false;
    }

    public Integer readAmbientBrightness() {
        for (int a : new int[]{AMBIENT_AREA, 0, 1}) {
            try { return cpm.getIntProperty(AMBIENT_BRIGHT, a); } catch (Throwable ignored) {}
        }
        return null;
    }

    public Float readChargeWorkCurrent() { return readF(CHARGE_WORK_CURRENT); }
    public Float readChargeWorkVoltage() { return readF(CHARGE_WORK_VOLTAGE); }

    private Float readF(int prop) {
        for (int a : new int[]{0, 1}) {
            try { return cpm.getFloatProperty(prop, a); } catch (Throwable ignored) {}
        }
        return null;
    }

    // OUTSIDE temperature: AC_AMBIENT_TEMP read as INT, formula (int-80)/2
    public Float readOutsideTempC() {
        try {
            int raw = cpm.getIntProperty(Modes.AC_AMBIENT_TEMP, 0);
            float c = (raw - 80) / 2.0f;
            Log.i(TAG, "outside temp raw=" + raw + " -> " + c + " C");
            if (c < -60f || c > 90f) return null; // sanity check
            return c;
        } catch (Throwable t) { Log.i(TAG, "outside temp failed: " + t); return null; }
    }

    // battery SoC, already the real value (Geely's adaptation layer hands over
    // 94.1, not a raw needing division — see Telemetry.FIELDS). Areas 0 and 1
    // both work per field-catalog.md; try 0 first.
    public Float readBatteryPct() {
        for (int a : new int[]{0, 1}) {
            try { return cpm.getFloatProperty(Modes.BATTERY_SOC, a); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    // raw CarPropertyValue read (for string/array types). returns the CPV or null
    public Object rawGetProperty(int prop, int area) {
        try { return cpm.getProperty(Integer.class, prop, area); }
        catch (Throwable a) {
            try { return cpm.getProperty(Float.class, prop, area); }
            catch (Throwable b) {
                try { return cpm.getProperty(String.class, prop, area); }
                catch (Throwable c) { return null; }
            }
        }
    }
    public Object cpvValue(Object cpv) {
        try { return cpv.getClass().getMethod("getValue").invoke(cpv); } catch (Throwable t) { return null; }
    }

    // ADAPTED boolean read (the raw value can be 2 = false; only the boolean is trustworthy)
    public Boolean readBool(int prop, int area) {
        try { return cpm.getBooleanProperty(prop, area); }
        catch (Throwable t) { return null; }
    }

    // generic read for diagnostics: t='f' float, 'i' int. returns a string or null
    public String readAny(int prop, int area, char t) {
        try {
            if (t == 'f') return String.valueOf(cpm.getFloatProperty(prop, area));
            return String.valueOf(cpm.getIntProperty(prop, area));
        } catch (Throwable ignored) { return null; }
    }

    // writes the temperature to the area it read from. clamped 16..32
    public boolean writeHvacTemp(float c, int area) {
        if (c < 16f) c = 16f; if (c > 32f) c = 32f;
        try { cpm.setFloatProperty(Modes.HVAC_TEMP, area, c); Log.i(TAG, "hvac temp SET area=" + area + " = " + c); return true; }
        catch (Throwable t) { Log.w(TAG, "hvac temp SET failed: " + t); return false; }
    }

    // --- Charging on/off ----------------------------------------------------
    // functionId CHARGE_FUNC_CHARGING; the real propId is
    // CHARGING_ALTERNATING_CURRENT_SWT. Discovered by watching `car setProperty`
    // in the log while the user pressed the buttons on the car's own screen. The
    // three adapted values follow Geely's base+N pattern; the mapping was
    // confirmed EMPIRICALLY by the current draw.
    public static final int CHARGE_SWITCH = 605028608;
    public static final int CHARGE_OFF = 605028609;   // raw 2 = stopped
    public static final int CHARGE_VAL2 = 605028610;  // in between (scheduled?)
    public static final int CHARGE_ON  = 605028611;   // raw 1 = charging

    public boolean setCharging(int adaptedValue) {
        if (cpm == null) return false;
        try {
            cpm.setIntProperty(CHARGE_SWITCH, 0, adaptedValue);
            Log.i(TAG, "charge: setProperty " + adaptedValue + " OK");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "charge: setProperty " + adaptedValue + " failed: " + t);
            return false;
        }
    }

    public Integer readCharging() {
        if (cpm == null) return null;
        try { return cpm.getIntProperty(CHARGE_SWITCH, 0); }
        catch (Throwable t) { Log.w(TAG, "charge: read failed: " + t); return null; }
    }

    // --- HVAC on/off --------------------------------------------------------
    // All of them with access 0x3 in car_service. HVAC_POWER_ON declares area 75,
    // the same one as the fan we already write to successfully.
    public static final int HVAC_POWER_ON = 354419984;
    public static final int HVAC_AC_ON    = 354419973;
    // NOOP on this car: it takes the write and changes nothing. Kept because the
    // WRITEPROP diagnostic still aims at it, and because a future reader needs to
    // find out here rather than by delegating the fan to it again. 354419975 is
    // MAX_DEFROST, not AUTO — see field-catalog.md for how much that cost.
    public static final int HVAC_AUTO_ON = 354419978; // 0x1520050A ZONED_AUTOMATIC_MODE_ON
    // The physical front-defrost button. Distinct from AIR_DIRECTION (which the
    // effort table also legitimately sets to DEFROST/4 for its own gentle first
    // cooling column) -- this is the one unambiguous signal that a HAND asked
    // for defrost specifically, not the table. See ComfortRuler.refit().
    public static final int HVAC_MAX_DEFROST = 354419975;
    // Boolean, READ_WRITE. The cfg declares area 117, but the adaptation layer
    // ignores the area on these props, so HVAC_FLAG_AREAS {75,0,1} does the job.
    public static final int HVAC_RECIRC_ON = 354419976; // 0x15200508 ZONED_AIR_RECIRCULATION_ON
    // Rear electric window defroster (0x15200514 / area 2).
    public static final int HVAC_ELECTRIC_DEFROSTER_ON = 354419988;
    // Pre-conditioning: the official app CAN turn the AC on with the car shut
    // down, so a path exists — but through the TBox (cellular network), not
    // necessarily reachable from the head unit. Candidates, judging by the names:
    public static final int HVAC_WAKE_REQ   = 0x2140105b;  // "wake the HVAC up"
    public static final int AC_REMOTE_SET   = 0x2140a371;  // AC_REMOTE_SET_STS
    public static final int AC_REMOTE_CTRL  = 0x2140a369;  // _AC_REMOTE_CONTROL_STS
    private static final int[] HVAC_FLAG_AREAS = {75, 0, 1, 2};

    public Boolean readHvacFlag(int prop) {
        if (cpm == null) return null;
        for (int a : HVAC_FLAG_AREAS) {
            try { return cpm.getBooleanProperty(prop, a); } catch (Throwable ignored) {}
        }
        return null;
    }

    public boolean setHvacFlag(int prop, boolean on) {
        if (cpm == null) return false;
        for (int a : HVAC_FLAG_AREAS) {
            try {
                cpm.setBooleanProperty(prop, a, on);
                Log.i(TAG, "hvac prop=" + prop + " area=" + a + " = " + on + " OK");
                return true;
            } catch (Throwable t) { Log.w(TAG, "hvac prop=" + prop + " area=" + a + ": " + t); }
        }
        return false;
    }

    public Boolean readRearDefrost() {
        if (cpm == null) return null;
        try { return cpm.getBooleanProperty(HVAC_ELECTRIC_DEFROSTER_ON, 2); } catch (Throwable ignored) {}
        return readHvacFlag(HVAC_ELECTRIC_DEFROSTER_ON);
    }

    public boolean setRearDefrost(boolean on) {
        if (cpm == null) return false;
        try {
            cpm.setBooleanProperty(HVAC_ELECTRIC_DEFROSTER_ON, 2, on);
            Log.i(TAG, "hvac rear defrost = " + on + " OK");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hvac rear defrost area 2: " + t);
            return setHvacFlag(HVAC_ELECTRIC_DEFROSTER_ON, on);
        }
    }

    // Generic read for the discovery flow: it uses the raw CarPropertyValue, so
    // it does not need to know the type up front (int/float/bool/array).
    // The sweep must see what readProp sees. These used to be two different read
    // paths that disagreed: DOOR_POS answers instantly through getIntProperty but
    // was invisible here, so a "nothing changed" from a directed diff was
    // measured through a narrower window than the one the rest of the app trusts.
    //
    // Two things were wrong. The generic getter is refused or reports a status
    // other than STATUS_AVAILABLE for properties the typed getters return
    // happily, so the status filter threw away real values; and the typed getters
    // are what the rest of this class uses everywhere else. Now the strict path
    // is tried first (it preserves the true type, which matters for the diff) and
    // the typed getters are the fallback, in the order that keeps precision:
    // float before int, so a float property is never silently rounded into a
    // different value on every read.
    public Object readGeneric(int prop, int area) {
        if (cpm == null) return null;
        try {
            CarPropertyValue<?> v = cpm.getProperty(Object.class, prop, area);
            if (v != null && v.getStatus() == CarPropertyValue.STATUS_AVAILABLE) {
                Object o = v.getValue();
                if (o instanceof Object[]) return java.util.Arrays.toString((Object[]) o);
                Log.i(TAG, "readGeneric(" + prop + "@" + area + "): answered by getProperty(Object.class) = " + o);
                return o;
            }
        } catch (Throwable t) { Log.i(TAG, "readGeneric(" + prop + "@" + area + "): getProperty(Object.class) threw " + t); }
        try {
            float f = cpm.getFloatProperty(prop, area);
            Log.i(TAG, "readGeneric(" + prop + "@" + area + "): answered by getFloatProperty = " + f);
            return f;
        } catch (Throwable t) { Log.i(TAG, "readGeneric(" + prop + "@" + area + "): getFloatProperty threw " + t); }
        try {
            int i = cpm.getIntProperty(prop, area);
            Log.i(TAG, "readGeneric(" + prop + "@" + area + "): answered by getIntProperty = " + i);
            return i;
        } catch (Throwable t) { Log.i(TAG, "readGeneric(" + prop + "@" + area + "): getIntProperty threw " + t); }
        try {
            boolean b = cpm.getBooleanProperty(prop, area);
            Log.i(TAG, "readGeneric(" + prop + "@" + area + "): answered by getBooleanProperty = " + b);
            return b;
        } catch (Throwable t) { Log.i(TAG, "readGeneric(" + prop + "@" + area + "): getBooleanProperty threw " + t); }
        Log.i(TAG, "readGeneric(" + prop + "@" + area + "): all four accessors failed, returning null");
        return null;
    }

    // Geely's own "functionId" constants (found by decompiling the OEM's own
    // apps — seat/mirror adjust, quick-settings tiles, etc.) are NOT raw VHAL
    // property ids. The OEM code always resolves them first, through
    // com.ecarx.xui.adaptapi.car.Car.createWrapper(ctx)
    //   .getWrappedPropertyId(areaType, funcId).getPropertyId()
    // — confirmed by reading several of these apps' own decompiled source
    // (decompiled/controlcenter/decompile_propmgr.log, dozens of call sites,
    // all following this exact three-call chain). A direct read of a
    // functionId as if it were a property id just reads whatever unrelated
    // (or nonexistent) property happens to share that number — which is
    // exactly what looked like "seat position isn't tracked" before this
    // existed: flat 0 at every area, because 356518789 was never a real
    // property id to begin with.
    //
    // Reflection, not a compile-time dependency: this class lives in a
    // framework jar loaded at runtime on the car, not in car-stubs.jar, and
    // adding it as a real dependency is more setup than one debug tool is
    // worth. Every step is defensive — any of the three calls failing
    // (class not found, method not found, null returned) just means "could
    // not resolve," logged and returned as null, same shape as every other
    // best-effort read in this class.
    public Integer wrapFuncId(Context ctx, int areaType, int funcId) {
        try {
            Class<?> carCls = Class.forName("com.ecarx.xui.adaptapi.car.Car");
            Object wrapper = carCls.getMethod("createWrapper", Context.class)
                                   .invoke(null, ctx);
            if (wrapper == null) return null;
            Object propId = wrapper.getClass()
                                    .getMethod("getWrappedPropertyId", int.class, int.class)
                                    .invoke(wrapper, areaType, funcId);
            if (propId == null) return null;
            Object id = propId.getClass().getMethod("getPropertyId").invoke(propId);
            return (id instanceof Integer) ? (Integer) id : null;
        } catch (Throwable t) {
            Log.w(TAG, "wrapFuncId(" + areaType + "," + funcId + "): " + t);
            return null;
        }
    }

    // android.car.media.CarAudioManager — like the functionId wrapper above,
    // this is a Geely-extended hidden class that lives in the framework jar,
    // not car-stubs.jar, so it is called by reflection rather than compiled
    // against. Confirmed real (not guessed) by decompiling the third-party app,
    // which uses this exact class + getCarManager("audio") to
    // read/write AVAS (the pedestrian warning sound) — see
    // backup-centralex/jadx-out and docs/field-catalog.md. The same class
    // also exposes getBeepLevel/setBeepLevel, getVehicleAlarmLevel/
    // setVehicleAlarmLevel, and a per-type getWarnVolume/setWarnVolume family
    // — none of that is from the third-party app, found instead by listing every method on
    // the class directly (android.car-full.jar's classes.dex).
    public Object audioCall(String method, Object... args) {
        if (car == null) return "ERR:no car";
        try {
            Object mgr = car.getCarManager("audio");
            if (mgr == null) return "ERR:no audio manager";
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                types[i] = (a instanceof Integer) ? int.class
                         : (a instanceof Boolean) ? boolean.class
                         : (a instanceof Float) ? float.class
                         : a.getClass();
            }
            return mgr.getClass().getMethod(method, types).invoke(mgr, args);
        } catch (java.lang.reflect.InvocationTargetException t) {
            Throwable cause = t.getCause();
            return "ERR:" + (cause != null ? cause : t);
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    // Property list declared by the VHAL (areaIds, min/max). It is the base of
    // the assisted discovery: without it, guessing areas is all that is left.
    public java.util.List<android.car.hardware.CarPropertyConfig> propertyList() {
        if (cpm == null) return null;
        try { return cpm.getPropertyList(); }
        catch (Throwable t) { Log.w(TAG, "getPropertyList failed: " + t); return null; }
    }

    // Probe: getPropertyList() returns metadata (areaIds, min/max) that today is
    // hardcoded in the code. This checks whether it covers Geely's functionIds or
    // only the standard AOSP props — the central premise of a distributable app.
    public void dumpPropertyList() {
        if (cpm == null) { Log.w(TAG, "proplist: no cpm"); return; }
        try {
            java.util.List<android.car.hardware.CarPropertyConfig> all = cpm.getPropertyList();
            Log.i(TAG, "proplist: TOTAL=" + (all == null ? -1 : all.size()));
            if (all == null) return;
            // the ids Drive Assist uses today, to see which ones show up in the list
            int[] wanted = {
                CHARGE_CURRENT_LIMIT, CHARGE_SWITCH, CHARGE_WORK_CURRENT, CHARGE_WORK_VOLTAGE,
                AMBIENT_COLOR, AMBIENT_BRIGHT, HVAC_POWER_ON,
                Modes.HVAC_FAN, Modes.HVAC_TEMP, Modes.AC_AMBIENT_TEMP,
                Modes.PROP_DRIVE, Modes.PROP_REGEN
            };
            java.util.HashMap<Integer, android.car.hardware.CarPropertyConfig> byId = new java.util.HashMap<>();
            for (android.car.hardware.CarPropertyConfig cfg : all) byId.put(cfg.getPropertyId(), cfg);
            for (int w : wanted) {
                android.car.hardware.CarPropertyConfig cfg = byId.get(w);
                if (cfg == null) { Log.i(TAG, "proplist: " + w + " -> MISSING"); continue; }
                StringBuilder sb = new StringBuilder("proplist: " + w + " -> areas=[");
                int[] areas = cfg.getAreaIds();
                for (int i = 0; i < areas.length; i++) { if (i > 0) sb.append(","); sb.append(areas[i]); }
                sb.append("]");
                try { sb.append(" min=").append(cfg.getMinValue()).append(" max=").append(cfg.getMaxValue()); }
                catch (Throwable ignored) {}
                Log.i(TAG, sb.toString());
            }
        } catch (Throwable t) { Log.w(TAG, "proplist failed: " + t, t); }
    }

    // writes to every area; returns true if at least one accepted it
    public boolean writeDrive(int val) { return writeAll(Modes.PROP_DRIVE, Modes.DRIVE_AREAS, val, "drive"); }
    public boolean writeRegen(int val) { return writeAll(Modes.PROP_REGEN, Modes.REGEN_AREAS, val, "regen"); }

    private boolean writeAll(int prop, int[] areas, int val, String label) {
        boolean ok = false;
        for (int a : areas) {
            try {
                cpm.setIntProperty(prop, a, val);
                ok = true;
                Log.i(TAG, label + " write prop=" + prop + " area=" + a + " val=" + val + " OK");
                try { Thread.sleep(300); } catch (InterruptedException e) {}
            } catch (Throwable t) {
                Log.w(TAG, label + " write area=" + a + " failed: " + t);
            }
        }
        return ok;
    }
}
