package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pure comparison engine (PRD D10 / FR-1001). Matching completeness:
 * every line on both sides is classified exactly once; the taxonomy is
 * never guessed (unknown provider statuses are STATUS_MISMATCH); amounts
 * compare in exact {@code long} minor units (incl. 2^53+1, never floats);
 * currency mismatches are their own break and make the numbers
 * incomparable; duplicate internal refs are a loud port-contract
 * violation, never a misclassification.
 */
class ComparisonEngineTest {

    private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");
    /** 2^53 + 1 — beyond double precision; long comparison is exact. */
    private static final long BEYOND_DOUBLE = 9_007_199_254_740_993L;

    @Test
    void emptySidesProduceAnEmptyResult() {
        ComparisonEngine.Result result = ComparisonEngine.compare(List.of(), List.of());
        assertThat(result.matchedPairs()).isZero();
        assertThat(result.breaks()).isEmpty();
        assertThat(result.breakCount()).isZero();
    }

    @Test
    void aCleanlyMatchedPairProducesNoBreaks() {
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "CONFIRMED", 150_000, 500)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 150_000, 500)));
        assertThat(result.matchedPairs()).isEqualTo(1);
        assertThat(result.breaks()).isEmpty();
    }

    @Test
    void aProviderLineWithoutAnInternalCounterpartIsMissingInternal() {
        // no internal side at all: the provider statement is the whole truth
        // for the window and the line classifies exactly once
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_only", "CONFIRMED", 1_000, 0)),
                List.of());

        assertThat(result.breaks()).hasSize(1);
        DetectedBreak break_ = result.breaks().get(0);
        assertThat(break_.breakType()).isEqualTo(BreakType.MISSING_INTERNAL);
        assertThat(break_.providerRef()).isEqualTo("hc_only");
        assertThat(break_.internalRef()).isNull();
        assertThat(break_.providerAmount()).isEqualTo(Money.of(1_000, "KES"));
        assertThat(break_.internalAmount()).isNull();
        assertThat(break_.providerStatus()).isEqualTo("CONFIRMED");
        assertThat(break_.internalStatus()).isNull();
    }

    @Test
    void anInternalLineWithoutAProviderLineIsMissingOnProvider() {
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "CONFIRMED", 1_000, 0)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 1_000, 0),
                        internalLine("int_orphan", "hc_orphan", "CONFIRMED", 2_500, 10)));

        assertThat(result.breaks()).hasSize(1);
        DetectedBreak break_ = result.breaks().get(0);
        assertThat(break_.breakType()).isEqualTo(BreakType.MISSING_ON_PROVIDER);
        assertThat(break_.providerRef()).isEqualTo("hc_orphan");
        assertThat(break_.internalRef()).isEqualTo("int_orphan");
        assertThat(break_.providerAmount()).isNull();
        assertThat(break_.internalAmount()).isEqualTo(Money.of(2_500, "KES"));
        assertThat(break_.internalStatus()).isEqualTo("CONFIRMED");
        assertThat(break_.providerStatus()).isNull();
    }

    @Test
    void anUnmatchableInternalLineWithoutAProviderRefIsMissingOnProvider() {
        // providerRef null: an internal movement the provider never knew —
        // it can never match and classifies as MISSING_ON_PROVIDER
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "CONFIRMED", 1_000, 0)),
                List.of(internalLine("int_noref", null, "CONFIRMED", 999, 0)));

        assertThat(result.matchedPairs()).isEqualTo(0);
        assertThat(result.breaks()).hasSize(2);
        assertThat(result.breaks())
                .extracting(DetectedBreak::breakType)
                .containsExactly(BreakType.MISSING_INTERNAL, BreakType.MISSING_ON_PROVIDER);
    }

    @Test
    void sameRefDifferentAmountsIsAmountMismatchInExactMinorUnits() {
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "CONFIRMED", 150_000, 500)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 149_500, 500)));

        assertThat(result.matchedPairs()).isEqualTo(1);
        assertThat(result.breaks()).hasSize(1);
        DetectedBreak break_ = result.breaks().get(0);
        assertThat(break_.breakType()).isEqualTo(BreakType.AMOUNT_MISMATCH);
        assertThat(break_.providerAmount()).isEqualTo(Money.of(150_000, "KES"));
        assertThat(break_.internalAmount()).isEqualTo(Money.of(149_500, "KES"));
        assertThat(break_.providerStatus()).isEqualTo("CONFIRMED");
        assertThat(break_.internalStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void amountsBeyondDoublePrecisionCompareExactly() {
        // 2^53+1 vs 2^53: a double comparison would call them equal
        assertThat((double) BEYOND_DOUBLE).isEqualTo((double) (BEYOND_DOUBLE - 1)); // double-blind
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_exact", "CONFIRMED", BEYOND_DOUBLE, 0)),
                List.of(internalLine("int_exact", "hc_exact", "CONFIRMED", BEYOND_DOUBLE - 1, 0)));

        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).breakType()).isEqualTo(BreakType.AMOUNT_MISMATCH);
        assertThat(result.breaks().get(0).providerAmount().amountMinor()).isEqualTo(BEYOND_DOUBLE);
        assertThat(result.breaks().get(0).internalAmount().amountMinor())
                .isEqualTo(BEYOND_DOUBLE - 1);

        // ... and equality beyond 2^53 is NOT a mismatch
        assertThat(ComparisonEngine.compare(
                List.of(providerLine("hc_same", "CONFIRMED", BEYOND_DOUBLE, 0)),
                List.of(internalLine("int_same", "hc_same", "CONFIRMED", BEYOND_DOUBLE, 0)))
                .breaks()).isEmpty();
    }

    @Test
    void differingFeesAreFeeMismatch() {
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "CONFIRMED", 150_000, 650)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 150_000, 500)));

        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).breakType()).isEqualTo(BreakType.FEE_MISMATCH);
        assertThat(result.breaks().get(0).providerFee()).isEqualTo(Money.of(650, "KES"));
        assertThat(result.breaks().get(0).internalFee()).isEqualTo(Money.of(500, "KES"));
    }

    @Test
    void mappedStatusDifferencesAreStatusMismatch() {
        // provider settled, internal still pending
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "SUCCEEDED", 1_000, 0)),
                List.of(internalLine("int_1", "hc_1", "PENDING", 1_000, 0)));

        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).breakType()).isEqualTo(BreakType.STATUS_MISMATCH);
        assertThat(result.breaks().get(0).providerStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.breaks().get(0).internalStatus()).isEqualTo("PENDING");
    }

    @Test
    void synonymsMapToTheSameCanonicalStatus() {
        // SUCCEEDED and CONFIRMED are the same fact (providers' internal
        // vocabulary) — no break
        assertThat(ComparisonEngine.compare(
                List.of(providerLine("hc_1", "SUCCEEDED", 1_000, 0)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 1_000, 0))).breaks()).isEmpty();

        // REVERSED and RETURNED are the same fact
        assertThat(ComparisonEngine.compare(
                List.of(providerLine("hc_1", "REVERSED", 1_000, 0)),
                List.of(internalLine("int_1", "hc_1", "RETURNED", 1_000, 0))).breaks()).isEmpty();
    }

    @Test
    void anUnmappableProviderStatusIsStatusMismatchNeverAGuess() {
        // the AMBIGUITY CONTRACT: an unknown provider status is a
        // STATUS_MISMATCH break, never a mapped guess
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "PARTIALLY_SETTLED", 1_000, 0)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 1_000, 0)));

        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).breakType()).isEqualTo(BreakType.STATUS_MISMATCH);
        assertThat(result.breaks().get(0).providerStatus()).isEqualTo("PARTIALLY_SETTLED");

        // blank internal status is also unmappable → STATUS_MISMATCH
        ComparisonEngine.Result blankInternal = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "CONFIRMED", 1_000, 0)),
                List.of(internalLine("int_1", "hc_1", "", 1_000, 0)));
        assertThat(blankInternal.breaks()).hasSize(1);
        assertThat(blankInternal.breaks().get(0).breakType()).isEqualTo(BreakType.STATUS_MISMATCH);
    }

    @Test
    void differingCurrenciesAreTheirOwnBreakAndSkipTheNumericComparison() {
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "CONFIRMED", 150_000, "USD", 500)),
                List.of(internalLine("int_1", "hc_1", "CONFIRMED", 150_000, 0)));

        assertThat(result.breaks()).hasSize(1);
        assertThat(result.breaks().get(0).breakType()).isEqualTo(BreakType.CURRENCY_MISMATCH);
        // 150_000 USD vs 150_000 KES numerically equal — no AMOUNT_MISMATCH
        // is attached: minor units of different currencies are incomparable

        // a fee-currency difference is equally a currency mismatch
        ComparisonEngine.Result feeCurrency = ComparisonEngine.compare(
                List.of(providerLine("hc_2", "CONFIRMED", 150_000, 500)),
                List.of(internalLine("int_2", "hc_2", "CONFIRMED", 150_000, 500, "USD")));
        assertThat(feeCurrency.breaks()).hasSize(1);
        assertThat(feeCurrency.breaks().get(0).breakType()).isEqualTo(BreakType.CURRENCY_MISMATCH);
    }

    @Test
    void onePairCanCarrySeveralIndependentBreaks() {
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_1", "SUCCEEDED", 150_000, 650)),
                List.of(internalLine("int_1", "hc_1", "PENDING", 149_500, 500)));

        assertThat(result.breaks()).hasSize(3);
        assertThat(result.breaks())
                .extracting(DetectedBreak::breakType)
                .containsExactlyInAnyOrder(BreakType.STATUS_MISMATCH, BreakType.AMOUNT_MISMATCH,
                        BreakType.FEE_MISMATCH);
        // every break of the pair carries both sides' facts
        for (DetectedBreak break_ : result.breaks()) {
            assertThat(break_.providerRef()).isEqualTo("hc_1");
            assertThat(break_.internalRef()).isEqualTo("int_1");
            assertThat(break_.providerAmount()).isEqualTo(Money.of(150_000, "KES"));
            assertThat(break_.internalAmount()).isEqualTo(Money.of(149_500, "KES"));
        }
    }

    @Test
    void everyLineIsClassifiedExactlyOnce() {
        // 3 provider lines: one clean match, one missing internal, one
        // status-mismatched match; 3 internal lines: one clean match, one
        // matched-with-status-break, one orphan (missing on provider)
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_clean", "CONFIRMED", 1_000, 0),
                        providerLine("hc_ghost", "CONFIRMED", 2_000, 0),
                        providerLine("hc_status", "SUCCEEDED", 3_000, 0)),
                List.of(internalLine("int_clean", "hc_clean", "CONFIRMED", 1_000, 0),
                        internalLine("int_status", "hc_status", "PENDING", 3_000, 0),
                        internalLine("int_orphan", "hc_orphan", "CONFIRMED", 9_000, 0)));

        assertThat(result.matchedPairs()).isEqualTo(2);
        assertThat(result.breakCount()).isEqualTo(3);
        // each seeded discrepancy lands in exactly one break
        assertThat(result.breaks())
                .extracting(DetectedBreak::breakType)
                .containsExactlyInAnyOrder(BreakType.MISSING_INTERNAL, BreakType.STATUS_MISMATCH,
                        BreakType.MISSING_ON_PROVIDER);
        // provider-line breaks come in statement order, then internal ones
        assertThat(result.breaks())
                .extracting(DetectedBreak::providerRef)
                .containsExactly("hc_ghost", "hc_status", "hc_orphan");
    }

    @Test
    void duplicateInternalRefsAreALoudPortContractViolation() {
        assertThatThrownBy(() -> ComparisonEngine.compare(
                List.of(providerLine("hc_dup", "CONFIRMED", 1_000, 0)),
                List.of(internalLine("int_a", "hc_dup", "CONFIRMED", 1_000, 0),
                        internalLine("int_b", "hc_dup", "CONFIRMED", 1_000, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal lines must be unique by provider ref")
                .hasMessageContaining("hc_dup");
    }

    @Test
    void duplicateProviderRefsAreComparedIndependentlyAgainstTheSameInternalLine() {
        // the engine classifies only what the taxonomy can express —
        // duplicates are the statement-reading side's investigation
        ComparisonEngine.Result result = ComparisonEngine.compare(
                List.of(providerLine("hc_dup", "CONFIRMED", 1_000, 0),
                        providerLine("hc_dup", "CONFIRMED", 1_000, 0)),
                List.of(internalLine("int_a", "hc_dup", "CONFIRMED", 1_000, 0)));

        assertThat(result.matchedPairs()).isEqualTo(2);
        assertThat(result.breaks()).isEmpty();
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> ComparisonEngine.compare(null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerLines is required");
        assertThatThrownBy(() -> ComparisonEngine.compare(List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("internalLines is required");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ProviderStatementLine providerLine(String ref, String status, long amountMinor,
                                                      long feeMinor) {
        return new ProviderStatementLine(ref, status, Money.of(amountMinor, "KES"),
                Money.of(feeMinor, "KES"), T0);
    }

    private static ProviderStatementLine providerLine(String ref, String status, long amountMinor,
                                                      String currency, long feeMinor) {
        return new ProviderStatementLine(ref, status, Money.of(amountMinor, currency),
                Money.of(feeMinor, "KES"), T0);
    }

    private static InternalLedgerLine internalLine(String internalRef, String providerRef,
                                                   String status, long amountMinor, long feeMinor) {
        return new InternalLedgerLine(internalRef, providerRef, status,
                Money.of(amountMinor, "KES"), Money.of(feeMinor, "KES"), T0);
    }

    private static InternalLedgerLine internalLine(String internalRef, String providerRef,
                                                   String status, long amountMinor, long feeMinor,
                                                   String feeCurrency) {
        return new InternalLedgerLine(internalRef, providerRef, status,
                Money.of(amountMinor, "KES"), Money.of(feeMinor, feeCurrency), T0);
    }
}
