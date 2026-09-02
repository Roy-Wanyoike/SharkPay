package com.sharkpay.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReconRun lifecycle: RUNNING → COMPLETED | FAILED, terminal states are
 * immutable, FAILED keeps its reason, rehydrate restores every field.
 */
class ReconRunTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T10:00:05Z");
    private static final ReconWindow WINDOW = new ReconWindow(
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"));

    @Test
    void startCreatesARunningRunWithZeroCounts() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        assertThat(run.state()).isEqualTo(ReconRunState.RUNNING);
        assertThat(run.startedAt()).isEqualTo(T0);
        assertThat(run.completedAt()).isNull();
        assertThat(run.failureReason()).isNull();
        assertThat(run.providerLines()).isZero();
        assertThat(run.internalLines()).isZero();
        assertThat(run.matchedPairs()).isZero();
        assertThat(run.breakCount()).isZero();
        assertThat(run.provider()).isEqualTo("honeycoin");
        assertThat(run.window()).isEqualTo(WINDOW);
    }

    @Test
    void completeRecordsTheCountsAndTimestamp() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        run.complete(new ReconRun.Counts(12, 10, 9, 3), T1);
        assertThat(run.state()).isEqualTo(ReconRunState.COMPLETED);
        assertThat(run.completedAt()).isEqualTo(T1);
        assertThat(run.providerLines()).isEqualTo(12);
        assertThat(run.internalLines()).isEqualTo(10);
        assertThat(run.matchedPairs()).isEqualTo(9);
        assertThat(run.breakCount()).isEqualTo(3);
        assertThat(run.failureReason()).isNull();
    }

    @Test
    void failRecordsTheReasonAndTimestamp() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        run.fail("provider statement unavailable for provider honeycoin", T1);
        assertThat(run.state()).isEqualTo(ReconRunState.FAILED);
        assertThat(run.completedAt()).isEqualTo(T1);
        assertThat(run.failureReason())
                .isEqualTo("provider statement unavailable for provider honeycoin");
    }

    @Test
    void aCompletedRunIsFrozen() {
        ReconRun run = completed();
        assertThatThrownBy(() -> run.complete(new ReconRun.Counts(1, 1, 1, 0), T1))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("already completed")
                .hasMessageContaining("cannot complete");
        assertThatThrownBy(() -> run.fail("late failure", T1))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("cannot fail");
    }

    @Test
    void aFailedRunIsFrozen() {
        ReconRun run = failed();
        assertThatThrownBy(() -> run.fail("another failure", T1))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("already failed");
        assertThatThrownBy(() -> run.complete(new ReconRun.Counts(1, 1, 1, 0), T1))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("cannot complete");
    }

    @Test
    void aBlankFailureReasonIsRejected() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        assertThatThrownBy(() -> run.fail("  ", T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure reason must not be blank");
        assertThatThrownBy(() -> run.fail(null, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeRejectsNullArguments() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        assertThatThrownBy(() -> run.complete(null, T1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("counts is required");
        assertThatThrownBy(() -> run.complete(new ReconRun.Counts(1, 1, 1, 0), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("now is required");
    }

    @Test
    void identityFieldsAreValidated() {
        assertThatThrownBy(() -> ReconRun.start(null, "honeycoin", WINDOW, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReconRun.start("run_01", " ", WINDOW, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider must not be blank");
        assertThatThrownBy(() -> ReconRun.start("run_01", "honeycoin", null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rehydrateRestoresEveryFieldWithoutStateGuards() {
        ReconRun run = ReconRun.rehydrate("run_42", "honeycoin", WINDOW, T0,
                ReconRunState.FAILED, T1, "provider down", 7, 6, 5, 2);
        assertThat(run.id()).isEqualTo("run_42");
        assertThat(run.state()).isEqualTo(ReconRunState.FAILED);
        assertThat(run.completedAt()).isEqualTo(T1);
        assertThat(run.failureReason()).isEqualTo("provider down");
        assertThat(run.providerLines()).isEqualTo(7);
        assertThat(run.internalLines()).isEqualTo(6);
        assertThat(run.matchedPairs()).isEqualTo(5);
        assertThat(run.breakCount()).isEqualTo(2);
        // rehydrated terminal run is still frozen
        assertThatThrownBy(() -> run.complete(new ReconRun.Counts(1, 1, 1, 0), T1))
                .isInstanceOf(ReconciliationStateException.class);
    }

    @Test
    void runStateWireNamesParsingAndTerminality() {
        assertThat(ReconRunState.RUNNING.wireName()).isEqualTo("running");
        assertThat(ReconRunState.COMPLETED.wireName()).isEqualTo("completed");
        assertThat(ReconRunState.FAILED.wireName()).isEqualTo("failed");
        assertThat(ReconRunState.RUNNING.isTerminal()).isFalse();
        assertThat(ReconRunState.COMPLETED.isTerminal()).isTrue();
        assertThat(ReconRunState.FAILED.isTerminal()).isTrue();
        assertThat(ReconRunState.fromWireName("completed")).isEqualTo(ReconRunState.COMPLETED);
        assertThatThrownBy(() -> ReconRunState.fromWireName("paused"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown recon run state");
    }

    private ReconRun completed() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        run.complete(new ReconRun.Counts(1, 1, 1, 0), T1);
        return run;
    }

    private ReconRun failed() {
        ReconRun run = ReconRun.start("run_01", "honeycoin", WINDOW, T0);
        run.fail("provider down", T1);
        return run;
    }
}
