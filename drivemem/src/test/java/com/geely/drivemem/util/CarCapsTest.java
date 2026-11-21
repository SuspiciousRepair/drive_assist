package com.geely.drivemem.util;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CarCapsTest {

    // Exactly what modehelper's CarCaps writes (its CarCapsTest pins the same
    // text on that side).
    private static File file(String body) throws Exception {
        File f = new File(Files.createTempDirectory("caps").toFile(), CarCaps.FILE);
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(body); }
        return f;
    }

    @Test public void readsWhatModeHelperWrites() throws Exception {
        CarCaps c = CarCaps.read(file("cameras=yes\nconfig=0623AB\ntype=06\nvariant=23\navm=2\nrvc=0\ndvr=0\n"));
        assertEquals(CarCaps.Cameras.YES, c.cameras);
        assertEquals("0623AB", c.config);
        assertEquals("06", c.type);
        assertEquals("23", c.variant);
        assertEquals("2", c.avm);
        assertEquals("0", c.rvc);
        assertEquals("0", c.dvr);
        assertFalse(CarCaps.blocksDashcam(c));
    }

    @Test public void noCamerasHidesTheDashcam() throws Exception {
        CarCaps c = CarCaps.read(file("cameras=no\nconfig=0622\ntype=06\nvariant=22\navm=3\nrvc=2\ndvr=0\n"));
        assertEquals(CarCaps.Cameras.NO, c.cameras);
        assertTrue(CarCaps.blocksDashcam(c));
    }

    @Test public void aRowNotReadHidesTheDashcamToo() throws Exception {
        CarCaps c = CarCaps.read(file("cameras=unknown\n"));
        assertEquals(CarCaps.Cameras.UNKNOWN, c.cameras);
        assertEquals("", c.config);
        assertTrue(CarCaps.blocksDashcam(c));
    }

    // An older ModeHelper writes no file and records without asking, so the
    // settings must stay.
    @Test public void noFileBlocksNothing() throws Exception {
        File missing = new File(Files.createTempDirectory("caps").toFile(), CarCaps.FILE);
        assertNull(CarCaps.read(missing));
        assertNull(CarCaps.read(file("garbage\n")));
        assertFalse(CarCaps.blocksDashcam(null));
    }

    @Test public void theRowIsGroupedForReadingFromAPhoto() {
        assertEquals("", CarCaps.grouped(""));
        assertEquals("0623EAE0 0190", CarCaps.grouped("0623EAE00190"));
        String row = "0623EAE00190460064CC0010041D30000B82032D88200700000F700A100061000000A000058DFD00";
        assertEquals("0623EAE0 01904600 64CC0010 041D3000 0B82032D\n"
            + "88200700 000F700A 10006100 0000A000 058DFD00", CarCaps.grouped(row));
    }
}
