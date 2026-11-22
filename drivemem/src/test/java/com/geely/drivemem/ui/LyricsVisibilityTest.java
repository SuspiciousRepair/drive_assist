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

    @Test public void spansTheColumnsTheOtherCardsLeaveFree() {
        assertEquals(2, ComfortCardVisibility.lyricsSpan(true, 1));
        assertEquals(1, ComfortCardVisibility.lyricsSpan(true, 2));
        assertEquals("no free column", 0, ComfortCardVisibility.lyricsSpan(true, 3));
        assertEquals("cards already scroll", 0, ComfortCardVisibility.lyricsSpan(true, 4));
        assertEquals("not wanted", 0, ComfortCardVisibility.lyricsSpan(false, 1));
    }
}
