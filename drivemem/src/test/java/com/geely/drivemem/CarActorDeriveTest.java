package com.geely.drivemem;

import com.geely.drivemem.car.CarActor;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarActorDeriveTest {

    /** Reference implementation of the pre-refactor direct-poll behavior
     * from CarActor.java lines 140-147:
     *   String v = c.readAny(605291008, 0, 'f');
     *   if (v == null) return Reading.error("no reading");
     *   float a = Float.parseFloat(v);
     *   String plugV = c.readAny(557887621, 0, 'i');
     *   boolean plugged = plugV != null && !"0".equals(plugV);
     *   return Reading.ok((a > 0.5f && plugged) ? 1 : 0);
     */
    private static CarActor.Reading oldDirectPoll(Float currentA, Integer plugRaw) {
        if (currentA == null) return CarActor.Reading.error("no reading");
        boolean plugged = plugRaw != null && plugRaw != 0;
        return CarActor.Reading.ok((currentA > 0.5f && plugged) ? 1 : 0);
    }

    private static CarActor.Reading toReadingA(Float currentA) {
        return currentA != null ? CarActor.Reading.ok(currentA) : CarActor.Reading.error("no reading");
    }

    private static CarActor.Reading toReadingPlug(Integer plugRaw) {
        return plugRaw != null ? CarActor.Reading.ok(plugRaw) : CarActor.Reading.error("no reading");
    }

    @Test
    public void deriveMatchesOldDirectPollAcrossAllInputCombinations() {
        Float[] testCurrents = new Float[]{
            16.0f,     // Active high current
            10.0f,     // Standard charging current
            0.51f,     // Just above 0.5A threshold
            0.50f,     // Exactly on threshold
            0.49f,     // Just below threshold
            0.0f,      // Zero current
            -5.0f,     // Negative / discharge current
            null       // Sensor read failure (null/error)
        };

        Integer[] testPlugs = new Integer[]{
            1,         // Plugged in
            0,         // Unplugged
            null       // Sensor read failure (null/error)
        };

        for (Float current : testCurrents) {
            for (Integer plug : testPlugs) {
                CarActor.Reading oldResult = oldDirectPoll(current, plug);
                CarActor.Reading newResult = CarActor.deriveIsCharging(toReadingA(current), toReadingPlug(plug));

                assertEquals("Status mismatch for current=" + current + ", plug=" + plug,
                    oldResult.status, newResult.status);

                if (oldResult.status == CarActor.Reading.Status.OK) {
                    assertEquals("Value mismatch for current=" + current + ", plug=" + plug,
                        oldResult.value, newResult.value);
                }
            }
        }
    }

    @Test
    public void deriveFromMechanismRecomputesOnDependencyUpdates() {
        CarActor actor = new CarActor(null);

        actor.deriveFrom("car.is_charging", new String[]{"car.charge_a", "car.plug_connected"},
            CarActor::deriveIsCharging);

        // Before dependencies are populated, status is not OK
        assertNotEquals(CarActor.Reading.Status.OK, actor.get("car.is_charging").status);

        // Populate dependencies: 11.4A, plugged in (1) -> is_charging = 1
        actor.putForTesting("car.charge_a", CarActor.Reading.ok(11.4f));
        actor.putForTesting("car.plug_connected", CarActor.Reading.ok(1));

        CarActor.Reading r1 = actor.get("car.is_charging");
        assertEquals(CarActor.Reading.Status.OK, r1.status);
        assertEquals(1, r1.value);
        assertEquals(Boolean.TRUE, CarActor.chargingFrom(r1));

        // Unplug cable: charge_a remains latched at 11.4A, plug_connected becomes 0 -> is_charging = 0
        actor.putForTesting("car.plug_connected", CarActor.Reading.ok(0));

        CarActor.Reading r2 = actor.get("car.is_charging");
        assertEquals(CarActor.Reading.Status.OK, r2.status);
        assertEquals(0, r2.value);
        assertEquals(Boolean.FALSE, CarActor.chargingFrom(r2));

        // Cable replugged: plug_connected becomes 1 -> is_charging becomes 1 again
        actor.putForTesting("car.plug_connected", CarActor.Reading.ok(1));

        CarActor.Reading r3 = actor.get("car.is_charging");
        assertEquals(CarActor.Reading.Status.OK, r3.status);
        assertEquals(1, r3.value);
        assertEquals(Boolean.TRUE, CarActor.chargingFrom(r3));

        // Current sensor failure: error propagates
        actor.putForTesting("car.charge_a", CarActor.Reading.error("sensor offline"));

        CarActor.Reading r4 = actor.get("car.is_charging");
        assertEquals(CarActor.Reading.Status.ERROR, r4.status);
        assertNull(CarActor.chargingFrom(r4));
    }
}
