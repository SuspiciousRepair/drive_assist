package com.geely.drivemem.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When the daily backup starts, and how long it waits after a failure. The
 * watchdog asks every 10 minutes: a backup that cannot work (drive full)
 * must not copy the whole database on every one of those calls. */
public class AppBackupAutoTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long H = 3600_000L;
    private static final long NOW = 1_794_000_000_000L;

    @Test public void firstBackupEverIsDue() {
        assertTrue(AppBackup.autoDue(NOW, 0, 0, 0));
    }

    @Test public void notDueWithinADayOfAGoodBackup() {
        assertFalse(AppBackup.autoDue(NOW, NOW - 23 * H, NOW - 23 * H, 0));
        assertTrue(AppBackup.autoDue(NOW, NOW - 24 * H, NOW - 24 * H, 0));
    }

    @Test public void afterOneFailureTheNextTryWaitsAnHour() {
        long lastOk = NOW - 30 * H;
        assertFalse(AppBackup.autoDue(NOW, lastOk, NOW - 10 * 60_000L, 1));   // the next watchdog call
        assertFalse(AppBackup.autoDue(NOW, lastOk, NOW - H + 1, 1));
        assertTrue(AppBackup.autoDue(NOW, lastOk, NOW - H, 1));
    }

    @Test public void theWaitDoublesAndStopsAtOneDay() {
        assertEquals(0, AppBackup.retryDelayMs(0));
        assertEquals(1 * H, AppBackup.retryDelayMs(1));
        assertEquals(2 * H, AppBackup.retryDelayMs(2));
        assertEquals(4 * H, AppBackup.retryDelayMs(3));
        assertEquals(8 * H, AppBackup.retryDelayMs(4));
        assertEquals(16 * H, AppBackup.retryDelayMs(5));
        assertEquals(24 * H, AppBackup.retryDelayMs(6));
        assertEquals(24 * H, AppBackup.retryDelayMs(100));
        assertEquals(24 * H, AppBackup.retryDelayMs(Integer.MAX_VALUE));
    }

    @Test public void manyFailuresStillTryOnceADay() {
        long lastOk = NOW - 30 * 24 * H;
        assertFalse(AppBackup.autoDue(NOW, lastOk, NOW - 23 * H, 50));
        assertTrue(AppBackup.autoDue(NOW, lastOk, NOW - 24 * H, 50));
    }

    @Test public void aGoodBackupResetsTheWait() {
        // fails is 0 again after a good one: only the daily rule counts.
        assertTrue(AppBackup.autoDue(NOW, NOW - 25 * H, NOW - 60_000L, 0));
    }

    @Test public void aLastTryInTheFutureDoesNotBlock() {
        // The head unit's clock was set back after the failed try.
        assertTrue(AppBackup.autoDue(NOW, 0, NOW + 5 * H, 3));
    }

    @Test public void theNewStateKeysAreNotInABackup() {
        assertFalse(Prefs.isBackedUp(AppBackup.PREF_LAST_TRY));
        assertFalse(Prefs.isBackedUp(AppBackup.PREF_AUTO_FAILS));
    }

    @Test public void copyAndSyncKeepTheBytes() throws Exception {
        File src = tmp.newFile("a.dabackup");
        try (FileOutputStream o = new FileOutputStream(src)) { o.write(new byte[]{1, 2, 3, 4, 5}); }
        File dst = new File(tmp.getRoot(), "b.dabackup.part");
        AppBackup.copy(src, dst);
        AppBackup.syncFile(dst);   // append mode: must not cut or grow the file
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Files.readAllBytes(dst.toPath()));
    }
}
