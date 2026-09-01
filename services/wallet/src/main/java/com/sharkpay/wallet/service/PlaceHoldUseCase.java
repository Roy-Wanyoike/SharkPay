package com.sharkpay.wallet.service;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Balances;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.InsufficientFundsException;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStateException;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.ports.HoldRepository;
import com.sharkpay.wallet.ports.IdempotencyStore;
import com.sharkpay.wallet.ports.WalletRepository;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Place-hold use-case: reserve funds on a wallet (the funds-control core).
 *
 * <p>Money safety:
 * <ul>
 *   <li>idempotent — a replayed Idempotency-Key returns the original hold
 *       and moves no money a second time; a same-key/different-payload
 *       replay is a 409 conflict;</li>
 *   <li>the reserved amount is checked against {@code available = total -
 *       active holds}, so the non-negative available-balance invariant holds
 *       after every successful place (the total ledger balance is never
 *       touched by holds);</li>
 *   <li>the amount must be positive and of the wallet's currency
 *       (mismatch ⇒ 422 currency_mismatch);</li>
 *   <li>only ACTIVE wallets accept new holds (frozen wallets block new
 *       outflows).</li>
 * </ul>
 *
 * <p>Events: {@code wallet.hold.placed.v1} plus
 * {@code wallet.balance.changed.v1} (the held partition changed).
 */
public final class PlaceHoldUseCase {

    private final WalletRepository wallets;
    private final HoldRepository holds;
    private final BalanceReader balances;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    public PlaceHoldUseCase(WalletRepository wallets, HoldRepository holds, BalanceReader balances,
                            IdempotencyStore idempotency, EventPublisher events, Clock clock) {
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.holds = Objects.requireNonNull(holds, "holdRepository is required");
        this.balances = Objects.requireNonNull(balances, "balanceReader is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey client Idempotency-Key (required, non-blank)
     * @param walletId       the wallet to reserve funds on
     * @param amountMinor    reserved amount in minor units (must be positive)
     * @param currency       the amount's currency (must equal the wallet's)
     * @param source         domain placing the hold
     * @param sourceRef      business object the hold belongs to
     * @param reason         optional audit note
     */
    public Result place(String idempotencyKey, String walletId, long amountMinor, String currency,
                        Source source, UUID sourceRef, String reason) {
        requireKey(idempotencyKey);
        if (walletId == null || walletId.isBlank()) {
            throw new IllegalArgumentException("wallet id is required");
        }
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(sourceRef, "sourceRef is required");
        String trimmedWalletId = walletId.trim();
        String trimmedKey = idempotencyKey.trim();
        Money amount = Money.of(amountMinor, currency);
        String fingerprint = fingerprint(trimmedWalletId, amount, source, sourceRef, reason);

        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.PLACE_HOLD, trimmedKey);
        if (stored.isPresent()) {
            return replay(stored.get(), trimmedKey, fingerprint);
        }

        try {
            return new Result(execute(trimmedWalletId, amount, source, sourceRef, reason, trimmedKey,
                    fingerprint), false);
        } catch (RuntimeException failure) {
            // Release the reservation so a retry can attempt again (no
            // partial state: nothing was persisted on failure paths).
            idempotency.remove(IdempotencyStore.Scope.PLACE_HOLD, trimmedKey);
            throw failure;
        }
    }

    private Hold execute(String walletId, Money amount, Source source, UUID sourceRef, String reason,
                         String key, String fingerprint) {
        Wallet wallet = wallets.findById(walletId)
                .orElseThrow(() -> new NoSuchElementException("wallet " + walletId + " not found"));
        if (!wallet.isActive()) {
            throw new WalletStateException(walletId, wallet.status(), "place a hold on a frozen wallet");
        }
        if (!amount.currency().equals(wallet.currency())) {
            throw new CurrencyMismatchException(wallet.currency(), amount.currency());
        }
        Balances current = balances.balancesOf(wallet);
        if (amount.compareTo(current.available()) > 0) {
            throw new InsufficientFundsException(current.available(), amount);
        }

        Hold hold = Hold.place(Ids.newHoldId(), walletId, amount, source, sourceRef, reason,
                clock.instant());
        holds.save(hold);
        idempotency.put(IdempotencyStore.Scope.PLACE_HOLD, key,
                new IdempotencyStore.StoredRequest(fingerprint, hold.id()));

        Balances after = balances.balancesOf(wallet);
        events.publish(WalletEvents.holdPlaced(wallet, hold, clock.instant()));
        events.publish(WalletEvents.balanceChanged(wallet, after, source, sourceRef, clock.instant()));
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
    static String fingerprint(String walletId, Money amount, Source source, UUID sourceRef,
                              String reason) {
        return "PLACE_HOLD|" + walletId + "|" + amount.amountMinor() + "|" + amount.currency()
                + "|" + source.wireName() + "|" + sourceRef + "|" + (reason == null ? "" : reason.trim());
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /**
     * @param hold   the placed (or replayed) hold
     * @param replay true when served from the idempotency store (the
     *               original hold is returned, no second reservation)
     */
    public record Result(Hold hold, boolean replay) {
    }
}
