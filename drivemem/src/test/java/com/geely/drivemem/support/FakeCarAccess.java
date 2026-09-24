package com.geely.drivemem.support;

import com.geely.drivemem.car.CarAccess;

/** Test-only write spy. No Android service or vehicle connection is created. */
public class FakeCarAccess extends CarAccess {
    private CarTrace.Sample sample;
    private boolean connected = true;
    public void observe(CarTrace.Sample sample) { this.sample = sample; }
    @Override public boolean connect(android.content.Context context) { connected = true; return true; }
    @Override public boolean isReady() { return connected; }
    @Override public void disconnect() { connected = false; }
    @Override public Integer readGear() { return connected && sample != null ? sample.gear : null; }
    @Override public Float readSpeed() {
        return connected && sample != null && Double.isFinite(sample.speedKmh) ? (float) sample.speedKmh : null;
    }
    @Override public Integer readCharging() {
        return !connected || sample == null || sample.charging == null ? null : sample.charging ? CHARGE_ON : CHARGE_OFF;
    }

    @Override public String readAny(int prop, int area, char t) {
        // Override parent's implementation which uses CarPropertyManager (not available in tests).
        // Delegate to readIntRaw to get values seeded via seedIntRaw().
        Integer val = readIntRaw(prop, area);
        if (val == null) return null;
        if (t == 'f') return String.valueOf(val.floatValue());
        return String.valueOf(val);
    }
        public Integer lastWriteDrive, lastWriteRegen, lastSetParkMode, lastSetAmbientColor,
                lastSetAmbientBrightness, lastSetCharging, lastSetChargeLimit;
        public boolean writeShouldSucceed = true;
        public boolean ambientColorWriteShouldReportSuccess = false; // the racy read-back — see CarDataHub

        @Override public boolean writeDrive(int val) { lastWriteDrive = val; return writeShouldSucceed; }
        @Override public boolean writeRegen(int val) { lastWriteRegen = val; return writeShouldSucceed; }
        @Override public boolean setParkMode(int value) { lastSetParkMode = value; return writeShouldSucceed; }
        @Override public boolean setAmbientColor(int rgb) {
            lastSetAmbientColor = rgb;
            return ambientColorWriteShouldReportSuccess;
        }
        @Override public boolean setAmbientBrightness(int level) { lastSetAmbientBrightness = level; return writeShouldSucceed; }
        @Override public boolean setCharging(int adaptedValue) { lastSetCharging = adaptedValue; return writeShouldSucceed; }
        @Override public int setChargeCurrentLimit(int amps) { lastSetChargeLimit = amps; return writeShouldSucceed ? amps : 0; }

        // Raw int property store, keyed by "prop@area" -- generic enough for
        // any raw-property consumer (Purge, window/door controls) to use
        // without adding a dedicated field per property the way the other
        // setters above do.
        private final java.util.Map<String, Integer> rawInts = new java.util.HashMap<>();
        private static String rawKey(int prop, int area) { return prop + "@" + area; }
        /** Seeds a raw property's value as if the car reported it. */
        public void seedIntRaw(int prop, int area, int val) { rawInts.put(rawKey(prop, area), val); }
        @Override public Integer readIntRaw(int prop, int area) {
            return connected ? rawInts.get(rawKey(prop, area)) : null;
        }
        @Override public boolean setIntRaw(int prop, int area, int val) {
            if (!writeShouldSucceed) return false;
            rawInts.put(rawKey(prop, area), val);
            return true;
        }
}
