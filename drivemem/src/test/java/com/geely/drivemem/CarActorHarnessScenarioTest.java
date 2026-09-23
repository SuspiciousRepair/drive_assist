package com.geely.drivemem;

import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarActorTestHarness;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Realistic scenarios for CarActor polling and charging state detection.
 * Tests the deterministic harness by replaying incidents from 2026-09-23.
 */
public class CarActorHarnessScenarioTest {

    private CarActorTestHarness harness;

    @Before
    public void setUp() {
        try {
            harness = new CarActorTestHarness();
            harness.observeKey("car.is_charging");
            harness.observeKey("car.plug_connected");
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Scenario 1: Charging amps latch high while plug is disconnected.
     * This is the core bug: charge_a reports 12A but plug is physically unplugged.
     * is_charging must report 0 (not charging) because plug_connected=0 overrides.
     */
    @Test
    public void latchtedCurrentWithUnpluggedCableShouldNotChargeCharging() {
        // Charge at 12A with cable connected.
        harness.seedProperty(605291008, 0, 12);  // charge_a = 12A
        harness.seedProperty(557887621, 0, 1);   // plug_connected = 1
        harness.tickNow();

        // Verify is_charging = 1 when current is high and plugged.
        CarActor.Reading charging = harness.actor().get("car.is_charging");
        assertEquals(CarActor.Reading.Status.OK, charging.status);
        assertEquals(1, charging.value);

        // Cable physically unplugged but charge_a latches at 12A.
        harness.seedProperty(557887621, 0, 0);   // plug_connected = 0
        harness.tickNow();

        // is_charging must become 0 despite charge_a still reporting 12A.
        charging = harness.actor().get("car.is_charging");
        assertEquals(CarActor.Reading.Status.OK, charging.status);
        assertEquals(0, charging.value);
        assertTrue("is_charging should have published a change",
            harness.getPublished("car.is_charging").size() >= 2);
    }

    /**
     * Scenario 2: One-tick glitch in current while cable stays connected.
     * A momentary current spike and drop shouldn't cause spurious state flips
     * if the cable stays connected.
     */
    @Test
    public void momentaryCurrentGlitchWithConnectedCableTracksChanges() {
        // Start charging at 12A.
        harness.seedProperty(605291008, 0, 12);
        harness.seedProperty(557887621, 0, 1);
        harness.tickNow();

        CarActor.Reading initial = harness.actor().get("car.is_charging");
        assertEquals(1, initial.value);

        // Cable stays connected but current glitches to 0.5A (threshold edge).
        harness.seedProperty(605291008, 0, 0);  // Current drops below threshold
        harness.tickNow();

        // Should detect the drop (if truly below 0.5A).
        CarActor.Reading glitch = harness.actor().get("car.is_charging");
        assertEquals(0, glitch.value);

        // Cable still connected, current resumes at 12A.
        harness.seedProperty(605291008, 0, 12);
        harness.tickNow();

        // Should detect the resumption.
        CarActor.Reading resumed = harness.actor().get("car.is_charging");
        assertEquals(1, resumed.value);

        // Verify we saw distinct state changes.
        assertTrue("Should publish charging state changes",
            harness.getPublished("car.is_charging").size() >= 3);
    }

    /**
     * Scenario 3: Change-detection: repeated identical poll values don't
     * cause spurious bus publishes.
     */
    @Test
    public void identicalConsecutivePollingDoesNotRepublish() {
        harness.seedProperty(605291008, 0, 12);
        harness.seedProperty(557887621, 0, 1);
        harness.tickNow();

        int initialPublishCount = harness.getPublished("car.is_charging").size();
        assertTrue("First poll should publish", initialPublishCount >= 1);

        // Poll again without changing anything.
        harness.seedProperty(605291008, 0, 12);
        harness.seedProperty(557887621, 0, 1);
        harness.tickNow();

        int repeatPublishCount = harness.getPublished("car.is_charging").size();

        // The second poll should NOT cause a new publish because the value
        // didn't change (no edge, same reading).
        assertEquals("Identical readings should not republish",
            initialPublishCount, repeatPublishCount);
    }

    /**
     * Scenario 4: Plug connected transitions trigger is_charging update
     * within one poll cycle when charge_a is stable.
     */
    @Test
    public void plugConnectToggleDetectedImmediately() {
        // Charging at 15A.
        harness.seedProperty(605291008, 0, 15);
        harness.seedProperty(557887621, 0, 1);
        harness.tickNow();

        assertEquals(1, harness.actor().get("car.is_charging").value);

        // Plug disconnects.
        harness.seedProperty(557887621, 0, 0);
        harness.tickNow();

        // is_charging should immediately reflect 0 in the same tick.
        assertEquals(0, harness.actor().get("car.is_charging").value);

        // Plug reconnects.
        harness.seedProperty(557887621, 0, 1);
        harness.tickNow();

        // is_charging should immediately reflect 1 again.
        assertEquals(1, harness.actor().get("car.is_charging").value);
    }

    /**
     * Scenario 5: Zero current (trickle charging at edge of sensor resolution)
     * should respect plug state. A sensor reporting 0A shouldn't be treated as
     * "definitely not charging" if the cable is connected and a real charge
     * might be happening below the 0.5A threshold.
     */
    @Test
    public void zeroCurrentWithCableConnectedReportsNotCharging() {
        // Cable connected, but no charge current flows (sensor at 0A).
        harness.seedProperty(605291008, 0, 0);
        harness.seedProperty(557887621, 0, 1);
        harness.tickNow();

        // Even though cable is connected, we can't confirm charging below the
        // 0.5A threshold, so is_charging = 0.
        CarActor.Reading reading = harness.actor().get("car.is_charging");
        assertEquals(0, reading.value);
    }
}
