package com.geely.drivemem.ui;

import com.geely.drivemem.util.Style;

/** One colour per speed bucket (SpeedBuckets), the same on the stats page
 * and on the trip route screen. */
final class SpeedBucketColors {
    private SpeedBucketColors() {}

    /** Index = SpeedBuckets.of(speed). Call after Style.load. */
    static int[] get() {
        // Keep categorical colors distinct even when ambient lighting makes
        // the theme's ACCENT and COOL identical. Darker variants suit light cards.
        return Style.LIGHT
                ? new int[]{0xFF087F78, 0xFF2463CC, 0xFFC46A09, 0xFFB02CA8}
                : new int[]{0xFF2DD4BF, 0xFF609FFF, 0xFFFFB454, 0xFFE879F9};
    }
}
