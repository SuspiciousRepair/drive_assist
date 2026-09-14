package com.geely.modehelper;

import android.car.Car;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

/** Provides access to car properties: drive mode, regen mode, gear, charging state,
 * speed, and outside temperature. Running as uid system allows reading properties
 * that would return null for a normal app. */
public class CarMode {
    static final String TAG = "ModeHelper";
    static final int PROP_DRIVE = 570491136, PROP_REGEN = 537003264;
    static final int[] DRIVE_AREAS = {0, 1, 16777216}, REGEN_AREAS = {0, 1};
    // values
    static final int DRIVE_ECO = 570491137, DRIVE_COMFORT = 570491138, DRIVE_SPORT = 570491139;
    static final int REGEN_LOW = 537003265, REGEN_MID = 537003266, REGEN_HIGH = 537003267;

    // AEB (Autonomous Emergency Braking) master switch, boolean, area 0. See
    // docs/field-catalog.md §6 — confirmed via directed diff on the OEM ADAS
    // screen and cross-checked against a decompiled reference app.
    static final int AEB_PROP = 557858874, AEB_AREA = 0;

    // gear: CURRENT_GEAR (same id the telemetry uses). VehicleGear enum:
    // NEUTRAL=1, REVERSE=2, PARK=4, DRIVE=8
    static final int GEAR = 289408001;
    static final int GEAR_DRIVE = 8;
    static final int GEAR_PARK = 4;

    private Car car;
    private CarPropertyManager cpm;

    /** Returns true if the car connection is established. */
    boolean isReady() { return cpm != null; }
    // CarPowerManager is not in the stubs -> we get it by reflection (it exists at runtime)
    Object powerManager() {
        try { return car != null ? car.getCarManager("power") : null; }
        catch (Throwable t) { return null; }
    }

    Integer readGear() {
        for (int a : new int[]{0, 1, 16777216}) {
            try { return cpm.getIntProperty(GEAR, a); } catch (Throwable ignored) {}
        }
        return null;
    }

    // For the dashcam subtitle track. Both ids are already verified in
    // docs/field-catalog.md, so these are copies of a known-good read rather
    // than anything new.
    static final int SPEED = 291504647;              // PERF_VEHICLE_SPEED, km/h, float
    static final int AC_AMBIENT_TEMP = 557884279;    // int, outside C = (raw - 80) / 2

    Float readSpeed() {
        for (int a : new int[]{0, 1}) {
            try { return cpm.getFloatProperty(SPEED, a); } catch (Throwable ignored) {}
        }
        return null;
    }

    Float readOutsideTempC() {
        try {
            float c = (cpm.getIntProperty(AC_AMBIENT_TEMP, 0) - 80) / 2.0f;
            // Same sanity band drivemem uses: this sensor reads nonsense while the
            // car is waking, and a bogus number in a subtitle is worse than none.
            return (c < -60f || c > 90f) ? null : c;
        } catch (Throwable t) { return null; }
    }

    // Charge switch values: ON=605028611 (charging), OFF=605028609, 610 intermediate, 0=no cable.
    // WiFi is enabled when charging state equals CHARGE_ON.
    static final int CHARGE_SWITCH = 605028608;
    static final int CHARGE_ON = 605028611;
    static final int CHARGE_CURRENT = 605291008, CHARGE_VOLTAGE = 605290752;
    Integer readCharging() {
        try { return cpm.getIntProperty(CHARGE_SWITCH, 0); } catch (Throwable t) { return null; }
    }
    Float readChargeCurrent() {
        for (int a : new int[]{0, 1}) { try { return cpm.getFloatProperty(CHARGE_CURRENT, a); } catch (Throwable ignored) {} }
        return null;
    }
    Float readChargeVoltage() {
        for (int a : new int[]{0, 1}) { try { return cpm.getFloatProperty(CHARGE_VOLTAGE, a); } catch (Throwable ignored) {} }
        return null;
    }

