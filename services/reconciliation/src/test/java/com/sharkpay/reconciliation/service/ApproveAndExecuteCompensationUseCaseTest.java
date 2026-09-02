package com.sharkpay.reconciliation.service;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.domain.CompensationLeg;
import com.sharkpay.reconciliation.domain.CompensationRejectedException;
import com.sharkpay.reconciliation.domain.FourEyesException;
import com.sharkpay.reconciliation.domain.PostingDirection;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.ports.LedgerPort;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G2 money-safety core: the 4-eyes compensation executes EXACTLY once
 * through the LedgerPort (same-principal approvals rejected before any
 * money moves, double-approve rejected, ledger business rejections post
 * nothing and leave the entry PROPOSED, the break is COMPENSATED with the
 * audit link, and a ledger replay of the same key is still one posting).
 */
class ApproveAndExecuteCompensationUseCaseTest {

    private static final String REQUESTER = "ops.alice";
    private static final String APPROVER = "ops.bob";

    private ReconTestEnv env;
    private String breakId;
    private String compensationId;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        env.seedProviderLine("hc_amount", "CONFIRMED", 150_000, 500);
        env.seedInternalLine("int_amount", "hc_amount", "CONFIRMED", 149_500, 500);
        breakId = env.triggerDefault("key-run").breaks().get(0).id();
        compensationId = env.proposeCompensation.propose("key-prop", breakId, REQUESTER,
                "settlement variance", legs(500, 500), null).entry().id();
    }

    @Test
    void theSecondPairOfEyesExecutesTheCompensationExactlyOnce() {
        ApproveAndExecuteCompensationUseCase.Result result =
                env.approveAndExecute.approveAndExecute(compensationId, APPROVER);

        CompensationEntry entry = result.entry();
        assertThat(entry.state()).isEqualTo(CompensationEntry.CompensationState.EXECUTED);
        assertThat(entry.requester()).isEqualTo(REQUESTER);
        assertThat(entry.approver()).isEqualTo(APPROVER);
        assertThat(entry.ledgerEntryId()).isNotNull();
        assertThat(entry.executedAt()).isEqualTo(env.clock.instant());

        // the break is COMPENSATED with the compensation link (audit trail)
        assertThat(result.break_().state()).isEqualTo(BreakState.COMPENSATED);
        assertThat(result.break_().compensationId()).isEqualTo(compensationId);

        // G2: exactly one posting through the ledger, keyed ops:adj:<breakId>
        assertThat(env.ledger.attempts()).isEqualTo(1);
        assertThat(env.ledger.committedCount()).isEqualTo(1);
        assertThat(env.ledger.hasCommitted("ops:adj:" + breakId)).isTrue();
        assertThat(env.ledger.committedPostings().get(0).transactionKey())
                .isEqualTo("ops:adj:" + breakId);
        assertThat(env.ledger.committedPostings().get(0).source()).isEqualTo(LedgerPort.Source.OPS);
        assertThat(env.ledger.committedPostings().get(0).entryType())
                .isEqualTo(LedgerPort.EntryType.ADJUSTMENT);
        assertThat(env.ledger.committedPostings().get(0).reason())
                .contains("settlement variance")
                .contains(breakId);

        // exactly one compensation.executed event
        assertThat(env.events.eventsOfType(ReconEvents.COMPENSATION_EXECUTED)).hasSize(1);

        // the entry is persisted with the journal id and both principals
        CompensationEntry stored = env.compensations.findById(compensationId).orElseThrow();
        assertThat(stored.state()).isEqualTo(CompensationEntry.CompensationState.EXECUTED);
        assertThat(stored.ledgerEntryId()).isEqualTo(entry.ledgerEntryId());
        assertThat(stored.approver()).isEqualTo(APPROVER);
    }

    @Test
    void aSecondApprovalIsRejectedAndPostsNothing() {
        env.approveAndExecute.approveAndExecute(compensationId, APPROVER);

        assertThatThrownBy(() ->
                env.approveAndExecute.approveAndExecute(compensationId, "ops.carol"))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("executes exactly once");

        assertThat(env.ledger.attempts()).isEqualTo(1);   // no second attempt
        assertThat(env.ledger.committedCount()).isEqualTo(1);
        assertThat(env.events.eventsOfType(ReconEvents.COMPENSATION_EXECUTED)).hasSize(1);
        // the break stays compensated by the FIRST entry
        assertThat(env.breaks.findById(breakId).orElseThrow().compensationId())
                .isEqualTo(compensationId);
    }

    @Test
    void theRequesterCanNeverApproveTheirOwnCompensation() {
        assertThatThrownBy(() ->
                env.approveAndExecute.approveAndExecute(compensationId, REQUESTER))
                .isInstanceOf(FourEyesException.class)
                .hasMessageContaining(REQUESTER)
                .hasMessageContaining("distinct persons");

        // rejected before any money moved
        assertThat(env.ledger.attempts()).isZero();
        assertThat(env.ledger.committedCount()).isZero();
        // no compensation executed (the run's own break.detected/run.completed
        // events from the setup run are not this operation's effect)
        assertThat(env.events.eventsOfType(ReconEvents.COMPENSATION_EXECUTED)).isEmpty();
        assertThat(env.compensations.findById(compensationId).orElseThrow().state())
                .isEqualTo(CompensationEntry.CompensationState.PROPOSED);
        assertThat(env.breaks.findById(breakId).orElseThrow().state()).isEqualTo(BreakState.OPEN);
    }

    @Test
    void theApproverIsTrimmedBeforeTheFourEyesCheck() {
        // " ops.bob " is the same principal as "ops.bob" — and still distinct
        // from the requester
        ApproveAndExecuteCompensationUseCase.Result result =
                env.approveAndExecute.approveAndExecute(compensationId, " ops.bob ");
        assertThat(result.entry().approver()).isEqualTo("ops.bob");

        // the requester is stored trimmed by the propose use case, so a
        // whitespace-padded requester can NEVER bypass 4-eyes by differing
        // from the trimmed approver of the same person
        String secondBreak = seedSecondBreak();
        String secondCompensation = env.proposeCompensation.propose("key-prop-2", secondBreak,
                " ops.alice ", "variance", legs(100, 100), null).entry().id();
        assertThat(env.compensations.findById(secondCompensation).orElseThrow().requester())
                .isEqualTo("ops.alice");
        assertThatThrownBy(() ->
                env.approveAndExecute.approveAndExecute(secondCompensation, "ops.alice"))
                .isInstanceOf(FourEyesException.class);
    }

    @Test
    void aBlankApproverIsRejectedBeforeAnythingHappens() {
        assertThatThrownBy(() -> env.approveAndExecute.approveAndExecute(compensationId, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approver must not be blank");
        assertThatThrownBy(() -> env.approveAndExecute.approveAndExecute(compensationId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(env.ledger.attempts()).isZero();
    }

    @Test
    void aLedgerBusinessRejectionPostsNothingAndLeavesTheEntryProposed() {
        env.ledger.rejectNext("insufficient_funds", "suspense below zero under row locks");

        assertThatThrownBy(() ->
                env.approveAndExecute.approveAndExecute(compensationId, APPROVER))
                .isInstanceOf(CompensationRejectedException.class)
                .hasMessageContaining("insufficient_funds")
                .hasMessageContaining("row locks");

        // nothing posted: the ledger rejected the only attempt
        assertThat(env.ledger.committedCount()).isZero();
        assertThat(env.ledger.rejections()).hasSize(1);
        assertThat(env.ledger.rejections().get(0).code()).isEqualTo("insufficient_funds");

        // the entry stays PROPOSED (operators amend and re-propose)
        assertThat(env.compensations.findById(compensationId).orElseThrow().state())
                .isEqualTo(CompensationEntry.CompensationState.PROPOSED);
        assertThat(env.compensations.findById(compensationId).orElseThrow().ledgerEntryId())
                .isNull();
        // the break stays open for compensation
        assertThat(env.breaks.findById(breakId).orElseThrow().state()).isEqualTo(BreakState.OPEN);
        // no compensation.executed event (only the setup run's events exist)
        assertThat(env.events.eventsOfType(ReconEvents.COMPENSATION_EXECUTED)).isEmpty();

        // and the operators can retry the SAME entry once amended: the next
        // approval posts (the rejection was one-shot)
        env.approveAndExecute.approveAndExecute(compensationId, APPROVER);
        assertThat(env.ledger.committedCount()).isEqualTo(1);
        assertThat(env.breaks.findById(breakId).orElseThrow().state())
                .isEqualTo(BreakState.COMPENSATED);
    }

    @Test
    void aTerminalBreakBlocksExecutionBeforeTheLedgerIsTouched() {
        // OPEN → INVESTIGATING → RESOLVED (the legal manual path)
        env.transitionBreak.transition(breakId, "investigating", "ops.alice",
                "hypothesis: statement re-issued");
        env.transitionBreak.transition(breakId, "resolved", "ops.alice", "matched by re-run");

        assertThatThrownBy(() ->
                env.approveAndExecute.approveAndExecute(compensationId, APPROVER))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("only an open or investigating break can be compensated");
        assertThat(env.ledger.attempts()).isZero();
    }

    @Test
    void anInvestigatingBreakCanBeCompensated() {
        env.transitionBreak.transition(breakId, "investigating", "ops.alice",
                "provider confirmed settlement variance");
        ApproveAndExecuteCompensationUseCase.Result result =
                env.approveAndExecute.approveAndExecute(compensationId, APPROVER);
        assertThat(result.break_().state()).isEqualTo(BreakState.COMPENSATED);
        assertThat(env.ledger.committedCount()).isEqualTo(1);
    }

    @Test
    void anUnknownCompensationOrBreakIsA404() {
        assertThatThrownBy(() -> env.approveAndExecute.approveAndExecute("cmp_unknown", APPROVER))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("cmp_unknown");
    }

    @Test
    void aCompensationForAVanishedBreakIsALoudIntegrityError() {
        // the compensation references a break the repository no longer has:
        // a loud integrity error before the ledger is touched, never a guess
        ApproveAndExecuteCompensationUseCase againstEmptyBreaks =
                new ApproveAndExecuteCompensationUseCase(env.compensations,
                        new com.sharkpay.reconciliation.fakes.InMemoryReconBreakRepository(),
                        env.ledger, env.events, env.eventFactory, env.randomness, env.clock);
        assertThatThrownBy(() -> againstEmptyBreaks.approveAndExecute(compensationId, APPROVER))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(breakId)
                .hasMessageContaining("not found");
        assertThat(env.ledger.attempts()).isZero();
        assertThat(env.compensations.findById(compensationId).orElseThrow().state())
                .isEqualTo(CompensationEntry.CompensationState.PROPOSED);
    }

    @Test
    void aLedgerReplayOfTheSameKeyIsStillExactlyOnePosting() {
        // a transport-level retry already committed the key: the ledger
        // returns the ORIGINAL entry id with replay = true
        UUID preCommitted = ((LedgerPort.PostingResult.Committed) env.ledger.post(
                LedgerPort.LedgerPosting.of("ops:adj:" + breakId,
                        LedgerPort.Source.OPS, UUID.randomUUID(), LedgerPort.EntryType.ADJUSTMENT,
                        "retry (recon break " + breakId + ")", portLegs(500, 500)))).entryId();

        ApproveAndExecuteCompensationUseCase.Result result =
                env.approveAndExecute.approveAndExecute(compensationId, APPROVER);

        // still exactly one posting under the key — the original one
        assertThat(env.ledger.committedCount()).isEqualTo(1);
        assertThat(result.entry().ledgerEntryId()).isEqualTo(preCommitted);
        assertThat(result.entry().ledgerReplay()).isTrue();
        assertThat(result.break_().state()).isEqualTo(BreakState.COMPENSATED);
    }

    @Test
    void aReversalCompensationPostsAReversalEntryReferencingThePriorOne() {
        // commit the prior entry so the reversal pairs against it — the
        // ledger's own entry id is the reversal reference (never invented)
        UUID priorEntry = ((LedgerPort.PostingResult.Committed) env.ledger.post(
                LedgerPort.LedgerPosting.of("ops:adj:some-prior-entry",
                        LedgerPort.Source.OPS, UUID.randomUUID(), LedgerPort.EntryType.ADJUSTMENT,
                        "prior", portLegs(500, 500)))).entryId();

        String reversalCompensation = env.proposeCompensation.propose("key-rev", breakId,
                REQUESTER, "corrects the wrong compensation", legs(500, 500), priorEntry)
                .entry().id();

        env.approveAndExecute.approveAndExecute(reversalCompensation, APPROVER);

        LedgerPort.LedgerPosting posted = env.ledger.committedPostings().stream()
                .filter(posting -> posting.entryType() == LedgerPort.EntryType.REVERSAL)
                .findFirst().orElseThrow();
        assertThat(posted.transactionKey()).isEqualTo("ops:adj:" + breakId + "#2");
        assertThat(posted.reversesEntryId()).isEqualTo(priorEntry);
        assertThat(env.ledger.committedCount()).isEqualTo(2); // prior + reversal
    }

    @Test
    void thePostingCarriesTheExactLegsOperatorBDrafted() {
        env.approveAndExecute.approveAndExecute(compensationId, APPROVER);

        List<LedgerPort.Leg> postedLegs = env.ledger.committedPostings().get(0).legs();
        assertThat(postedLegs).hasSize(2);
        assertThat(postedLegs.get(0).accountRef()).isEqualTo("suspense:recon:KES");
        assertThat(postedLegs.get(0).direction()).isEqualTo(LedgerPort.Direction.DEBIT);
        assertThat(postedLegs.get(0).amount()).isEqualTo(Money.of(500, "KES"));
        assertThat(postedLegs.get(1).accountRef()).isEqualTo("honeycoin:settlement:KES");
        assertThat(postedLegs.get(1).direction()).isEqualTo(LedgerPort.Direction.CREDIT);
        assertThat(postedLegs.get(1).amount()).isEqualTo(Money.of(500, "KES"));
    }

    private String seedSecondBreak() {
        env.providers.seed("hc_other", "CONFIRMED", 1_000, "KES", 0,
                java.time.Instant.parse("2026-09-01T12:00:00Z"));
        return env.triggerRun.trigger("key-run-2", "honeycoin", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO).breaks().get(0).id();
    }

    private static List<CompensationLeg> legs(long debit, long credit) {
        return List.of(new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                        Money.of(debit, "KES")),
                new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                        Money.of(credit, "KES")));
    }

    /** The same legs as the use case's toPosting maps them onto the port. */
    private static List<LedgerPort.Leg> portLegs(long debit, long credit) {
        return legs(debit, credit).stream()
                .map(leg -> new LedgerPort.Leg(leg.accountRef(),
                        LedgerPort.Direction.valueOf(leg.direction().name()), leg.amount()))
                .toList();
    }
}
