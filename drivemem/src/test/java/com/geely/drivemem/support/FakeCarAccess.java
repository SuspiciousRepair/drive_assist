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
}
