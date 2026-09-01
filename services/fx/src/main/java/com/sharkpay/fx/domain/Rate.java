package com.sharkpay.fx.domain;

/**
 * An FX conversion rate for a base&#8594;quote currency pair, represented as
 * an <em>exact rational</em> {@code numerator / denominator} — never a
 * binary-fraction type (PRD money invariants / ADR 001).
 *
 * <h2>Semantics (canonical — see {@code services/fx/README.md})</h2>
 * {@code rate = numerator / denominator} is the number of <b>quote-currency
 * MINOR units</b> obtained for <b>one base-currency MINOR unit</b>, with the
 * currencies' exponents already folded in:
 *
 * <pre>targetMinor = sourceMinor × numerator / denominator   (exact rational)</pre>
 *
 * Example (task canonical): 1 USD (= 100 minor) converts to 129.00 KES
 * (= 12 900 minor), so the rate is {@code 12900 / 100}, which reduces to
 * {@code 129 / 1}. Converting 100 USD-minor (one whole dollar) yields
 * 100 × 129 = 12 900 KES-minor.
 *
 * <h2>API wire representation</h2>
 * {@link #toApiRate()} renders the contract shape from
 * {@code contracts/openapi/v1/fx.yaml}: {@code value_minor / 10^exponent} is
 * the number of quote-currency minor units per <b>one base-currency MAJOR
 * unit</b> (e.g. value_minor 12900, exponent 0 means 12900 KES-minor =
 * 129.00 KES per 1 USD). Because this object is an exact rational, the
 * decimal rendering is computed exactly; a rate that is not representable in
 * base 10 (e.g. {@code 1/3}) is rejected — rate feeds must publish decimal
 * rates.
 *
 * <p>Instances are immutable and reduced to lowest terms, so value equality
 * works naturally. All arithmetic uses {@code long}/{@code BigInteger} only.
 */
public final class Rate {

    private final long numerator;
    private final long denominator;
    private final String baseCurrency;
    private final String quoteCurrency;

    public Rate(long numerator, long denominator, String baseCurrency, String quoteCurrency) {
        if (numerator <= 0) {
            throw new FxDomainException("rate numerator must be positive: " + numerator);
        }
        if (denominator <= 0) {
            throw new FxDomainException("rate denominator must be positive: " + denominator);
        }
        requireCurrency(baseCurrency, "base");
        requireCurrency(quoteCurrency, "quote");
        // Reduce to lowest terms so equal rates are equal objects.
        java.math.BigInteger n = java.math.BigInteger.valueOf(numerator);
        java.math.BigInteger d = java.math.BigInteger.valueOf(denominator);
        java.math.BigInteger gcd = n.gcd(d);
        this.numerator = n.divide(gcd).longValueExact();
        this.denominator = d.divide(gcd).longValueExact();
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
    }

    private static void requireCurrency(String currency, String role) {
        if (currency == null || currency.isBlank()) {
            throw new FxDomainException(role + " currency is required");
        }
        if (!com.sharkpay.money.Currencies.isSupported(currency)) {
            throw new com.sharkpay.money.UnknownCurrencyException(currency);
        }
    }

    public long numerator() {
        return numerator;
    }

    public long denominator() {
        return denominator;
    }

    public String baseCurrency() {
        return baseCurrency;
    }

    public String quoteCurrency() {
        return quoteCurrency;
    }

    /**
     * Exact rational scaling by {@code factor / total}: returns the rate
     * {@code (numerator × factor) / (denominator × total)} in lowest terms.
     * Used to apply the mark-up policy (pure integer math — no rounding).
     */
    public Rate scale(long factor, long total) {
        if (factor <= 0 || total <= 0) {
            throw new FxDomainException("scale factor and total must be positive: factor=" + factor + ", total=" + total);
        }
        java.math.BigInteger n = java.math.BigInteger.valueOf(numerator).multiply(java.math.BigInteger.valueOf(factor));
        java.math.BigInteger d = java.math.BigInteger.valueOf(denominator).multiply(java.math.BigInteger.valueOf(total));
        java.math.BigInteger gcd = n.gcd(d);
        return new Rate(n.divide(gcd).longValueExact(), d.divide(gcd).longValueExact(), baseCurrency, quoteCurrency);
    }

