package com.sharkpay.money;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, integer-only money value: {@code amountMinor} in minor units,
 * a canonical currency code, and that currency's minor-unit exponent.
 * <p>
 * Semantics are a 1:1 port of {@code packages/go/money} so the Java and Go
 * runtimes agree on every edge case. Construction always validates (the
 * constructor is private), so an instance can never hold an unknown
 * currency or a mismatched exponent.
 * <p>
 * <b>Floats are forbidden anywhere in money code.</b> All arithmetic is
 * {@code long}/{@code BigInteger} on minor units; overflow throws
 * {@link MoneyOverflowException} instead of wrapping silently.
 * <p>
 * {@link #toString()} renders the decimal form only (e.g. {@code "12.34"}),
 * without the currency — mirroring the Go implementation.
 */
public final class Money implements Comparable<Money> {

    private final long amountMinor;
    private final String currency;
    private final int exponent;

    private Money(long amountMinor, String currency, int exponent) {
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.exponent = exponent;
    }

    /**
     * Creates a Money from minor units and a currency code
     * (case-insensitive; surrounding whitespace tolerated).
     */
    public static Money of(long amountMinor, String currency) {
        Objects.requireNonNull(currency, "currency");
        String cur = Currencies.normalize(currency);
        return new Money(amountMinor, cur, Currencies.exponentFor(cur));
    }

    /** Creates a zero Money of the given currency. */
    public static Money zero(String currency) {
        return of(0L, currency);
    }

    /**
     * Parses a strict decimal string such as {@code "12.34"}, {@code "-0.01"},
     * {@code "+12.5"} or {@code "12"} into a Money of the given currency.
     * The string may carry at most {@code exponent} fraction digits; fewer
     * are right-padded with zeros. No double/float is ever involved.
     *
     * @throws InvalidAmountException if the string is not a valid decimal
     *         amount for the currency's exponent
     * @throws MoneyOverflowException if the magnitude does not fit in a
     *         signed 64-bit minor-unit amount
     */
    public static Money fromString(String amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        String cur = Currencies.normalize(currency);
        int exp = Currencies.exponentFor(cur);

        String s = amount.trim();
        if (s.isEmpty() || s.equals(".") || s.equals("-") || s.equals("+")) {
            throw new InvalidAmountException(amount);
        }
        boolean negative = false;
        char first = s.charAt(0);
        if (first == '-' || first == '+') {
            negative = first == '-';
            s = s.substring(1);
        }
        String intPart = s;
        String fracPart = "";
        int dot = s.indexOf('.');
        if (dot >= 0) {
            intPart = s.substring(0, dot);
            fracPart = s.substring(dot + 1);
        }
        if (intPart.isEmpty()) {
            intPart = "0";
        }
        if (!allDigits(intPart) || !allDigits(fracPart)) {
            throw new InvalidAmountException(amount);
        }
        if (fracPart.length() > exp) {
            throw new InvalidAmountException(
                "\"" + amount + "\" has more than " + exp + " fraction digit(s) for " + cur);
        }
        if (fracPart.length() < exp) {
            fracPart = fracPart + "0".repeat(exp - fracPart.length());
        }
        BigInteger magnitude = new BigInteger(intPart + fracPart);

        long value;
        if (negative) {
            // |MinInt64| = 2^63 is not a positive long but its negation is
            // representable, so compare against the exact bound.
            BigInteger minInt64 = BigInteger.TWO.pow(63);
            if (magnitude.compareTo(minInt64) > 0) {
                throw new MoneyOverflowException("\"" + amount + "\"");
            }
            value = magnitude.negate().longValue();
        } else {
            if (magnitude.bitLength() > 63) {
                throw new MoneyOverflowException("\"" + amount + "\"");
            }
            value = magnitude.longValue();
        }
        return new Money(value, cur, exp);
    }

    /** The amount in minor units (e.g. cents). */
    public long amountMinor() {
        return amountMinor;
    }

    /** The canonical, uppercase currency code. */
    public String currency() {
        return currency;
    }

    /** The currency's minor-unit exponent (e.g. 2 for KES, 6 for USDC). */
    public int exponent() {
        return exponent;
    }

    /** Adds two monies of the same currency. */
    public Money add(Money other) {
        requireSameCurrency(other);
        try {
            return new Money(Math.addExact(amountMinor, other.amountMinor), currency, exponent);
        } catch (ArithmeticException e) {
            throw new MoneyOverflowException("add overflow", e);
        }
    }

    /** Subtracts a money of the same currency from this one. */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        try {
            return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency, exponent);
        } catch (ArithmeticException e) {
            throw new MoneyOverflowException("subtract overflow", e);
        }
    }

    /**
     * Negates this money. Mirrors the Go implementation: negating
     * {@code Long.MIN_VALUE} yields {@code Long.MIN_VALUE} (wrap-around).
     */
    public Money negate() {
        return new Money(-amountMinor, currency, exponent);
    }

    /**
     * Returns the absolute value. Mirrors the Go implementation: the
     * absolute value of {@code Long.MIN_VALUE} is {@code Long.MIN_VALUE}
     * (wrap-around), never a positive overflow.
     */
    public Money abs() {
        return amountMinor < 0 ? negate() : this;
    }

    public boolean isZero() {
        return amountMinor == 0L;
    }

    public boolean isNegative() {
        return amountMinor < 0L;
    }

    public boolean isPositive() {
        return amountMinor > 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return amountMinor == money.amountMinor && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinor, currency);
    }

    /**
     * Compares two monies of the same currency.
     *
     * @throws CurrencyMismatchException if the currencies differ
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    /**
     * Splits this money into parts proportional to {@code ratios}.
     * <p>
     * {@code ratios} must be non-negative integers that sum exactly to
     * {@code total}, which acts as the denominator of the split:
     * {@code allocate([1, 2], 3)} splits into thirds, {@code allocate([50, 50], 100)}
     * splits in half.
     * <p>
     * Parts are computed with the largest-remainder method: each part is the
     * floor of its exact share, and leftover units are handed out one by one
     * to the parts with the largest fractional remainders (ties broken by
     * lower index, so the result is deterministic). Consequently:
     * <ul>
     *   <li>the parts always sum to this money exactly — no minor unit is
     *       lost or created (critical for split payments and fee distribution);</li>
     *   <li>each part is within one minor unit of its exact proportional share;</li>
     *   <li>a ratio of 0 always yields a zero part;</li>
     *   <li>for non-negative amounts, a strictly larger ratio never yields a
     *       smaller part.</li>
     * </ul>
     * Negative amounts are allocated on their magnitude and negated part by
     * part, preserving the exact-sum invariant.
     *
     * @throws InvalidRatiosException for empty ratios, non-positive total,
     *         negative ratios, or ratios not summing to total
     */
    public Money[] allocate(int[] ratios, int total) {
        Objects.requireNonNull(ratios, "ratios");
        if (ratios.length == 0) {
            throw new InvalidRatiosException("no ratios given");
        }
        if (total <= 0) {
            throw new InvalidRatiosException("total must be positive: " + total);
        }
        long sum = 0L;
        for (int i = 0; i < ratios.length; i++) {
            int r = ratios[i];
            if (r < 0) {
                throw new InvalidRatiosException("ratios[" + i + "] is negative: " + r);
            }
            // Incremental check also guards against overflow.
            if (sum > total - r) {
                throw new InvalidRatiosException("ratios sum above total " + total);
            }
            sum += r;
        }
        if (sum != total) {
            throw new InvalidRatiosException("ratios sum to " + sum + ", want total " + total);
        }

        boolean negative = amountMinor < 0L;
        BigInteger magnitude = BigInteger.valueOf(amountMinor).abs();
        BigInteger denominator = BigInteger.valueOf(total);

        BigInteger[] quotients = new BigInteger[ratios.length];
        BigInteger[] remainders = new BigInteger[ratios.length];
        BigInteger sumQuotients = BigInteger.ZERO;
        for (int i = 0; i < ratios.length; i++) {
            BigInteger[] qr =
                magnitude.multiply(BigInteger.valueOf(ratios[i])).divideAndRemainder(denominator);
            quotients[i] = qr[0];
            remainders[i] = qr[1];
            sumQuotients = sumQuotients.add(qr[0]);
        }
        // The number of leftover minor units; provably < ratios.length
        // because the ratios sum exactly to total.
        BigInteger leftover = magnitude.subtract(sumQuotients);

        // Largest remainder first; ties broken by lower index.
        Integer[] order = new Integer[ratios.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int c = remainders[b].compareTo(remainders[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int extra = leftover.intValueExact();
        for (int i = 0; i < extra; i++) {
            quotients[order[i]] = quotients[order[i]].add(BigInteger.ONE);
        }

        Money[] parts = new Money[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            long amount = quotients[i].longValue();
            if (negative) {
                // For the quotient 2^63 (only reachable when this money is
                // Long.MIN_VALUE and one part takes the whole magnitude) the
                // wrap lands exactly on Long.MIN_VALUE.
                amount = -amount;
            }
            parts[i] = new Money(amount, currency, exponent);
        }
        return parts;
    }

    /** Renders the decimal form only, e.g. {@code "12.34"} — no currency. */
    @Override
    public String toString() {
        boolean negative = amountMinor < 0L;
        BigInteger magnitude = BigInteger.valueOf(amountMinor).abs();
        BigInteger scale = BigInteger.TEN.pow(exponent);
        BigInteger[] qr = magnitude.divideAndRemainder(scale);
        String digits;
        if (exponent > 0) {
            digits = qr[0] + "." + String.format("%0" + exponent + "d", qr[1]);
        } else {
            digits = qr[0].toString();
        }
        return negative ? "-" + digits : digits;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) {
            return true; // an empty fraction part is all digits (Go parity)
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
