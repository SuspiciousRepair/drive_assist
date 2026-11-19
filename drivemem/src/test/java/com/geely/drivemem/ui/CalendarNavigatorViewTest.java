package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class CalendarNavigatorViewTest {

    private static String[] ranges(double... cuts) {
        return CalendarNavigatorView.scaleRanges(cuts, "none", "under %s km", "%1$s – %2$s km", "%s km or more");
    }

    // The owner's case: the key said "52+ km"; the card spells out all six.
    @Test public void eachColourGetsItsRange() {
        assertArrayEquals(new String[]{"none", "under 10 km", "10 – 19 km", "19 – 31 km",
            "31 – 52 km", "52 km or more"}, ranges(9.7, 19.2, 31.4, 51.6));
    }

    @Test public void noDrivingDaysOnlyHasNone() {
        assertArrayEquals(new String[]{"none"}, ranges());
    }
}
