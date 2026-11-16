package com.geely.drivemem.controls;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Doors screen shows what the car's setting really points at. */
public class LockSoundTest {
    @Test public void ourFilesMapToTheirIds() {
        assertEquals(LockSound.FART, LockSound.idForPath("/data/user/0/com.geely.modehelper/files/lock_fart.wav"));
        assertEquals(LockSound.CUSTOM, LockSound.idForPath("/data/data/com.geely.modehelper/files/lock_custom.wav"));
    }

    @Test public void anythingElseIsTheCarsOwnSound() {
        assertEquals(LockSound.CAR, LockSound.idForPath("/vendor/etc/carlock/haoyunbugu.wav"));
        assertEquals(LockSound.CAR, LockSound.idForPath(null));
        assertEquals(LockSound.CAR, LockSound.idForPath("null"));
        assertEquals(LockSound.CAR, LockSound.idForPath("/storage/BFD4-1DEB/x/fart.wav"));
    }

    @Test public void unknownFilesInOurDirAreNotGuessed() {
        assertEquals(LockSound.CAR, LockSound.idForPath("/data/user/0/com.geely.modehelper/files/lock_flatulence.wav"));
        assertEquals(LockSound.CAR, LockSound.idForPath("/data/user/0/com.geely.modehelper/files/lock_custom.ogg"));
    }

    @Test public void fartModeOffGivesBackTheRightSound() {
        assertEquals(LockSound.CAR, LockSound.base(LockSound.FART, null));
        assertEquals(LockSound.CAR, LockSound.base(LockSound.FART, LockSound.CAR));
        assertEquals(LockSound.CUSTOM, LockSound.base(LockSound.FART, LockSound.CUSTOM));
        assertEquals(LockSound.CAR, LockSound.base(LockSound.FART, "junk"));
    }

    @Test public void withoutFartModeTheCarSettingWins() {
        // A pick in the car's own Settings must not be overruled by a stale pref.
        assertEquals(LockSound.CAR, LockSound.base(LockSound.CAR, LockSound.CUSTOM));
        assertEquals(LockSound.CUSTOM, LockSound.base(LockSound.CUSTOM, LockSound.CAR));
    }

    // modehelper downloads https links only; the screen refuses the rest
    // itself and shows "Could not download that link."
    @Test public void onlyHttpsLinksAreSent() {
        assertTrue(LockSound.linkAllowed("https://example.com/LockChime.wav"));
        assertTrue(LockSound.linkAllowed("  HTTPS://example.com/a.wav "));
        assertFalse(LockSound.linkAllowed("http://example.com/LockChime.wav"));
        assertFalse(LockSound.linkAllowed("example.com/LockChime.wav"));
        assertFalse(LockSound.linkAllowed("file:///sdcard/LockChime.wav"));
        assertFalse(LockSound.linkAllowed("https://"));
        assertFalse(LockSound.linkAllowed(null));
    }
}
