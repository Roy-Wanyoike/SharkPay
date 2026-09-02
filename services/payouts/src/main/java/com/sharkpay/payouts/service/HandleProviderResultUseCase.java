package com.sharkpay.payouts.service;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.ReturnCompensationException;
import com.sharkpay.payouts.events.PayoutEvents;
import com.sharkpay.payouts.ports.EventPublisher;
import com.sharkpay.payouts.ports.IdempotencyStore;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PayoutRepository;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider-result ingestion (callbacks and poll outcomes — the internal
 * {@code POST /internal/payouts/{id}/provider-result} surface). Applies the
 * payout state machine per the provider status and keeps the ledger
 * aligned at every step:
 *
 * <ul>
 *   <li><b>SUCCEEDED</b>: settles — one atomic capture entry moves the held
 *       funds from payouts-clearing to the rail account, the fee to the fee
 *       account; state walks PROCESSING → SENT → SUCCEEDED;</li>
 *   <li><b>FAILED</b>: terminal failure — hold released via strict ledger
 *       reversal (full refund, money never left);</li>
 *   <li><b>RETURNED</b>: the compensation entry (see below);</li>
 *   <li><b>PENDING/PROCESSING</b>: rail accepted — PROCESSING → SENT;</li>
 *   <li><b>UNKNOWN</b>: the ambiguity contract — parked, ops alert logged,
 *       never a terminal transition and never a debit retry.</li>
 * </ul>
 *
 * <p><b>Return compensation (the hard money part).</b> A return re-credits
 * the principal wallet via a ledger REVERSAL posting — history is never
 * mutated. Exact integer math: {@code reversal = returned −
 * non_refundable_fee}; the fee account retains the non-refundable portion;
 * the funds source (rail account when the payout was captured, clearing
 * when not) is debited the returned amount. Edges handled:
 * partial returns, double-return rejection (idempotent on the provider
 * return reference; a second, different return on a terminal payout is a
 * 409), currency mismatch (422) and a negative compensation (422 + ops
 * case, no posting).</p>
 */
