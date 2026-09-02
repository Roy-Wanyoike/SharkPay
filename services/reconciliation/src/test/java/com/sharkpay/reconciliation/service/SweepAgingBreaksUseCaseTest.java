package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.AgingBucket;
import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RB-7 aging sweeper: recompute the live bucket of every ACTIVE break,
 * persist transitions, publish the ops alert exactly once per transition
 * (AGING = page, STALE = S2-minimum escalation); terminal breaks are never
 * swept; repeated sweeps without a transition are no-ops.
 */
class SweepAgingBreaksUseCaseTest {

    private ReconTestEnv env;
    private List<ReconBreak> seeded;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        // two discrepancies: one for sweeping, one to resolve first
        env.seedProviderLine("hc_amount", "CONFIRMED", 150_000, 500);
        env.seedInternalLine("int_amount", "hc_amount", "CONFIRMED", 149_500, 500);
        env.seedProviderLine("hc_fee", "CONFIRMED", 3_000, 650);
        env.seedInternalLine("int_fee", "hc_fee", "CONFIRMED", 3_000, 500);
        seeded = env.triggerDefault("key-run").breaks();
        assertThat(seeded).hasSize(2);
    }

    @Test
    void aFreshSweepWithoutTransitionsIsANoOp() {
        SweepAgingBreaksUseCase.Result result = env.sweepAging.sweep();

        assertThat(result.escalatedCount()).isZero();
        assertThat(result.escalated()).isEmpty();
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED)).isEmpty();
        // no repository write for a no-op transition
        assertThat(env.breaks.listActive()).hasSize(2);
        assertThat(seeded.get(0).bucket()).isEqualTo(AgingBucket.FRESH);
    }

    @Test
    void aBreakCrossing24hPagesExactlyOnce() {
        env.clock.advance(Duration.ofHours(24).plusMinutes(5));

        SweepAgingBreaksUseCase.Result result = env.sweepAging.sweep();

        assertThat(result.escalatedCount()).isEqualTo(2);
        assertThat(result.escalated()).allSatisfy(break_ ->
                assertThat(break_.bucket()).isEqualTo(AgingBucket.AGING));
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED)).hasSize(2);

        // a second sweep right away is a no-op — one alert per transition
        SweepAgingBreaksUseCase.Result second = env.sweepAging.sweep();
        assertThat(second.escalatedCount()).isZero();
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED)).hasSize(2);
    }

    @Test
    void aBreakAgesThroughAllThreeBucketsWithOneAlertEach() {
        // FRESH → AGING
        env.clock.advance(Duration.ofHours(30));
        assertThat(env.sweepAging.sweep().escalatedCount()).isEqualTo(2);

        // AGING → STALE at >72 h
        env.clock.advance(Duration.ofHours(43));
        SweepAgingBreaksUseCase.Result stale = env.sweepAging.sweep();
        assertThat(stale.escalatedCount()).isEqualTo(2);
        assertThat(stale.escalated()).allSatisfy(break_ ->
                assertThat(break_.bucket()).isEqualTo(AgingBucket.STALE));

        // total: one AGING alert + one STALE alert per break
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED)).hasSize(4);
        // STALE is the ceiling — further sweeps stay no-ops
        env.clock.advance(Duration.ofHours(24));
        assertThat(env.sweepAging.sweep().escalatedCount()).isZero();

        // the escalated events carry the live bucket + age
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED))
                .allSatisfy(event -> {
                    var data = (com.sharkpay.reconciliation.events.ReconEvents.BreakEscalatedData)
                            event.data();
                    assertThat(data.bucket()).isIn("aging", "stale");
                    assertThat(data.age_hours()).isPositive();
                });
    }

    @Test
    void terminalBreaksAreNeverSwept() {
        String resolved = seeded.get(0).id();
        // OPEN → INVESTIGATING → RESOLVED (the legal manual path)
        env.transitionBreak.transition(resolved, "investigating", "ops.alice",
                "hypothesis: timing skew");
        env.transitionBreak.transition(resolved, "resolved", "ops.alice", "matched by re-run");
        env.clock.advance(Duration.ofHours(80));

        SweepAgingBreaksUseCase.Result result = env.sweepAging.sweep();

        // only the still-open break escalated
        assertThat(result.escalatedCount()).isEqualTo(1);
        assertThat(result.escalated().get(0).id()).isNotEqualTo(resolved);
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED)).hasSize(1);
        // the resolved break stays terminal with no aging side effects
        assertThat(env.breaks.findById(resolved).orElseThrow().state())
                .isEqualTo(BreakState.RESOLVED);
    }

    @Test
    void anInvestigatingBreakStillAgesAndEscalates() {
        env.transitionBreak.transition(seeded.get(0).id(), "investigating", "ops.alice",
                "hypothesis: settlement file late");
        env.clock.advance(Duration.ofHours(25));

        SweepAgingBreaksUseCase.Result result = env.sweepAging.sweep();
        assertThat(result.escalatedCount()).isEqualTo(2);
        assertThat(env.breaks.listByState(BreakState.INVESTIGATING).get(0).bucket())
                .isEqualTo(AgingBucket.AGING);
    }
}
