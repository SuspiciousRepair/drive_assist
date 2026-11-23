package com.geely.drivemem;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.geely.drivemem.net.Updater;

import org.junit.Test;

/** "Later" on the update window: the home screen stays quiet for that version. */
public class UpdateLaterTest {
    @Test public void neverSaidLaterPrompts() {
        assertTrue(Updater.autoPrompts(29923334, 0));
    }

    @Test public void laterOnThisVersionStaysQuiet() {
        assertFalse(Updater.autoPrompts(29923334, 29923334));
    }

    @Test public void olderVersionStaysQuiet() {
        assertFalse(Updater.autoPrompts(29923300, 29923334));
    }

    @Test public void newerVersionPromptsAgain() {
        assertTrue(Updater.autoPrompts(29923400, 29923334));
    }
}
