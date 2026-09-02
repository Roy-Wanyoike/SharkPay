package com.sharkpay.reconciliation.service;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.domain.CompensationLeg;
import com.sharkpay.reconciliation.domain.IdempotencyConflictException;
import com.sharkpay.reconciliation.domain.PostingDirection;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.ports.IdempotencyStore;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Operator A drafts the compensation (RB-7 step 2): the entry is created
 * PROPOSED with the RB-7 ledger key ({@code ops:adj:<breakId>}, sequence
 * suffix for later ones), nothing posts to the ledger yet, and the
 * Idempotency-Key binds the draft (replay ⇒ same proposal; different
 * payload ⇒ 409).
 */
class ProposeCompensationUseCaseTest {

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
    void operatorADraftsAProposedEntryWithTheRb7Key() {
        ProposeCompensationUseCase.Result result = propose("key-1");

        assertThat(result.replay()).isFalse();
        CompensationEntry entry = result.entry();
        assertThat(entry.id()).startsWith("cmp_");
        assertThat(entry.breakId()).isEqualTo(breakId);
        assertThat(entry.provider()).isEqualTo("honeycoin");
        assertThat(entry.state()).isEqualTo(CompensationEntry.CompensationState.PROPOSED);
        assertThat(entry.compensationKey()).isEqualTo("ops:adj:" + breakId);
        assertThat(entry.requester()).isEqualTo("ops.alice");
        assertThat(entry.legs()).hasSize(2);
        assertThat(env.compensations.count()).isEqualTo(1);
        assertThat(env.ledger.attempts()).isZero(); // nothing posted yet (4-eyes pending)
    }

