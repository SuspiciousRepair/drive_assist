package com.geely.drivemem;

import com.geely.drivemem.net.AbrpUploader;
import com.geely.drivemem.sensors.Obd2Reader;
import com.geely.drivemem.util.Modes;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class AbrpUploaderTest {

    @Test
    public void testPrefConstant() {
        assertEquals("abrp_send_location", AbrpUploader.PREF_SEND_LOCATION);
    }

    @Test
    public void testBuildTlmWithLocation() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("battery", 75);
        data.put("speed", 60.5f);
        data.put("instant_power_kw_est", 18.2f);
        data.put("gear", 8); // Drive
        data.put("odometer", 12500.0f);
        data.put("range", 280.0f);

        double[] loc = new double[]{40.7580, -73.9855, 10.0, 180.0, 16.8, 4.0};

        JSONObject tlm = AbrpUploader.buildTlm(null, data, false, loc);
        assertNotNull("Built payload must not be null", tlm);

        // Verify vehicle data
        assertEquals(75, tlm.getInt("soc"));
        assertEquals(60.5, tlm.getDouble("speed"), 0.01);
        assertFalse("SoC-derived power estimate must not be sent to ABRP", tlm.has("power"));
        assertEquals(0, tlm.getInt("is_charging"));
        assertEquals(0, tlm.getInt("is_parked"));
        assertEquals(12500.0, tlm.getDouble("odometer"), 0.01);
        assertEquals(280.0, tlm.getDouble("est_battery_range"), 0.01);

        // Verify location fields are included when loc is present
        assertTrue("Must include lat", tlm.has("lat"));
        assertEquals(40.7580, tlm.getDouble("lat"), 0.0001);
        assertTrue("Must include lon", tlm.has("lon"));
        assertEquals(-73.9855, tlm.getDouble("lon"), 0.0001);
        assertTrue("Must include elevation", tlm.has("elevation"));
        assertEquals(10.0, tlm.getDouble("elevation"), 0.0001);
        assertTrue("Must include heading", tlm.has("heading"));
        assertEquals(180.0, tlm.getDouble("heading"), 0.0001);
    }

    @Test
    public void testBuildTlmWithoutLocation() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("battery", 82);
        data.put("speed", 45.0f);
        data.put("instant_power_kw_est", 12.0f);
        data.put("gear", 8); // Drive
        data.put("odometer", 12510.0f);
        data.put("range", 310.0f);

        // Location disabled or null GPS fix
        JSONObject tlm = AbrpUploader.buildTlm(null, data, false, null);
        assertNotNull("Built payload must not be null", tlm);

        // Location fields MUST NOT be present
        assertFalse("Must not include lat when location is omitted", tlm.has("lat"));
        assertFalse("Must not include lon when location is omitted", tlm.has("lon"));
        assertFalse("Must not include elevation when location is omitted", tlm.has("elevation"));
        assertFalse("Must not include heading when location is omitted", tlm.has("heading"));

        // Essential vehicle telemetry must still be present
        assertEquals(82, tlm.getInt("soc"));
        assertEquals(45.0, tlm.getDouble("speed"), 0.01);
        assertFalse("SoC-derived power estimate must not be sent to ABRP", tlm.has("power"));
        assertEquals(0, tlm.getInt("is_charging"));
        assertEquals(0, tlm.getInt("is_parked"));
        assertEquals(12510.0, tlm.getDouble("odometer"), 0.01);
        assertEquals(310.0, tlm.getDouble("est_battery_range"), 0.01);
    }

    @Test
    public void testChargingAndParkedStates() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("battery", 50);
        data.put("gear", Modes.GEAR_PARK_ADAPTED);
        data.put("charge_v", 380.0f); // DC fast charging voltage

        // 1. DCFC charging while parked, location omitted
        JSONObject tlmDcfc = AbrpUploader.buildTlm(null, data, true, null);
        assertNotNull(tlmDcfc);
        assertEquals(1, tlmDcfc.getInt("is_charging"));
        assertEquals(1, tlmDcfc.getInt("is_parked"));
        assertEquals(1, tlmDcfc.getInt("is_dcfc"));
        assertFalse(tlmDcfc.has("lat"));

        // 2. AC charging (~220V), location omitted
        data.put("charge_v", 220.0f);
        JSONObject tlmAc = AbrpUploader.buildTlm(null, data, true, null);
        assertNotNull(tlmAc);
        assertEquals(1, tlmAc.getInt("is_charging"));
        assertEquals(0, tlmAc.getInt("is_dcfc"));
    }

    // Real bug, 2026-09-24: an unknown charging state (CarActor's poll
    // hasn't answered, e.g. a transient VHAL hiccup) was being collapsed to
    // "not charging" before reaching ABRP -- both a false is_charging=0
    // claim, and (in sampleOnce(), not reachable from here) a dropped
    // sample entirely whenever this coincided with speed<=1. Same "unknown
    // is a real state" rule Telemetry.java's own is_charging already
    // follows: omit the flag rather than guess it.
    @Test
    public void testUnknownChargingStateOmitsFlagsRatherThanGuessingFalse() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("battery", 50);
        data.put("gear", Modes.GEAR_PARK_ADAPTED);
        data.put("charge_v", 380.0f);

        JSONObject tlm = AbrpUploader.buildTlm(null, data, null, null);
        assertNotNull(tlm);
        assertFalse("is_charging must be omitted, not defaulted to 0, when unknown",
            tlm.has("is_charging"));
        assertFalse("is_dcfc must be omitted when charging state is unknown",
            tlm.has("is_dcfc"));
        // Unrelated fields still go out normally.
        assertEquals(1, tlm.getInt("is_parked"));
        assertEquals(50, tlm.getInt("soc"));
    }

    @Test
    public void testAcChargingReportsNotDcfcEvenWithFreshObdPackVoltage() throws Exception {
        try {
            // Fresh OBD2 reading with battery pack voltage well above 250V (e.g. 395V)
            Obd2Reader.Reading obd = new Obd2Reader.Reading(
                70.0, 395.0, 32.0, 25.0, 7.5, 0, System.currentTimeMillis());
            AbrpUploader.setLastObdReadingForTesting(obd);

            Map<String, Object> data = new HashMap<>();
            data.put("battery", 70);
            data.put("gear", Modes.GEAR_PARK_ADAPTED);
            data.put("charge_v", 230.0f); // Normal AC mains charge port voltage (~230V)

            JSONObject tlm = AbrpUploader.buildTlm(null, data, true, null);
            assertNotNull(tlm);
            assertEquals(1, tlm.getInt("is_charging"));
            assertEquals(0, tlm.getInt("is_dcfc"));
            // OBD2 pack voltage is still preferred for the general "voltage" field
            assertEquals(395.0, tlm.getDouble("voltage"), 0.01);
        } finally {
            AbrpUploader.setLastObdReadingForTesting(null);
        }
    }
}
