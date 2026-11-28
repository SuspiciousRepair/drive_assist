package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class LyricsVisibilityTest {

    @Test public void showsOnlyWhenEverythingHolds() {
        assertTrue(ComfortCardVisibility.showLyrics(true, true, true, true, false));
    }

    @Test public void eachConditionHidesIt() {
        assertFalse("setting off", ComfortCardVisibility.showLyrics(false, true, true, true, false));
        assertFalse("closed with ✕ (last choice)", ComfortCardVisibility.showLyrics(true, false, true, true, false));
        assertFalse("no music card", ComfortCardVisibility.showLyrics(true, true, false, true, false));
        assertFalse("no lines", ComfortCardVisibility.showLyrics(true, true, true, false, false));
        assertFalse("HA panel up", ComfortCardVisibility.showLyrics(true, true, true, true, true));
    }

    @Test public void jumboFitsOnlyWhenTheOthersTakeOneColumn() {
        assertTrue(ComfortCardVisibility.jumboFits(1));
        assertFalse(ComfortCardVisibility.jumboFits(2));
        assertFalse(ComfortCardVisibility.jumboFits(3));
    }

    @Test public void aCrowdTakesTheRoomUntilTheDriverAsksAgain() {
        // A second column of cards came: the card gives way.
        assertFalse(ComfortCardVisibility.jumboPlaced(2, 0));
        // The driver taps Lyrics (the pack turns the ask into the real count).
        assertTrue(ComfortCardVisibility.jumboPlaced(2, Integer.MAX_VALUE));
        assertEquals(2, ComfortCardVisibility.jumboAskAfterPack(2, Integer.MAX_VALUE));
        // The same crowd, or a smaller one: it stays.
        assertTrue(ComfortCardVisibility.jumboPlaced(2, 2));
        assertEquals(2, ComfortCardVisibility.jumboAskAfterPack(2, 2));
    }

    @Test public void moreCardsAfterTheAskTakeTheRoomAgain() {
        assertFalse(ComfortCardVisibility.jumboPlaced(3, 2));
        assertEquals(0, ComfortCardVisibility.jumboAskAfterPack(3, 2));
    }

    @Test public void theAskIsForgottenWhenTheCardFitsByItself() {
        assertTrue(ComfortCardVisibility.jumboPlaced(1, 2));
        assertEquals(0, ComfortCardVisibility.jumboAskAfterPack(1, 2));
        assertEquals(0, ComfortCardVisibility.jumboAskAfterPack(2, 0));
    }

    @Test public void theCardStandsBesideTheDriversColumn() {
        assertEquals(0, ComfortCardVisibility.jumboIndex(0, false));
        assertEquals(1, ComfortCardVisibility.jumboIndex(1, false));
        assertEquals(1, ComfortCardVisibility.jumboIndex(2, false));   // the 2nd column is pushed right
        assertEquals(0, ComfortCardVisibility.jumboIndex(0, true));
        assertEquals(0, ComfortCardVisibility.jumboIndex(1, true));
        assertEquals(1, ComfortCardVisibility.jumboIndex(2, true));    // the driver's column is the last
    }
}
