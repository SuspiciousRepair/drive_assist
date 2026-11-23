package com.geely.drivemem.sensors;

import com.geely.drivemem.car.Aux12vHub;

import org.junit.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Unit tests for Aux12vHub decoding and Aux12vMonitor debouncing, phase
 * classification, predictive health scoring, and baseline reset on 12V change. */
public class Aux12vMonitorTest {

    private static final long DAY = 86_400_000L;
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    public void vhalDecoderConvertsTenthsAndRejectsOutOfBand() {
        assertEquals(12.8f, Aux12vHub.decodeRawVoltage("128"), 0.001f);
        assertEquals(14.0f, Aux12vHub.decodeRawVoltage("140"), 0.001f);
        assertNull(Aux12vHub.decodeRawVoltage("0"));
        assertNull(Aux12vHub.decodeRawVoltage("56"));
        assertNull(Aux12vHub.decodeRawVoltage("250"));
        assertNull(Aux12vHub.decodeRawVoltage("bad"));
    }

    @Test
    public void debouncerRejectsSingleFrameGlitchButAcceptsRealStep() {
        Aux12vMonitor.Debouncer d = new Aux12vMonitor.Debouncer();
        assertEquals(12.7, d.accept(12.7), 0.001);
        // Single-frame 11.4 V glitch (seen in EX2 CAN 0x176 capture) -> rejected
        assertNull(d.accept(11.4));
        // Next sample back at 12.6 V -> accepted, glitch never leaked
        assertEquals(12.6, d.accept(12.6), 0.001);

        // Real DC-DC engagement jump (12.6 -> 14.2 -> 14.2): first buffered, second accepted
        assertNull(d.accept(14.2));
        assertEquals(14.2, d.accept(14.2), 0.001);
    }

    @Test
    public void healthySeriesStaysGoodAndWatchTriggersOnThreeLowMornings() {
        long t0 = 1_800_000_000_000L;
        List<Aux12vMonitor.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            samples.add(new Aux12vMonitor.Sample(t0 + i * DAY, 12.6, Aux12vMonitor.PHASE_RESTING, 20.0, 12.0));
        }
        Aux12vMonitor.Summary good = Aux12vMonitor.evaluate(samples, null, t0 + 12 * DAY, UTC);
        assertEquals(Aux12vMonitor.Health.GOOD, good.health);
        assertEquals(12.6, good.baselineV, 0.01);

