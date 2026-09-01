package com.sharkpay.wallet.service;

import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.ports.HoldRepository;
import com.sharkpay.wallet.ports.IdempotencyStore;
import com.sharkpay.wallet.ports.WalletRepository;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Capture-hold use-case: convert a hold's reserved funds into a settled
 * debit. ACTIVE → CAPTURED (terminal) with partial capture allowed —
 * {@code captured + released = amount} exactly: the captured part is settled
 * (the ledger debit arrives separately as
 * {@code ledger.posting.committed.v1}; the ledger is the sole authority) and
 * the remainder is released back to available immediately.
 *
 * <p>Idempotent: replayed key + same payload ⇒ the original hold is
 * returned with no second effect; same key + different payload ⇒ 409.
 *
 * <p>Events: {@code wallet.hold.captured.v1} plus
 * {@code wallet.balance.changed.v1}.
 */
public final class CaptureHoldUseCase {

    private final HoldRepository holds;
    private final WalletRepository wallets;
    private final BalanceReader balances;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    public CaptureHoldUseCase(HoldRepository holds, WalletRepository wallets, BalanceReader balances,
                              IdempotencyStore idempotency, EventPublisher events, Clock clock) {
        this.holds = Objects.requireNonNull(holds, "holdRepository is required");
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.balances = Objects.requireNonNull(balances, "balanceReader is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey client Idempotency-Key (required, non-blank)
     * @param holdId         the ACTIVE hold to capture
     * @param amountMinor    capture amount in minor units (null = capture
     *                       the full reserved amount; must be positive and
     *                       not above the reserved amount)
     * @param reason         optional audit note
     */
    public Result capture(String idempotencyKey, String holdId, Long amountMinor, String reason) {
        requireKey(idempotencyKey);
        if (holdId == null || holdId.isBlank()) {
            throw new IllegalArgumentException("hold id is required");
        }
        if (amountMinor != null && amountMinor <= 0L) {
            throw new IllegalArgumentException("capture amount must be positive: " + amountMinor);
        }
        String trimmedHoldId = holdId.trim();
        String trimmedKey = idempotencyKey.trim();
        String fingerprint = fingerprint(trimmedHoldId, amountMinor, reason);

        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.CAPTURE_HOLD, trimmedKey);
        if (stored.isPresent()) {
            return replay(stored.get(), trimmedKey, fingerprint);
        }

        try {
            return new Result(execute(trimmedHoldId, amountMinor, reason, trimmedKey, fingerprint),
                    false);
        } catch (RuntimeException failure) {
            idempotency.remove(IdempotencyStore.Scope.CAPTURE_HOLD, trimmedKey);
            throw failure;
        }
    }

    private Hold execute(String holdId, Long amountMinor, String reason, String key,
                         String fingerprint) {
        Hold hold = holds.findById(holdId)
                .orElseThrow(() -> new NoSuchElementException("hold " + holdId + " not found"));
        Money captureAmount = amountMinor == null
                ? hold.amount()
                : Money.of(amountMinor, hold.amount().currency());
        hold.capture(captureAmount, clock.instant());
        holds.save(hold);
        idempotency.put(IdempotencyStore.Scope.CAPTURE_HOLD, key,
                new IdempotencyStore.StoredRequest(fingerprint, hold.id()));

        Wallet wallet = wallets.findById(hold.walletId())
                .orElseThrow(() -> new NoSuchElementException(
                        "wallet " + hold.walletId() + " of hold " + holdId + " is missing"));
        events.publish(WalletEvents.holdCaptured(wallet, hold, clock.instant()));
        events.publish(WalletEvents.balanceChanged(wallet, balances.balancesOf(wallet),
                hold.source(), hold.sourceRef(), clock.instant()));
        return hold;
    }

    private Result replay(IdempotencyStore.StoredRequest request, String key, String fingerprint) {
        if (!request.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        Hold original = holds.findById(request.entityId())
                .orElseThrow(() -> new NoSuchElementException(
                        "hold " + request.entityId() + " referenced by idempotency key "
                                + key + " is missing"));
        return new Result(original, true);
    }

    /** Canonical request fingerprint for conflict detection. */
    static String fingerprint(String holdId, Long amountMinor, String reason) {
        return "CAPTURE_HOLD|" + holdId + "|" + (amountMinor == null ? "FULL" : amountMinor)
                + "|" + (reason == null ? "" : reason.trim());
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /**
     * @param hold   the captured (or replayed) hold
     * @param replay true when served from the idempotency store
     */
    public record Result(Hold hold, boolean replay) {
    }
}
