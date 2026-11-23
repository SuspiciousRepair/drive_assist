package com.geely.drivemem;

import static org.junit.Assert.assertEquals;

import com.geely.drivemem.util.Prefs;

import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The currency symbol chosen in Config → Elements → Localization. */
public class PrefsCurrencyTest {
    private Locale saved;

    @Before public void setUp() { saved = Locale.getDefault(); }

    @After public void tearDown() {
        Locale.setDefault(saved);
        Prefs.setCurrency(null, Prefs.DEFAULT_CURRENCY);
    }

    @Test public void emptyOrMissingFallsBackToReal() {
        assertEquals("R$", Prefs.normalizeCurrency(null));
        assertEquals("R$", Prefs.normalizeCurrency(""));
        assertEquals("R$", Prefs.normalizeCurrency("   "));
    }

    @Test public void customSymbolIsTrimmed() {
        assertEquals("CHF", Prefs.normalizeCurrency("  CHF "));
    }

    @Test public void moneyUsesTheChosenSymbol() {
        Locale.setDefault(new Locale("pt", "BR"));
        Prefs.setCurrency(null, "€");
        assertEquals("€", Prefs.currencySymbol());
        assertEquals("€ 1.234,50", Prefs.formatMoney(1234.5));
        assertEquals("€ 0,84/kWh", Prefs.formatMoneyPerKwh(0.84));
    }

    @Test public void defaultIsReal() {
        Locale.setDefault(Locale.US);
        Prefs.setCurrency(null, null);
        assertEquals("R$ 42.80", Prefs.formatMoney(42.8));
    }
}
