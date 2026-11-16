package com.geely.drivemem.util;

import android.content.SharedPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The end of a restore. Once the trip merge has committed, the live rows
 * have new ids: the prefs that hold row ids must be saved and the app must
 * restart, whatever else fails. Files are put in place before the merge, so
 * a full disk stops the restore while nothing is changed yet. */
public class AppBackupRestoreOrderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ---- finishRestore ----------------------------------------------------

    @Test public void prefsAreSavedFirstAndRestartIsLast() {
        List<String> log = new ArrayList<>();
        Throwable err = AppBackup.finishRestore(
            () -> { log.add("prefs"); return true; },
            () -> log.add("restart"),
            () -> log.add("certs"),
            () -> log.add("helper"));
        assertNull(err);
        assertEquals(Arrays.asList("prefs", "certs", "helper", "restart"), log);
    }

    @Test public void aStepThatFailsStopsNeitherTheNextStepsNorTheRestart() {
        List<String> log = new ArrayList<>();
        Throwable err = AppBackup.finishRestore(
            () -> { log.add("prefs"); return true; },
            () -> log.add("restart"),
            () -> { log.add("certs"); throw new java.io.IOException("disk full"); },
            () -> log.add("helper"));
        assertEquals("disk full", err.getMessage());
        assertEquals(Arrays.asList("prefs", "certs", "helper", "restart"), log);
    }

    @Test public void aSaveThatReportsFalseIsTriedAgain() {
        int[] calls = {0};
        boolean[] restarted = {false};
        Throwable err = AppBackup.finishRestore(
            () -> ++calls[0] >= 2,
            () -> restarted[0] = true);
        assertNull(err);
        assertEquals(2, calls[0]);
        assertTrue(restarted[0]);
    }

    @Test public void aSaveThatNeverWorksStillRunsTheStepsAndRestarts() {
        int[] calls = {0};
        List<String> log = new ArrayList<>();
        Throwable err = AppBackup.finishRestore(
            () -> { calls[0]++; return false; },
            () -> log.add("restart"),
            () -> log.add("certs"));
        assertNotNull(err);
        assertEquals(AppBackup.SAVE_TRIES, calls[0]);
        assertEquals(Arrays.asList("certs", "restart"), log);
    }

    @Test public void aSaveThatThrowsStillRestarts() {
        boolean[] restarted = {false};
        Throwable err = AppBackup.finishRestore(
            () -> { throw new IllegalStateException("prefs"); },
            () -> restarted[0] = true);
        assertEquals("prefs", err.getMessage());
        assertTrue(restarted[0]);
    }

    @Test public void theSaveErrorIsTheOneReportedWhenALaterStepFailsToo() {
        Throwable err = AppBackup.finishRestore(
            () -> false,
            () -> {},
            () -> { throw new java.io.IOException("certs"); });
        assertEquals("could not save the settings", err.getMessage());
    }

    // ---- staged files -----------------------------------------------------

    private static File write(File dir, String name, String text) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes("UTF-8")); }
        return f;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }

    @Test public void stagingLeavesTheLiveFilesAloneUntilTheMove() throws Exception {
        File backup = tmp.newFolder("backup");
        File files = tmp.newFolder("files");
        write(backup, "mqtt_ca.crt", "new ca");
        write(files, "mqtt_ca.crt", "old ca");
        write(files, "mqtt_client.p12", "old p12");          // not in the backup

        List<File[]> staged = AppBackup.stageFiles(backup, files,
            new String[]{"mqtt_client.p12", "mqtt_ca.crt"});

        assertEquals(1, staged.size());
        assertEquals("old ca", read(new File(files, "mqtt_ca.crt")));
        assertEquals("new ca", read(new File(files, "mqtt_ca.crt" + AppBackup.STAGED_SUFFIX)));

        AppBackup.moveStaged(staged);

        assertEquals("new ca", read(new File(files, "mqtt_ca.crt")));
        assertEquals("old p12", read(new File(files, "mqtt_client.p12")));
        assertFalse(new File(files, "mqtt_ca.crt" + AppBackup.STAGED_SUFFIX).exists());
    }

    @Test public void aRestoreThatStopsBeforeTheMergeLeavesNoStagedFile() throws Exception {
        File backup = tmp.newFolder("backup");
        File files = tmp.newFolder("files");
        write(backup, "mqtt_ca.crt", "new ca");
        write(files, "mqtt_ca.crt", "old ca");

        List<File[]> staged = AppBackup.stageFiles(backup, files, new String[]{"mqtt_ca.crt"});
        AppBackup.discardStaged(staged);

        assertEquals("old ca", read(new File(files, "mqtt_ca.crt")));
        assertEquals(1, files.list().length);
    }

    @Test public void aCopyThatFailsRemovesWhatWasStagedAndChangesNothing() throws Exception {
        File backup = tmp.newFolder("backup");
        File files = tmp.newFolder("files");
        write(backup, "a.crt", "new a");
        write(backup, "b.crt", "new b");
        write(files, "a.crt", "old a");
        // The place b.crt would be staged at is a directory: that copy fails.
        assertTrue(new File(files, "b.crt" + AppBackup.STAGED_SUFFIX).mkdir());

        try {
            AppBackup.stageFiles(backup, files, new String[]{"a.crt", "b.crt"});
            fail("must throw");
        } catch (Exception expected) { }

        assertEquals("old a", read(new File(files, "a.crt")));
        assertFalse(new File(files, "a.crt" + AppBackup.STAGED_SUFFIX).exists());
    }

    // ---- ids kept in prefs ------------------------------------------------

    /** An Editor that only records what is put. */
    private static SharedPreferences.Editor recorder(Map<String, Object> out) {
        final Object[] self = {null};
        self[0] = Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.Editor.class}, (proxy, m, args) -> {
                if (m.getName().startsWith("put")) out.put((String) args[0], args[1]);
                return m.getReturnType() == boolean.class ? (Object) Boolean.TRUE : self[0];
            });
        return (SharedPreferences.Editor) self[0];
    }

    @Test public void idsKeptInPrefsMoveWithTheirRows() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("trip_open_start_sample_id", 12L);
        prefs.put("charge_open_start_sample_id", -1L);      // none
        prefs.put("charge_open_row_id", 3L);
        prefs.put("valet_row_id", 2L);
        prefs.put("drive_card_dismissed_trip", 5L);
        prefs.put("theme", "vapor");
        Map<String, Long> offsets = new HashMap<>();
        offsets.put("telemetry_sample", 1000L);
        offsets.put("charge_session", 40L);
        offsets.put("valet_session", 7L);
        offsets.put("trip", 0L);                            // an empty backup table: no move

        Map<String, Object> put = new HashMap<>();
        AppBackup.shiftIdPrefs(prefs, recorder(put), offsets);

        assertEquals(3, put.size());
        assertEquals(1012L, put.get("trip_open_start_sample_id"));
        assertEquals(43L, put.get("charge_open_row_id"));
        assertEquals(9L, put.get("valet_row_id"));
    }
}
