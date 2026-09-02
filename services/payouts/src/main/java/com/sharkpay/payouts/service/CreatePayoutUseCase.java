package com.sharkpay.payouts.service;

import com.sharkpay.money.Currencies;
import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.Destination;
import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.InsufficientFundsException;
import com.sharkpay.payouts.domain.KycRequiredException;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutFeePolicy;
import com.sharkpay.payouts.domain.PrincipalNotActiveException;
import com.sharkpay.payouts.domain.Rail;
import com.sharkpay.payouts.domain.UnknownWalletException;
import com.sharkpay.payouts.domain.WalletFrozenException;
import com.sharkpay.payouts.events.PayoutEvents;
import com.sharkpay.payouts.ports.EventPublisher;
import com.sharkpay.payouts.ports.IdempotencyStore;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PayoutRepository;
import com.sharkpay.payouts.ports.PrincipalLookup;
import com.sharkpay.payouts.ports.SchedulerPort;
import com.sharkpay.payouts.ports.WalletHoldPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Create-payout use-case (contracts/openapi/v1/payouts.yaml): withdraw from
 * a wallet to an external rail destination. The response state reflects
 * synchronous evaluation — {@code PENDING_RISK} when accepted (hold posted,
 * handed to the release scheduler) or terminal {@code FAILED} when the
 * ledger rejects the hold (early rejection, funds never moved).
 *
 * <p>Validation: destination shape per rail, rail/currency compatibility,
 * wallet exists + ACTIVE + matching currency + sufficient available
 * balance (amount + fee), principal ACTIVE with KYC ≥ LIMITED. The hold
 * posting debits the wallet (amount + fee) into the payouts clearing
 * account — one atomic 2-leg entry, idempotent on
 * {@code payouts:{id}:hold}.</p>
 */
