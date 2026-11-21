package com.geely.drivemem.art;

import org.junit.Test;

import static org.junit.Assert.*;

/** MotorPedal: the art's pedal value from the drive motor's power. */
public class MotorPedalTest {

    @Test public void regenIsTheMotorsOwnForce() {
        // -20 kW at 50 km/h: 1440 N on 1400 kg = 3.7 km/h per second.
        assertEquals(-0.62f, MotorPedal.of(-20f, 50f), 0.02f);
        assertEquals(-0.26f, MotorPedal.of(-5f, 30f), 0.02f);
        // Stronger regen is a stronger value, and it stops at -1.
        assertTrue(MotorPedal.of(-30f, 50f) < MotorPedal.of(-20f, 50f));
        assertEquals(-1f, MotorPedal.of(-80f, 30f), 0f);
    }

    @Test public void regenShowsAtAHeldSpeedToo() {
        // Down a hill at a constant 60 km/h with 8 kW coming in.
        assertTrue(MotorPedal.of(-8f, 60f) < -0.04f);
    }

    @Test public void holdingASpeedIsNotAPush() {
        // What a flat road costs, about: 80 km/h ~8 kW, 120 km/h ~21 kW,
        // 50 km/h ~3.5 kW. None of it may light the red breath.
        assertEquals(0f, MotorPedal.red(MotorPedal.of(8f, 80f)), 0f);
        assertEquals(0f, MotorPedal.red(MotorPedal.of(21f, 120f)), 0f);
        assertEquals(0f, MotorPedal.red(MotorPedal.of(3.5f, 50f)), 0f);
        // Creeping in a queue, or held on the brake in D.
        assertEquals(0f, MotorPedal.of(0.3f, 0f), 0f);
        assertEquals(0f, MotorPedal.red(MotorPedal.of(1f, 8f)), 0f);
    }

    @Test public void speedingUpIsAPush() {
        // 15 kW at 50 km/h: about 2.2 km/h per second.
        assertEquals(0.37f, MotorPedal.of(15f, 50f), 0.03f);
        assertTrue(MotorPedal.red(MotorPedal.of(15f, 50f)) > 0.2f);
        // Pulling away: 10 kW from a stop is a strong push.
        assertTrue(MotorPedal.of(10f, 0f) > 0.9f);
        assertEquals(1f, MotorPedal.of(60f, 40f), 0f);
    }

    @Test public void zeroPowerIsNothing() {
        assertEquals(0f, MotorPedal.of(0f, 0f), 0f);
        assertEquals(0f, MotorPedal.of(0f, 100f), 0f);
        // A speed below zero (a bad reading) is taken as a stop.
        assertEquals(MotorPedal.of(-5f, 0f), MotorPedal.of(-5f, -3f), 0f);
    }

    @Test public void redStartsLateAndFromZero() {
        assertEquals(0f, MotorPedal.red(-1f), 0f);
        assertEquals(0f, MotorPedal.red(0f), 0f);
        assertEquals(0f, MotorPedal.red(MotorPedal.RED_FROM), 0f);
        assertTrue(MotorPedal.red(MotorPedal.RED_FROM + 0.01f) < 0.02f);   // no jump
        assertEquals(0.5f, MotorPedal.red((1f + MotorPedal.RED_FROM) / 2f), 0.001f);
        assertEquals(1f, MotorPedal.red(1f), 0f);
        assertEquals(1f, MotorPedal.red(2f), 0f);
    }
}
