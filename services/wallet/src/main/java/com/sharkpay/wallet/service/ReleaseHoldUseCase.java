package com.sharkpay.wallet.service;

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
 * Release-hold use-case: return a hold's reserved funds to available.
 * ACTIVE → RELEASED (terminal). Releasing a released/captured hold is a 409
 * conflict. Frozen wallets allow releases (returning reserved money is not
 * a new outflow).
 *
 * <p>Idempotent: replayed key + same payload ⇒ the original hold is
 * returned with no second effect; same key + different payload ⇒ 409.
 *
 * <p>Events: {@code wallet.hold.released.v1} plus
 * {@code wallet.balance.changed.v1}.
 */
public final class ReleaseHoldUseCase {

    private final HoldRepository holds;
    private final WalletRepository wallets;
    private final BalanceReader balances;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    public ReleaseHoldUseCase(HoldRepository holds, WalletRepository wallets, BalanceReader balances,
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
     * @param holdId         the ACTIVE hold to release
     * @param reason         optional audit note
     */
    public Result release(String idempotencyKey, String holdId, String reason) {
        requireKey(idempotencyKey);
        if (holdId == null || holdId.isBlank()) {
            throw new IllegalArgumentException("hold id is required");
        }
        String trimmedHoldId = holdId.trim();
        String trimmedKey = idempotencyKey.trim();
        String fingerprint = fingerprint(trimmedHoldId, reason);

        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.RELEASE_HOLD, trimmedKey);
        if (stored.isPresent()) {
            return replay(stored.get(), trimmedKey, fingerprint);
        }

        try {
            return new Result(execute(trimmedHoldId, reason, trimmedKey, fingerprint), false);
        } catch (RuntimeException failure) {
            idempotency.remove(IdempotencyStore.Scope.RELEASE_HOLD, trimmedKey);
            throw failure;
        }
    }

    private Hold execute(String holdId, String reason, String key, String fingerprint) {
        Hold hold = holds.findById(holdId)
                .orElseThrow(() -> new NoSuchElementException("hold " + holdId + " not found"));
        hold.release(clock.instant());
        holds.save(hold);
        idempotency.put(IdempotencyStore.Scope.RELEASE_HOLD, key,
                new IdempotencyStore.StoredRequest(fingerprint, hold.id()));

        Wallet wallet = wallets.findById(hold.walletId())
                .orElseThrow(() -> new NoSuchElementException(
                        "wallet " + hold.walletId() + " of hold " + holdId + " is missing"));
        events.publish(WalletEvents.holdReleased(wallet, hold, clock.instant()));
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
    static String fingerprint(String holdId, String reason) {
        return "RELEASE_HOLD|" + holdId + "|" + (reason == null ? "" : reason.trim());
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /**
     * @param hold   the released (or replayed) hold
     * @param replay true when served from the idempotency store
     */
    public record Result(Hold hold, boolean replay) {
    }
}