public final class CreatePayoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePayoutUseCase.class);

    /** Default payout TTL (payouts.yaml expires_in_seconds default). */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(900);

    private final WalletHoldPort wallets;
    private final PrincipalLookup principals;
    private final LedgerPort ledger;
    private final PayoutRepository payouts;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final SchedulerPort scheduler;
    private final PayoutFeePolicy feePolicy;
    private final Clock clock;

    public CreatePayoutUseCase(WalletHoldPort wallets, PrincipalLookup principals,
                               LedgerPort ledger, PayoutRepository payouts,
                               IdempotencyStore idempotency, EventPublisher events,
                               SchedulerPort scheduler, PayoutFeePolicy feePolicy, Clock clock) {
        this.wallets = Objects.requireNonNull(wallets, "walletHoldPort is required");
        this.principals = Objects.requireNonNull(principals, "principalLookup is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.scheduler = Objects.requireNonNull(scheduler, "schedulerPort is required");
        this.feePolicy = Objects.requireNonNull(feePolicy, "feePolicy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey    client Idempotency-Key (required, non-blank)
     * @param sourceWalletId    debited wallet
     * @param amountMinor       positive minor units of {@code currency}
     * @param destination       validated external destination (domain value)
     * @param railHint          optional rail; must equal the destination's rail
     * @param metadata          passthrough metadata (≤ 20 entries)
     * @param expiresInSeconds  TTL before auto-cancel (60..86400; null = 900)
     * @param executeAfter      release time ({@code now} for the public API;
     *                          future timestamps exercise the batch window)
     */
    public Result create(String idempotencyKey, String sourceWalletId, long amountMinor,
                         String currency, Destination destination, String railHint,
                         Map<String, String> metadata, Integer expiresInSeconds,
                         Instant executeAfter) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(sourceWalletId, "sourceWalletId is required");
        Money amount = Money.of(amountMinor, Currencies.normalize(currency));
        Objects.requireNonNull(destination, "destination is required");

        Rail rail = resolveRail(railHint, destination);
        PayoutFeePolicy.Quote fee = feePolicy.quote(rail, amount);

        String fingerprint = fingerprint(sourceWalletId, amount, destination, rail);
        Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.CREATE_PAYOUT, idempotencyKey.trim());
        if (stored.isPresent()) {
            return replay(stored.get(), idempotencyKey, fingerprint);
        }

        WalletHoldPort.WalletSnapshot wallet = wallets.findWallet(sourceWalletId.trim())
                .orElseThrow(() -> new UnknownWalletException(sourceWalletId));
        if (!wallet.isActive()) {
            throw new WalletFrozenException(wallet.walletId());
        }
        if (!amount.currency().equals(wallet.currency())) {
            throw new CurrencyMismatchException(amount.currency(), wallet.currency());
        }
        PrincipalLookup.PrincipalSnapshot principal = principals.findById(wallet.principalId())
                .orElseThrow(() -> new NoSuchElementException(
                        "principal " + wallet.principalId() + " of wallet " + wallet.walletId()
                                + " not found"));
        if (!principal.isActive()) {
            throw new PrincipalNotActiveException(principal.principalId(),
                    principal.status().name());
        }
        if (principal.kycTier() == PrincipalLookup.KycTier.UNVERIFIED) {
            throw new KycRequiredException(principal.principalId());
        }
        Money totalDebit = amount.add(fee.fee());
        if (wallet.available().amountMinor() < totalDebit.amountMinor()) {
            throw new InsufficientFundsException(wallet.available(), totalDebit);
        }

        Duration ttl = ttlOf(expiresInSeconds);
        Instant now = clock.instant();
        Instant releaseAt = executeAfter == null ? now : executeAfter;
        if (releaseAt.isBefore(now)) {
            releaseAt = now;
        }
        Instant expiresAt = now.plus(ttl);
        if (!expiresAt.isAfter(releaseAt)) {
            expiresAt = releaseAt.plus(Duration.ofSeconds(60));
        }

        Ids.Identity identity = Ids.newPayoutId();
        Payout payout = Payout.newPayout(identity.publicId(), identity.internalRef(),
                wallet.walletId(), wallet.ledgerAccountId(), amount, fee.fee(),
                fee.nonRefundable(), rail, destination, metadata, releaseAt, expiresAt, now);

        LedgerPort.PostingResult outcome;
        try {
            outcome = ledger.post(PayoutMoney.holdEntry(payout));
        } catch (RuntimeException portFailure) {
            idempotency.remove(IdempotencyStore.Scope.CREATE_PAYOUT, idempotencyKey.trim());
            throw portFailure;
        }

        switch (outcome) {
            case LedgerPort.PostingResult.Committed committed -> {
                payout.accept(releaseAt, committed.entryId(), now);
                payouts.save(payout);
                idempotency.put(IdempotencyStore.Scope.CREATE_PAYOUT, idempotencyKey.trim(),
                        new IdempotencyStore.StoredRequest(fingerprint, payout.id()));
                events.publish(PayoutEvents.created(payout, now));
                scheduler.requestRelease(payout.id(), payout.executeAfter());
                log.info("payout {} accepted: held {} {} (fee {}), releases at {}",
                        payout.id(), totalDebit.amountMinor(), amount.currency(),
                        fee.fee().amountMinor(), payout.executeAfter());
            }
            case LedgerPort.PostingResult.Rejected rejected -> {
                // early rejection: no hold landed, funds never moved — the
                // 201 response carries terminal FAILED (payouts.yaml)
                payout.markFailed(rejected.code() + ": " + rejected.reason(), now);
                payouts.save(payout);
                idempotency.put(IdempotencyStore.Scope.CREATE_PAYOUT, idempotencyKey.trim(),
                        new IdempotencyStore.StoredRequest(fingerprint, payout.id()));
                events.publish(PayoutEvents.failed(payout, now));
                log.warn("payout {} rejected at hold: {} ({})", payout.id(), rejected.code(),
                        rejected.reason());
            }
        }
        return new Result(payout, false);
    }

    private static Rail resolveRail(String railHint, Destination destination) {
        Rail implied = destination.rail();
        if (railHint == null || railHint.isBlank()) {
            return implied;
        }
        Rail hinted = Rail.fromWire(railHint);
        if (hinted != implied) {
            throw new com.sharkpay.payouts.domain.UnsupportedDestinationException(
                    "rail hint " + hinted.wireName() + " is not compatible with destination type "
                            + destination.type());
        }
        return hinted;
    }

    private static Duration ttlOf(Integer expiresInSeconds) {
        if (expiresInSeconds == null) {
            return DEFAULT_TTL;
        }
        if (expiresInSeconds < 60 || expiresInSeconds > 86_400) {
            throw new IllegalArgumentException(
                    "expires_in_seconds must be within [60, 86400]: " + expiresInSeconds);
        }
        return Duration.ofSeconds(expiresInSeconds);
    }

    private Result replay(IdempotencyStore.StoredRequest request, String key, String fingerprint) {
        if (!request.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        Payout original = payouts.findById(request.entityId())
                .orElseThrow(() -> new NoSuchElementException(
                        "payout " + request.entityId() + " referenced by idempotency key " + key
                                + " is missing"));
        return new Result(original, true);
    }

    static String fingerprint(String sourceWalletId, Money amount, Destination destination,
                              Rail rail) {
        return "CREATE_PAYOUT|" + sourceWalletId + "|" + amount.amountMinor() + "|"
                + amount.currency() + "|" + destination.describe() + "|" + rail.wireName();
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
    }

    /**
     * @param payout the created (or replayed) payout — PENDING_RISK when
     *               accepted, FAILED on early rejection
     * @param replay true when served from the idempotency store
     */
    public record Result(Payout payout, boolean replay) {

        public Result {
            Objects.requireNonNull(payout, "payout is required");
        }
    }
}
