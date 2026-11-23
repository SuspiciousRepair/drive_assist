package com.geely.drivemem.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class ArtColorTest {

    @Test public void theColourWithMostPixelsWins() {
        int[] px = {0xFFCC2020, 0xFFCC2020, 0xFFCC2020, 0xFF2020CC, 0xFF2020CC, 0xFFFFFFFF};
        assertEquals(0xFFCC2020, ArtColor.dominant(px, 0));
    }

    @Test public void nearbyShadesCountAsOneAndAverage() {
        // 0x40..0x4F share a group: two of them beat one bright pixel twice.
        int[] px = {0xFF404040, 0xFF4E4E4E, 0xFFF0F0F0};
        assertEquals(0xFF474747, ArtColor.dominant(px, 0));
    }

    @Test public void transparentPixelsDoNotCount() {
        int[] px = {0x00FF0000, 0x00FF0000, 0x00FF0000, 0xFF00AA00};
        assertEquals(0xFF00AA00, ArtColor.dominant(px, 0));
    }

    @Test public void noPixelGivesTheFallback() {
        assertEquals(0xFF123456, ArtColor.dominant(new int[0], 0xFF123456));
        assertEquals(0xFF123456, ArtColor.dominant(null, 0xFF123456));
        assertEquals(0xFF123456, ArtColor.dominant(new int[]{0x00FFFFFF}, 0xFF123456));
    }

    @Test public void textIsDarkOnLightAndWhiteOnDark() {
        assertEquals(0xFF111111, ArtColor.textOn(0xFFFFFFFF));
        assertEquals(0xFF111111, ArtColor.textOn(0xFFF2D04A));   // a yellow cover
        assertEquals(0xFFFFFFFF, ArtColor.textOn(0xFF000000));
        assertEquals(0xFFFFFFFF, ArtColor.textOn(0xFF1E3A8A));   // a deep blue cover
    }
}
