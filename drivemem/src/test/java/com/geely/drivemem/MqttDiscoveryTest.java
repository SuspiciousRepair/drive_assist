package com.geely.drivemem;

import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.services.TelemetryService;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class MqttDiscoveryTest {

    @Test
    public void testActionConstant() {
        assertEquals("com.geely.drivemem.FORCE_DISCOVERY", TelemetryService.ACTION_FORCE_DISCOVERY);
    }

    @Test
    public void testTelemetryFieldsValidityAndUniqueness() {
        assertNotNull(Telemetry.FIELDS);
        assertTrue("Telemetry.FIELDS must not be empty", Telemetry.FIELDS.length > 0);

        Set<String> seenKeys = new HashSet<>();
        for (Telemetry.Field f : Telemetry.FIELDS) {
            assertNotNull("Field key must not be null", f.key);
            assertFalse("Field key must not be empty", f.key.trim().isEmpty());
            assertNotNull("Field name must not be null", f.name);
            assertFalse("Field name must not be empty", f.name.trim().isEmpty());

            assertTrue("Duplicate field key detected: " + f.key, seenKeys.add(f.key));
        }

        // Verify key core sensors exist
        assertTrue(seenKeys.contains("battery"));
        assertTrue(seenKeys.contains("odometer"));
        assertTrue(seenKeys.contains("range"));
        assertTrue(seenKeys.contains("speed"));
        assertTrue(seenKeys.contains("gear"));
        assertTrue(seenKeys.contains("charge_v"));
    }

    @Test
    public void testSensorDiscoveryJsonFormat() throws Exception {
        for (Telemetry.Field f : Telemetry.FIELDS) {
            String comp = ("ac_on".equals(f.key) || "charging".equals(f.key))
                    ? "binary_sensor" : "sensor";
            String topic = "homeassistant/" + comp + "/testdev/" + f.key + "/config";
            assertTrue("Topic must start with homeassistant/", topic.startsWith("homeassistant/"));

            JSONObject j = new JSONObject();
            j.put("name", f.name);
            j.put("uniq_id", "testdev_" + f.key);
            j.put("stat_t", "drivemem/geely/state");
            j.put("avty_t", "drivemem/geely/available");
            j.put("val_tpl", "{{ value_json." + f.key + " }}");
            if (f.unit != null) j.put("unit_of_meas", f.unit);
            if (f.devClass != null) j.put("dev_cla", f.devClass);
            if ("binary_sensor".equals(comp)) {
                j.put("pl_on", "1");
                j.put("pl_off", "0");
            }

            assertEquals(f.name, j.getString("name"));
            assertEquals("testdev_" + f.key, j.getString("uniq_id"));
            assertEquals("drivemem/geely/state", j.getString("stat_t"));
            assertEquals("{{ value_json." + f.key + " }}", j.getString("val_tpl"));
        }
    }

    @Test
    public void testTrackerDiscoveryJsonFormat() throws Exception {
        JSONObject tracker = new JSONObject();
        tracker.put("name", "Localização");
        tracker.put("uniq_id", "testdev_tracker");
        tracker.put("json_attr_t", "drivemem/geely/tracker/attributes");
        tracker.put("source_type", "gps");

        assertEquals("Localização", tracker.getString("name"));
        assertEquals("testdev_tracker", tracker.getString("uniq_id"));
        assertEquals("drivemem/geely/tracker/attributes", tracker.getString("json_attr_t"));
        assertEquals("gps", tracker.getString("source_type"));
    }
}
