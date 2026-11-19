package com.geely.drivemem.sensors;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Pins down the fix for a real reported bug: OBD2 was genuinely connected
 * the whole drive, but the app still showed "estimated" energy. Root cause
 * was applyReading() only computing power when voltage and current both
 * parsed in the exact same poll round -- a single dropped packet on either
 * one (not a real disconnection) left power stuck until they happened to
 * land together again. */
public class Obd2ReaderTest {

    // "62" + PID + data bytes, the same shape Obd2Reader.parseDataBytes
    // expects out of a real dongle response.
    private static final String VOLT_380V = "624B210ED8"; // (14*256+216)/10 = 380.0
    private static final String VOLT_400V = "624B210FA0"; // (15*256+160)/10 = 400.0
    private static final String CURR_50A  = "624B22157C"; // (21*256+124-5000)/10 = 50.0
    private static final String CURR_20A_CHG = "624B2212C0"; // (18*256+192-5000)/10 = -20.0
    private static final String TEMP_21C = "624B483D"; // 0x3D - 40 = 21 C

    @Before public void reset() {
        Obd2Reader.resetForTest();
    }

    /** DID 4B37, from the owner's Car Scanner log: 03E8 = 100.0 % SoH. */
    @Test public void stateOfHealthIsTenthsOfAPercent() {
        assertEquals(true, Obd2Reader.applySoh("7EA05624B3703E8"));
        assertEquals(100.0, Obd2Reader.sohForTest(), 1e-9);
        assertEquals(true, Obd2Reader.applySoh("624B3703A2"));      // 930 -> 93.0
        assertEquals(93.0, Obd2Reader.sohForTest(), 1e-9);
    }

    @Test public void stateOfHealthOutOfRangeOrMissingIsNotKept() {
        Obd2Reader.applySoh("624B3703A2");                          // 93.0
        assertEquals(false, Obd2Reader.applySoh("624B370000"));     // 0 %
        assertEquals(false, Obd2Reader.applySoh("624B37FFFF"));     // 6553.5 %
        assertEquals(false, Obd2Reader.applySoh("NO DATA"));
        assertEquals(false, Obd2Reader.applySoh(null));
        assertEquals(93.0, Obd2Reader.sohForTest(), 1e-9);
        // not connected: the hub gets nothing
        assertNull(Obd2Reader.connectedSoh());
    }

    @Test public void completeRoundComputesPowerNormally() {
        Obd2Reader.applyReading(null, VOLT_380V, CURR_50A, null, null);
        // 380.0V * 50.0A = 19000W = 19.0kW
        assertEquals(19.0f, Obd2Reader.freshPowerKw(5000), 0.001f);
    }

    @Test public void incompleteRoundAloneLeavesPowerUnset() {
        // Only voltage parses this round -- current PID missed (dropped
        // packet, bad byte). Before the fix this was already the case;
        // still correct after it: one half of a pair isn't enough on its
        // own, there's no current to compute against yet.
        Obd2Reader.applyReading(null, VOLT_380V, null, null, null);
        assertNull(Obd2Reader.freshPowerKw(5000));
    }

    @Test public void recoversPowerAcrossTwoIncompleteRoundsInsteadOfStayingStuck() {
        // Round 1: voltage parses, current doesn't (dropped packet).
        Obd2Reader.applyReading(null, VOLT_380V, null, null, null);
        assertNull("no current known yet", Obd2Reader.freshPowerKw(5000));

        // Round 2: current parses, voltage doesn't this time (a different
        // packet drops) -- the two never arrive together in the same
        // round, which is exactly the bug: OBD2 is genuinely connected
        // and both readings ARE known, just not simultaneously.
        Obd2Reader.applyReading(null, null, CURR_50A, null, null);

        // Fixed behavior: power is computed from voltage's latest known
        // value (round 1) and current's latest known value (round 2),
        // not stuck null waiting for a lucky simultaneous pair.
        assertEquals(19.0f, Obd2Reader.freshPowerKw(5000), 0.001f);
    }

    @Test public void laterCompleteRoundUpdatesPowerFromNewValues() {
        Obd2Reader.applyReading(null, VOLT_380V, CURR_50A, null, null);
        assertEquals(19.0f, Obd2Reader.freshPowerKw(5000), 0.001f);

        // A later round with a fresh charging current (negative sign) and
        // a different voltage should fully replace the old power reading,
        // not blend with it.
        Obd2Reader.applyReading(null, VOLT_400V, CURR_20A_CHG, null, null);
        // 400.0V * -20.0A = -8000W = -8.0kW
        assertEquals(-8.0f, Obd2Reader.freshPowerKw(5000), 0.001f);
    }

    @Test public void batteryTemperatureIsDid4B48MinusForty() {
        // Pair with a valid power PID so this synthetic round is fresh.
        Obd2Reader.applyReading(null, VOLT_380V, null, TEMP_21C, null);

        assertEquals(21.0f, Obd2Reader.freshBattTempC(5000), 0.001f);
    }

    @Test public void theOldNonTemperatureDid4B3CIsIgnored() {
        // 4B3C read 67 on a 20 C cold start; a reply to it must not be
        // mistaken for the temperature DID.
        Obd2Reader.applyReading(null, VOLT_380V, null, "624B3C43", null);

        assertNull(Obd2Reader.freshBattTempC(5000));
    }

    /** #144: during a DC fast charge the dongle gave nothing for 38 min and
     * no file said why. A run of silent rounds is now written: once at the
     * start with the raw answers, again once a minute, and when it ends. */
    @Test public void silentRoundsAreLoggedAtStartOncePerMinuteAndAtTheEnd() {
        long t = 1_000_000L;
        assertNull(Obd2Reader.roundEvent(true, t, "624B3602A3", "624B483D"));

        assertEquals("no answer for 0 s: soc=\"7EA 03 7F 22 22\" temp=\"NO DATA\"",
            Obd2Reader.roundEvent(false, t, "7EA 03 7F 22 22\r\n", "NO DATA\r"));
        assertNull(Obd2Reader.roundEvent(false, t + 2_000, "NO DATA", "NO DATA"));
        assertNull(Obd2Reader.roundEvent(false, t + 59_000, "NO DATA", "NO DATA"));
        assertEquals("no answer for 60 s: soc=\"(link error)\" temp=\"NO DATA\"",
            Obd2Reader.roundEvent(false, t + Obd2Reader.EVENT_REPEAT_MS, null, "NO DATA"));

        assertEquals("answers again after 120 s",
            Obd2Reader.roundEvent(true, t + 120_000, "624B3602A3", "624B483D"));
        assertNull(Obd2Reader.roundEvent(true, t + 122_000, "624B3602A3", "624B483D"));

        // A new silent run is written at once, not held back by the old one.
        assertEquals("no answer for 0 s: soc=\"NO DATA\" temp=\"NO DATA\"",
            Obd2Reader.roundEvent(false, t + 124_000, "NO DATA", "NO DATA"));
    }
}
