package com.geely.drivemem.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AbrpSamplerTest {

    @Test public void samplerSleepsLongerWhenTheOptionalIntegrationIsInactive() {
        assertEquals(60_000L, AbrpUploader.samplerDelayMs(false, false));
        assertEquals(60_000L, AbrpUploader.samplerDelayMs(true, false));
    }

    @Test public void samplerKeepsLiveCadenceWithConfigurationAndTelemetry() {
        assertEquals(6_000L, AbrpUploader.samplerDelayMs(true, true));
    }

    // Real bug, 2026-09-25: a stale batch (16 min old, delayed by a dead-zone
    // parking garage) went out via /bulk one second before the next live
    // point. ABRP's own docs say /bulk sits in a ~60s server-side processing
    // queue while /send (live) has none -- so the live "driving" point very
    // likely reconciled before the stale "parked" one did, and the trip
    // never split in ABRP's own history. shouldWaitForBulkWindow() decides
    // whether that race is even possible for a given batch.
    @Test public void doesNotWaitWhenNothingWasQueued() {
        assertFalse(AbrpUploader.shouldWaitForBulkWindow(0, 1_000_000L));
    }

    @Test public void doesNotWaitForAFreshBatch() {
        // A normal ~1-minute batch of continuous driving data: oldest
        // sample is 50s old, well inside ABRP's own 60s window already.
        long now = 1_000_000L;
        assertFalse(AbrpUploader.shouldWaitForBulkWindow(now - 50, now));
    }

    @Test public void waitsForAStaleBatchThatOutlivedTheBulkWindow() {
        long now = 1_000_000L;
        // 16 minutes old, matching the real incident.
        assertTrue(AbrpUploader.shouldWaitForBulkWindow(now - 960, now));
    }

    @Test public void boundaryAtExactlyTheWindowDoesNotWait() {
        long now = 1_000_000L;
        assertFalse(AbrpUploader.shouldWaitForBulkWindow(now - 60, now));
        assertTrue(AbrpUploader.shouldWaitForBulkWindow(now - 61, now));
    }
}
