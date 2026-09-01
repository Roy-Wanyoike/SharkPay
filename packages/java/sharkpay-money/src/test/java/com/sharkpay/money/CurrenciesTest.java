package com.sharkpay.money;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrenciesTest {

    @Test
    void normalizeTrimsUppercasesAndRejectsUnknown() {
        assertEquals("KES", Currencies.normalize(" kes "));
        assertEquals("USD", Currencies.normalize("usd"));
        assertEquals("USDC", Currencies.normalize("UsDc"));
        assertThrows(UnknownCurrencyException.class, () -> Currencies.normalize("XXX"));
        assertThrows(UnknownCurrencyException.class, () -> Currencies.normalize(""));
        assertThrows(UnknownCurrencyException.class, () -> Currencies.normalize(null));
    }

    @Test
    void exponentForMatchesV1CurrencyTable() {
        assertEquals(2, Currencies.exponentFor("KES"));
        assertEquals(2, Currencies.exponentFor("USD"));
        assertEquals(2, Currencies.exponentFor("EUR"));
        assertEquals(2, Currencies.exponentFor("GBP"));
        assertEquals(6, Currencies.exponentFor("USDC"));
        assertEquals(6, Currencies.exponentFor("USDT"));
    }

    @Test
    void supportedReturnsSortedCodes() {
        List<String> codes = List.copyOf(Currencies.supported());
        assertEquals(List.of("EUR", "GBP", "KES", "USD", "USDC", "USDT"), codes);
    }

    @Test
    void isSupportedIsCaseInsensitive() {
        assertTrue(Currencies.isSupported("kes"));
        assertTrue(Currencies.isSupported("USDT"));
        assertFalse(Currencies.isSupported("BTC"));
        assertFalse(Currencies.isSupported(null));
    }

    @Test
    void supportedSetIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
            () -> Currencies.supported().add("BTC"));
    }
}
