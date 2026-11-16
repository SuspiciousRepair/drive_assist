package com.geely.drivemem.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpsReaderTest {

    @Test public void onlyTheGpsProviderMayBecomeTheLiveFix() {
        assertTrue(GpsReader.acceptLive("gps"));
        assertFalse(GpsReader.acceptLive("network"));
        assertFalse(GpsReader.acceptLive("passive"));
        assertFalse(GpsReader.acceptLive(null));
    }

    @Test public void aLiveFixIsCurrentForTenSeconds() {
        assertTrue(GpsReader.liveFresh(0));
        assertTrue(GpsReader.liveFresh(2_000));
        assertTrue(GpsReader.liveFresh(GpsReader.LIVE_MAX_AGE_MS));
    }

    @Test public void anOldLiveFixIsNotServedAsCurrent() {
        assertFalse(GpsReader.liveFresh(GpsReader.LIVE_MAX_AGE_MS + 1));
        assertFalse(GpsReader.liveFresh(60 * 60 * 1000L));
        assertFalse(GpsReader.liveFresh(-1));
    }

    @Test public void aFixWithoutAltitudeHasNoValueNotZero() {
        assertTrue(Double.isNaN(GpsReader.altitudeOrNaN(false, 0.0)));
        assertTrue(Double.isNaN(GpsReader.altitudeOrNaN(true, Double.NaN)));
    }

    @Test public void aRealAltitudeIsKeptIncludingZero() {
        assertEquals(712.5, GpsReader.altitudeOrNaN(true, 712.5), 1e-9);
        assertEquals(0.0, GpsReader.altitudeOrNaN(true, 0.0), 1e-9);
        assertEquals(-3.0, GpsReader.altitudeOrNaN(true, -3.0), 1e-9);
    }
}
