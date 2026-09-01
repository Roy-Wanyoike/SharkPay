package com.sharkpay.fx.fakes;

import com.sharkpay.fx.domain.Rate;
import com.sharkpay.fx.domain.UnsupportedCurrencyPairException;
import com.sharkpay.fx.ports.RateProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake rate source ("fake now, external rate sources later" —
 * ADR 003 consumer-driven ports). Serves a fixed table of indicative market
 * rates for the sandbox.
 *
 * <p>Table entries are exact rationals of quote-currency minor units per
 * base-currency minor unit (see {@link Rate} semantics) and are chosen to
 * be exactly decimal-representable so the API rendering never rounds. The
 * sandbox USD:KES mid is 129.00 (1 USD-minor = 129 KES-minor) and the
 * reverse direction 0.007719 USD per KES.
 */
public final class FakeRateProvider implements RateProvider {

    private final Map<String, Rate> table = new ConcurrentHashMap<>();

    public FakeRateProvider() {
        this(defaults());
    }

    public FakeRateProvider(Map<String, Rate> table) {
        this.table.putAll(table);
    }

    private static Map<String, Rate> defaults() {
        return Map.ofEntries(
                Map.entry("USD:KES", new Rate(129, 1, "USD", "KES")),            // 129.00
                Map.entry("KES:USD", new Rate(7719, 1_000_000, "KES", "USD")),   // 0.007719
                Map.entry("EUR:USD", new Rate(27, 25, "EUR", "USD")),            // 1.08
                Map.entry("USD:EUR", new Rate(463, 500, "USD", "EUR")),          // 0.926
                Map.entry("GBP:USD", new Rate(127, 100, "GBP", "USD")),          // 1.27
                Map.entry("USD:GBP", new Rate(787, 1000, "USD", "GBP")),         // 0.787
                Map.entry("EUR:KES", new Rate(13932, 100, "EUR", "KES")),        // 139.32
                Map.entry("KES:EUR", new Rate(7177, 1_000_000, "KES", "EUR")),   // 0.007177
                Map.entry("USD:USDC", new Rate(10_000, 1, "USD", "USDC")),       // 1.00 (exponent 2 -> 6)
                Map.entry("USDC:USD", new Rate(1, 10_000, "USDC", "USD")));
    }

    @Override
    public Rate rawRate(String baseCurrency, String quoteCurrency) {
        Rate rate = table.get(baseCurrency + ":" + quoteCurrency);
        if (rate == null) {
            throw new UnsupportedCurrencyPairException(baseCurrency, quoteCurrency);
        }
        return rate;
    }

    /** Test/sandbox hook: overrides or adds a pair. */
    public FakeRateProvider withRate(String base, String quote, long numerator, long denominator) {
        table.put(base + ":" + quote, new Rate(numerator, denominator, base, quote));
        return this;
    }
}
