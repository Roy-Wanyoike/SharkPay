package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manual break lifecycle transitions (RB-7): OPEN → INVESTIGATING →
 * RESOLVED | WAIVED, every transition records the principal and the
 * hypothesis note, and COMPENSATED is deliberately unreachable by hand
 * (only a 4-eyes compensation execution gets there).
 */
class TransitionBreakUseCaseTest {

    private ReconTestEnv env;
    private String breakId;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        env.seedProviderLine("hc_amount", "CONFIRMED", 150_000, 500);
        env.seedInternalLine("int_amount", "hc_amount", "CONFIRMED", 149_500, 500);
        breakId = env.triggerDefault("key-run").breaks().get(0).id();
    }

    @Test
    void openToInvestigatingRecordsPrincipalAndNote() {
        BreakView view = env.transitionBreak.transition(breakId, "investigating", "ops.alice",
                "timing skew: settlement file arrives late");

        assertThat(view.break_().state()).isEqualTo(BreakState.INVESTIGATING);
        assertThat(view.break_().lastActor()).isEqualTo("ops.alice");
        assertThat(view.break_().note()).isEqualTo("timing skew: settlement file arrives late");
        // the transition persisted
        assertThat(env.breaks.findById(breakId).orElseThrow().state())
                .isEqualTo(BreakState.INVESTIGATING);
    }

    @Test
    void investigatingToResolvedAndWaived() {
        env.transitionBreak.transition(breakId, "investigating", "ops.alice", "h1");
        BreakView resolved = env.transitionBreak.transition(breakId, "resolved", "ops.alice",
                "provider re-issued the statement");
        assertThat(resolved.break_().state()).isEqualTo(BreakState.RESOLVED);
        assertThat(resolved.break_().resolvedAt()).isEqualTo(env.clock.instant());

        // a second break goes the waive route
        env.seedProviderLine("hc_other", "CONFIRMED", 1_000, 0);
        String other = env.triggerRun.trigger("key-run-2", "honeycoin", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO).breaks().stream()
                .filter(break_ -> break_.providerRef().equals("hc_other"))
                .findFirst().orElseThrow().id();
        env.transitionBreak.transition(other, "investigating", "ops.alice", "fee change");
        BreakView waived = env.transitionBreak.transition(other, "waived", "ops.bob",
                "documented fee change, accepted");
        assertThat(waived.break_().state()).isEqualTo(BreakState.WAIVED);
    }

    @Test
    void compensatedIsNotReachableByHand() {
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, "compensated", "ops.alice",
                "posting"))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("only reach compensated via a 4-eyes compensation");
    }

    @Test
    void unknownTargetStatesAreRejected() {
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, "closed", "ops.alice", "n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown break state 'closed'")
                .hasMessageContaining("investigating, resolved, waived");
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, null, "ops.alice", "n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, "RESOLVED", "ops.alice",
                "n"))   // case-insensitive target is fine
                .isInstanceOf(ReconciliationStateException.class); // (OPEN → resolved is illegal)
    }

    @Test
    void principalAndNoteAreValidated() {
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, "investigating", null, "n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal must not be blank");
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, "investigating", " ", "n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, "investigating", "ops.alice",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a note");
    }

    @Test
    void anUnknownBreakIsA404AndTheTransitionCaseIsTrimmed() {
        assertThatThrownBy(() -> env.transitionBreak.transition("brk_unknown", "investigating",
                "ops.alice", "n"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("brk_unknown");

        BreakView view = env.transitionBreak.transition(breakId, " investigating ", " ops.alice ",
                "trimmed");
        assertThat(view.break_().state()).isEqualTo(BreakState.INVESTIGATING);
        assertThat(view.break_().lastActor()).isEqualTo("ops.alice");
    }

    @Test
    void anIllegalSourceStateIsA409() {
        env.transitionBreak.transition(breakId, "investigating", "ops.alice", "h");
        assertThatThrownBy(() -> env.transitionBreak.transition(breakId, "investigating",
                "ops.alice", "h2"))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("legal transition to investigating requires open");
    }
}
