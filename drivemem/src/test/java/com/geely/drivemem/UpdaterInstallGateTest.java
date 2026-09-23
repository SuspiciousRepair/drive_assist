package com.geely.drivemem;

import com.geely.drivemem.net.Updater;
import com.geely.drivemem.state.CarState;
import com.geely.drivemem.state.ChargeSession;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** installBlockedReason() is the one place update() decides whether it's
 * safe to install right now. Added 2026-09-23 after a real DC fast charge
 * stopped at ~12 minutes (station's own receipt) right after an OTA
 * install landed while charging -- installing was already blocked while
 * driving, but not while charging. */
public class UpdaterInstallGateTest {

    @After public void resetSharedState() {
        CarState.setParkedForTesting(true);
        ChargeSession.resetForTesting();
    }

    @Test public void blockedWhileCharging() {
        CarState.setParkedForTesting(true);
        ChargeSession.setSessionStateForTesting(true, true, System.currentTimeMillis(), 50, 60, 1000, 5);
        assertNotNull(Updater.installBlockedReason(false));
    }

    @Test public void blockedWhileMoving() {
        CarState.setParkedForTesting(false);
        ChargeSession.resetForTesting();
        assertNotNull(Updater.installBlockedReason(false));
    }

    @Test public void allowedWhenParkedAndNotCharging() {
        CarState.setParkedForTesting(true);
        ChargeSession.resetForTesting();
        assertNull(Updater.installBlockedReason(false));
    }

    @Test public void forceBypassesBothChecks() {
        CarState.setParkedForTesting(false);
        ChargeSession.setSessionStateForTesting(true, true, System.currentTimeMillis(), 50, 60, 1000, 5);
        assertNull(Updater.installBlockedReason(true));
    }
}
