package com.sharkpay.payments.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic provider-routing policy (pure domain function, heavily
 * tested; explained in services/payments/README.md).
 *
 * <p><b>Stage 1 — hard filters (fail closed):</b> a candidate failing any of
 * these is never eligible:</p>
 * <ol>
 *   <li>supports the payment's currency;</li>
 *   <li>supports the payment's rail;</li>
 *   <li>supports the payment's region (or advertises GLOBAL);</li>
 *   <li>circuit breaker not OPEN;</li>
 *   <li>KYC tier gate — principal tier rank ≥ candidate minimum (fail closed
 *       on unknown tiers);</li>
 *   <li>tier limits — amount within the candidate's [min, max] transaction
 *       band.</li>
 * </ol>
 *
 * <p><b>Stage 2 — deterministic integer scoring</b> over the eligible set
 * (lower is better):</p>
 * <pre>
 * score = 5·costNorm + 3·latencyNorm + 2·failureRate        (all × 10 000)
 * costNorm     = costBps × 10 000 / max(costBps over eligible)
 * latencyNorm  = p99Millis × 10 000 / max(p99Millis over eligible)
 * failureRate  = 10 000 − successRateBps
 * </pre>
 * <p>Weights mirror the Go gateway's documented defaults (cost 0.5, latency
 * 0.3, health 0.2 — cost drives unit economics, latency is second, health is
 * tertiary), scaled ×10 into exact long arithmetic so every environment
 * computes the identical score (no binary-fraction surprises). Ties break by
 * provider id ascending — routing is replayable, which reconstruction and
 * audits require.</p>
 */
public final class RouterPolicy {

    /** Cost weight (×10 of 0.5). */
    public static final int COST_WEIGHT = 5;
    /** Latency weight (×10 of 0.3). */
    public static final int LATENCY_WEIGHT = 3;
    /** Health weight (×10 of 0.2). */
    public static final int HEALTH_WEIGHT = 2;

    private static final int SCALE = 10_000;

    /**
     * Routes one payment.
     *
     * @param currency   canonical currency code
     * @param rail       the product-level rail choice
     * @param region     payer region (ISO-3166 alpha-2)
     * @param amountMinor amount in minor units
     * @param tierRank   principal KYC tier rank (0/1/2)
     * @param candidates all routable candidates
     * @return the best eligible candidate; empty when none survives the hard
     *         filters (fail closed — never invent a provider)
     */
    public Optional<ProviderCandidate> select(String currency, Rail rail, String region,
                                              long amountMinor, int tierRank,
                                              List<ProviderCandidate> candidates) {
        return rank(currency, rail, region, amountMinor, tierRank, candidates).stream().findFirst();
    }

    /**
     * Eligible candidates ordered best-first (the fail-over list; the head is
     * what {@link #select} returns).
     */
    public List<ProviderCandidate> rank(String currency, Rail rail, String region, long amountMinor,
                                        int tierRank, List<ProviderCandidate> candidates) {
        List<ProviderCandidate> eligible = candidates == null ? List.of()
                : candidates.stream()
                        .filter(candidate -> eligible(currency, rail, region, amountMinor, tierRank, candidate))
                        .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }
        long maxCost = 1;
        long maxLatency = 1;
        for (ProviderCandidate candidate : eligible) {
            maxCost = Math.max(maxCost, candidate.costBps());
            maxLatency = Math.max(maxLatency, candidate.p99Millis());
        }
        final long maxCostFinal = maxCost;
        final long maxLatencyFinal = maxLatency;
        return eligible.stream()
                .sorted(Comparator.comparingLong(
                                (ProviderCandidate candidate) -> score(candidate, maxCostFinal, maxLatencyFinal))
                        .thenComparing(ProviderCandidate::providerId))
                .toList();
    }

    /** Whether {@code candidate} survives every hard filter. */
    public boolean eligible(String currency, Rail rail, String region, long amountMinor,
                            int tierRank, ProviderCandidate candidate) {
        return candidate != null
                && !candidate.breakerOpen()
                && candidate.supportsCurrency(currency)
                && candidate.supportsRail(rail)
                && candidate.supportsRegion(region)
                && candidate.withinTierLimits(amountMinor, tierRank);
    }

    /**
     * The exact integer score (diagnostics; lower is better). Maxima are
     * floored at 1 like {@link #rank} does, so a degenerate all-zero
     * candidate set scores 0 instead of dividing by zero.
     */
    public long score(ProviderCandidate candidate, long maxCostBps, long maxP99Millis) {
        long costNorm = candidate.costBps() * SCALE / Math.max(1, maxCostBps);
        long latencyNorm = candidate.p99Millis() * SCALE / Math.max(1, maxP99Millis);
        long failureRate = SCALE - candidate.successRateBps();
        return (long) COST_WEIGHT * costNorm + (long) LATENCY_WEIGHT * latencyNorm
                + (long) HEALTH_WEIGHT * failureRate;
    }
}
