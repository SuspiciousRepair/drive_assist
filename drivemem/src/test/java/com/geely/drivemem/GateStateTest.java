package com.geely.drivemem;

import com.geely.drivemem.state.GateState;

import org.junit.Test;
import static org.junit.Assert.*;

public class GateStateTest {

    private static final class Recorder implements GateState.Listener {
        boolean lastAvailable;
        String lastState;
        int calls;
        @Override public void onGate(boolean available, String state) {
            lastAvailable = available;
            lastState = state;
            calls++;
        }
    }

    @Test public void setAvailableUpdatesAndFiresListener() {
        Recorder r = new Recorder();
        GateState.setListener(r);
        GateState.setAvailable(true);
        assertTrue(GateState.available());
        assertTrue(r.lastAvailable);
        assertTrue(r.calls > 0);
        GateState.setListener(null);
    }

    @Test public void setStateLowercasesAndTrims() {
        Recorder r = new Recorder();
        GateState.setListener(r);
        GateState.setState(" OPEN ");
        assertEquals("open", GateState.state());
        assertEquals("open", r.lastState);
        GateState.setListener(null);
    }

    @Test public void setStateNullOrEmptyBecomesNull() {
        GateState.setState("closed");
        assertEquals("closed", GateState.state());

        GateState.setState(null);
        assertNull(GateState.state());

        GateState.setState("opening");
        assertEquals("opening", GateState.state());

        GateState.setState("");
        assertNull(GateState.state());
    }

    @Test public void setConnectedUpdatesAndFires() {
        Recorder r = new Recorder();
        GateState.setListener(r);
        GateState.setConnected(true);
        assertTrue(GateState.connected());
        assertTrue(r.calls > 0);

        GateState.setConnected(false);
        assertFalse(GateState.connected());
        GateState.setListener(null);
    }

    @Test public void pressWithNoSenderReturnsFalseNoThrow() {
        // Force a known "no sender" state regardless of what other tests left
        // behind: set a sender we control, then clear it with itself.
        final boolean[] tempCalled = {false};
        GateState.Sender temp = new GateState.Sender() {
            @Override public void pressGate() { tempCalled[0] = true; }
        };
        GateState.setSender(temp);
        GateState.clearSender(temp);

        assertFalse(GateState.press());
        assertFalse(tempCalled[0]);
    }

    @Test public void pressWithSenderInvokesPressGateAndReturnsTrue() {
        final boolean[] pressed = {false};
        GateState.Sender s = new GateState.Sender() {
            @Override public void pressGate() { pressed[0] = true; }
        };
        GateState.setSender(s);
        assertTrue(GateState.press());
        assertTrue(pressed[0]);
        GateState.clearSender(s);
    }

    @Test public void clearSenderIsCompareAndClear() {
        final boolean[] s1Pressed = {false};
        GateState.Sender s1 = new GateState.Sender() {
            @Override public void pressGate() { s1Pressed[0] = true; }
        };
        GateState.Sender s2 = new GateState.Sender() {
            @Override public void pressGate() { fail("s2 should never fire"); }
        };

        GateState.setSender(s1);
        GateState.clearSender(s2); // different instance: must NOT clear s1

        assertTrue(GateState.press());
        assertTrue(s1Pressed[0]);

        GateState.clearSender(s1); // same instance: must actually clear
        assertFalse(GateState.press());
    }

    @Test public void replacingSenderViaSetSenderUsesNewestOnly() {
        final boolean[] oldPressed = {false};
        final boolean[] newPressed = {false};
        GateState.Sender oldSender = new GateState.Sender() {
            @Override public void pressGate() { oldPressed[0] = true; }
        };
        GateState.Sender newSender = new GateState.Sender() {
            @Override public void pressGate() { newPressed[0] = true; }
        };

        GateState.setSender(oldSender);
        GateState.setSender(newSender);

        assertTrue(GateState.press());
        assertFalse(oldPressed[0]);
        assertTrue(newPressed[0]);

        GateState.clearSender(newSender);
    }
}