public final class HandleProviderResultUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleProviderResultUseCase.class);

    private final PayoutRepository payouts;
    private final LedgerPort ledger;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    public HandleProviderResultUseCase(PayoutRepository payouts, LedgerPort ledger,
                                       IdempotencyStore idempotency, EventPublisher events,
                                       Clock clock) {
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Ingests one provider result for a payout. Idempotent on the provider
     * return reference when supplied (double-return rejection): the same
     * reference replays the already-applied outcome with no second effect.
     */
    public Result ingest(String payoutId, ProviderGatewayPort.ProviderStatus status,
                         String providerRef, String reason, Long returnedAmountMinor,
                         String returnedCurrency, String providerReturnRef) {
        Objects.requireNonNull(payoutId, "payoutId is required");
        Objects.requireNonNull(status, "status is required");
        String fingerprint = "PROVIDER_RESULT|" + payoutId.trim() + "|" + status
                + "|" + (providerReturnRef == null ? "" : providerReturnRef);

        if (providerReturnRef != null && !providerReturnRef.isBlank()) {
            Optional<IdempotencyStore.StoredRequest> stored = idempotency.find(
                    IdempotencyStore.Scope.PROVIDER_RESULT, providerReturnRef.trim());
            if (stored.isPresent()) {
                if (!stored.get().requestFingerprint().equals(fingerprint)) {
                    throw new com.sharkpay.payouts.domain.IdempotencyConflictException(
                            providerReturnRef);
                }
                Payout applied = payouts.findById(stored.get().entityId())
                        .orElseThrow(() -> new java.util.NoSuchElementException(
                                "payout " + stored.get().entityId() + " not found"));
                log.info("provider result replay for payout {} (return ref {})", payoutId,
                        providerReturnRef);
                return new Result(applied, true);
            }
        }

        Payout payout = payouts.findById(payoutId.trim())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "payout " + payoutId + " not found"));
        apply(payout, status, reason, returnedAmountMinor, returnedCurrency);
        if (providerReturnRef != null && !providerReturnRef.isBlank()) {
            idempotency.put(IdempotencyStore.Scope.PROVIDER_RESULT, providerReturnRef.trim(),
                    new IdempotencyStore.StoredRequest(fingerprint, payout.id()));
        }
        return new Result(payout, false);
    }

    /** Applies a provider status to a loaded payout (poll path reuses this). */
    void apply(Payout payout, ProviderGatewayPort.ProviderStatus status, String reason,
               Long returnedAmountMinor, String returnedCurrency) {
        switch (status) {
            case PENDING, PROCESSING -> advanceToSent(payout, reason);
            case SUCCEEDED -> settle(payout);
            case FAILED -> fail(payout, reason);
            case RETURNED -> compensate(payout, reason, returnedAmountMinor, returnedCurrency);
            case UNKNOWN -> log.warn(
                    "OPS-ALERT payout {} provider status UNKNOWN (ambiguous) — parked in {} "
                            + "pending manual provider confirmation; no transition, no retry",
                    payout.id(), payout.state().wireName());
        }
    }

    private void advanceToSent(Payout payout, String note) {
        java.time.Instant now = clock.instant();
        if (payout.state() == PayoutState.PROCESSING) {
            payout.markSent(now);
            payouts.save(payout);
            events.publish(PayoutEvents.sent(payout, now));
            log.info("payout {} accepted by rail ({}), provider ref {}", payout.id(),
                    note == null ? "pending" : note, payout.providerRef());
        }
        // SENT and everything else: no transition (status is monotonic)
    }

    private void settle(Payout payout) {
        java.time.Instant now = clock.instant();
        if (payout.state() == PayoutState.SUCCEEDED) {
            // duplicate settled callback (providers deliver at-least-once):
            // the terminal state and the capture entry already exist — no-op
            log.info("payout {} already SUCCEEDED — duplicate settled result ignored",
                    payout.id());
            return;
        }
        if (payout.state() == PayoutState.PROCESSING) {
            // the rail settled without an intermediate SENT signal — walk
            // the state machine forward (no transition may be skipped)
            payout.markSent(now);
            events.publish(PayoutEvents.sent(payout, now));
        }
        if (payout.state() != PayoutState.SENT) {
            throw new com.sharkpay.payouts.domain.PayoutStateException(payout.id(),
                    payout.state(), PayoutState.SUCCEEDED);
        }
        LedgerPort.PostingResult outcome = ledger.post(PayoutMoney.settleEntry(payout));
        switch (outcome) {
            case LedgerPort.PostingResult.Committed committed -> {
                payout.markSucceeded(committed.entryId(), now);
                payouts.save(payout);
                events.publish(PayoutEvents.succeeded(payout, now));
                log.info("payout {} settled: capture entry {}", payout.id(), committed.entryId());
            }
            case LedgerPort.PostingResult.Rejected rejected -> {
                // money moved at the rail but the books cannot recognise it —
                // park (no transition) and page ops; the payout stays SENT
                throw new com.sharkpay.payouts.domain.LedgerPostingException(
                        PayoutMoney.settleKey(payout),
                        "settle posting rejected (" + rejected.code() + ": " + rejected.reason()
                                + ") — payout " + payout.id() + " parked in SENT for ops", null);
            }
        }
    }

    private void fail(Payout payout, String reason) {
        java.time.Instant now = clock.instant();
        if (payout.state() == PayoutState.FAILED) {
            // duplicate failure callback (at-least-once delivery): the hold
            // release is already posted and the state is terminal — no-op
            log.info("payout {} already FAILED — duplicate failure result ignored", payout.id());
            return;
        }
        String auditReason = reason == null || reason.isBlank()
                ? "failed at rail" : reason.trim();
        // money safety: the FAILED transition is legal only from
        // CREATED/PENDING_RISK/PROCESSING — validate BEFORE releasing the
        // hold, otherwise a late/contradictory failure result on a payout
        // whose money already left (SENT/SUCCEEDED/RETURNED) would post a
        // second full refund while the rail holds the funds
        PayoutState from = payout.state();
        if (from != PayoutState.CREATED && from != PayoutState.PENDING_RISK
                && from != PayoutState.PROCESSING) {
            throw new com.sharkpay.payouts.domain.PayoutStateException(payout.id(), from,
                    PayoutState.FAILED);
        }
        HoldReleaser.release(ledger, payout, "payout failed: " + auditReason);
        payout.markFailed(auditReason, now);
        payouts.save(payout);
        events.publish(PayoutEvents.failed(payout, now));
        log.info("payout {} failed at rail: {}", payout.id(), auditReason);
    }

    /**
     * The return compensation: one atomic REVERSAL entry, exact integer
     * math, never a history mutation.
     */
    private void compensate(Payout payout, String reason, Long returnedAmountMinor,
                            String returnedCurrency) {
        java.time.Instant now = clock.instant();
        if (payout.state() != PayoutState.SENT && payout.state() != PayoutState.SUCCEEDED) {
            throw ReturnCompensationException.notReturnable(payout.id(), payout.state());
        }
        String currency = returnedCurrency == null || returnedCurrency.isBlank()
                ? payout.amount().currency()
                : com.sharkpay.money.Currencies.normalize(returnedCurrency);
        Money returned = returnedAmountMinor == null
                ? payout.amount()
                : Money.of(returnedAmountMinor, currency);
        if (!returned.isPositive()) {
            throw new ReturnCompensationException(payout.id(),
                    ReturnCompensationException.Reason.NEGATIVE_COMPENSATION,
                    "return for payout " + payout.id() + " reports a non-positive amount "
                            + returned.amountMinor() + " " + returned.currency()
                            + " — a return must move funds; ops case required");
        }
        if (!returned.currency().equals(payout.amount().currency())) {
            throw ReturnCompensationException.currencyMismatch(payout.id(),
                    payout.amount().currency(), returned.currency());
        }
        if (returned.amountMinor() > payout.amount().amountMinor()) {
            throw new ReturnCompensationException(payout.id(),
                    ReturnCompensationException.Reason.NEGATIVE_COMPENSATION,
                    "return for payout " + payout.id() + " reports " + returned.amountMinor()
                            + " " + returned.currency() + " which exceeds the payout amount "
                            + payout.amount().amountMinor() + " — ops case required");
        }
        Money nonRefundable = payout.nonRefundableFee();
        long reversalMinor = returned.amountMinor() - nonRefundable.amountMinor();
        if (reversalMinor < 0) {
            log.error("OPS-CASE payout {} return compensation would be negative ({} - {})",
                    payout.id(), returned.amountMinor(), nonRefundable.amountMinor());
            throw ReturnCompensationException.negative(payout.id(), returned, nonRefundable);
        }

        LedgerPort.PostingResult outcome = ledger.post(PayoutMoney.returnCompensationEntry(
                payout, returned, reason == null || reason.isBlank() ? "returned by rail"
                        : reason.trim()));
        switch (outcome) {
            case LedgerPort.PostingResult.Committed committed -> {
                payout.markReturned(reason == null || reason.isBlank()
                        ? "returned by rail" : reason.trim(), committed.entryId(), now);
                payouts.save(payout);
                events.publish(PayoutEvents.returned(payout, now));
                log.info("payout {} returned: compensation entry {} re-credits {} {} "
                                + "(non-refundable fee {} retained)", payout.id(),
                        committed.entryId(), reversalMinor, payout.amount().currency(),
                        nonRefundable.amountMinor());
            }
            case LedgerPort.PostingResult.Rejected rejected -> {
                throw new com.sharkpay.payouts.domain.LedgerPostingException(
                        PayoutMoney.returnKey(payout),
                        "return compensation rejected (" + rejected.code() + ": "
                                + rejected.reason() + ") — payout " + payout.id()
                                + " parked for ops, no re-credit", null);
            }
        }
    }

    /** @param payout the payout after applying the result; @param replay idempotent replay */
    public record Result(Payout payout, boolean replay) {

        public Result {
            Objects.requireNonNull(payout, "payout is required");
        }
    }
}
