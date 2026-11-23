package com.geely.drivemem.util;

import android.view.Gravity;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for RHD detection, resolution, and mirroring primitives. */
public class StyleRhdTest {

    @After public void resetRhd() {
        Style.RHD = false;
    }

    private static CarCaps caps(String body) throws Exception {
        File f = new File(Files.createTempDirectory("caps-rhd").toFile(), CarCaps.FILE);
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(body); }
        return CarCaps.read(f);
    }

    @Test public void detectDriveSideOnlyCommitsKnownCarCapsValues() throws Exception {
        CarCaps rhdCar = caps("cameras=yes\nconfig=0623\nrhd=1\n");
        CarCaps lhdCar = caps("cameras=yes\nconfig=0623\nrhd=0\n");
        CarCaps unknownCar = caps("cameras=unknown\n");
        CarCaps oldCar = caps("cameras=yes\nconfig=0623\n");
        CarCaps negativeRhd = caps("cameras=yes\nconfig=0623\nrhd=-1\n");

        assertEquals(Style.DRIVE_SIDE_RIGHT, Style.detectDriveSide(rhdCar));
        assertEquals(Style.DRIVE_SIDE_LEFT, Style.detectDriveSide(lhdCar));
        assertNull(Style.detectDriveSide(unknownCar));
        assertNull(Style.detectDriveSide(oldCar));
        assertNull(Style.detectDriveSide(negativeRhd));
        assertNull(Style.detectDriveSide(null));
    }

    @Test public void manualLeftAndRightOverrideCarCaps() throws Exception {
        CarCaps rhdCar = caps("cameras=yes\nconfig=0623\nrhd=1\n");
        CarCaps lhdCar = caps("cameras=yes\nconfig=0623\nrhd=0\n");

        assertFalse(Style.resolveRhd(Style.DRIVE_SIDE_LEFT, rhdCar));
        assertFalse(Style.resolveRhd(Style.DRIVE_SIDE_LEFT, lhdCar));
        assertFalse(Style.resolveRhd(Style.DRIVE_SIDE_LEFT, null));

        assertTrue(Style.resolveRhd(Style.DRIVE_SIDE_RIGHT, rhdCar));
        assertTrue(Style.resolveRhd(Style.DRIVE_SIDE_RIGHT, lhdCar));
        assertTrue(Style.resolveRhd(Style.DRIVE_SIDE_RIGHT, null));
    }

    @Test public void unsetOrLegacyAutoFallsBackToCarCaps() throws Exception {
        CarCaps rhdCar = caps("cameras=yes\nconfig=0623\nrhd=1\n");
        CarCaps lhdCar = caps("cameras=yes\nconfig=0623\nrhd=0\n");
        CarCaps unknownCar = caps("cameras=unknown\n");

        assertTrue(Style.resolveRhd(null, rhdCar));
        assertFalse(Style.resolveRhd(null, lhdCar));
        assertFalse(Style.resolveRhd(null, unknownCar));
        assertFalse(Style.resolveRhd(null, null));

        assertTrue(Style.resolveRhd("auto", rhdCar));
        assertFalse(Style.resolveRhd("auto", lhdCar));
        assertTrue(Style.resolveRhd("unexpected", rhdCar));
        assertFalse(Style.resolveRhd("unexpected", lhdCar));
        assertFalse(Style.resolveRhd("unexpected", null));
    }

    @Test public void gravitiesFlipPhysicallyBetweenLhdAndRhd() {
        Style.RHD = false;
        assertEquals(Gravity.LEFT, Style.driverGravity());
        assertEquals(Gravity.RIGHT, Style.passengerGravity());

        Style.RHD = true;
        assertEquals(Gravity.RIGHT, Style.driverGravity());
        assertEquals(Gravity.LEFT, Style.passengerGravity());
    }

    @Test public void mirrorXFlipsNormalizedCoordinateInRhd() {
        Style.RHD = false;
        assertEquals(0.75f, Style.mirrorX(0.75f), 1e-6f);
        assertEquals(0.0f, Style.mirrorX(0.0f), 1e-6f);
        assertEquals(1.0f, Style.mirrorX(1.0f), 1e-6f);

        Style.RHD = true;
        assertEquals(0.25f, Style.mirrorX(0.75f), 1e-6f);
        assertEquals(1.0f, Style.mirrorX(0.0f), 1e-6f);
        assertEquals(0.0f, Style.mirrorX(1.0f), 1e-6f);
        assertEquals(0.5f, Style.mirrorX(0.5f), 1e-6f);
    }

    @Test public void mirrorIndexReversesOrderOnlyInRhd() {
        for (int n = 1; n <= 4; n++) {
            for (int i = 0; i < n; i++) {
                assertEquals(i, Style.mirrorIndex(i, n, false));
                assertEquals(n - 1 - i, Style.mirrorIndex(i, n, true));
            }
        }
    }

    @Test public void shouldScrollToDriverEdgePreservesManualScroll() {
        assertFalse(Style.shouldScrollToDriverEdge(0, 3, false));
        assertFalse(Style.shouldScrollToDriverEdge(2, 3, false));
        assertTrue(Style.shouldScrollToDriverEdge(0, 3, true));
        assertTrue(Style.shouldScrollToDriverEdge(2, 3, true));
        assertTrue(Style.shouldScrollToDriverEdge(4, 3, true));
        assertFalse(Style.shouldScrollToDriverEdge(3, 3, true));
    }

    @Test public void driveSidePrefIsBackedUpAndPhoneRestorable() {
        assertEquals("drive_side", Prefs.KEY_DRIVE_SIDE);
        assertTrue(Prefs.isBackedUp(Prefs.KEY_DRIVE_SIDE));
        assertTrue(Prefs.isPhoneRestorable(Prefs.KEY_DRIVE_SIDE));
    }
}
