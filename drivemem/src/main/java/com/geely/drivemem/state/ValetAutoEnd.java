package com.geely.drivemem.state;

/** Pure-Java state machine for automatically ending Valet Mode when the owner
 * returns with their trusted phone.
 *
 * Lifecycle:
 * 1. When Valet starts, auto-end is DISARMED so handing the car over while the
 *    owner's phone is still in range never accidentally cancels Valet.
 * 2. Once the trusted phone is absent continuously for {@link #ABSENCE_ARM_MS}
 *    (20 s), auto-end becomes ARMED.
 * 3. Once ARMED and the trusted phone reconnects continuously, Valet auto-ends
 *    when EITHER:
 *    - Any door opened (within {@link #DOOR_LEAD_MS} before reconnect or any
 *      time after reconnect) AND the trusted phone stays connected for
 *      {@link #RETURN_DWELL_MS} (60 s); OR
 *    - The car is driven {@link #RETURN_DRIVE_KM} (150 m) while the trusted
 *      phone stays connected (measured via odometer delta or speed integration).
 *
 * Zero Android dependencies so every edge case is directly unit-tested. */
public final class ValetAutoEnd {
    /** Continuous phone absence required after Valet start before arming. */
    public static final long ABSENCE_ARM_MS = 20_000L;
    /** Continuous trusted-phone presence after door open before auto-ending. */
    public static final long RETURN_DWELL_MS = 60_000L;
    /** A door opened up to this long before BT finished reconnecting still counts. */
    public static final long DOOR_LEAD_MS = 30_000L;
    /** Distance driven with trusted phone continuously connected before auto-ending. */
    public static final double RETURN_DRIVE_KM = 0.15;

    private boolean active;
    private boolean armed;
    private long absentSinceMs;
    private long returnedSinceMs;
    private long lastDoorOpenMs;
    private long lastTickMs;
    private Double returnStartOdoKm;
    private double estDrivenKm;

    /** Resets state when a Valet session begins (or resumes after restart). */
    public synchronized void reset(long nowMs, boolean trustedPhonePresent) {
        active = true;
        armed = false;
        absentSinceMs = trustedPhonePresent ? 0L : nowMs;
        returnedSinceMs = 0L;
        lastDoorOpenMs = 0L;
        lastTickMs = nowMs;
        returnStartOdoKm = null;
        estDrivenKm = 0.0;
    }

    /** Clears state when Valet ends. */
    public synchronized void clear() {
        active = false;
        armed = false;
        absentSinceMs = 0L;
        returnedSinceMs = 0L;
        lastDoorOpenMs = 0L;
        lastTickMs = 0L;
        returnStartOdoKm = null;
        estDrivenKm = 0.0;
    }

    public synchronized boolean isArmed() { return armed; }

    /** Records a closed -> open transition on any vehicle door. */
    public synchronized void onDoorOpened(long nowMs) {
        if (!active || !armed) return;
        lastDoorOpenMs = nowMs;
    }

    /** Advances the state machine on each telemetry tick (~1-2 s).
     * @return true when Valet Mode should automatically end now. */
    public synchronized boolean onTick(long nowMs, boolean trustedPhonePresent,
                                       Double odoKm, Double speedKmh) {
        if (!active) return false;

        if (!armed) {
            if (!trustedPhonePresent) {
                if (absentSinceMs == 0L) absentSinceMs = nowMs;
                if (nowMs - absentSinceMs >= ABSENCE_ARM_MS) {
                    armed = true;
                }
            } else {
                absentSinceMs = 0L;
            }
            lastTickMs = nowMs;
            return false;
        }

        // Armed: waiting for owner's trusted phone to return.
        if (!trustedPhonePresent) {
            returnedSinceMs = 0L;
            returnStartOdoKm = null;
            estDrivenKm = 0.0;
            lastTickMs = nowMs;
            return false;
        }

        boolean justReturned = (returnedSinceMs == 0L);
        if (justReturned) {
            returnedSinceMs = nowMs;
            returnStartOdoKm = odoKm;
            estDrivenKm = 0.0;
        } else if (returnStartOdoKm == null && odoKm != null) {
            returnStartOdoKm = odoKm;
        }

        // Track distance only after the phone has returned (never across pre-return gaps).
        if (!justReturned && speedKmh != null && speedKmh > 1.0 && lastTickMs > 0L && nowMs > lastTickMs) {
            long dtMs = Math.min(10_000L, nowMs - lastTickMs);
            estDrivenKm += speedKmh * (dtMs / 3_600_000.0);
        }
        double odoDeltaKm = (odoKm != null && returnStartOdoKm != null)
            ? Math.max(0.0, odoKm - returnStartOdoKm) : 0.0;
        double drivenKm = Math.max(odoDeltaKm, estDrivenKm);
        lastTickMs = nowMs;

        // Trigger 1: door opened + 1 minute continuous trusted-phone presence.
        boolean doorMatched = lastDoorOpenMs > 0L && lastDoorOpenMs >= returnedSinceMs - DOOR_LEAD_MS;
        if (doorMatched && (nowMs - returnedSinceMs) >= RETURN_DWELL_MS) {
            return true;
        }

        // Trigger 2: car driven >= 150 m with trusted phone continuously connected.
        return drivenKm >= RETURN_DRIVE_KM;
    }
}