    @Test
    void theSameKeyWithTheSamePayloadReplaysTheOriginalProposal() {
        ProposeCompensationUseCase.Result first = propose("key-1");
        ProposeCompensationUseCase.Result replay = propose("key-1");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.entry().id()).isEqualTo(first.entry().id());
        assertThat(replay.entry().compensationKey()).isEqualTo(first.entry().compensationKey());
        assertThat(env.compensations.count()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsAConflict() {
        propose("key-1");

        assertThatThrownBy(() -> env.proposeCompensation.propose("key-1", breakId, "ops.alice",
                "different reason", legs(500, 500), null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("key-1");
        // a leg change is also a different payload
        assertThatThrownBy(() -> env.proposeCompensation.propose("key-1", breakId, "ops.alice",
                "settlement variance", legs(600, 600), null))
                .isInstanceOf(IdempotencyConflictException.class);
        // a requester change is also a different payload
        assertThatThrownBy(() -> env.proposeCompensation.propose("key-1", breakId, "ops.bob",
                "settlement variance", legs(500, 500), null))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(env.compensations.count()).isEqualTo(1);
    }

    @Test
    void aTerminalBreakIsNeverCompensated() {
        // OPEN → INVESTIGATING → RESOLVED (the legal manual path)
        env.transitionBreak.transition(breakId, "investigating", "ops.alice",
                "hypothesis: statement re-issued");
        env.transitionBreak.transition(breakId, "resolved", "ops.alice", "matched by re-run");

        assertThatThrownBy(() -> propose("key-1"))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("a terminal break is never compensated");
        assertThat(env.compensations.count()).isZero();
    }

    @Test
    void anUnknownBreakIsA404() {
        assertThatThrownBy(() -> env.proposeCompensation.propose("key-1", "brk_unknown",
                "ops.alice", "r", legs(500, 500), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("brk_unknown");
    }

    @Test
    void laterCompensationsForTheSameBreakGetSequenceSuffixedKeys() {
        propose("key-1");
        ProposeCompensationUseCase.Result second = propose("key-2");
        assertThat(second.entry().compensationKey()).isEqualTo("ops:adj:" + breakId + "#2");

        ProposeCompensationUseCase.Result third = propose("key-3");
        assertThat(third.entry().compensationKey()).isEqualTo("ops:adj:" + breakId + "#3");

        // proposal order preserved
        assertThat(env.compensations.listByBreak(breakId))
                .extracting(CompensationEntry::compensationKey)
                .containsExactly("ops:adj:" + breakId, "ops:adj:" + breakId + "#2",
                        "ops:adj:" + breakId + "#3");
        assertThat(env.compensations.countByBreak(breakId)).isEqualTo(3);
    }

    @Test
    void theKeyAndBodyAreValidatedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> env.proposeCompensation.propose(null, breakId, "ops.alice",
                "r", legs(500, 500), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.proposeCompensation.propose(" ", breakId, "ops.alice",
                "r", legs(500, 500), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.proposeCompensation.propose("k".repeat(129), breakId,
                "ops.alice", "r", legs(500, 500), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 128 characters");

        // domain validation propagates: unbalanced legs
        assertThatThrownBy(() -> env.proposeCompensation.propose("key-1", breakId, "ops.alice",
                "r", legs(500, 400), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must balance per currency");
        assertThat(env.compensations.count()).isZero();
    }

    @Test
    void aReversalCompensationCarriesTheEntryItCompensates() {
        UUID priorEntry = UUID.randomUUID();
        ProposeCompensationUseCase.Result result = env.proposeCompensation.propose("key-rev",
                breakId, "ops.alice", "corrects the wrong compensation", legs(500, 500), priorEntry);

        assertThat(result.entry().reversesEntryId()).isEqualTo(priorEntry);
        assertThat(result.entry().compensationKey()).isEqualTo("ops:adj:" + breakId);
    }

    @Test
    void theRequesterIsStoredTrimmedSoPaddedSpellingIsTheSamePerson() {
        // 4-eyes integrity: " ops.alice " and "ops.alice" must never be two
        // principals (the approver comparison trims; the requester is
        // canonicalised here)
        ProposeCompensationUseCase.Result padded = env.proposeCompensation.propose("key-pad",
                breakId, " ops.alice ", "variance", legs(500, 500), null);
        assertThat(padded.entry().requester()).isEqualTo("ops.alice");

        // the idempotency fingerprint also treats both spellings as the same
        // request: a replay with the trimmed name returns the original entry
        ProposeCompensationUseCase.Result replay = env.proposeCompensation.propose("key-pad",
                breakId, "ops.alice", "variance", legs(500, 500), null);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.entry().id()).isEqualTo(padded.entry().id());
    }

    @Test
    void aNullRequesterIsRejectedByTheDomain() {
        assertThatThrownBy(() -> env.proposeCompensation.propose("key-1", breakId, null, "r",
                legs(500, 500), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("requester is required");
        assertThat(env.compensations.count()).isZero();
    }

    @Test
    void aKeyBoundToAMissingCompensationIsALoudIntegrityError() {
        // the stored record's entry must exist: a replay that cannot resolve
        // its own entity is an integrity break, never a silent re-draft
        String fingerprint = ProposeCompensationUseCase.fingerprint(breakId, "ops.alice",
                "settlement variance", legs(500, 500), null);
        env.idempotency.put(IdempotencyStore.Scope.PROPOSE_COMPENSATION, "key-9",
                new IdempotencyStore.StoredRequest(fingerprint, "cmp_missing"));

        assertThatThrownBy(() -> propose("key-9"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("cmp_missing")
                .hasMessageContaining("referenced by idempotency key key-9");
        assertThat(env.compensations.count()).isZero();
    }

    private ProposeCompensationUseCase.Result propose(String key) {
        return env.proposeCompensation.propose(key, breakId, "ops.alice", "settlement variance",
                legs(500, 500), null);
    }

    private static List<CompensationLeg> legs(long debit, long credit) {
        return List.of(new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                        Money.of(debit, "KES")),
                new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                        Money.of(credit, "KES")));
    }
}
