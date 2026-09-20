package com.geely.drivemem.sensors;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class EnergySourceTest {

    @Test public void resolveIsNoDataWhenBothCountsAreZero() {
        assertEquals(EnergySource.NO_DATA, EnergySource.resolve(0, 0));
    }

    @Test public void resolveIsMeasuredWhenOnlyMeasuredSamplesExist() {
        assertEquals(EnergySource.MEASURED, EnergySource.resolve(5, 0));
    }

    @Test public void resolveIsEstimatedWhenOnlyEstimatedSamplesExist() {
        assertEquals(EnergySource.ESTIMATED, EnergySource.resolve(0, 5));
    }

    @Test public void resolveIsMixedWhenBothKindsExist() {
        assertEquals(EnergySource.MIXED, EnergySource.resolve(3, 2));
    }

    @Test public void combineOfAllMeasuredStaysMeasured() {
        assertEquals(EnergySource.MEASURED,
            EnergySource.combine(Arrays.asList(EnergySource.MEASURED, EnergySource.MEASURED)));
    }

    @Test public void combineOfAllEstimatedStaysEstimated() {
        assertEquals(EnergySource.ESTIMATED,
            EnergySource.combine(Arrays.asList(EnergySource.ESTIMATED, EnergySource.ESTIMATED)));
    }

    @Test public void combineOfMeasuredAndEstimatedIsMixed() {
        assertEquals(EnergySource.MIXED,
            EnergySource.combine(Arrays.asList(EnergySource.MEASURED, EnergySource.ESTIMATED)));
    }

    @Test public void combineWithAnyMixedStaysMixed() {
        assertEquals(EnergySource.MIXED,
            EnergySource.combine(Arrays.asList(EnergySource.MIXED, EnergySource.MEASURED)));
    }

    @Test public void combineIgnoresNoDataDaysInsteadOfDraggingARealDayToMixed() {
        assertEquals(EnergySource.MEASURED,
            EnergySource.combine(Arrays.asList(EnergySource.NO_DATA, EnergySource.MEASURED)));
    }

    @Test public void combineOfAllNoDataStaysNoData() {
        assertEquals(EnergySource.NO_DATA,
            EnergySource.combine(Arrays.asList(EnergySource.NO_DATA, EnergySource.NO_DATA)));
    }

    @Test public void combineOfEmptyListIsNoData() {
        assertEquals(EnergySource.NO_DATA, EnergySource.combine(Collections.emptyList()));
    }
}
