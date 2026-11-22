package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class LyricsVisibilityTest {

    @Test public void showsOnlyWhenEverythingHolds() {
        assertTrue(ComfortCardVisibility.showLyrics(true, true, true, true, false));
    }

    @Test public void eachConditionHidesIt() {
        assertFalse("setting off", ComfortCardVisibility.showLyrics(false, true, true, true, false));
        assertFalse("driving", ComfortCardVisibility.showLyrics(true, false, true, true, false));
        assertFalse("no music card", ComfortCardVisibility.showLyrics(true, true, false, true, false));
        assertFalse("no lines", ComfortCardVisibility.showLyrics(true, true, true, false, false));
        assertFalse("HA panel up", ComfortCardVisibility.showLyrics(true, true, true, true, true));
    }

    @Test public void fitsInTheThreeColumnsOnScreen() {
        assertTrue(ComfortCardVisibility.lyricsFit(1));
        assertTrue(ComfortCardVisibility.lyricsFit(3));
        assertFalse(ComfortCardVisibility.lyricsFit(4));
    }
}
