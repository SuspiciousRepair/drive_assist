package com.geely.drivemem.util;

import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

public class DashcamSettingsTest {
    private static final String APP_PATH = "/Android/data/com.geely.drivemem/files";

    @Test public void writableMountIsAvailableForRecordingAndReading() {
        assertTrue(DashcamSettings.acceptsStorageState("mounted", true));
        assertTrue(DashcamSettings.acceptsStorageState("mounted", false));
    }

    @Test public void readOnlyMountKeepsExistingClipsVisibleWithoutBecomingADestination() {
        assertTrue(DashcamSettings.acceptsStorageState("mounted_ro", false));
        assertFalse(DashcamSettings.acceptsStorageState("mounted_ro", true));
    }

    @Test public void unmountedOrBrokenMediaIsNotTreatedAsAccessible() {
        for (String state : new String[] {null, "", "unmounted", "removed", "bad_removal",
                "checking", "ejecting", "unmountable", "shared", "unknown"}) {
            assertFalse(String.valueOf(state), DashcamSettings.acceptsStorageState(state, false));
            assertFalse(String.valueOf(state), DashcamSettings.acceptsStorageState(state, true));
        }
    }

    @Test public void supportedSegmentDurationsAndInvalidPreferences() {
        for (int minutes : new int[]{1, 3, 5, 10})
            assertEquals(minutes, DashcamSettings.validatedSegmentMinutes(minutes));
        for (int minutes : new int[]{-1, 0, 2, 4, 6, 11, Integer.MAX_VALUE})
            assertEquals(5, DashcamSettings.validatedSegmentMinutes(minutes));
    }

    @Test public void storageIdsCannotEscapeVolumeRootOrSelectAndroidAliases() {
        for (String id : new String[]{null, "", "../ABCD", "/sdcard", "ABCD/1234", ".hidden",
                "emulated", "self", "primary", "EMULATED", "ABCD.1234", "a".repeat(65)}) {
            assertFalse(String.valueOf(id), DashcamSettings.validStorageId(id));
            assertEquals("internal", DashcamSettings.validatedStorageId(id));
        }
        for (String id : new String[]{"internal", "ABCD-1234", "usb_drive-1", "a".repeat(64)})
            assertTrue(id, DashcamSettings.validStorageId(id));
    }

    @Test public void discoveryOnlyAcceptsMountedAppDirectoriesAndDeduplicates() {
        File primary = new File("/storage/emulated/10" + APP_PATH);
        File usb = new File("/storage/ABCD-1234" + APP_PATH);
        List<DashcamSettings.StorageOption> options = DashcamSettings.discover(primary,
                new File[]{primary, null, usb, usb,
                    new File("/storage/self" + APP_PATH),
                    new File("/storage/primary" + APP_PATH),
                    new File("/storage/ABCD-1234/../OTHER" + APP_PATH),
                    new File("/storage/FAKE/Android/data/another.app/files"),
                    new File("/mnt/media_rw/OTHER" + APP_PATH),
                    new File("/storage/OTHER" + APP_PATH + "/nested")});
        assertEquals(2, options.size());
        assertEquals("internal", options.get(0).id);
        assertFalse(options.get(0).removable);
        assertEquals(new File(primary, "dashcam"), options.get(0).directory);
        assertEquals("ABCD-1234", options.get(1).id);
        assertTrue(options.get(1).removable);
        assertEquals(new File(usb, "dashcam"), options.get(1).directory);
    }

    @Test public void disconnectedPreferenceFallsBackAndReconnectResolvesSameId() {
        File primary = new File("/storage/emulated/0" + APP_PATH);
        File usb = new File("/storage/ABCD-1234" + APP_PATH);
        List<DashcamSettings.StorageOption> disconnected = DashcamSettings.discover(primary, null);
        assertEquals("internal", DashcamSettings.resolveStorage("ABCD-1234", disconnected).id);
        List<DashcamSettings.StorageOption> connected = DashcamSettings.discover(primary, new File[]{usb});
        assertEquals("ABCD-1234", DashcamSettings.resolveStorage("ABCD-1234", connected).id);
        assertEquals("internal", DashcamSettings.resolveStorage("../../bad", connected).id);
    }
}
