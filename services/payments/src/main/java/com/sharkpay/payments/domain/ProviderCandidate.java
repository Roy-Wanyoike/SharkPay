package com.sharkpay.payments.domain;

import java.util.Set;

/**
 * One routable provider as seen by the payments router: the capability set
 * (rails / currencies / regions), the economic and health signals the policy
 * scores on, and the tier gates. Immutable value object; built by the
 * {@link com.sharkpay.payments.ports.ProviderGatewayPort} from the provider
 * registry (capability) plus the ops cost/latency table.
 *
 * <p>Integer-only discipline: cost is basis points, latency is milliseconds,
 * success rate is basis points (10 000 = 100%). No floats anywhere near the
 * money path — and none in the scoring either, so routing is exactly
 * replayable (audits, reconstruction, §7.3).</p>
 *
 * @param providerId     stable provider identifier ("honeycoin", ...)
 * @param rails          rails the provider serves (wire names)
 * @param currencies     currencies the provider settles
 * @param regions        regions the provider serves (ISO-3166 alpha-2 or "GLOBAL")
 * @param costBps        provider cost in basis points of the amount
 * @param p99Millis      observed p99 wire latency, milliseconds
 * @param successRateBps success rate over the health window, basis points
 * @param breakerOpen    circuit breaker state OPEN (excluded outright)
 * @param minTierRank    minimum KYC tier rank (0 unverified, 1 limited, 2 full)
 * @param minTxnMinor    smallest transfer the provider accepts (null = none)
 * @param maxTxnMinor    largest transfer the provider accepts (null = none)
 */
public record ProviderCandidate(String providerId, Set<String> rails, Set<String> currencies,
                                Set<String> regions, long costBps, long p99Millis,
                                long successRateBps, boolean breakerOpen, int minTierRank,
                                Long minTxnMinor, Long maxTxnMinor) {

    public ProviderCandidate {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id is required");
        }
        rails = Set.copyOf(rails);
        currencies = Set.copyOf(currencies);
        regions = Set.copyOf(regions);
        if (costBps < 0) {
            throw new IllegalArgumentException("cost bps must be non-negative: " + costBps);
        }
        if (p99Millis < 0) {
            throw new IllegalArgumentException("p99 latency must be non-negative: " + p99Millis);
        }
        if (successRateBps < 0 || successRateBps > 10_000) {
            throw new IllegalArgumentException("success rate bps must be within [0, 10000]: "
                    + successRateBps);
        }
        if (minTierRank < 0 || minTierRank > 2) {
            throw new IllegalArgumentException("tier rank must be within [0, 2]: " + minTierRank);
        }
        if (minTxnMinor != null && minTxnMinor < 0) {
            throw new IllegalArgumentException("min txn must be non-negative");
        }
        if (maxTxnMinor != null && maxTxnMinor < 0) {
            throw new IllegalArgumentException("max txn must be non-negative");
        }
        if (minTxnMinor != null && maxTxnMinor != null && minTxnMinor > maxTxnMinor) {
            throw new IllegalArgumentException("min txn must be <= max txn");
        }
    }

    boolean supportsRail(Rail rail) {
        return rails.contains(rail.wireName());
    }

    boolean supportsCurrency(String currency) {
        return currencies.contains(currency);
    }

    boolean supportsRegion(String region) {
        return regions.contains("GLOBAL") || regions.contains(region);
    }

    boolean withinTierLimits(long amountMinor, int principalTierRank) {
        if (principalTierRank < minTierRank) {
            return false;
        }
        if (minTxnMinor != null && amountMinor < minTxnMinor) {
            return false;
        }
        return maxTxnMinor == null || amountMinor <= maxTxnMinor;
    }
}
