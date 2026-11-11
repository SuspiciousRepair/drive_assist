package com.geely.drivemem.net;

import org.junit.Test;
import static org.junit.Assert.*;

public class PubDedupeTest {
    @Test public void firstMessageIsSent() {
        assertTrue(new PubDedupe().shouldSend("t", "a", 0));
    }
    @Test public void repeatIsSkippedUntilKeepalive() {
        PubDedupe d = new PubDedupe();
        d.shouldSend("t", "a", 0);
        assertFalse(d.shouldSend("t", "a", 10_000));
        assertTrue(d.shouldSend("t", "a", PubDedupe.KEEPALIVE_MS));
    }
    @Test public void changeIsSentAtOnce() {
        PubDedupe d = new PubDedupe();
        d.shouldSend("t", "a", 0);
        assertTrue(d.shouldSend("t", "b", 1));
    }
    @Test public void topicsAreIndependent() {
        PubDedupe d = new PubDedupe();
        d.shouldSend("t1", "a", 0);
        assertTrue(d.shouldSend("t2", "a", 1));
    }
    @Test public void clearSendsEverythingAgain() {
        PubDedupe d = new PubDedupe();
        d.shouldSend("t", "a", 0);
        d.clear();
        assertTrue(d.shouldSend("t", "a", 1));
    }
    @Test public void forgottenSendIsRetried() {
        PubDedupe d = new PubDedupe();
        d.shouldSend("t", "a", 0);
        d.forget("t");
        assertTrue(d.shouldSend("t", "a", 1));
    }
    @Test public void clockGoingBackwardsSends() {
        PubDedupe d = new PubDedupe();
        d.shouldSend("t", "a", 10_000);
        assertTrue(d.shouldSend("t", "a", 5_000));
    }
}
