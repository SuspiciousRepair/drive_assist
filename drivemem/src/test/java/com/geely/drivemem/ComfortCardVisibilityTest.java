package com.geely.drivemem;

import com.geely.drivemem.ui.ComfortCardVisibility;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for ComfortCardVisibility pure-function visibility rules.
 * Table-driven tests covering all combinations of inputs that matter for each rule.
 */
public class ComfortCardVisibilityTest {

    // === Gate Card Visibility Tests ===

    @Test
    public void testShowGateCardBothConditions() {
        // Visible only when both available AND connected
        assertTrue(ComfortCardVisibility.showGateCard(true, true));
    }

    @Test
    public void testShowGateCardAvailableButNotConnected() {
        // Hidden if connection is lost, even when HA says available
        // (security control: can't verify an outdated "available" state)
        assertFalse(ComfortCardVisibility.showGateCard(true, false));
    }

    @Test
    public void testShowGateCardConnectedButNotAvailable() {
        // Hidden when gate is not available (unreachable)
        assertFalse(ComfortCardVisibility.showGateCard(false, true));
    }

    @Test
    public void testShowGateCardNeitherAvailableNorConnected() {
        // Hidden when both are false
        assertFalse(ComfortCardVisibility.showGateCard(false, false));
    }

    // === Turbo Card Visibility Tests ===

    @Test
    public void testShowTurboCardEnabledAndDriving() {
        // Visible when enabled and actively driving (not parked)
        assertTrue(ComfortCardVisibility.showTurboCard(true, false));
    }

    @Test
    public void testShowTurboCardEnabledButParked() {
        // Hidden while parked, even if enabled
        assertFalse(ComfortCardVisibility.showTurboCard(true, true));
    }

    @Test
    public void testShowTurboCardDisabledAndDriving() {
        // Hidden when disabled, even while driving
        // (card doesn't exist at all if disabled)
        assertFalse(ComfortCardVisibility.showTurboCard(false, false));
    }

    @Test
    public void testShowTurboCardDisabledAndParked() {
        // Hidden when both disabled and parked
        assertFalse(ComfortCardVisibility.showTurboCard(false, true));
    }

    // === Music Card Visibility Tests ===

    @Test
    public void testShowMusicCardWithTitle() {
        // Visible when title is not null and not empty
        assertTrue(ComfortCardVisibility.showMusicCard("Song Title"));
    }

    @Test
    public void testShowMusicCardEmptyTitle() {
        // Hidden when title is empty string
        assertFalse(ComfortCardVisibility.showMusicCard(""));
    }

    @Test
    public void testShowMusicCardNullTitle() {
        // Hidden when no track loaded (title is null)
        assertFalse(ComfortCardVisibility.showMusicCard(null));
    }

    @Test
    public void testShowMusicCardWhitespaceTitle() {
        // Visible even for whitespace-only title
        // (the isEmpty() check doesn't trim)
        assertTrue(ComfortCardVisibility.showMusicCard("  "));
    }

    // === Active Charging Visibility Tests ===

    @Test
    public void testShowActiveChargingWhileParkedAndCharging() {
        // Visible when parked and actively charging
        assertTrue(ComfortCardVisibility.showActiveCharging(true, true));
    }

    @Test
    public void testShowActiveChargingWhileDrivingAndCharging() {
        // Hidden while driving, even if charging signal present
        // (shouldn't happen, but rule enforces it)
        assertFalse(ComfortCardVisibility.showActiveCharging(false, true));
    }

    @Test
    public void testShowActiveChargingParkedButNotCharging() {
        // Hidden when parked but not actively charging
        assertFalse(ComfortCardVisibility.showActiveCharging(true, false));
    }

    @Test
    public void testShowActiveChargingDrivingAndNotCharging() {
        // Hidden when both false
        assertFalse(ComfortCardVisibility.showActiveCharging(false, false));
    }

    // === Completed Charging Visibility Tests ===

    @Test
    public void testShowCompletedChargingWithRetainedSession() {
        // Visible when a completed session exists and hasn't been dismissed
        assertTrue(ComfortCardVisibility.showCompletedCharging(true));
    }

    @Test
    public void testShowCompletedChargingNoRetainedSession() {
        // Hidden when no session to show
        assertFalse(ComfortCardVisibility.showCompletedCharging(false));
    }

