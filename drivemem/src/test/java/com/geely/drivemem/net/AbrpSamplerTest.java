package com.geely.drivemem.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AbrpSamplerTest {

    @Test public void samplerSleepsLongerWhenTheOptionalIntegrationIsInactive() {
        assertEquals(60_000L, AbrpUploader.samplerDelayMs(false, false));
        assertEquals(60_000L, AbrpUploader.samplerDelayMs(true, false));
    }

    @Test public void samplerKeepsLiveCadenceWithConfigurationAndTelemetry() {
        assertEquals(6_000L, AbrpUploader.samplerDelayMs(true, true));
    }
}
