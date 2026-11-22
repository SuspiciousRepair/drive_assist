package com.geely.drivemem.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class LrcTest {

    @Test public void readsTimesAndTextInOrder() {
        Lrc l = Lrc.parse("[ar:Someone]\n[00:01.50]one\n[00:03.25]two\n[01:02.345]three\n");
        assertNotNull(l);
        assertEquals(3, l.size());
        assertEquals(1500, l.timeAt(0));
        assertEquals(3250, l.timeAt(1));
        assertEquals(62_345, l.timeAt(2));
        assertEquals("three", l.textAt(2));
    }

    @Test public void oneDigitFractionIsTenths_noFractionIsWholeSeconds() {
        Lrc l = Lrc.parse("[00:02.5]a\r\n[00:04]b");
        assertEquals(2500, l.timeAt(0));
        assertEquals(4000, l.timeAt(1));
    }

    @Test public void repeatedTagsRepeatTheLineAndAreSorted() {
        Lrc l = Lrc.parse("[00:10.00][00:30.00]chorus\n[00:20.00]verse");
        assertEquals(3, l.size());
        assertEquals("chorus", l.textAt(0));
        assertEquals("verse", l.textAt(1));
        assertEquals("chorus", l.textAt(2));
        assertEquals(30_000, l.timeAt(2));
    }

    @Test public void positiveOffsetMakesLinesEarlier() {
        Lrc l = Lrc.parse("[offset:+500]\n[00:02.00]a");
        assertEquals(1500, l.timeAt(0));
    }

    @Test public void emptyTextIsKeptAsAGap() {
        Lrc l = Lrc.parse("[00:01.00]a\n[00:05.00]\n[00:09.00]b");
        assertEquals(3, l.size());
        assertEquals("", l.textAt(1));
    }

    @Test public void tagInTheMiddleOfTheTextIsText() {
        Lrc l = Lrc.parse("[00:01.00]see [00:02.00] here");
        assertEquals(1, l.size());
        assertEquals("see [00:02.00] here", l.textAt(0));
    }

    @Test public void plainLyricsOrNothingGiveNull() {
        assertNull(Lrc.parse(null));
        assertNull(Lrc.parse(""));
        assertNull(Lrc.parse("just words\nno times"));
    }

    @Test public void indexAtFindsTheLineSungNow() {
        Lrc l = Lrc.parse("[00:01.00]a\n[00:03.00]b\n[00:05.00]c");
        assertEquals(-1, l.indexAt(0));
        assertEquals(-1, l.indexAt(999));
        assertEquals(0, l.indexAt(1000));
        assertEquals(0, l.indexAt(2999));
        assertEquals(1, l.indexAt(3000));
        assertEquals(2, l.indexAt(600_000));
    }
}
