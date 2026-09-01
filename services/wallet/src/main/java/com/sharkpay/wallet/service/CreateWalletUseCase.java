package com.sharkpay.wallet.service;

import com.sharkpay.money.Currencies;
import com.sharkpay.wallet.domain.DuplicateWalletException;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.PrincipalNotActiveException;
import com.sharkpay.wallet.domain.UnknownPrincipalException;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.ports.IdempotencyStore;
import com.sharkpay.wallet.ports.LedgerAccounts;
import com.sharkpay.wallet.ports.PrincipalLookup;
import com.sharkpay.wallet.ports.WalletRepository;
import com.sharkpay.wallet.ports.WalletRepository.WalletFilter;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Create-wallet use-case. Requires an Idempotency-Key: a duplicate key with
 * the same payload replays the original wallet (no second wallet, no second
 * event); a duplicate key with a different payload is a 409 conflict.
 *
 * <p>Domain rules: the principal must exist and be ACTIVE; the currency must
 * be one of the six supported V1 currencies; a principal has at most one
 * wallet per currency (enforced again by the unique constraint on the
 * wallets table).
 */
public final class CreateWalletUseCase {

    private final WalletRepository wallets;
    private final PrincipalLookup principals;
    private final LedgerAccounts ledgerAccounts;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    public CreateWalletUseCase(WalletRepository wallets, PrincipalLookup principals,
                               LedgerAccounts ledgerAccounts, IdempotencyStore idempotency,
                               EventPublisher events, Clock clock) {
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.principals = Objects.requireNonNull(principals, "principalLookup is required");
        this.ledgerAccounts = Objects.requireNonNull(ledgerAccounts, "ledgerAccounts is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey client Idempotency-Key (required, non-blank)
     * @param principalId    owning principal (must exist and be ACTIVE)
     * @param currency       one of KES USD EUR GBP USDC USDT (case-insensitive)
     */
    public Result create(String idempotencyKey, UUID principalId, String currency) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(principalId, "principalId is required");
        String canonicalCurrency = Currencies.normalize(currency);
        String fingerprint = fingerprint(principalId, canonicalCurrency);

        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.CREATE_WALLET, idempotencyKey.trim());
        if (stored.isPresent()) {
            return replay(stored.get(), idempotencyKey, fingerprint);
        }

        PrincipalLookup.PrincipalSnapshot principal = principals.findById(principalId)
                .orElseThrow(() -> new UnknownPrincipalException(principalId));
        if (principal.status() != PrincipalLookup.PrincipalStatus.ACTIVE) {
            throw new PrincipalNotActiveException(principalId, principal.status().name());
        }
        if (wallets.findByPrincipalAndCurrency(principalId, canonicalCurrency).isPresent()) {
            throw new DuplicateWalletException(principalId, canonicalCurrency);
        }

        UUID ledgerAccountId = ledgerAccounts.provisionWalletAccount(principalId, canonicalCurrency);
        Wallet wallet = Wallet.newWallet(Ids.newWalletId(), principalId, canonicalCurrency,
                ledgerAccountId, clock.instant());
        wallets.save(wallet);
        idempotency.put(IdempotencyStore.Scope.CREATE_WALLET, idempotencyKey.trim(),
                new IdempotencyStore.StoredRequest(fingerprint, wallet.id()));
        events.publish(WalletEvents.walletStateChanged(wallet, null, "wallet created", clock.instant()));
        return new Result(wallet, false);
    }

    private Result replay(IdempotencyStore.StoredRequest request, String key, String fingerprint) {
        if (!request.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        Wallet original = wallets.findById(request.entityId())
                .orElseThrow(() -> new NoSuchElementException(
                        "wallet " + request.entityId() + " referenced by idempotency key "
                                + key + " is missing"));
        return new Result(original, true);
    }

    /** Canonical request fingerprint for conflict detection. */
    static String fingerprint(UUID principalId, String canonicalCurrency) {
        return "CREATE_WALLET|" + principalId + "|" + canonicalCurrency;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /**
     * @param wallet the created (or replayed) wallet
     * @param replay true when this call was served from the idempotency
     *               store (original wallet returned, no second effect)
     */
    public record Result(Wallet wallet, boolean replay) {
    }
}
