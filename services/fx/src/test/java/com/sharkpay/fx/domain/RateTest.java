package com.sharkpay.fx.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import com.sharkpay.money.UnknownCurrencyException;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateTest {

    // ---- construction + value semantics -----------------------------------

    @Test
    void reducesToLowestTermsSoEqualRatesAreEqual() {
        Rate rate = new Rate(12900, 100, "USD", "KES");
        assertEquals(129, rate.numerator());
        assertEquals(1, rate.denominator());
        assertEquals(new Rate(129, 1, "USD", "KES"), rate);
        assertEquals(new Rate(129, 1, "USD", "KES").hashCode(), rate.hashCode());
        assertNotEquals(new Rate(129, 1, "USD", "KES"), new Rate(129, 1, "EUR", "KES"));
        assertTrue(rate.toString().contains("129"));
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(FxDomainException.class, () -> new Rate(0, 1, "USD", "KES"));
        assertThrows(FxDomainException.class, () -> new Rate(-1, 1, "USD", "KES"));
        assertThrows(FxDomainException.class, () -> new Rate(1, 0, "USD", "KES"));
        assertThrows(FxDomainException.class, () -> new Rate(1, -3, "USD", "KES"));
        assertThrows(FxDomainException.class, () -> new Rate(1, 1, null, "KES"));
        assertThrows(FxDomainException.class, () -> new Rate(1, 1, " ", "KES"));
        assertThrows(FxDomainException.class, () -> new Rate(1, 1, "USD", null));
        assertThrows(UnknownCurrencyException.class, () -> new Rate(1, 1, "XYZ", "KES"));
        assertThrows(UnknownCurrencyException.class, () -> new Rate(1, 1, "USD", "XYZ"));
    }

    // ---- conversion: exactness, truncation, dust ---------------------------

    @Test
    void convertsTheCanonicalUsdToKesExampleExactly() {
        // 1 USD (= 100 minor) → 129.00 KES (= 12900 minor) ⇒ rate = 12900/100
        Rate rate = new Rate(12900, 100, "USD", "KES");
        assertEquals(Money.of(12900, "KES"), rate.convert(Money.of(100, "USD")).target());
        assertEquals(Money.of(129, "KES"), rate.convert(Money.of(1, "USD")).target()); // one cent → 1.29 KES
    }

    @Test
    void truncatesFractionsAndReportsDustExactly() {
        // A rate producing fractions: exact value of 100 minor at 1/3 = 33.33…
        Rate rate = new Rate(1, 3, "USD", "KES");
        Rate.ConversionResult result = rate.convert(Money.of(100, "USD"));
        assertEquals(Money.of(33, "KES"), result.target());        // truncated toward zero
        assertEquals(1, result.dustNumerator());                    // exact value = 33 + 1/3
        // recombination invariant — the remainder is never silently lost:
        assertEquals(100L, result.target().amountMinor() * 3 + result.dustNumerator());
    }

    @Test
    void dustIsAlwaysAFractionOfOneMinorUnit() {
        Rate rate = new Rate(1, 3, "USD", "KES");
        for (long amount = 1; amount <= 40; amount++) {
            Rate.ConversionResult result = rate.convert(Money.of(amount, "USD"));
            assertTrue(result.dustNumerator() >= 0 && result.dustNumerator() < 3,
                    "dust must be < one minor unit, got " + result.dustNumerator());
            assertEquals(amount, result.target().amountMinor() * 3 + result.dustNumerator());
        }
    }

    @Test
    void truncatesTowardZeroForNegativeAmounts() {
        Rate rate = new Rate(1, 3, "USD", "KES");
        Rate.ConversionResult result = rate.convert(Money.of(-100, "USD"));
        assertEquals(Money.of(-33, "KES"), result.target());
        assertEquals(-1, result.dustNumerator());
        assertEquals(-100L, result.target().amountMinor() * 3 + result.dustNumerator());
    }

    @Test
    void rejectsNullAndMismatchedSource() {
        Rate rate = new Rate(129, 1, "USD", "KES");
        assertThrows(FxDomainException.class, () -> rate.convert(null));
        assertThrows(CurrencyMismatchException.class, () -> rate.convert(Money.of(100, "KES")));
        assertThrows(CurrencyMismatchException.class, () -> rate.convert(Money.of(100, "EUR")));
    }

    @Test
    void rejectsOverflowOfMinorUnits() {
        Rate rate = new Rate(129, 1, "USD", "KES");
        assertThrows(FxDomainException.class, () -> rate.convert(Money.of(Long.MAX_VALUE / 2, "USD")));
    }

    // ---- exact rational scaling -------------------------------------------

    @Test
    void scaleIsExactRationalArithmetic() {
        Rate raw = new Rate(129, 1, "USD", "KES");
        assertEquals(raw, raw.scale(10000, 10000));
        // 129 × 9850/10000 = 1270650/10000 = 25413/200 in lowest terms
        assertEquals(new Rate(25413, 200, "USD", "KES"), raw.scale(9850, 10000));
        // 129 × 9500/10000 = 2451/20
        assertEquals(new Rate(2451, 20, "USD", "KES"), raw.scale(9500, 10000));
        // 129 × 1/10000
        assertEquals(new Rate(129, 10000, "USD", "KES"), raw.scale(1, 10000));
        assertThrows(FxDomainException.class, () -> raw.scale(0, 10000));
        assertThrows(FxDomainException.class, () -> raw.scale(10, 0));
    }

    @Test
    void scaleOverflowIsRejected() {
        Rate huge = new Rate(Long.MAX_VALUE / 10, 1, "USD", "KES");
        assertThrows(ArithmeticException.class, () -> huge.scale(10000, 1));
    }

    // ---- API wire rendering --------------------------------------------------

    @Test
    void rendersApiRateAsQuoteMinorPerBaseMajorUnit() {
        // 129.00 KES per USD → 12900 KES-minor per 1 USD
        assertEquals(new Rate.ApiRate(12900, 0), new Rate(129, 1, "USD", "KES").toApiRate());
        // 127.065 KES per USD → 12706.5 KES-minor per 1 USD
        assertEquals(new Rate.ApiRate(127065, 1), new Rate(25413, 200, "USD", "KES").toApiRate());
        // 0.125 USD per EUR → 12.5 USD-minor per 1 EUR
        assertEquals(new Rate.ApiRate(125, 1), new Rate(1, 8, "EUR", "USD").toApiRate());
        // 0.7719 USD-minor per KES (i.e. 0.007719 USD per KES)
        assertEquals(new Rate.ApiRate(7719, 4), new Rate(7719, 1_000_000, "KES", "USD").toApiRate());
    }

    @Test
    void foldsBaseAndQuoteExponentsIntoTheApiRate() {
        // 1 USD (exponent 2) = 1.00 USDC (exponent 6) → 1,000,000 USDC-minor per USD
        assertEquals(new Rate.ApiRate(1_000_000, 0), new Rate(10_000, 1, "USD", "USDC").toApiRate());
        // reverse: 1 USDC = 1.00 USD → 100 USD-minor per 1 USDC
        assertEquals(new Rate.ApiRate(100, 0), new Rate(1, 10_000, "USDC", "USD").toApiRate());
    }

    @Test
    void rejectsRatesThatAreNotExactlyDecimal() {
        assertThrows(FxDomainException.class, () -> new Rate(1, 3, "USD", "KES").toApiRate());
        assertThrows(FxDomainException.class, () -> new Rate(10, 21, "USD", "KES").toApiRate());
    }

    @Test
    void rejectsRatesWhoseApiExponentExceedsEighteenDigits() {
        // denominator 2^21 reduced against USD's 10^2 (2^2·5^2) leaves 2^19:
        // 19 fractional digits, above the contract maximum of 18
        assertThrows(FxDomainException.class, () -> new Rate(1, 2_097_152, "USD", "KES").toApiRate());
    }

    @Test
    void equalsIsValueBasedAcrossCurrenciesAndRates() {
        Rate rate = new Rate(129, 1, "USD", "KES");
        assertEquals(rate, rate);
        assertNotEquals(rate, null);
        assertNotEquals(rate, "129/1");
        assertNotEquals(rate, new Rate(128, 1, "USD", "KES"));
        assertNotEquals(rate, new Rate(129, 1, "USD", "EUR"));
        assertTrue(rate.toString().contains("USD->KES"));
    }

    @Test
    void apiRateValidatesItsOwnInvariants() {
        assertThrows(FxDomainException.class, () -> new Rate.ApiRate(0, 0));
        assertThrows(FxDomainException.class, () -> new Rate.ApiRate(1, -1));
        assertThrows(FxDomainException.class, () -> new Rate.ApiRate(1, 19));
    }
}