    // === Journey Card Visibility Tests ===

    @Test
    public void testShowJourneyCardValetActive() {
        // Visible when Valet is active, regardless of other state
        assertTrue(ComfortCardVisibility.showJourneyCard(true, false, false, false, false));
        assertTrue(ComfortCardVisibility.showJourneyCard(true, true, true, true, true));
    }

    @Test
    public void testShowJourneyCardParked() {
        // Always visible when parked (entry point for Valet), even if drive card disabled
        assertTrue(ComfortCardVisibility.showJourneyCard(false, true, false, false, false));
    }

    @Test
    public void testShowJourneyCardDrivingWithCardEnabledNotDismissed() {
        // Visible when driving, drive card enabled, and not dismissed for this trip
        assertTrue(ComfortCardVisibility.showJourneyCard(false, false, true, true, false));
    }

    @Test
    public void testShowJourneyCardDrivingWithCardEnabledButDismissed() {
        // Hidden when dismissed for this specific trip
        assertFalse(ComfortCardVisibility.showJourneyCard(false, false, true, true, true));
    }

    @Test
    public void testShowJourneyCardDrivingWithCardDisabled() {
        // Hidden when drive card feature is disabled
        assertFalse(ComfortCardVisibility.showJourneyCard(false, false, false, true, false));
    }

    @Test
    public void testShowJourneyCardIdle() {
        // Hidden when not parked, not driving, and Valet not active
        assertFalse(ComfortCardVisibility.showJourneyCard(false, false, true, false, false));
    }

    // === Journey Dismiss Button Visibility Tests ===

    @Test
    public void testShowJourneyDismissWhileDrivingNotInValet() {
        // Visible only while actively driving, and not in Valet mode
        assertTrue(ComfortCardVisibility.showJourneyDismissButton(true, false));
    }

    @Test
    public void testShowJourneyDismissWhileDrivingInValet() {
        // Hidden when Valet is active, even while "driving"
        // (can't dismiss the Valet card's trip, Stop button is there instead)
        assertFalse(ComfortCardVisibility.showJourneyDismissButton(true, true));
    }

    @Test
    public void testShowJourneyDismissWhileParked() {
        // Hidden while parked
        assertFalse(ComfortCardVisibility.showJourneyDismissButton(false, false));
    }

    @Test
    public void testShowJourneyDismissWhileParkedInValet() {
        // Hidden while parked, even in Valet
        assertFalse(ComfortCardVisibility.showJourneyDismissButton(false, true));
    }

    // === Journey Action Button Visibility Tests ===

    @Test
    public void testShowJourneyActionWhileParked() {
        // Visible while parked (Start or Stop Valet)
        assertTrue(ComfortCardVisibility.showJourneyActionButton(true, false));
    }

    @Test
    public void testShowJourneyActionWhileParkedInValet() {
        // Visible while parked and in Valet (Stop button)
        assertTrue(ComfortCardVisibility.showJourneyActionButton(true, true));
    }

    @Test
    public void testShowJourneyActionWhileDrivingInValet() {
        // Visible while Valet is active, even while driving
        // (Stop button must reach driver mid-drive)
        assertTrue(ComfortCardVisibility.showJourneyActionButton(false, true));
    }

    @Test
    public void testShowJourneyActionWhileDrivingNotInValet() {
        // Hidden while driving normally (no action to show)
        assertFalse(ComfortCardVisibility.showJourneyActionButton(false, false));
    }

    // === Panel Card Visibility Tests ===

    @Test
    public void testShowPanelCardWithContentNotDismissed() {
        // Visible when there's content and it hasn't been dismissed
        assertTrue(ComfortCardVisibility.showPanelCard(false, false));
    }

    @Test
    public void testShowPanelCardWithContentButDismissed() {
        // Hidden when content was dismissed
        assertFalse(ComfortCardVisibility.showPanelCard(false, true));
    }

    @Test
    public void testShowPanelCardEmptyContentNotDismissed() {
        // Hidden when payload is empty (no content to show)
        assertFalse(ComfortCardVisibility.showPanelCard(true, false));
    }

    @Test
    public void testShowPanelCardEmptyContentAndDismissed() {
        // Hidden when both empty and dismissed
        assertFalse(ComfortCardVisibility.showPanelCard(true, true));
    }
}