    /**
     * Converts a source amount in the base currency into the quote currency.
     *
     * <p><b>Remainder policy: truncate.</b> The exact result is the rational
     * {@code sourceMinor × numerator / denominator}, computed with
     * {@code BigInteger}. The returned target amount is truncated toward
     * zero; the truncated fractional part is <em>never silently lost</em> —
     * it is reported as {@code dustNumerator / denominator} of one target
     * minor unit (always smaller in magnitude than a single minor unit) and,
     * by policy, accrues to the FX position account (README &#167;Remainder
     * policy). The invariant {@code target.amountMinor × denominator +
     * dustNumerator = sourceMinor × numerator} holds exactly.
     */
    public ConversionResult convert(com.sharkpay.money.Money source) {
        if (source == null) {
            throw new FxDomainException("source money is required");
        }
        if (!source.currency().equals(baseCurrency)) {
            throw new com.sharkpay.money.CurrencyMismatchException(baseCurrency, source.currency());
        }
        java.math.BigInteger product = java.math.BigInteger.valueOf(source.amountMinor())
                .multiply(java.math.BigInteger.valueOf(numerator));
        java.math.BigInteger[] quotientAndRemainder = product.divideAndRemainder(java.math.BigInteger.valueOf(denominator));
        try {
            long targetMinor = quotientAndRemainder[0].longValueExact();
            long dustNumerator = quotientAndRemainder[1].longValueExact();
            return new ConversionResult(com.sharkpay.money.Money.of(targetMinor, quoteCurrency), dustNumerator);
        } catch (java.lang.ArithmeticException overflow) {
            throw new FxDomainException(
                    "converted amount overflows minor units for pair " + baseCurrency + "->" + quoteCurrency, overflow);
        }
    }

    /**
     * Exact decimal wire representation mandated by
     * {@code contracts/openapi/v1/fx.yaml}.
     *
     * <p>{@code (numerator × 10^baseExponent) / denominator} (quote minor
     * units per one base major unit) is reduced, and the minimal exponent
     * that renders it as the integer {@code value_minor} is chosen. Example:
     * the rate {@code 129 / 1} USD&#8594;KES renders as
     * {@code value_minor=12900, exponent=0} (12900 KES-minor per 1 USD).
     */
    public ApiRate toApiRate() {
        int baseExponent = com.sharkpay.money.Currencies.exponentFor(baseCurrency);
        java.math.BigInteger p = java.math.BigInteger.valueOf(numerator).multiply(java.math.BigInteger.TEN.pow(baseExponent));
        java.math.BigInteger q = java.math.BigInteger.valueOf(denominator);
        java.math.BigInteger gcd = p.gcd(q);
        p = p.divide(gcd);
        q = q.divide(gcd);
        // The reduced denominator must divide a power of ten (only factors 2 and 5).
        int twos = 0;
        java.math.BigInteger remaining = q;
        while (!remaining.testBit(0)) {
            remaining = remaining.shiftRight(1);
            twos++;
        }
        java.math.BigInteger five = java.math.BigInteger.valueOf(5);
        int fives = 0;
        while (remaining.mod(five).signum() == 0) {
            remaining = remaining.divide(five);
            fives++;
        }
        if (!remaining.equals(java.math.BigInteger.ONE)) {
            throw new FxDomainException(
                    "rate " + numerator + "/" + denominator + " is not exactly representable as a decimal");
        }
        int exponent = Math.max(twos, fives);
        if (exponent > 18) {
            throw new FxDomainException("rate exponent " + exponent + " exceeds the API maximum of 18");
        }
        java.math.BigInteger value = p.multiply(java.math.BigInteger.TEN.pow(exponent)).divide(q);
        return new ApiRate(value.longValueExact(), exponent);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rate rate)) {
            return false;
        }
        return numerator == rate.numerator
                && denominator == rate.denominator
                && baseCurrency.equals(rate.baseCurrency)
                && quoteCurrency.equals(rate.quoteCurrency);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(baseCurrency, quoteCurrency);
        result = 31 * result + (int) (numerator ^ (numerator >>> 32));
        result = 31 * result + (int) (denominator ^ (denominator >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "Rate{" + baseCurrency + "->" + quoteCurrency + " " + numerator + "/" + denominator + "}";
    }

    /**
     * Wire shape from the OpenAPI contract: {@code rate = value_minor ×
     * 10^-exponent} quote-currency minor units per one base-currency major
     * unit.
     */
    public record ApiRate(long valueMinor, int exponent) {
        public ApiRate {
            if (valueMinor < 1) {
                throw new FxDomainException("api rate value_minor must be >= 1: " + valueMinor);
            }
            if (exponent < 0 || exponent > 18) {
                throw new FxDomainException("api rate exponent must be in [0, 18]: " + exponent);
            }
        }
    }

    /**
     * Result of a conversion: the truncated target money plus the dust
     * fraction {@code dustNumerator / denominator} of one target minor unit
     * that was truncated away (documented, never silently lost).
     */
    public record ConversionResult(com.sharkpay.money.Money target, long dustNumerator) {
    }
}
