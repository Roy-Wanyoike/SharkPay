package com.sharkpay.payments.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The rail × currency fee table used at intent creation ("The fee is computed
 * at intent creation from the rail/currency schedule" — payments.yaml
 * createPayment). Pure domain data with a deterministic default-rail rule:
 * when a request carries no rail hint, the first rail in
 * {@link Rail#canonicalOrder()} that serves the currency is used.
 *
 * <p>V1 defaults (product-owned numbers; swap via configuration when ops
 * lands the live schedule — the policy math stays this class):</p>
 * <ul>
 *   <li>honeycoin — KES 50 bps (min 100 minor), USDC 60 bps (min 100)</li>
 *   <li>mpesa — KES 250 bps (min 100, max 5 000)</li>
 *   <li>bank — KES/USD/EUR/GBP 30 bps (min 500)</li>
 *   <li>on_chain — USDC/USDT 80 bps (min 100 000)</li>
 * </ul>
 */
public final class FeeSchedules {

    private static final Map<Rail, Map<String, FeePolicy>> TABLE = buildTable();

    private FeeSchedules() {
    }

    private static Map<Rail, Map<String, FeePolicy>> buildTable() {
        Map<Rail, Map<String, FeePolicy>> table = new java.util.EnumMap<>(Rail.class);
        table.put(Rail.HONEYCOIN, Map.of(
                "KES", new FeePolicy(Rail.HONEYCOIN, "KES", 50, 0, 100, null),
                "USDC", new FeePolicy(Rail.HONEYCOIN, "USDC", 60, 0, 100, null)));
        table.put(Rail.MPESA, Map.of(
                "KES", new FeePolicy(Rail.MPESA, "KES", 250, 0, 100, 5_000L)));
        table.put(Rail.BANK, Map.of(
                "KES", new FeePolicy(Rail.BANK, "KES", 30, 0, 500, null),
                "USD", new FeePolicy(Rail.BANK, "USD", 30, 0, 500, null),
                "EUR", new FeePolicy(Rail.BANK, "EUR", 30, 0, 500, null),
                "GBP", new FeePolicy(Rail.BANK, "GBP", 30, 0, 500, null)));
        table.put(Rail.ON_CHAIN, Map.of(
                "USDC", new FeePolicy(Rail.ON_CHAIN, "USDC", 80, 0, 100_000, null),
                "USDT", new FeePolicy(Rail.ON_CHAIN, "USDT", 80, 0, 100_000, null)));
        return java.util.Collections.unmodifiableMap(table);
    }

    /** The fee policy for a rail/currency pair, when one exists. */
    public static Optional<FeePolicy> forRailAndCurrency(Rail rail, String currency) {
        if (rail == null || currency == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(TABLE.getOrDefault(rail, Map.of()).get(currency));
    }

    /**
     * The deterministic default rail for a currency: the first rail in
     * canonical order whose schedule serves the currency. Empty when no rail
     * collects the currency at all.
     */
    public static Optional<Rail> defaultRailFor(String currency) {
        for (Rail rail : Rail.canonicalOrder()) {
            if (forRailAndCurrency(rail, currency).isPresent()) {
                return Optional.of(rail);
            }
        }
        return Optional.empty();
    }

    /** Whether any rail collects the currency (fail-closed pre-flight). */
    public static boolean isCollectable(String currency) {
        return defaultRailFor(currency).isPresent();
    }
}
