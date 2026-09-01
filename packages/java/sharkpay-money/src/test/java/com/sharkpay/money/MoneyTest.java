package com.sharkpay.money;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Nested
    class Construction {

        @Test
        void ofCanonicalizesCurrencyCaseAndWhitespace() {
            Money m = Money.of(1234L, " kes ");
            assertEquals("KES", m.currency());
            assertEquals(1234L, m.amountMinor());
            assertEquals(2, m.exponent());
        }

        @Test
        void ofRejectsUnknownCurrency() {
            assertThrows(UnknownCurrencyException.class, () -> Money.of(1L, "XXX"));
            assertThrows(NullPointerException.class, () -> Money.of(1L, null));
        }

        @Test
        void zeroIsZeroOfCurrency() {
            Money z = Money.zero("usdc");
            assertTrue(z.isZero());
            assertEquals("USDC", z.currency());
            assertEquals(6, z.exponent());
        }
    }

    @Nested
    class Parsing {

        @Test
        void parsesPlainDecimals() {
            assertEquals(1234L, Money.fromString("12.34", "KES").amountMinor());
            assertEquals(1200L, Money.fromString("12", "KES").amountMinor());
            assertEquals(1L, Money.fromString("0.01", "KES").amountMinor());
            assertEquals(-1L, Money.fromString("-0.01", "KES").amountMinor());
            assertEquals(1250L, Money.fromString("+12.5", "KES").amountMinor());
            assertEquals(50L, Money.fromString(" .5 ", "KES").amountMinor());
            assertEquals(50L, Money.fromString(".5", "KES").amountMinor());
            assertEquals(5L, Money.fromString(".05", "KES").amountMinor());
            assertEquals(0L, Money.fromString("0", "KES").amountMinor());
        }

        @Test
        void parsesStablecoinSixDecimals() {
            assertEquals(1L, Money.fromString("0.000001", "USDC").amountMinor());
            assertEquals(1234567L, Money.fromString("1.234567", "USDC").amountMinor());
            assertEquals(1234000000L, Money.fromString("1234", "USDC").amountMinor());
        }

        @Test
        void rejectsMalformedAmounts() {
            for (String bad : new String[]{"", " ", ".", "-", "+", "1a.2", "12.3x4",
                "12..34", "--1", "1,000", "1.2.3"}) {
                assertThrows(InvalidAmountException.class,
                    () -> Money.fromString(bad, "KES"), "should reject: \"" + bad + "\"");
            }
        }

        @Test
        void rejectsTooManyFractionDigits() {
            // KES has exponent 2; three fraction digits must be rejected.
            assertThrows(InvalidAmountException.class, () -> Money.fromString("12.345", "KES"));
        }

        @Test
        void positiveMagnitudeAboveLongMaxOverflows() {
            // 2^63 minor units (one above Long.MAX_VALUE) in KES decimals.
            assertThrows(MoneyOverflowException.class,
                () -> Money.fromString("92233720368547758.08", "KES"));
        }

        @Test
        void negativeMagnitudeAtMinInt64IsAccepted() {
            Money m = Money.fromString("-92233720368547758.08", "KES");
            assertEquals(Long.MIN_VALUE, m.amountMinor());
        }

        @Test
        void negativeMagnitudeBeyondMinInt64Overflows() {
            assertThrows(MoneyOverflowException.class,
                () -> Money.fromString("-92233720368547758.09", "KES"));
        }

        @Test
        void maxLongMinorUnitsRoundTrip() {
            assertEquals(Long.MAX_VALUE,
                Money.fromString("92233720368547758.07", "KES").amountMinor());
        }

        @Test
        void wholeNumbersScaleByExponent() {
            // "9223372036854775807" KES is 922337203685477580700 minor units — overflow.
            assertThrows(MoneyOverflowException.class,
                () -> Money.fromString("9223372036854775807", "KES"));
            assertThrows(MoneyOverflowException.class,
                () -> Money.fromString("-9223372036854775808", "KES"));
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addAndSubtract() {
            Money a = Money.of(100L, "KES");
            Money b = Money.of(23L, "KES");
            assertEquals(123L, a.add(b).amountMinor());
            assertEquals(77L, a.subtract(b).amountMinor());
        }

        @Test
        void addRejectsCurrencyMismatch() {
            assertThrows(CurrencyMismatchException.class, () -> Money.of(1L, "KES").add(Money.of(1L, "USD")));
        }

        @Test
        void subtractRejectsCurrencyMismatch() {
            assertThrows(CurrencyMismatchException.class, () -> Money.of(1L, "KES").subtract(Money.of(1L, "GBP")));
        }

        @Test
        void addOverflowThrows() {
            Money max = Money.of(Long.MAX_VALUE, "KES");
            assertThrows(MoneyOverflowException.class, () -> max.add(Money.of(1L, "KES")));
        }

        @Test
        void subtractOverflowThrows() {
            Money min = Money.of(Long.MIN_VALUE, "KES");
            assertThrows(MoneyOverflowException.class, () -> min.subtract(Money.of(1L, "KES")));
        }

        @Test
        void negateAndAbs() {
            assertEquals(-5L, Money.of(5L, "KES").negate().amountMinor());
            assertEquals(5L, Money.of(-5L, "KES").abs().amountMinor());
            assertEquals(5L, Money.of(5L, "KES").abs().amountMinor());
            // Documented wrap-around: negating MIN_VALUE stays MIN_VALUE.
            assertEquals(Long.MIN_VALUE, Money.of(Long.MIN_VALUE, "KES").negate().amountMinor());
            assertEquals(Long.MIN_VALUE, Money.of(Long.MIN_VALUE, "KES").abs().amountMinor());
        }

        @Test
        void signPredicates() {
            assertTrue(Money.of(-1L, "KES").isNegative());
            assertTrue(Money.of(1L, "KES").isPositive());
            assertTrue(Money.zero("KES").isZero());
            assertFalse(Money.of(1L, "KES").isNegative());
            assertFalse(Money.of(-1L, "KES").isPositive());
        }
    }

    @Nested
    class EqualityOrdering {

        @Test
        void equalityRequiresSameCurrencyAndAmount() {
            assertEquals(Money.of(100L, "KES"), Money.of(100L, "kes"));
            assertEquals(Money.of(100L, "KES").hashCode(), Money.of(100L, "KES").hashCode());
            assertNotEquals(Money.of(100L, "KES"), Money.of(100L, "USD"));
            assertNotEquals(Money.of(100L, "KES"), Money.of(101L, "KES"));
            assertNotEquals(Money.of(100L, "KES"), null);
            assertNotEquals(Money.of(100L, "KES"), "100 KES");
        }

        @Test
        void compareToOrdersSameCurrency() {
            assertTrue(Money.of(1L, "KES").compareTo(Money.of(2L, "KES")) < 0);
            assertTrue(Money.of(2L, "KES").compareTo(Money.of(1L, "KES")) > 0);
            assertEquals(0, Money.of(2L, "KES").compareTo(Money.of(2L, "KES")));
            // Extreme values order correctly.
            assertTrue(Money.of(Long.MIN_VALUE, "KES").compareTo(Money.of(Long.MAX_VALUE, "KES")) < 0);
        }

        @Test
        void compareToRejectsCurrencyMismatch() {
            assertThrows(CurrencyMismatchException.class,
                () -> Money.of(1L, "KES").compareTo(Money.of(1L, "EUR")));
        }
    }

    @Nested
    class Rendering {

        @Test
        void rendersTwoDecimalCurrencies() {
            assertEquals("12.34", Money.of(1234L, "KES").toString());
            assertEquals("-0.01", Money.of(-1L, "KES").toString());
            assertEquals("0.05", Money.of(5L, "KES").toString());
            assertEquals("0.00", Money.zero("KES").toString());
            assertEquals("-12.34", Money.of(-1234L, "KES").toString());
        }

        @Test
        void rendersSixDecimalStablecoins() {
            assertEquals("0.000001", Money.of(1L, "USDC").toString());
            assertEquals("1.234567", Money.of(1234567L, "USDC").toString());
            assertEquals("-0.000001", Money.of(-1L, "USDT").toString());
        }

        @Test
        void rendersExtremeValues() {
            assertEquals("92233720368547758.07", Money.of(Long.MAX_VALUE, "KES").toString());
            assertEquals("-92233720368547758.08", Money.of(Long.MIN_VALUE, "KES").toString());
        }
    }
}