    // generic readers (diagnostics: hunting down the "cable connected" prop)
    Boolean readBoolProp(int prop, int area) {
        try { return cpm.getBooleanProperty(prop, area); } catch (Throwable t) { return null; }
    }
    // Generic writer. Package-private on purpose: only a narrowly-scoped,
    // hardcoded-property receiver (e.g. AebWriteTestReceiver) may call this —
    // never wire it behind a receiver that takes an arbitrary id from the
    // caller. See ProbeReceiver's own comment for why.
    boolean writeBoolProp(int prop, int area, boolean val) {
        try { cpm.setBooleanProperty(prop, area, val); return true; }
        catch (Throwable t) { Log.w(TAG, "writeBoolProp " + prop + "@" + area + ": " + t); return false; }
    }
    Float readFloatProp(int prop, int area) {
        try { return cpm.getFloatProperty(prop, area); } catch (Throwable t) { return null; }
    }
    Integer readIntProp(int prop, int area) {
        try { return cpm.getIntProperty(prop, area); } catch (Throwable t) { return null; }
    }
    // android.car.media.CarAudioManager — hidden framework class, called by
    // reflection rather than compiled against (same class drivemem's
    // CarAccess.audioCall() uses). Package-private: only a narrowly-scoped,
    // hardcoded-method receiver (e.g. AvasMuteTestReceiver) may call this —
    // never wire it behind a receiver that takes an arbitrary method name
    // from the caller. See ProbeReceiver's own comment for why.
    Object audioCall(String method, Object... args) {
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
    // is this prop in the VHAL list?
    boolean hasProp(int prop) {
        try {
            java.util.List<android.car.hardware.CarPropertyConfig> l = cpm.getPropertyList();
            if (l != null) for (android.car.hardware.CarPropertyConfig c : l) if (c.getPropertyId() == prop) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // ids of declared props falling inside an id range (for the cable diff)
    int[] propIdsInRange(int lo, int hi) {
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        try { for (android.car.hardware.CarPropertyConfig c : cpm.getPropertyList()) {
            int id = c.getPropertyId(); if (id >= lo && id <= hi) ids.add(id);
        } } catch (Throwable ignored) {}
        int[] a = new int[ids.size()]; for (int i = 0; i < a.length; i++) a[i] = ids.get(i); return a;
    }
    // read anything as a string (int -> float -> bool)
    String readAny(int prop, int area) {
        try { return String.valueOf(cpm.getIntProperty(prop, area)); } catch (Throwable ignored) {}
        try { return String.valueOf(cpm.getFloatProperty(prop, area)); } catch (Throwable ignored) {}
        try { return String.valueOf(cpm.getBooleanProperty(prop, area)); } catch (Throwable ignored) {}
        return "?";
    }

    /** Establishes the car connection and waits up to 6 seconds for it to connect.
     * Returns true on success, false if the connection fails or times out. */
    boolean connect(Context ctx) {
        disconnect();
        try {
            car = Car.createCar(ctx, new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) {}
                public void onServiceDisconnected(ComponentName n) { cpm = null; }
            });
            car.connect();
            for (int i = 0; i < 60 && !car.isConnected(); i++) Thread.sleep(100);
            if (!car.isConnected()) return false;
            cpm = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            return cpm != null;
        } catch (Throwable t) { Log.w(TAG, "connect: " + t); return false; }
    }

    void disconnect() {
        try { if (car != null) car.disconnect(); } catch (Throwable ignored) {}
        car = null; cpm = null;
    }

    Integer readDrive() {
        for (int a : DRIVE_AREAS) { try { return cpm.getIntProperty(PROP_DRIVE, a); } catch (Throwable ignored) {} }
        return null;
    }
    Integer readRegen() {
        for (int a : REGEN_AREAS) { try { return cpm.getIntProperty(PROP_REGEN, a); } catch (Throwable ignored) {} }
        return null;
    }
    void writeDrive(int v) {
        for (int a : DRIVE_AREAS) { try { cpm.setIntProperty(PROP_DRIVE, a, v); } catch (Throwable ignored) {} }
    }
    void writeRegen(int v) {
        for (int a : REGEN_AREAS) { try { cpm.setIntProperty(PROP_REGEN, a, v); } catch (Throwable ignored) {} }
    }
}
