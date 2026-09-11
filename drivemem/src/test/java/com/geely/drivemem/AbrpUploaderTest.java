package com.geely.drivemem;

import com.geely.drivemem.net.AbrpUploader;
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
        assertEquals(18.2, tlm.getDouble("power"), 0.01);
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
        assertEquals(12.0, tlm.getDouble("power"), 0.01);
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
}
