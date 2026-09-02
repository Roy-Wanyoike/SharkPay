package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.ports.IdempotencyStore;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PayoutRepository;
import com.sharkpay.payouts.ports.SchedulerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Cancel-payout use-case (contracts/openapi/v1/payouts.yaml
 * {@code POST /payouts/{id}/cancel}): user cancellation is possible only
 * before the provider accepts the payout (CREATED or PENDING_RISK); later
 * states return 409 {@code state_conflict}. Any active hold is released
 * via a full ledger reversal.
 */
public final class CancelPayoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelPayoutUseCase.class);

    private final PayoutRepository payouts;
    private final LedgerPort ledger;
    private final IdempotencyStore idempotency;
    private final SchedulerPort scheduler;
    private final java.time.Clock clock;

    public CancelPayoutUseCase(PayoutRepository payouts, LedgerPort ledger,
                               IdempotencyStore idempotency, SchedulerPort scheduler,
                               java.time.Clock clock) {
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.scheduler = Objects.requireNonNull(scheduler, "schedulerPort is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey client Idempotency-Key (required)
     * @param payoutId       the payout to cancel
     * @param reason         optional caller reason (audit note)
     */
    public Result cancel(String idempotencyKey, String payoutId, String reason) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
        Objects.requireNonNull(payoutId, "payoutId is required");
        String key = idempotencyKey.trim();
        String fingerprint = "CANCEL_PAYOUT|" + payoutId.trim();

        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.CANCEL_PAYOUT, key);
        if (stored.isPresent()) {
            IdempotencyStore.StoredRequest request = stored.get();
            if (!request.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(key);
            }
            Payout original = payouts.findById(request.entityId())
                    .orElseThrow(() -> new java.util.NoSuchElementException(
                            "payout " + request.entityId() + " referenced by idempotency key "
                                    + key + " is missing"));
            return new Result(original, true);
        }

        Payout payout = payouts.findById(payoutId.trim())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "payout " + payoutId + " not found"));
        payout.cancel(reason == null || reason.isBlank()
                ? "cancelled by principal" : reason.trim(), clock.instant(), false);
        HoldReleaser.release(ledger, payout, "payout cancelled");
        payouts.save(payout);
        scheduler.cancelRelease(payout.id());
        idempotency.put(IdempotencyStore.Scope.CANCEL_PAYOUT, key,
                new IdempotencyStore.StoredRequest(fingerprint, payout.id()));
        log.info("payout {} cancelled; hold released", payout.id());
        return new Result(payout, false);
    }

    /** @param payout the cancelled payout; @param replay idempotent replay */
    public record Result(Payout payout, boolean replay) {

        public Result {
            Objects.requireNonNull(payout, "payout is required");
        }
    }
}
