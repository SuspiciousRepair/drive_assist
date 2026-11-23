package com.geely.drivemem.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;

import org.junit.Test;

/** The floating panel is rebuilt as our screens close, after any change made
 * on them (theme, Turbo card buttons, driver side). Rebuilding as they open
 * ran BEFORE the change and kept a switched-off AVAS button on the panel (#15). */
public class OverlayRebuildTest {
    @Test public void rebuildsWhenOurScreensClose() {
        assertTrue(OverlayService.rebuildOnForegroundChange(false));
    }

    @Test public void doesNotRebuildWhenOurScreensOpen() {
        assertFalse(OverlayService.rebuildOnForegroundChange(true));
    }

    @Test public void gravityAnchorsToDriverEdge() {
        assertEquals(Gravity.BOTTOM | Gravity.LEFT, OverlayService.overlayGravity(false));
        assertEquals(Gravity.BOTTOM | Gravity.RIGHT, OverlayService.overlayGravity(true));
    }

    @Test public void dragXNegatesDeltaInRhd() {
        assertEquals(120, OverlayService.overlayDragX(100, 20, false));
        assertEquals(80, OverlayService.overlayDragX(100, -20, false));
        assertEquals(80, OverlayService.overlayDragX(100, 20, true));
        assertEquals(120, OverlayService.overlayDragX(100, -20, true));
    }
}
