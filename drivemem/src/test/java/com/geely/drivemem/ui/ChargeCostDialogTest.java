package com.geely.drivemem.ui;

import static org.junit.Assert.assertEquals;

import com.geely.drivemem.state.CarState;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** The cost window closes when the car leaves Park (CLAUDE.md safety rule). */
public class ChargeCostDialogTest {
    @Test public void leavingParkClosesTheWindowOnTheGivenThread() {
        AtomicInteger closed = new AtomicInteger(), posted = new AtomicInteger();
        CarState.Listener l = ChargeCostDialog.closeWhenDriving(closed::incrementAndGet,
            r -> { posted.incrementAndGet(); r.run(); });
        l.onParked(false);
        assertEquals(1, closed.get());
        assertEquals(1, posted.get());
    }

    @Test public void parkingDoesNotCloseIt() {
        AtomicInteger closed = new AtomicInteger();
        CarState.Listener l = ChargeCostDialog.closeWhenDriving(closed::incrementAndGet, Runnable::run);
        l.onParked(true);
        assertEquals(0, closed.get());
    }
}
