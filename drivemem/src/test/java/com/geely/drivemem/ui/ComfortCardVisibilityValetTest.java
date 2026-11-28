package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ComfortCardVisibilityValetTest {

    @Test public void valetModeHidesEveryCardExceptClimateAndValetJourney() {
        assertFalse(ComfortCardVisibility.showGateCard(true, true, true));
        assertFalse(ComfortCardVisibility.showTurboCard(true, false, true));
        assertFalse(ComfortCardVisibility.showMusicCard("Song Title", true));
        assertFalse(ComfortCardVisibility.showPanelCard(false, false, true));
        assertFalse(ComfortCardVisibility.showActiveCharging(true, true, true));
        assertFalse(ComfortCardVisibility.showCompletedCharging(true, true, true, false));
        assertFalse(ComfortCardVisibility.showDockStats(true));
        // Journey/Valet card itself remains visible so owner can end Valet
        assertTrue(ComfortCardVisibility.showJourneyCard(true, true, false, false, false));
        assertTrue(ComfortCardVisibility.showJourneyCard(true, false, false, true, false));
    }

    @Test public void normalModeShowsCardsAndDockStatsNormally() {
        assertTrue(ComfortCardVisibility.showGateCard(true, true, false));
        assertTrue(ComfortCardVisibility.showTurboCard(true, false, false));
        assertTrue(ComfortCardVisibility.showMusicCard("Song Title", false));
        assertTrue(ComfortCardVisibility.showPanelCard(false, false, false));
        assertTrue(ComfortCardVisibility.showDockStats(false));
    }

    @Test
    public void phoneDockButtonNeedsAPhoneAndNoValet() {
        assertTrue(ComfortCardVisibility.showDockPhone(false, true));
        assertFalse(ComfortCardVisibility.showDockPhone(false, false));
        assertFalse(ComfortCardVisibility.showDockPhone(true, true));
    }
}
