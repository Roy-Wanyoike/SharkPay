package com.sharkpay.payments.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic provider-routing policy: every hard filter, the integer
 * scoring (cost 5 / latency 3 / health 2, all × 10 000), the stable
 * provider-id tie-break and the fail-over ranking (README "Router policy").
 */
class RouterPolicyTest {

    private final RouterPolicy router = new RouterPolicy();

    private static ProviderCandidate candidate(String id, long costBps, long p99Millis,
                                               long successRateBps) {
        return new ProviderCandidate(id, java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), costBps, p99Millis,
                successRateBps, false, 0, null, null);
    }

    private static ProviderCandidate candidate(String id) {
        return candidate(id, 50, 500, 9_900);
    }

    // ── stage 1: hard filters (fail closed) ────────────────────────────────

    @Test
    void noCandidatesAtAllFailsClosed() {
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of())).isEmpty();
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, null)).isEmpty();
    }

    @Test
    void currencyCapabilityFilters() {
        ProviderCandidate usdOnly = new ProviderCandidate("usd-rail", java.util.Set.of("honeycoin"),
                java.util.Set.of("USD"), java.util.Set.of("KE"), 50, 500, 9_900, false, 0, null,
                null);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(usdOnly)))
                .isEmpty();
    }

    @Test
    void railCapabilityFilters() {
        ProviderCandidate mpesaOnly = new ProviderCandidate("mpesa-pro",
                java.util.Set.of("mpesa"), java.util.Set.of("KES"), java.util.Set.of("KE"), 50,
                500, 9_900, false, 0, null, null);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(mpesaOnly)))
                .isEmpty();
    }

    @Test
    void regionFiltersButGlobalPasses() {
        ProviderCandidate us = new ProviderCandidate("us-pro", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("US"), 50, 500, 9_900, false, 0, null,
                null);
        ProviderCandidate global = new ProviderCandidate("global-pro",
                java.util.Set.of("honeycoin"), java.util.Set.of("KES"), java.util.Set.of("GLOBAL"),
                50, 500, 9_900, false, 0, null, null);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(us)))
                .isEmpty();
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(global, us)))
                .hasValue(global);
    }

    @Test
    void openBreakerIsExcludedOutright() {
        ProviderCandidate open = new ProviderCandidate("open-pro", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 1, 1, 10_000, true, 0, null,
                null);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(open))).isEmpty();
        assertThat(router.rank("KES", Rail.HONEYCOIN, "KE", 1_000, 2,
                List.of(open, candidate("healthy"))))
                .containsOnly(candidate("healthy"));
    }

    @Test
    void kycTierGateFailsClosedBelowTheMinimum() {
        ProviderCandidate fullKycOnly = new ProviderCandidate("tier2-pro",
                java.util.Set.of("honeycoin"), java.util.Set.of("KES"), java.util.Set.of("KE"),
                50, 500, 9_900, false, 2, null, null);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 1,
                List.of(fullKycOnly))).isEmpty();
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(fullKycOnly)))
                .hasValue(fullKycOnly);
    }

    @Test
    void tierLimitsFilterAmountsOutsideTheBand() {
        ProviderCandidate banded = new ProviderCandidate("banded", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 50, 500, 9_900, false, 0, 100L,
                10_000L);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 99, 2, List.of(banded))).isEmpty();
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 10_001, 2, List.of(banded)))
                .isEmpty();
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 10_000, 2, List.of(banded)))
                .hasValue(banded);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 100, 2, List.of(banded)))
                .hasValue(banded);
    }

    @Test
    void eligibilitySurvivesNullOnlyViaTheNullCheck() {
        assertThat(router.eligible("KES", Rail.HONEYCOIN, "KE", 1_000, 2, null)).isFalse();
        assertThat(router.eligible("KES", Rail.HONEYCOIN, "KE", 1_000, 2, candidate("x")))
                .isTrue();
    }

    // ── stage 2: deterministic integer scoring ─────────────────────────────

    @Test
    void cheapestCandidateWinsWhenLatencyAndHealthAreEqual() {
        ProviderCandidate cheap = candidate("cheap", 10, 500, 9_900);
        ProviderCandidate expensive = candidate("expensive", 90, 500, 9_900);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2,
                List.of(expensive, cheap))).hasValue(cheap);
    }

    @Test
    void lowerP99BeatsHigherLatencyAtEqualCostAndHealth() {
        ProviderCandidate fast = candidate("fast", 50, 100, 9_900);
        ProviderCandidate slow = candidate("slow", 50, 900, 9_900);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(slow, fast)))
                .hasValue(fast);
    }

    @Test
    void lowerSuccessRateScoresWorse() {
        ProviderCandidate healthy = candidate("healthy", 50, 500, 10_000);
        ProviderCandidate flaky = candidate("flaky", 50, 500, 5_000);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(flaky, healthy)))
                .hasValue(healthy);
    }

    @Test
    void weightsTradeOffExactlyLikeTheDocumentedFormula() {
        // a: cost 0, p99 100, success 9000 → 5·0 + 3·10000 + 2·1000 = 32 000
        // b: cost 50, p99 100, success 10000 → 5·10000 + 3·10000 + 2·0 = 80 000
        // a wins despite the worse success rate: cost drives unit economics,
        // latency is second, health is tertiary (README weights 5/3/2).
        ProviderCandidate a = candidate("a", 0, 100, 9_000);
        ProviderCandidate b = candidate("b", 50, 100, 10_000);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(b, a)))
                .hasValue(a);
        assertThat(router.score(a, 50, 100)).isEqualTo(32_000L);
        assertThat(router.score(b, 50, 100)).isEqualTo(80_000L);
    }

    @Test
    void equalScoresTieBreakOnProviderIdAscending() {
        ProviderCandidate a = candidate("aaa", 50, 500, 9_900);
        ProviderCandidate b = candidate("bbb", 50, 500, 9_900);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(b, a)))
                .hasValue(a);
    }

    @Test
    void rankReturnsTheFullFailOverLadderBestFirst() {
        ProviderCandidate best = candidate("aaa", 10, 100, 10_000);
        ProviderCandidate middle = candidate("bbb", 50, 500, 9_900);
        ProviderCandidate worst = candidate("ccc", 90, 900, 5_000);
        ProviderCandidate excluded = new ProviderCandidate("zzz", java.util.Set.of("mpesa"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 1, 1, 10_000, false, 0, null,
                null);
        List<ProviderCandidate> ranked = router.rank("KES", Rail.HONEYCOIN, "KE", 1_000, 2,
                List.of(worst, excluded, best, middle));
        assertThat(ranked).containsExactly(best, middle, worst);
        assertThat(ranked).doesNotContain(excluded);
    }

    @Test
    void scoringUsesTheEligibleMaximumsNotTheRawMaximums() {
        // an expensive-but-ineligible candidate must not distort the norm
        ProviderCandidate ineligible = new ProviderCandidate("ineligible",
                java.util.Set.of("mpesa"), java.util.Set.of("KES"), java.util.Set.of("KE"),
                10_000, 100_000, 1, false, 0, null, null);
        ProviderCandidate a = candidate("a", 50, 500, 9_900);
        ProviderCandidate b = candidate("b", 25, 500, 9_900);
        List<ProviderCandidate> ranked = router.rank("KES", Rail.HONEYCOIN, "KE", 1_000, 2,
                List.of(ineligible, b, a));
        assertThat(ranked).containsExactly(b, a);
        // with maxCost = 50 over the eligible set: b norm = 5000, a norm = 10000
        assertThat(router.score(b, 50, 500)).isEqualTo(5 * 5_000L + 3 * 10_000L + 2 * 100L);
        assertThat(router.score(a, 50, 500)).isEqualTo(5 * 10_000L + 3 * 10_000L + 2 * 100L);
    }

    @Test
    void zeroLatencyAndZeroCostCandidatesNeverDivideByZero() {
        ProviderCandidate zero = new ProviderCandidate("zero", java.util.Set.of("honeycoin"),
                java.util.Set.of("KES"), java.util.Set.of("KE"), 0, 0, 10_000, false, 0, null,
                null);
        assertThat(router.select("KES", Rail.HONEYCOIN, "KE", 1_000, 2, List.of(zero)))
                .hasValue(zero);
        assertThat(router.score(zero, 0, 0)).isEqualTo(0L);
    }

    @Test
    void weightsMatchTheDocumentedFiveThreeTwo() {
        assertThat(RouterPolicy.COST_WEIGHT).isEqualTo(5);
        assertThat(RouterPolicy.LATENCY_WEIGHT).isEqualTo(3);
        assertThat(RouterPolicy.HEALTH_WEIGHT).isEqualTo(2);
    }
}
