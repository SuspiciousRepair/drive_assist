package com.geely.drivemem.net;

import org.junit.Test;
import static org.junit.Assert.*;

/** The word "force" in an update command is not part of the URL. */
public class UpdaterForceWordTest {

    @Test public void wordComesOffTheUrl() {
        assertEquals("", Updater.stripForceWord("force"));
        assertEquals("https://example.com/a.apk",
                Updater.stripForceWord("force https://example.com/a.apk"));
        assertEquals("https://example.com/a.apk",
                Updater.stripForceWord(" https://example.com/a.apk FORCE "));
        assertEquals("go", Updater.stripForceWord("Force go"));
    }

    @Test public void urlsStayAsTheyAre() {
        assertEquals("https://salesforce.example/a.apk?force=1",
                Updater.stripForceWord("https://salesforce.example/a.apk?force=1"));
        assertEquals("", Updater.stripForceWord(null));
    }

    @Test public void onlyTheWordIsAForceWord() {
        assertTrue(Updater.isForceWord("force"));
        assertFalse(Updater.isForceWord("https://example.com/a.apk?force"));
        assertFalse(Updater.isForceWord("forced"));
    }

    @Test public void blockedVersionedUpdateRetriesUntilInstalled() {
        String u = "https://example.com/drive_assist.apk?v=29931000";
        assertFalse("older installed version means helper never finished installing",
                Updater.alreadyAppliedUrl(u, u, 29930500, false));
        assertTrue("installed at or past v= skips retained redelivery",
                Updater.alreadyAppliedUrl(u, u, 29931000, false));
        assertFalse("explicit force always runs",
                Updater.alreadyAppliedUrl(u, u, 29931000, true));
        assertTrue("versionless URL still dedupes against lastUrl",
                Updater.alreadyAppliedUrl("https://example.com/a.apk?t=1", "https://example.com/a.apk?t=1", 100, false));
    }
}
