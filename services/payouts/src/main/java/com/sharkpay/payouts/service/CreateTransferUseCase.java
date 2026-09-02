package com.sharkpay.payouts.service;

import com.sharkpay.money.Currencies;
import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.InsufficientFundsException;
import com.sharkpay.payouts.domain.SameWalletException;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.domain.TransferState;
import com.sharkpay.payouts.domain.UnknownWalletException;
import com.sharkpay.payouts.domain.WalletFrozenException;
import com.sharkpay.payouts.events.TransferEvents;
import com.sharkpay.payouts.ports.EventPublisher;
import com.sharkpay.payouts.ports.IdempotencyStore;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.TransferRepository;
import com.sharkpay.payouts.ports.WalletHoldPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Create-transfer use-case (contracts/openapi/v1/transfers.yaml): an
 * internal wallet-to-wallet movement commits as ONE atomic ledger
 * transaction containing both legs — the money is never partially posted.
 *
 * <p>Pre-flight validation (wallet existence/status/currency, available
 * balance) rejects the request with 404/422 without creating anything; the
 * ledger's row-locked balance check remains the authority, and its
 * rejection persists the transfer as terminal FAILED (201, never partially
 * posted — the ledger entry is all-or-nothing). Idempotency-Key is
 * required: same key + same payload replays the original transfer (no
 * second posting); same key + different payload is a 409.</p>
 */
public final class CreateTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateTransferUseCase.class);

    private final WalletHoldPort wallets;
    private final LedgerPort ledger;
    private final TransferRepository transfers;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final Clock clock;

    public CreateTransferUseCase(WalletHoldPort wallets, LedgerPort ledger,
                                 TransferRepository transfers, IdempotencyStore idempotency,
                                 EventPublisher events, Clock clock) {
        this.wallets = Objects.requireNonNull(wallets, "walletHoldPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.transfers = Objects.requireNonNull(transfers, "transferRepository is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey     client Idempotency-Key (required, non-blank)
     * @param sourceWalletId     debited wallet (must exist, be ACTIVE, hold the currency)
     * @param destinationWalletId credited wallet (must exist, be ACTIVE, hold the currency)
     * @param amountMinor        positive minor units of {@code currency}
     */
    public Result create(String idempotencyKey, String sourceWalletId, String destinationWalletId,
                         long amountMinor, String currency, Map<String, String> metadata) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(sourceWalletId, "sourceWalletId is required");
        Objects.requireNonNull(destinationWalletId, "destinationWalletId is required");
        Money amount = Money.of(amountMinor, Currencies.normalize(currency));
        String fingerprint = fingerprint(sourceWalletId, destinationWalletId, amount);

        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.CREATE_TRANSFER, idempotencyKey.trim());
        if (stored.isPresent()) {
            return replay(stored.get(), idempotencyKey, fingerprint);
        }

        // pre-flight: cheap read-side validation; the ledger stays the
        // authority (its row-locked check can still reject the posting)
        WalletHoldPort.WalletSnapshot source = wallets.findWallet(sourceWalletId.trim())
                .orElseThrow(() -> new UnknownWalletException(sourceWalletId));
        WalletHoldPort.WalletSnapshot destination = wallets.findWallet(destinationWalletId.trim())
                .orElseThrow(() -> new UnknownWalletException(destinationWalletId));
        if (source.walletId().equals(destination.walletId())) {
            throw new SameWalletException(source.walletId());
        }
        if (!source.isActive()) {
            throw new WalletFrozenException(source.walletId());
        }
        if (!destination.isActive()) {
            throw new WalletFrozenException(destination.walletId());
        }
        if (!amount.currency().equals(source.currency())
                || !amount.currency().equals(destination.currency())) {
            throw new CurrencyMismatchException(amount.currency(),
                    source.currency() + "/" + destination.currency());
        }
        if (source.available().amountMinor() < amount.amountMinor()) {
            throw new InsufficientFundsException(source.available(), amount);
        }

        Ids.Identity identity = Ids.newTransferId();
        Transfer transfer = Transfer.instantiate(identity.publicId(), identity.internalRef(),
                source.walletId(), destination.walletId(), amount, metadata, clock.instant());

        // the single atomic 2-leg posting: debit source, credit destination
        LedgerPort.LedgerPosting posting = LedgerPort.LedgerPosting.of(
                "transfers:" + transfer.id(), LedgerPort.Source.TRANSFERS, transfer.internalRef(),
                LedgerPort.EntryType.CAPTURE, "wallet transfer",
                List.of(new LedgerPort.Leg(source.ledgerAccountId().toString(),
                                LedgerPort.Direction.DEBIT, amount),
                        new LedgerPort.Leg(destination.ledgerAccountId().toString(),
                                LedgerPort.Direction.CREDIT, amount)));

        LedgerPort.PostingResult outcome;
        try {
            outcome = ledger.post(posting);
        } catch (RuntimeException portFailure) {
            idempotency.remove(IdempotencyStore.Scope.CREATE_TRANSFER, idempotencyKey.trim());
            throw portFailure;
        }

        switch (outcome) {
            case LedgerPort.PostingResult.Committed committed -> {
                transfer.markSucceeded(committed.entryId(), clock.instant());
                transfers.save(transfer);
                idempotency.put(IdempotencyStore.Scope.CREATE_TRANSFER, idempotencyKey.trim(),
                        new IdempotencyStore.StoredRequest(fingerprint, transfer.id()));
                events.publish(TransferEvents.succeeded(transfer, clock.instant()));
                log.info("transfer {} committed as ledger entry {} ({} {})",
                        transfer.id(), committed.entryId(), amount.amountMinor(),
                        amount.currency());
            }
            case LedgerPort.PostingResult.Rejected rejected -> {
                // all-or-nothing: the ledger rejected the entry, so no leg
                // landed — the transfer terminates FAILED, never partially
                // posted, and the failed event is emitted exactly once
                transfer.markFailed(rejected.code() + ": " + rejected.reason(), clock.instant());
                transfers.save(transfer);
                idempotency.put(IdempotencyStore.Scope.CREATE_TRANSFER, idempotencyKey.trim(),
                        new IdempotencyStore.StoredRequest(fingerprint, transfer.id()));
                events.publish(TransferEvents.failed(transfer, clock.instant()));
                log.warn("transfer {} rejected by ledger: {} ({})", transfer.id(), rejected.code(),
                        rejected.reason());
            }
        }
        return new Result(transfer, false);
    }

    private Result replay(IdempotencyStore.StoredRequest request, String key, String fingerprint) {
        if (!request.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        Transfer original = transfers.findById(request.entityId())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "transfer " + request.entityId() + " referenced by idempotency key "
                                + key + " is missing"));
        return new Result(original, true);
    }

    static String fingerprint(String sourceWalletId, String destinationWalletId, Money amount) {
        return "CREATE_TRANSFER|" + sourceWalletId + "|" + destinationWalletId + "|"
                + amount.amountMinor() + "|" + amount.currency();
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /**
     * @param transfer the created (or replayed) transfer; the state is
     *                 terminal (SUCCEEDED or FAILED) per the synchronous V1
     *                 execution model
     * @param replay   true when this call was served from the idempotency
     *                 store (original transfer returned, no second effect)
     */
    public record Result(Transfer transfer, boolean replay) {

        public Result {
            Objects.requireNonNull(transfer, "transfer is required");
            if (!transfer.isTerminal()) {
                throw new IllegalStateException("transfer " + transfer.id() + " must be terminal "
                        + "after the synchronous execution, was " + transfer.state());
            }
        }

        public TransferState state() {
            return transfer.state();
        }
    }
}
