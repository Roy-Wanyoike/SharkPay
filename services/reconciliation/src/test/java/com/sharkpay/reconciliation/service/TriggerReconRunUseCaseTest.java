package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.BreakType;
import com.sharkpay.reconciliation.domain.IdempotencyConflictException;
import com.sharkpay.reconciliation.domain.ReconRunState;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.fakes.FakeLedgerStatement;
import com.sharkpay.reconciliation.fakes.FakeProviderStatement;
import com.sharkpay.reconciliation.ports.IdempotencyStore;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outer consistency loop's run trigger (WP-10): fetch both statement
 * sides of the half-open window, compare, record every break, persist the
 * settlement report, complete the run — idempotent on the
 * Idempotency-Key (same key ⇒ same run, no second effect; different
 * payload ⇒ 409 conflict; a FAILED run replays deterministically).
 */
class TriggerReconRunUseCaseTest {

    private ReconTestEnv env;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
    }

    @Test
    void aZeroBreakRunCompletesWithNoBreaksAndPublishesRunCompleted() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);

        TriggerReconRunUseCase.Result result = env.triggerDefault("key-1");

        assertThat(result.replay()).isFalse();
        assertThat(result.run().state()).isEqualTo(ReconRunState.COMPLETED);
        assertThat(result.run().providerLines()).isEqualTo(1);
        assertThat(result.run().internalLines()).isEqualTo(1);
        assertThat(result.run().matchedPairs()).isEqualTo(1);
        assertThat(result.run().breakCount()).isZero();
        assertThat(result.breaks()).isEmpty();
        assertThat(result.report()).isNotNull();
        assertThat(result.report().breakSummary().total()).isZero();
        assertThat(env.runs.count()).isEqualTo(1);
        assertThat(env.breaks.count()).isZero();
        assertThat(env.reports.count()).isEqualTo(1);
        assertThat(env.events.events()).hasSize(1);
        assertThat(env.events.events().get(0).type()).isEqualTo(ReconEvents.RUN_COMPLETED);
    }

    @Test
    void everySeededDiscrepancyLandsInExactlyOneBreakWithEvents() {
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);                       // MISSING_INTERNAL
        env.seedInternalLine("int_orphan", "hc_orphan", "CONFIRMED", 9_000, 0);        // MISSING_ON_PROVIDER
        env.seedProviderLine("hc_amount", "CONFIRMED", 150_000, 500);                  // AMOUNT_MISMATCH
        env.seedInternalLine("int_amount", "hc_amount", "CONFIRMED", 149_500, 500);
        env.seedProviderLine("hc_status", "SUCCEEDED", 1_000, 0);                      // STATUS_MISMATCH
        env.seedInternalLine("int_status", "hc_status", "PENDING", 1_000, 0);
        env.seedProviderLine("hc_fee", "CONFIRMED", 3_000, 650);                       // FEE_MISMATCH
        env.seedInternalLine("int_fee", "hc_fee", "CONFIRMED", 3_000, 500);

        TriggerReconRunUseCase.Result result = env.triggerDefault("key-1");

        assertThat(result.run().state()).isEqualTo(ReconRunState.COMPLETED);
        assertThat(result.run().providerLines()).isEqualTo(4);
        assertThat(result.run().internalLines()).isEqualTo(4);
        assertThat(result.run().matchedPairs()).isEqualTo(3);
        assertThat(result.run().breakCount()).isEqualTo(5);
        assertThat(result.breaks())
                .extracting(com.sharkpay.reconciliation.domain.ReconBreak::breakType)
                .containsExactlyInAnyOrder(BreakType.MISSING_INTERNAL, BreakType.MISSING_ON_PROVIDER,
                        BreakType.AMOUNT_MISMATCH, BreakType.STATUS_MISMATCH, BreakType.FEE_MISMATCH);
        assertThat(result.breaks()).hasSize(5);

        // exactly one break.detected event per break + exactly one run.completed
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_DETECTED)).hasSize(5);
        assertThat(env.events.eventsOfType(ReconEvents.RUN_COMPLETED)).hasSize(1);
        // the settlement report carries the taxonomy counts
        assertThat(result.report().breakSummary().missingInternal()).isEqualTo(1);
        assertThat(result.report().breakSummary().missingOnProvider()).isEqualTo(1);
        assertThat(result.report().breakSummary().amountMismatch()).isEqualTo(1);
        assertThat(result.report().breakSummary().statusMismatch()).isEqualTo(1);
        assertThat(result.report().breakSummary().feeMismatch()).isEqualTo(1);
        assertThat(result.report().breakSummary().total()).isEqualTo(5);
    }

    @Test
    void theSameKeyReplaysTheSameRunWithNoSecondEffect() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);

        TriggerReconRunUseCase.Result first = env.triggerDefault("key-1");
        int eventsAfterFirst = env.events.count();
        int fetchesAfterFirst = env.providers.fetchCount();

        TriggerReconRunUseCase.Result replay = env.triggerDefault("key-1");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThat(replay.breaks()).hasSize(1);
        assertThat(replay.breaks().get(0).id()).isEqualTo(first.breaks().get(0).id());
        assertThat(replay.report()).isNotNull();
        // no second effect: no new runs, breaks, reports, events, fetches
        assertThat(env.runs.count()).isEqualTo(1);
        assertThat(env.breaks.count()).isEqualTo(1);
        assertThat(env.reports.count()).isEqualTo(1);
        assertThat(env.events.count()).isEqualTo(eventsAfterFirst);
        assertThat(env.providers.fetchCount()).isEqualTo(fetchesAfterFirst);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsAConflict() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        env.triggerDefault("key-1");

        assertThatThrownBy(() -> env.triggerRun.trigger("key-1", "honeycoin",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO.plus(Duration.ofHours(1))))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("key-1");
        assertThatThrownBy(() -> env.triggerRun.trigger("key-1", "otherprovider",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO))
                .isInstanceOf(IdempotencyConflictException.class);

        // provider spelling normalisation: " honeycoin " is the same request
        TriggerReconRunUseCase.Result replay = env.triggerRun.trigger("key-1", " honeycoin ",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO);
        assertThat(replay.replay()).isTrue();
        // the fetch used the trimmed canonical provider name
        assertThat(env.providers.fetches().get(0).provider()).isEqualTo("honeycoin");
    }

    @Test
    void anUnavailableStatementSideFailsTheRunAuditableAndReplayable() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        env.providers.failNextFetch();

        TriggerReconRunUseCase.Result result = env.triggerDefault("key-1");

        assertThat(result.run().state()).isEqualTo(ReconRunState.FAILED);
        assertThat(result.run().failureReason()).contains("provider statement unavailable");
        assertThat(result.breaks()).isEmpty();
        assertThat(result.report()).isNull();
        assertThat(env.breaks.count()).isZero();
        assertThat(env.reports.count()).isZero();
        assertThat(env.events.count()).isZero(); // no run.completed for a failed run

        // the same key deterministically replays the SAME failed run
        TriggerReconRunUseCase.Result replay = env.triggerDefault("key-1");
        assertThat(replay.replay()).isTrue();
        assertThat(replay.run().id()).isEqualTo(result.run().id());
        assertThat(replay.run().state()).isEqualTo(ReconRunState.FAILED);
        assertThat(env.runs.count()).isEqualTo(1);

        // the ledger side failing is equally auditable
        ReconTestEnv ledgerDown = new ReconTestEnv();
        ledgerDown.seedMatch("hc_clean", 150_000, "KES", 500);
        ledgerDown.ledgerStatement.failNextFetch();
        assertThat(ledgerDown.triggerDefault("key-ledger").run().state())
                .isEqualTo(ReconRunState.FAILED);
        assertThat(ledgerDown.triggerDefault("key-ledger").run().failureReason())
                .contains("ledger statement unavailable");
    }

    @Test
    void theFetchedWindowIsHalfOpen() {
        // a line at exactly FROM is inside; a line at exactly TO is outside
        env.providers.seed("hc_at_from", "CONFIRMED", 150_000, "KES", 500, ReconTestEnv.WINDOW_FROM);
        env.providers.seed("hc_at_to", "CONFIRMED", 150_000, "KES", 500, ReconTestEnv.WINDOW_TO);
        env.ledgerStatement.seed("int_at_from", "hc_at_from", "CONFIRMED", 150_000, "KES", 500,
                ReconTestEnv.WINDOW_FROM);
        env.ledgerStatement.seed("int_at_to", "hc_at_to", "CONFIRMED", 150_000, "KES", 500,
                ReconTestEnv.WINDOW_TO);

        TriggerReconRunUseCase.Result result = env.triggerDefault("key-1");

        assertThat(result.run().providerLines()).isEqualTo(1);  // hc_at_from only
        assertThat(result.run().internalLines()).isEqualTo(1);
        assertThat(result.run().matchedPairs()).isEqualTo(1);
        assertThat(result.run().breakCount()).isZero();

        // the ports were asked for exactly [from, to)
        assertThat(env.providers.fetches()).containsExactly(
                new FakeProviderStatement.RecordedFetch("honeycoin", ReconTestEnv.WINDOW_FROM,
                        ReconTestEnv.WINDOW_TO));
        assertThat(env.ledgerStatement.fetches()).containsExactly(
                new FakeLedgerStatement.RecordedFetch("honeycoin", ReconTestEnv.WINDOW_FROM,
                        ReconTestEnv.WINDOW_TO));
    }

    @Test
    void aLineInsideTheWindowBeyondTheEdgesIsServed() {
        Instant mid = ReconTestEnv.WINDOW_FROM.plus(Duration.ofHours(12));
        env.providers.seed("hc_mid", "CONFIRMED", 1_000, "KES", 0, mid);
        TriggerReconRunUseCase.Result result = env.triggerDefault("key-1");
        assertThat(result.run().providerLines()).isEqualTo(1);
        assertThat(result.run().breakCount()).isEqualTo(1);   // no internal side
    }

    @Test
    void inputValidationRejectsBadKeysProvidersAndWindows() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);

        assertThatThrownBy(() -> env.triggerRun.trigger(null, "honeycoin",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.triggerRun.trigger("   ", "honeycoin",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.triggerRun.trigger("k".repeat(129), "honeycoin",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 128 characters");
        assertThatThrownBy(() -> env.triggerRun.trigger("key-1", " ",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider must not be blank");
        assertThatThrownBy(() -> env.triggerRun.trigger("key-1", null,
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env.triggerRun.trigger("key-1", "honeycoin", null,
                ReconTestEnv.WINDOW_TO))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from is required");
        assertThatThrownBy(() -> env.triggerRun.trigger("key-1", "honeycoin",
                ReconTestEnv.WINDOW_FROM, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("to is required");
        // a backwards window is rejected before any run is created
        assertThatThrownBy(() -> env.triggerRun.trigger("key-1", "honeycoin",
                ReconTestEnv.WINDOW_TO, ReconTestEnv.WINDOW_FROM))
                .isInstanceOf(com.sharkpay.reconciliation.domain.InvalidWindowException.class);
        assertThat(env.runs.count()).isZero();
    }

    @Test
    void aKeyBoundToAMissingRunIsALoudIntegrityError() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        // a stored record whose run vanished is an integrity break: loud,
        // never a silently fresh run under the same key
        String fingerprint = TriggerReconRunUseCase.fingerprint("honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(ReconTestEnv.WINDOW_FROM,
                        ReconTestEnv.WINDOW_TO));
        env.idempotency.put(IdempotencyStore.Scope.TRIGGER_RUN, "key-1",
                new IdempotencyStore.StoredRequest(fingerprint, "run_missing"));

        assertThatThrownBy(() -> env.triggerDefault("key-1"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("run_missing")
                .hasMessageContaining("referenced by idempotency key key-1");
        // nothing new was created under the key
        assertThat(env.runs.count()).isZero();
    }

    @Test
    void theKeyAndBodyAreBoundThroughTheIdempotencyStore() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        TriggerReconRunUseCase.Result result = env.triggerDefault("key-1");

        assertThat(env.idempotency.contains(IdempotencyStore.Scope.TRIGGER_RUN, "key-1")).isTrue();
        // the stored record binds the canonical request fingerprint to the run id
        assertThat(env.idempotency.find(IdempotencyStore.Scope.TRIGGER_RUN, "key-1"))
                .map(IdempotencyStore.StoredRequest::entityId)
                .contains(result.run().id());
        assertThat(env.idempotency.find(IdempotencyStore.Scope.TRIGGER_RUN, "key-1"))
                .map(IdempotencyStore.StoredRequest::requestFingerprint)
                .contains("TRIGGER_RUN|honeycoin|" + ReconTestEnv.WINDOW_FROM + "|"
                        + ReconTestEnv.WINDOW_TO);
    }

    @Test
    void runsOfAProviderAreListedNewestFirst() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        env.triggerDefault("key-1");
        env.clock.advance(Duration.ofMinutes(30));
        env.triggerRun.trigger("key-2", "honeycoin", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO);
        env.clock.advance(Duration.ofMinutes(30));
        env.triggerRun.trigger("key-3", "honeycoin", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO);

        assertThat(env.runs.listByProvider("honeycoin")).hasSize(3);
        // the newest run is the one with the latest startedAt (newest first)
        var runs = env.runs.listByProvider("honeycoin");
        assertThat(runs.get(0).startedAt()).isAfter(runs.get(1).startedAt());
        assertThat(runs.get(1).startedAt()).isAfter(runs.get(2).startedAt());
        assertThat(runs).allSatisfy(run -> assertThat(run.state()).isEqualTo(ReconRunState.COMPLETED));
        // another provider's runs are not mixed in
        assertThat(env.runs.listByProvider("other")).isEmpty();
    }
}
