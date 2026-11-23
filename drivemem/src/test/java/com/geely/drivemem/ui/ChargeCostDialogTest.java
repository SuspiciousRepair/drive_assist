package com.geely.drivemem.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChargeCostDialogTest {

    @Test public void digitsAreInterpretedAsCents() {
        assertEquals(0, ChargeCostDialog.centsFromText(""));
        assertEquals(1, ChargeCostDialog.centsFromText("0,01"));
        assertEquals(1234, ChargeCostDialog.centsFromText("12,34"));
    }

    @Test public void centsAlwaysShowTwoDecimalPlaces() {
        assertEquals("0,00", ChargeCostDialog.formatCents(0));
        assertEquals("0,01", ChargeCostDialog.formatCents(1));
        assertEquals("12,34", ChargeCostDialog.formatCents(1234));
    }

    @Test public void ratePerKwhBecomesRoundedTotalCost() {
        assertEquals(3.66, ChargeCostDialog.totalCostFromRateCents(60, 6.1), 0.001);
        assertEquals(0.0, ChargeCostDialog.totalCostFromRateCents(0, 14.2), 0.001);
    }
}
