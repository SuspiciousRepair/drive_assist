package com.geely.drivemem.sensors;

/** Turns roadcastd's signal changes into power samples. No Android, no
 * threads, no clock of its own: CanEnergyReader feeds it, tests drive it.
 *
 * Signals (catalog names; all calibrated in roadcast's catalog):
 * - BMSH_BattVolt (0x178), V
 * - BMSH_BattCurr (0x250), A; positive = discharge. Checked on the car on
 *   2026-11-14 while AC charging: -16 A at 404 V against 241 V x 28 A at
 *   the wall, so negative is into the battery.
 * - VCU_ThermalPwrAct (0x315), kW, climate (compressor + heater) electrical
 * - VCU_DrvPwrAct (0x315), kW, drive
 * - OBC_uInAct / OBC_iInAct (0x221), the charger's own mains V and A
 *
 * Power is V x I, the battery's own terminal power. That is the same quantity
 * the OBD2 dongle measures (Obd2Reader), so the two are interchangeable in
 * EnergyIntegrator, only this one arrives ten times a second.
 *
 * roadcastd sends a value only when it CHANGES, so a steady current yields no
 * updates at all. The daemon's heartbeat (once a second, with the source
 * state) is what says the last values still hold; {@link #onHeartbeat} emits
 * a sample then, so the integrator never sees a gap on a steady load. */
public final class CanPower {
    public static final String VOLT = "BMSH_BattVolt";
    public static final String CURR = "BMSH_BattCurr";
    public static final String CLIMATE = "VCU_ThermalPwrAct";
    public static final String DRIVE = "VCU_DrvPwrAct";
    public static final String OBC_V = "OBC_uInAct";
    public static final String OBC_A = "OBC_iInAct";
    public static final String[] SIGNALS = {VOLT, CURR, CLIMATE, DRIVE, OBC_V, OBC_A};

    /** Values outside these bands are not these signals. Bands from Capy
     * (github.com/Timoteohss/capy, Apache-2.0, by Timoteo Sousa). */
    static final double V_MIN = 100, V_MAX = 1000, A_ABS = 500, CLIMATE_MAX = 30;

    /** Without a heartbeat for this long the values are no longer trusted. */
    public static final long STALE_MS = 3_000;
    /** Heartbeats re-emit only if no sample went out for this long. */
    static final long HEARTBEAT_EMIT_GAP_MS = 900;

    private Double volt, curr, climate, drive, obcV, obcA;
    private boolean alive = false;
    private long lastAliveMono = 0;
    private long lastEmitMono = 0;
    private Double lastKw;

    /** A signal changed. Returns the power to integrate (kW, positive =
     * discharge), or null if this change does not make a new sample. */
    public synchronized Double onSignal(String name, double physical, boolean valid, long nowMono) {
        Double v = (valid && !Double.isNaN(physical) && !Double.isInfinite(physical)) ? physical : null;
        switch (name) {
            case VOLT: volt = (v != null && v >= V_MIN && v <= V_MAX) ? v : null; break;
            case CURR: curr = (v != null && Math.abs(v) <= A_ABS) ? v : null; break;
            case CLIMATE: climate = (v != null && v >= 0 && v <= CLIMATE_MAX) ? v : null; return null;
            case DRIVE: drive = v; return null;
            case OBC_V: obcV = v; return null;
            case OBC_A: obcA = v; return null;
            default: return null;
        }
        alive = true;
        lastAliveMono = nowMono;
        return emit(nowMono);
    }

    /** The daemon's heartbeat. {@code sourceState} 1 = it is reading the HAL. */
    public synchronized Double onHeartbeat(int sourceState, long nowMono) {
        if (sourceState != 1) { alive = false; return null; }
        alive = true;
        lastAliveMono = nowMono;
        if (nowMono - lastEmitMono < HEARTBEAT_EMIT_GAP_MS) return null;
        return emit(nowMono);
    }

    /** The connection dropped: nothing held is valid any more. */
    public synchronized void reset() {
        volt = curr = climate = drive = obcV = obcA = null;
        alive = false;
        lastAliveMono = 0;
        lastEmitMono = 0;
        lastKw = null;
    }

    private Double emit(long nowMono) {
        if (volt == null || curr == null) return null;
        lastEmitMono = nowMono;
        lastKw = volt * curr / 1000.0;
        return lastKw;
    }

    public synchronized boolean live(long nowMono) {
        return alive && nowMono >= lastAliveMono && nowMono - lastAliveMono <= STALE_MS;
    }

    public synchronized Double powerKw(long nowMono) { return live(nowMono) ? lastKw : null; }
    public synchronized Double packVolts(long nowMono) { return live(nowMono) ? volt : null; }
    public synchronized Double packAmps(long nowMono) { return live(nowMono) ? curr : null; }
    public synchronized Double climateKw(long nowMono) { return live(nowMono) ? climate : null; }
    public synchronized Double driveKw(long nowMono) { return live(nowMono) ? drive : null; }
    /** The charger's mains input, W, or null when it reads nothing. */
    public synchronized Double chargerInputW(long nowMono) {
        if (!live(nowMono) || obcV == null || obcA == null) return null;
        double w = obcV * obcA;
        return w > 0 ? w : null;
    }
}
