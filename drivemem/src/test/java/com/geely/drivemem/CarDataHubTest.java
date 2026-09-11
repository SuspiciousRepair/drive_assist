package com.geely.drivemem;

import com.geely.drivemem.car.CarDataHub;

import org.junit.Test;
import static org.junit.Assert.*;

// CarDataHub.Range/ValueSet are the FIRST clamp/reject boundary several car
// writes get (see CarDataHub's own class comment) — worth locking down with
// a test independent of any car or device.
public class CarDataHubTest {

    @Test public void rangeClampsBelowMin() {
        CarDataHub.Range r = new CarDataHub.Range(5, 32);
        assertEquals(5.0, r.clamp(0));
    }

    @Test public void rangeClampsAboveMax() {
        CarDataHub.Range r = new CarDataHub.Range(5, 32);
        assertEquals(32.0, r.clamp(100));
    }

    @Test public void rangePassesThroughInBounds() {
        CarDataHub.Range r = new CarDataHub.Range(5, 32);
        assertEquals(16.0, r.clamp(16));
    }

    @Test public void valueSetResolvesByName() {
        CarDataHub.ValueSet v = CarDataHub.ValueSet.of("off", 0, "sport", 1);
        assertEquals(Integer.valueOf(1), v.resolve("sport"));
    }

    @Test public void valueSetResolvesByRawValueAlreadyInSet() {
        CarDataHub.ValueSet v = CarDataHub.ValueSet.of("off", 0, "sport", 1);
        assertEquals(Integer.valueOf(1), v.resolve(1));
    }

    @Test public void valueSetRejectsUnknownName() {
        CarDataHub.ValueSet v = CarDataHub.ValueSet.of("off", 0, "sport", 1);
        assertNull(v.resolve("turbo"));
    }

    @Test public void valueSetRejectsRawValueNotInSet() {
        CarDataHub.ValueSet v = CarDataHub.ValueSet.of("off", 0, "sport", 1);
        assertNull(v.resolve(99));
    }
}
