package com.geely.drivemem.util;

import org.junit.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpxExportTest {
    private static final TimeZone SP = TimeZone.getTimeZone("America/Sao_Paulo");
    // 2026-11-14 10:00 in Sao Paulo (UTC-3) = 13:00 UTC.
    private static final long T0 = 1_794_661_200_000L;

    private static String gpx(GpxExport.Track... t) throws Exception {
        StringWriter w = new StringWriter();
        GpxExport.write(w, Arrays.asList(t), SP);
        return w.toString();
    }

    @Test public void oneTrackPerTripWithTimeHeightAndRawValues() throws Exception {
        GpxExport.Track t = new GpxExport.Track(9, T0, 31.24);
        t.points.add(new GpxExport.Point(T0, -10.5, -50.25, 812.34, 42.0, 11.5, 2, 1));
        t.points.add(new GpxExport.Point(T0 + 5_000, -10.5001, -50.2502, Double.NaN, Double.NaN, Double.NaN, null, null));
        String s = gpx(t);
        assertTrue(s.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\""));
        assertTrue(s.contains("<name>2026-11-14 10:00 (31.2 km)</name>"));
        assertTrue(s.contains("<number>9</number>"));
        assertTrue(s.contains("<trkpt lat=\"-10.500000\" lon=\"-50.250000\"><ele>812.3</ele>"
            + "<time>2026-11-14T13:00:00Z</time><extensions><da:speed_kmh>42.0</da:speed_kmh>"
            + "<da:power_kw>11.50</da:power_kw><da:drive_mode>2</da:drive_mode><da:regen_mode>1</da:regen_mode>"
            + "</extensions></trkpt>"));
        // Nothing recorded: no element at all, never a made-up 0.
        assertTrue(s.contains("<trkpt lat=\"-10.500100\" lon=\"-50.250200\"><time>2026-11-14T13:00:05Z</time></trkpt>"));
        assertTrue(s.endsWith("</gpx>\n"));
    }

    @Test public void tracksStayInOrderAndUnknownDistanceIsLeftOut() throws Exception {
        GpxExport.Track a = new GpxExport.Track(1, T0, Double.NaN);
        GpxExport.Track b = new GpxExport.Track(2, T0 + 3_600_000, 5.0);
        String s = gpx(a, b);
        assertTrue(s.contains("<name>2026-11-14 10:00</name>"));
        assertTrue(s.indexOf("<number>1</number>") < s.indexOf("<number>2</number>"));
    }

    @Test public void numbersUseDotWhateverTheLocale() throws Exception {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("pt", "BR"));
            GpxExport.Track t = new GpxExport.Track(3, T0, 1.5);
            t.points.add(new GpxExport.Point(T0, -10.5, -50.25, 1.5, Double.NaN, Double.NaN, null, null));
            String s = gpx(t);
            assertTrue(s.contains("lat=\"-10.500000\""));
            assertTrue(s.contains("(1.5 km)"));
            assertFalse(s.contains(","));
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    @Test public void emptyFileIsStillValid() throws Exception {
        StringWriter w = new StringWriter();
        GpxExport.write(w, Collections.emptyList(), SP);
        assertTrue(w.toString().endsWith("xmlns:da=\"" + GpxExport.NS + "\">\n</gpx>\n"));
    }

    @Test public void monthRangeIsLocalCalendarMonth() {
        long[] r = GpxExport.monthRange("2026-11", SP);
        assertEquals(1_793_502_000_000L, r[0]);   // 2026-11-01 00:00 -03:00
        assertEquals(1_796_094_000_000L, r[1]);   // 2026-12-01 00:00 -03:00
        long[] dec = GpxExport.monthRange("2026-12", SP);
        assertEquals(r[1], dec[0]);
    }

    @Test public void onlyRealMonthsPass() {
        assertTrue(GpxExport.isMonth("2026-11"));
        assertFalse(GpxExport.isMonth("2026-13"));
        assertFalse(GpxExport.isMonth("2026-1"));
        assertFalse(GpxExport.isMonth("2026-11' OR 1=1"));
        assertFalse(GpxExport.isMonth(null));
    }
}
