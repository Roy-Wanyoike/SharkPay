package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.domain.CompensationRejectedException;
import com.sharkpay.reconciliation.domain.FourEyesException;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.ports.CompensationEntryRepository;
import com.sharkpay.reconciliation.ports.EventPublisher;
import com.sharkpay.reconciliation.ports.LedgerPort;
import com.sharkpay.reconciliation.ports.Randomness;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Operator B's review + the execution (RB-7 steps 3–5): the second pair of
 * eyes approves the exact legs, then — and only then — the compensation
 * posts to the ledger through the {@link LedgerPort} (POST to the ledger's
 * internal transactions API, never SQL). Order of the checks, all before
 * any money moves:
 *
 * <ol>
 *   <li><b>4-eyes</b> — the approver must differ from the requester
 *       (SECURITY §4): one person can never draft and post a manual
 *       compensation; both principals are recorded on the entry;</li>
 *   <li><b>entry state</b> — a compensation executes at most once
 *       (double-approve is a 409);</li>
 *   <li><b>break state</b> — the break must still be active (an
 *       already-resolved/compensated/waived break is a 409: there is
 *       nothing open to compensate);</li>
 *   <li>the ledger post itself — idempotent on the compensation key, so a
 *       transport-level retry of the same key replays the same journal
 *       entry ({@code replay = true} — still exactly one posting, the
 *       original entry id is recorded).</li>
 * </ol>
 *
 * <p>On a ledger business rejection nothing has been posted: the entry
 * stays PROPOSED and the rejection surfaces as 422 (the operators amend
 * and propose a new entry). On success the entry records the journal entry
 * id + both principals, the break moves to COMPENSATED with the entry
 * linked (the audit link), and the
 * {@code recon.compensation.executed.v1} event is published.</p>
 */
public final class ApproveAndExecuteCompensationUseCase {

    private final CompensationEntryRepository compensations;
    private final ReconBreakRepository breaks;
    private final LedgerPort ledger;
    private final EventPublisher events;
    private final ReconEvents eventFactory;
    private final Randomness randomness;
    private final Clock clock;

    /** Serializes execution in-JVM (production adds the DB row gate). */
    private final Object executionLock = new Object();

    public ApproveAndExecuteCompensationUseCase(CompensationEntryRepository compensations,
                                                ReconBreakRepository breaks, LedgerPort ledger,
                                                EventPublisher events, ReconEvents eventFactory,
                                                Randomness randomness, Clock clock) {
        this.compensations = Objects.requireNonNull(compensations,
                "compensationEntryRepository is required");
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.eventFactory = Objects.requireNonNull(eventFactory, "eventFactory is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param compensationId the PROPOSED compensation
     * @param approver       operator B — must differ from the requester
     */
    public Result approveAndExecute(String compensationId, String approver) {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("approver must not be blank");
        }
        String approverPrincipal = approver.trim();

        synchronized (executionLock) {
            CompensationEntry entry = compensations.findById(compensationId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "compensation " + compensationId + " not found"));

            // 1. 4-eyes first: the requester never approves their own entry
            if (entry.requester().equals(approverPrincipal)) {
                throw new FourEyesException(approverPrincipal);
            }

            // 2. exactly-once at this service
            if (entry.state() == CompensationEntry.CompensationState.EXECUTED) {
                throw new ReconciliationStateException(
                        "compensation " + compensationId + " is already executed (ledger entry "
                                + entry.ledgerEntryId() + "); a compensation executes exactly once");
            }

            // 3. the break must still be open for compensation
            ReconBreak break_ = breaks.findById(entry.breakId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "recon break " + entry.breakId() + " not found"));
            if (!break_.state().isActive()) {
                throw new ReconciliationStateException(
                        "break " + break_.id() + " is " + break_.state().wireName()
                                + "; only an open or investigating break can be compensated");
            }

            // 4. post through the ledger (money authority), exactly once per key
            LedgerPort.LedgerPosting posting = toPosting(entry);
            LedgerPort.PostingResult result = ledger.post(posting);
            if (result instanceof LedgerPort.PostingResult.Rejected rejected) {
                throw new CompensationRejectedException(rejected.code(), rejected.reason());
            }
            LedgerPort.PostingResult.Committed committed =
                    (LedgerPort.PostingResult.Committed) result;

            entry.execute(approverPrincipal, committed.entryId(), committed.replay(),
                    clock.instant());
            compensations.save(entry);

            break_.markCompensated(entry.id(), clock.instant());
            breaks.save(break_);

            events.publish(eventFactory.compensationExecuted(entry, clock.instant()));
            return new Result(entry, break_);
        }
    }

    /** Maps the domain entry onto the ledger posting (RB-7 step 4). */
    private LedgerPort.LedgerPosting toPosting(CompensationEntry entry) {
        List<LedgerPort.Leg> legs = entry.legs().stream()
                .map(leg -> new LedgerPort.Leg(leg.accountRef(),
                        LedgerPort.Direction.valueOf(leg.direction().name()), leg.amount()))
                .toList();
        String reason = entry.reason() + " (recon break " + entry.breakId() + ")";
        if (entry.reversesEntryId() != null) {
            return LedgerPort.LedgerPosting.reversalOf(entry.compensationKey(),
                    LedgerPort.Source.OPS, randomness.uuidV7(), entry.reversesEntryId(), reason,
                    legs);
        }
        return LedgerPort.LedgerPosting.of(entry.compensationKey(), LedgerPort.Source.OPS,
                randomness.uuidV7(), LedgerPort.EntryType.ADJUSTMENT, reason, legs);
    }

    /** @param entry   the executed compensation (both principals recorded) */
    public record Result(CompensationEntry entry, ReconBreak break_) {
    }
}