        // 3 consecutive mornings below 12.25 V -> WATCH
        samples.add(new Aux12vMonitor.Sample(t0 + 12 * DAY, 12.2, Aux12vMonitor.PHASE_RESTING, 18.0, 12.0));
        samples.add(new Aux12vMonitor.Sample(t0 + 13 * DAY, 12.2, Aux12vMonitor.PHASE_RESTING, 18.0, 12.0));
        samples.add(new Aux12vMonitor.Sample(t0 + 14 * DAY, 12.2, Aux12vMonitor.PHASE_RESTING, 18.0, 12.0));
        Aux12vMonitor.Summary watch = Aux12vMonitor.evaluate(samples, null, t0 + 15 * DAY, UTC);
        assertEquals(Aux12vMonitor.Health.WATCH, watch.health);
        assertEquals(12.2, watch.latestRestingV, 0.01);
    }

    @Test
    public void weakTriggersOnLowRestingOrSevereWakeSagAndResetsOnReplacement() {
        long t0 = 1_800_000_000_000L;
        List<Aux12vMonitor.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            samples.add(new Aux12vMonitor.Sample(t0 + i * DAY, 12.5, Aux12vMonitor.PHASE_RESTING, 20.0, 10.0));
        }
        samples.add(new Aux12vMonitor.Sample(t0 + 5 * DAY, 11.9, Aux12vMonitor.PHASE_RESTING, 18.0, 12.0));
        samples.add(new Aux12vMonitor.Sample(t0 + 6 * DAY, 12.0, Aux12vMonitor.PHASE_RESTING, 18.0, 12.0));
        Aux12vMonitor.Summary weak = Aux12vMonitor.evaluate(samples, null, t0 + 7 * DAY, UTC);
        assertEquals(Aux12vMonitor.Health.WEAK, weak.health);

        // Replace 12V battery on day 7, then 4 healthy mornings -> resets baseline to GOOD
        long replacedAt = t0 + 7 * DAY;
        for (int i = 8; i < 12; i++) {
            samples.add(new Aux12vMonitor.Sample(t0 + i * DAY, 12.7, Aux12vMonitor.PHASE_RESTING, 18.0, 12.0));
        }
        Aux12vMonitor.Summary fresh = Aux12vMonitor.evaluate(samples, replacedAt, t0 + 12 * DAY, UTC);
        assertEquals(Aux12vMonitor.Health.GOOD, fresh.health);
        assertEquals(12.7, fresh.baselineV, 0.01);
        assertEquals(4, fresh.morningTrend.size());
    }

    @Test
    public void dcdcOutOfRangeOrFactoryWarnTriggersFault() {
        long t0 = 1_800_000_000_000L;
        List<Aux12vMonitor.Sample> samples = new ArrayList<>();
        samples.add(new Aux12vMonitor.Sample(t0, 12.9, Aux12vMonitor.PHASE_RESTING, 20.0, 10.0));
        // Smart EV float at 12.9 V while driving/charging is healthy, never a fault
        samples.add(new Aux12vMonitor.Sample(t0 + 1000L, 12.9, Aux12vMonitor.PHASE_DCDC, 20.0, null));
        Aux12vMonitor.Summary floatOk = Aux12vMonitor.evaluate(samples, null, t0 + 2000L, UTC);
        assertEquals(Aux12vMonitor.Health.GOOD, floatOk.health);

        // Real DC-DC under-load collapse (<12.1 V while driving) -> FAULT
        samples.add(new Aux12vMonitor.Sample(t0 + 3000L, 11.8, Aux12vMonitor.PHASE_DCDC, 20.0, null));
        Aux12vMonitor.Summary lowFault = Aux12vMonitor.evaluate(samples, null, t0 + 4000L, UTC);
        assertEquals(Aux12vMonitor.Health.FAULT, lowFault.health);

        // Recovery to normal 14.0 V clears transient DC-DC fault
        samples.add(new Aux12vMonitor.Sample(t0 + 5000L, 14.0, Aux12vMonitor.PHASE_DCDC, 20.0, null));
        Aux12vMonitor.Summary recovered = Aux12vMonitor.evaluate(samples, null, t0 + 6000L, UTC);
        assertEquals(Aux12vMonitor.Health.GOOD, recovered.health);

        // Overvoltage (>14.9 V) -> FAULT
        samples.add(new Aux12vMonitor.Sample(t0 + 7000L, 15.2, Aux12vMonitor.PHASE_DCDC, 20.0, null));
        Aux12vMonitor.Summary fault = Aux12vMonitor.evaluate(samples, null, t0 + 8000L, UTC);
        assertEquals(Aux12vMonitor.Health.FAULT, fault.health);
        assertNotNull(fault.latestReadyV);

        // Parked + AC charging at 12.9 V classifies as RESTING (not DCDC_CHARGING)
        assertEquals(Aux12vMonitor.PHASE_RESTING,
            Aux12vMonitor.classifyPhase(12.9, true, true, false, false));
    }

    @Test
    public void spuriousWarnAtHealthyVoltageIsIgnoredWhileLowVoltageWarnFaults() {
        long t0 = 1_800_000_000_000L;
        List<Aux12vMonitor.Sample> samples = new ArrayList<>();
        samples.add(new Aux12vMonitor.Sample(t0, 13.9, Aux12vMonitor.PHASE_DCDC, 32.0, null));
        // Reproduces live EX2 PEPS_WARNING_BATTERY_SAVING firing while bus was 13.9 V
        samples.add(new Aux12vMonitor.Sample(t0 + 60_000L, 13.9, Aux12vMonitor.PHASE_WARN, 32.0, null));
        samples.add(new Aux12vMonitor.Sample(t0 + 120_000L, 12.9, Aux12vMonitor.PHASE_RESTING, 31.5, null));
        Aux12vMonitor.Summary ok = Aux12vMonitor.evaluate(samples, null, t0 + 180_000L, UTC);
        assertEquals(Aux12vMonitor.Health.GOOD, ok.health);

        // Genuine low-voltage factory warning (< 12.6 V) still triggers FAULT
        samples.add(new Aux12vMonitor.Sample(t0 + 240_000L, 11.7, Aux12vMonitor.PHASE_WARN, 25.0, null));
        Aux12vMonitor.Summary bad = Aux12vMonitor.evaluate(samples, null, t0 + 300_000L, UTC);
        assertEquals(Aux12vMonitor.Health.FAULT, bad.health);
    }
}
