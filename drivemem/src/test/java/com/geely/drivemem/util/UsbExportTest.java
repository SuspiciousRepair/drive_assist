package com.geely.drivemem.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which files an export writes again. A clip never changes once it is
 * written, so the same size means the same clip. car.db and the CSV files
 * can change and keep their size: they are always copied. */
public class UsbExportTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String dir, String name, String text) throws Exception {
        File d = new File(tmp.getRoot(), dir);
        d.mkdirs();
        File f = new File(d, name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes("UTF-8")); }
        return f;
    }

    @Test public void aChangedFileOfTheSameSizeIsCopiedAgain() throws Exception {
        File car = write("car", "drive_assist_trips.csv", "1,10.5\r\n");
        File stick = write("stick", "drive_assist_trips.csv", "1,99.5\r\n");   // same length, other value
        assertTrue(UsbExport.sameSize(car, stick));
        assertTrue(UsbExport.shouldCopy(car, stick, false));
    }

    @Test public void aClipAlreadyOnTheStickIsSkipped() throws Exception {
        File car = write("car", "dash_20261114_101500.mp4", "0123456789");
        File stick = write("stick", "dash_20261114_101500.mp4", "0123456789");
        assertFalse(UsbExport.shouldCopy(car, stick, true));
    }

    @Test public void aClipCutShortOnTheStickIsCopiedAgain() throws Exception {
        File car = write("car", "dash_20261114_101500.mp4", "0123456789");
        File stick = write("stick", "dash_20261114_101500.mp4", "01234");
        assertTrue(UsbExport.shouldCopy(car, stick, true));
    }

    @Test public void aFileNotOnTheStickIsAlwaysCopied() throws Exception {
        File car = write("car", "car.db", "abc");
        File missing = new File(tmp.getRoot(), "stick/car.db");
        assertTrue(UsbExport.shouldCopy(car, missing, true));
        assertTrue(UsbExport.shouldCopy(car, missing, false));
    }
}
