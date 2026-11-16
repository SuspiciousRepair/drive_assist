package com.geely.drivemem.car;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** telemetry.tick listeners read the hub cache (ChargeSession reads
 * energy.power_kw, the 30 s sampler reads the modes). The cache has no age,
 * so a tick must run its due polls BEFORE it publishes telemetry.tick. It
 * used to publish first: after a long suspend the first tick handed its
 * listeners the values from before the sleep. */
public class CarActorTickOrderTest {

    @Test public void telemetryTickListenersSeeThisTicksPolls() {
        CarActorTestHarness h = new CarActorTestHarness();
        h.car().seedIntRaw(605291008, 0, 12);   // charge_a, so the snapshot is not empty
        final int[] source = {0};
        h.actor().registerPoll("test.value", 2000, c -> CarActor.Reading.ok(source[0]));
        h.tickNow();   // runs the queued registration; the poll exists from here on
        source[0] = 1;

        List<Object> seen = new ArrayList<>();
        EntityBus.Listener l = (key, reading) -> seen.add(h.actor().get("test.value").value);
        EntityBus.subscribe("telemetry.tick", l);
        try {
            h.tickNow();     // the cache still holds 0; the poll must run first
            source[0] = 2;   // the value changed while the head unit slept
            h.tickNow();
        } finally {
            EntityBus.unsubscribe("telemetry.tick", l);
        }
        assertEquals(java.util.Arrays.asList((Object) 1, (Object) 2), seen);
    }
}
