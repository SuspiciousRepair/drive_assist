package com.geely.drivemem.controls;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The Doors screen lights the button the car's setting really points at. */
public class LockSoundTest {
    @Test public void ourFilesMapToTheirIds() {
        assertEquals("flatulence", LockSound.idForPath("/data/user/0/com.geely.modehelper/files/lock_flatulence.wav"));
        assertEquals("flatus", LockSound.idForPath("/data/data/com.geely.modehelper/files/lock_flatus.wav"));
    }

    @Test public void anythingElseIsTheCarsOwnSound() {
        assertEquals(LockSound.CAR, LockSound.idForPath("/vendor/etc/carlock/haoyunbugu.wav"));
        assertEquals(LockSound.CAR, LockSound.idForPath(null));
        assertEquals(LockSound.CAR, LockSound.idForPath("null"));
        assertEquals(LockSound.CAR, LockSound.idForPath("/storage/BFD4-1DEB/x/fart.wav"));
    }

    @Test public void unknownFilesInOurDirAreNotGuessed() {
        assertEquals(LockSound.CAR, LockSound.idForPath("/data/user/0/com.geely.modehelper/files/lock_other.wav"));
        assertEquals(LockSound.CAR, LockSound.idForPath("/data/user/0/com.geely.modehelper/files/lock_flatus.ogg"));
    }
}
