package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReconBreak lifecycle and aging: detection shape, legal manual
 * transitions, the COMPENSATED-only-via-compensation rule, terminal-state
 * rejection, bucket advancement (sweeper), and builder validation.
 */
class ReconBreakTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-02T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-04T11:00:00Z");

    @Test
    void detectRecordsTheBothSideFactsOpenAndFresh() {
        DetectedBreak detected = new DetectedBreak(BreakType.AMOUNT_MISMATCH, "hc_tr_1",
                "payout:po_1", Money.of(150_000, "KES"), Money.of(149_500, "KES"),
                Money.of(500, "KES"), Money.of(500, "KES"), "CONFIRMED", "CONFIRMED");
        ReconBreak break_ = ReconBreak.detect("brk_01", "run_01", "honeycoin", detected, T0);

        assertThat(break_.state()).isEqualTo(BreakState.OPEN);
        assertThat(break_.bucket()).isEqualTo(AgingBucket.FRESH);
        assertThat(break_.detectedAt()).isEqualTo(T0);
        assertThat(break_.lastTransitionAt()).isEqualTo(T0);
        assertThat(break_.breakType()).isEqualTo(BreakType.AMOUNT_MISMATCH);
        assertThat(break_.providerRef()).isEqualTo("hc_tr_1");
        assertThat(break_.internalRef()).isEqualTo("payout:po_1");
        assertThat(break_.providerAmount()).isEqualTo(Money.of(150_000, "KES"));
        assertThat(break_.internalAmount()).isEqualTo(Money.of(149_500, "KES"));
        assertThat(break_.providerFee()).isEqualTo(Money.of(500, "KES"));
        assertThat(break_.internalFee()).isEqualTo(Money.of(500, "KES"));
        assertThat(break_.providerStatus()).isEqualTo("CONFIRMED");
        assertThat(break_.internalStatus()).isEqualTo("CONFIRMED");
        assertThat(break_.compensationId()).isNull();
        assertThat(break_.resolvedAt()).isNull();
        assertThat(break_.escalatedAt()).isNull();
        assertThat(break_.note()).isNull();
        assertThat(break_.lastActor()).isNull();
    }

    @Test
    void openToInvestigatingToResolvedRecordsPrincipalAndNote() {
        ReconBreak break_ = detected();
        break_.startInvestigation("ops.alice", "timing skew hypothesis", T0);
        assertThat(break_.state()).isEqualTo(BreakState.INVESTIGATING);
        assertThat(break_.lastActor()).isEqualTo("ops.alice");
        assertThat(break_.note()).isEqualTo("timing skew hypothesis");
        assertThat(break_.lastTransitionAt()).isEqualTo(T0);
        assertThat(break_.resolvedAt()).isNull();

        break_.resolve("ops.alice", "provider re-issued statement, matched", T1);
        assertThat(break_.state()).isEqualTo(BreakState.RESOLVED);
        assertThat(break_.resolvedAt()).isEqualTo(T1);
        assertThat(break_.lastTransitionAt()).isEqualTo(T1);
    }

    @Test
    void investigatingToWaivedRecordsTheDecision() {
        ReconBreak break_ = detected();
        break_.startInvestigation("ops.alice", "dup", T0);
        break_.waive("ops.bob", "provider fee change, accepted", T1);
        assertThat(break_.state()).isEqualTo(BreakState.WAIVED);
        assertThat(break_.resolvedAt()).isEqualTo(T1);
        assertThat(break_.note()).isEqualTo("provider fee change, accepted");
    }

    @Test
    void manualTransitionsRequireTheLegalSourceState() {
        // resolve straight from OPEN is illegal (no hypothesis was written)
        assertThatThrownBy(() -> detected().resolve("ops.alice", "note", T0))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("legal transition to resolved requires investigating");
        // waive straight from OPEN is illegal
        assertThatThrownBy(() -> detected().waive("ops.alice", "note", T0))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("requires investigating");
        // investigate twice is illegal
        ReconBreak break_ = detected();
        break_.startInvestigation("ops.alice", "h1", T0);
        assertThatThrownBy(() -> break_.startInvestigation("ops.alice", "h2", T1))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("legal transition to investigating requires open");
    }

    @Test
    void terminalBreaksRejectEveryTransition() {
        ReconBreak resolved = detected();
        resolved.startInvestigation("ops.alice", "h", T0);
        resolved.resolve("ops.alice", "done", T1);
        assertThatThrownBy(() -> resolved.startInvestigation("ops.alice", "again", T1))
                .isInstanceOf(ReconciliationStateException.class);
        assertThatThrownBy(() -> resolved.resolve("ops.alice", "again", T1))
                .isInstanceOf(ReconciliationStateException.class);
        assertThatThrownBy(() -> resolved.waive("ops.alice", "again", T1))
                .isInstanceOf(ReconciliationStateException.class);
        assertThatThrownBy(() -> resolved.markCompensated("cmp_01", T1))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("a terminal break can never be compensated");
    }

    @Test
    void aNoteIsMandatoryAndBounded() {
        ReconBreak break_ = detected();
        assertThatThrownBy(() -> break_.startInvestigation("ops.alice", null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a note");
        assertThatThrownBy(() -> break_.startInvestigation("ops.alice", "   ", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a note");
        assertThatThrownBy(() -> break_.startInvestigation("ops.alice", "x".repeat(501), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 500 characters");
        assertThatThrownBy(() -> break_.startInvestigation(null, "note", T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void markCompensatedLinksTheCompensationAsAuditTrail() {
        ReconBreak break_ = detected();
        break_.markCompensated("cmp_01", T1);
        assertThat(break_.state()).isEqualTo(BreakState.COMPENSATED);
        assertThat(break_.compensationId()).isEqualTo("cmp_01");
        assertThat(break_.resolvedAt()).isEqualTo(T1);
        assertThat(break_.lastTransitionAt()).isEqualTo(T1);

        // an investigating break can also be compensated directly
        ReconBreak investigating = detected();
        investigating.startInvestigation("ops.alice", "h", T0);
        investigating.markCompensated("cmp_02", T1);
        assertThat(investigating.state()).isEqualTo(BreakState.COMPENSATED);
    }

    @Test
    void markCompensatedRequiresTheCompensationId() {
        ReconBreak break_ = detected();
        assertThatThrownBy(() -> break_.markCompensated(null, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compensationId is required");
        assertThatThrownBy(() -> break_.markCompensated(" ", T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void advanceBucketMovesFreshToAgingToStaleOncePerTransition() {
        ReconBreak break_ = detected();

        // FRESH → nothing to do while still fresh
        assertThat(break_.advanceBucket(AgingBucket.FRESH, T0)).isFalse();

        // FRESH → AGING at 24 h+
        assertThat(break_.advanceBucket(AgingBucket.AGING, T1)).isTrue();
        assertThat(break_.bucket()).isEqualTo(AgingBucket.AGING);
        assertThat(break_.escalatedAt()).isEqualTo(T1);

        // AGING → AGING is a no-op (one alert per transition, RB-7)
        assertThat(break_.advanceBucket(AgingBucket.AGING, T1.plusSeconds(60))).isFalse();

        // AGING → STALE at >72 h
        assertThat(break_.advanceBucket(AgingBucket.STALE, T2)).isTrue();
        assertThat(break_.bucket()).isEqualTo(AgingBucket.STALE);
        assertThat(break_.escalatedAt()).isEqualTo(T2);

        // STALE is the ceiling
        assertThat(break_.advanceBucket(AgingBucket.STALE, T2.plusSeconds(60))).isFalse();
    }

    @Test
    void advanceBucketNeverRegressesAndRejectsNulls() {
        ReconBreak break_ = detected();
        break_.advanceBucket(AgingBucket.STALE, T1);
        assertThat(break_.advanceBucket(AgingBucket.AGING, T2)).isFalse();
        assertThat(break_.bucket()).isEqualTo(AgingBucket.STALE);

        assertThatThrownBy(() -> break_.advanceBucket(null, T2))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> break_.advanceBucket(AgingBucket.FRESH, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void agingStopsAtResolution() {
        ReconBreak break_ = detected();
        break_.startInvestigation("ops.alice", "h", T0);
        break_.resolve("ops.alice", "done", T1);
        assertThatThrownBy(() -> break_.advanceBucket(AgingBucket.STALE, T2))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("aging stops at resolution");
    }

    @Test
    void detectRejectsNullArguments() {
        DetectedBreak detected = new DetectedBreak(BreakType.MISSING_INTERNAL, "hc_tr_1", null,
                Money.of(150_000, "KES"), null, Money.of(500, "KES"), null, "CONFIRMED", null);
        assertThatThrownBy(() -> ReconBreak.detect("brk_01", "run_01", "honeycoin", null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReconBreak.detect("brk_01", "run_01", "honeycoin", detected, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builderRequiresIdentityAndLifecycleFields() {
        assertThatThrownBy(() -> new ReconBreak.Builder(null, "run_01", "honeycoin",
                BreakType.MISSING_INTERNAL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReconBreak.Builder("brk_01", " ", "honeycoin",
                BreakType.MISSING_INTERNAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> new ReconBreak.Builder("brk_01", "run_01", "honeycoin", null))
                .isInstanceOf(NullPointerException.class);

        // detectedAt / state / bucket are mandatory
        ReconBreak.Builder noDetected = new ReconBreak.Builder("brk_01", "run_01", "honeycoin",
                BreakType.MISSING_INTERNAL).state(BreakState.OPEN).bucket(AgingBucket.FRESH);
        assertThatThrownBy(noDetected::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detectedAt is required");
        ReconBreak.Builder noState = new ReconBreak.Builder("brk_01", "run_01", "honeycoin",
                BreakType.MISSING_INTERNAL).detectedAt(T0).bucket(AgingBucket.FRESH);
        assertThatThrownBy(noState::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state is required");
        ReconBreak.Builder noBucket = new ReconBreak.Builder("brk_01", "run_01", "honeycoin",
                BreakType.MISSING_INTERNAL).detectedAt(T0).state(BreakState.OPEN);
        assertThatThrownBy(noBucket::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aging bucket is required");
    }

    @Test
    void rehydrateRestoresEveryFieldIncludingAuditColumns() {
        ReconBreak break_ = new ReconBreak.Builder("brk_9", "run_9", "honeycoin",
                BreakType.FEE_MISMATCH)
                .providerRef("hc_tr_9")
                .internalRef("payout:po_9")
                .providerAmount(Money.of(1_000, "KES"))
                .internalAmount(Money.of(1_000, "KES"))
                .providerFee(Money.of(150, "KES"))
                .internalFee(Money.of(100, "KES"))
                .providerStatus("CONFIRMED")
                .internalStatus("CONFIRMED")
                .detectedAt(T0)
                .state(BreakState.COMPENSATED)
                .bucket(AgingBucket.STALE)
                .note("settled via suspense")
                .lastActor("ops.bob")
                .lastTransitionAt(T1)
                .compensationId("cmp_9")
                .resolvedAt(T1)
                .escalatedAt(T2)
                .build();
        assertThat(break_.id()).isEqualTo("brk_9");
        assertThat(break_.state()).isEqualTo(BreakState.COMPENSATED);
        assertThat(break_.note()).isEqualTo("settled via suspense");
        assertThat(break_.lastActor()).isEqualTo("ops.bob");
        assertThat(break_.compensationId()).isEqualTo("cmp_9");
        assertThat(break_.escalatedAt()).isEqualTo(T2);
    }

    @Test
    void breakStateWireNamesTerminalityAndParsing() {
        assertThat(BreakState.OPEN.wireName()).isEqualTo("open");
        assertThat(BreakState.INVESTIGATING.wireName()).isEqualTo("investigating");
        assertThat(BreakState.RESOLVED.wireName()).isEqualTo("resolved");
        assertThat(BreakState.COMPENSATED.wireName()).isEqualTo("compensated");
        assertThat(BreakState.WAIVED.wireName()).isEqualTo("waived");
        assertThat(BreakState.OPEN.isActive()).isTrue();
        assertThat(BreakState.INVESTIGATING.isActive()).isTrue();
        for (BreakState terminal : List.of(BreakState.RESOLVED, BreakState.COMPENSATED,
                BreakState.WAIVED)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.isActive()).isFalse();
        }
        assertThat(BreakState.fromWireName("investigating")).isEqualTo(BreakState.INVESTIGATING);
        assertThatThrownBy(() -> BreakState.fromWireName("closed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown break state");
    }

    @Test
    void breakTypeWireNamesAndParsing() {
        assertThat(BreakType.MISSING_ON_PROVIDER.wireName()).isEqualTo("missing_on_provider");
        assertThat(BreakType.MISSING_INTERNAL.wireName()).isEqualTo("missing_internal");
        assertThat(BreakType.AMOUNT_MISMATCH.wireName()).isEqualTo("amount_mismatch");
        assertThat(BreakType.STATUS_MISMATCH.wireName()).isEqualTo("status_mismatch");
        assertThat(BreakType.FEE_MISMATCH.wireName()).isEqualTo("fee_mismatch");
        assertThat(BreakType.CURRENCY_MISMATCH.wireName()).isEqualTo("currency_mismatch");
        assertThat(BreakType.fromWireName("amount_mismatch")).isEqualTo(BreakType.AMOUNT_MISMATCH);
        assertThatThrownBy(() -> BreakType.fromWireName("weird_break"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown break type");
    }

    private static ReconBreak detected() {
        DetectedBreak detected = new DetectedBreak(BreakType.AMOUNT_MISMATCH, "hc_tr_1",
                "payout:po_1", Money.of(150_000, "KES"), Money.of(149_500, "KES"),
                Money.of(500, "KES"), Money.of(500, "KES"), "CONFIRMED", "CONFIRMED");
        return ReconBreak.detect("brk_01", "run_01", "honeycoin", detected, T0);
    }
}
