package com.geely.drivemem;

import com.geely.drivemem.state.PanelState;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelStateTest {

    private static final class Recorder implements PanelState.Listener {
        String last;
        int calls;
        @Override public void onPanel(String json) { last = json; calls++; }
    }

    @Test public void setUpdatesCurrentAndFiresListener() {
        Recorder r = new Recorder();
        PanelState.setListener(r);

        PanelState.set("{\"a\":1}");

        assertEquals("{\"a\":1}", PanelState.get());
        assertEquals("{\"a\":1}", r.last);
        assertEquals(1, r.calls);

        PanelState.setListener(null);
    }

    @Test public void getReturnsLastSetValueAcrossMultipleSets() {
        PanelState.set("first");
        assertEquals("first", PanelState.get());

        PanelState.set("second");
        assertEquals("second", PanelState.get());

        PanelState.set("third");
        assertEquals("third", PanelState.get());
    }

    @Test public void setWithNoListenerDoesNotThrow() {
        PanelState.setListener(null);
        PanelState.set("no listener here"); // must not throw
        assertEquals("no listener here", PanelState.get());
    }

    @Test public void nullPayloadPropagatesAsIs() {
        Recorder r = new Recorder();
        PanelState.setListener(r);

        PanelState.set("something");
        assertEquals("something", PanelState.get());

        // PanelState.set has no null-guard (unlike GateState.setState): a null
        // payload must pass straight through to both get() and the listener,
        // not be normalized to "" or silently dropped.
        PanelState.set(null);

        assertNull(PanelState.get());
        assertNull(r.last);
        assertEquals(2, r.calls);

        PanelState.setListener(null);
    }
}
