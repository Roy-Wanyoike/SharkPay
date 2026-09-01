package com.sharkpay.payments.storage;

import com.sharkpay.money.Money;
import com.sharkpay.payments.domain.Destination;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.Rail;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the {@code payment_intents} table (V1__payments_init.sql):
 * the mutable snapshot of the PaymentIntent aggregate. Money columns are
 * BIGINT minor units (never float); the transition audit log lives in the
 * append-only {@code payment_state_transitions} and is drained from the
 * aggregate on save.
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntentEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "internal_id", nullable = false)
    private UUID internalId;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "source_wallet_id")
    private String sourceWalletId;

    @Column(name = "destination_type", nullable = false, length = 16)
    private String destinationType;

    @Column(name = "destination_wallet_id")
    private String destinationWalletId;

    @Column(name = "destination_external")
    private String destinationExternal;

    @Column(name = "destination_fx_quote")
    private String destinationFxQuote;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 4)
    private String currency;

    @Column(name = "exponent", nullable = false)
    private int exponent;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Column(name = "rail", nullable = false, length = 12)
    private String rail;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "metadata_json", nullable = false)
    private String metadataJson;

    @Column(name = "provider")
    private String provider;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "hold_id")
    private String holdId;

    @Column(name = "hold_entry_id")
    private UUID holdEntryId;

    @Column(name = "capture_entry_id")
    private UUID captureEntryId;

    @Column(name = "release_entry_id")
    private UUID releaseEntryId;

    @Column(name = "reversal_entry_id")
    private UUID reversalEntryId;

    @Column(name = "reversed_amount_minor")
    private Long reversedAmountMinor;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "transition_seq", nullable = false)
    private long transitionSeq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentIntentEntity() {
    }

    private PaymentIntentEntity(String id, UUID internalId, UUID principalId, String sourceWalletId,
                                String destinationType, String destinationWalletId,
                                String destinationExternal, String destinationFxQuote,
                                long amountMinor, String currency, int exponent, long feeMinor,
                                String rail, String state, String idempotencyKey, Instant expiresAt,
                                String metadataJson, String provider, String providerRef,
                                String holdId, UUID holdEntryId, UUID captureEntryId,
                                UUID releaseEntryId, UUID reversalEntryId, Long reversedAmountMinor,
                                String failureReason, long transitionSeq, Instant createdAt,
                                Instant updatedAt) {
        this.id = id;
        this.internalId = internalId;
        this.principalId = principalId;
        this.sourceWalletId = sourceWalletId;
        this.destinationType = destinationType;
        this.destinationWalletId = destinationWalletId;
        this.destinationExternal = destinationExternal;
        this.destinationFxQuote = destinationFxQuote;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.exponent = exponent;
        this.feeMinor = feeMinor;
        this.rail = rail;
        this.state = state;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.metadataJson = metadataJson;
        this.provider = provider;
        this.providerRef = providerRef;
        this.holdId = holdId;
        this.holdEntryId = holdEntryId;
        this.captureEntryId = captureEntryId;
        this.releaseEntryId = releaseEntryId;
        this.reversalEntryId = reversalEntryId;
        this.reversedAmountMinor = reversedAmountMinor;
        this.failureReason = failureReason;
        this.transitionSeq = transitionSeq;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Maps a domain aggregate to a fresh entity (insert path). */
    public static PaymentIntentEntity fromDomain(PaymentIntent intent, String metadataJson) {
        return new PaymentIntentEntity(intent.id(), intent.internalId(), intent.principalId(),
                intent.sourceWalletId(), intent.destination().type().name(),
                intent.destination().walletId(), intent.destination().externalDetails(),
                intent.destination().fxQuoteRef(), intent.amount().amountMinor(),
                intent.amount().currency(), intent.amount().exponent(), intent.fee().amountMinor(),
                intent.rail().wireName(), intent.state().wireName(), intent.idempotencyKey(),
                intent.expiresAt(), metadataJson, intent.provider(), intent.providerRef(),
                intent.holdId(), intent.holdEntryId(), intent.captureEntryId(),
                intent.releaseEntryId(), intent.reversalEntryId(),
                intent.reversedAmount() == null ? null : intent.reversedAmount().amountMinor(),
                intent.failureReason(), intent.transitionSeq(), intent.createdAt(),
                intent.updatedAt());
    }

    /** Maps to the domain aggregate (metadata JSON decoded by the adapter). */
    public PaymentIntent toDomain(Map<String, String> metadata) {
        Destination destination = switch (Destination.Type.valueOf(destinationType)) {
            case INTERNAL_WALLET -> Destination.internalWallet(destinationWalletId);
            case EXTERNAL_RAIL -> Destination.externalRail(destinationExternal);
            case FX_QUOTE -> Destination.fxQuote(destinationFxQuote);
        };
        Money reversed = reversedAmountMinor == null ? null
                : Money.of(reversedAmountMinor, currency);
        return PaymentIntent.rehydrate(id, internalId, principalId, sourceWalletId, destination,
                Money.of(amountMinor, currency), Money.of(feeMinor, currency),
                Rail.fromWire(rail), PaymentState.fromWire(state), idempotencyKey, expiresAt,
                metadata, provider, providerRef, holdId, holdEntryId, captureEntryId,
                releaseEntryId, reversalEntryId, reversed, failureReason, createdAt, updatedAt,
                transitionSeq);
    }

    /** Refreshes the mutable lifecycle columns from the domain aggregate. */
    public void applyDomain(PaymentIntent intent) {
        this.state = intent.state().wireName();
        this.provider = intent.provider();
        this.providerRef = intent.providerRef();
        this.holdId = intent.holdId();
        this.holdEntryId = intent.holdEntryId();
        this.captureEntryId = intent.captureEntryId();
        this.releaseEntryId = intent.releaseEntryId();
        this.reversalEntryId = intent.reversalEntryId();
        this.reversedAmountMinor = intent.reversedAmount() == null ? null
                : intent.reversedAmount().amountMinor();
        this.failureReason = intent.failureReason();
        this.transitionSeq = intent.transitionSeq();
        this.updatedAt = intent.updatedAt();
    }

    public String getId() {
        return id;
    }

    public UUID getInternalId() {
        return internalId;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public String getState() {
        return state;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Raw metadata JSON document (decoded by the port adapter). */
    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getTransitionSeq() {
        return transitionSeq;
    }
}
