package com.geely.drivemem.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The floating panel is rebuilt as our screens close, after any change made
 * on them (theme, Turbo card buttons). Rebuilding as they open ran BEFORE the
 * change and kept a switched-off AVAS button on the panel (#15). */
public class OverlayRebuildTest {
    @Test public void rebuildsWhenOurScreensClose() {
        assertTrue(OverlayService.rebuildOnForegroundChange(false));
    }

    @Test public void doesNotRebuildWhenOurScreensOpen() {
        assertFalse(OverlayService.rebuildOnForegroundChange(true));
    }
}
