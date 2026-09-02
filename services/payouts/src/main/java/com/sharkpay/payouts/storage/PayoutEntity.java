package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.domain.Destination;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.Rail;
import com.sharkpay.payouts.domain.StateTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of the {@code payouts} table (V1__payouts_init.sql). Field
 * access with public fields; domain translation lives in
 * {@link #fromDomain(Payout)}, {@link #toDomain(List)} and
 * {@link #applyDomain(Payout)} (mirrors the wallet service's storage
 * package). The destination is stored flattened (per-type columns, only
 * the type's columns populated — schema CHECKs enforce the rest); money is
 * integer minor units; metadata is a JSON column.
 */
@Entity
@Table(name = "payouts")
public class PayoutEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 40)
    public String id;

    /** Ledger source_ref (surrogate UUID). */
    @Column(name = "internal_ref", nullable = false, updatable = false, unique = true)
    public UUID internalRef;

    @Column(name = "source_wallet", nullable = false, length = 40)
    public String sourceWallet;

    /** The principal wallet's ledger account (legs key on it). */
    @Column(name = "wallet_ledger_account", nullable = false, updatable = false)
    public UUID walletLedgerAccount;

    @Column(name = "amount_minor", nullable = false)
    public long amountMinor;

    @Column(name = "fee_minor", nullable = false)
    public long feeMinor;

    @Column(name = "non_refundable_fee_minor", nullable = false)
    public long nonRefundableFeeMinor;

    @Column(name = "currency", nullable = false, length = 4)
    public String currency;

    @Column(name = "exponent", nullable = false)
    public int exponent;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 10)
    public Rail rail;

    @Column(name = "destination_type", nullable = false, updatable = false, length = 10)
    public String destinationType;

    @Column(name = "destination_msisdn", updatable = false, length = 20)
    public String destinationMsisdn;

    @Column(name = "destination_bank_code", updatable = false, length = 64)
    public String destinationBankCode;

    @Column(name = "destination_account_number", updatable = false, length = 64)
    public String destinationAccountNumber;

    @Column(name = "destination_account_name", updatable = false, length = 128)
    public String destinationAccountName;

    @Column(name = "destination_country", updatable = false, length = 2)
    public String destinationCountry;

    @Column(name = "destination_network", updatable = false, length = 16)
    public String destinationNetwork;

    @Column(name = "destination_address", updatable = false, length = 42)
    public String destinationAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    public PayoutState state;

    @Column(name = "provider_ref", length = 128)
    public String providerRef;

    @Column(name = "failure_reason", length = 512)
    public String failureReason;

    @Column(name = "return_reason", length = 512)
    public String returnReason;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "execute_after")
    public Instant executeAfter;

    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "hold_entry_id", updatable = true)
    public UUID holdEntryId;

    @Column(name = "settle_entry_id", updatable = true)
    public UUID settleEntryId;

    @Column(name = "return_entry_id", updatable = true)
    public UUID returnEntryId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    public Map<String, String> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /** New entity for a domain payout (the id is the domain id). */
    public static PayoutEntity fromDomain(Payout payout) {
        PayoutEntity entity = new PayoutEntity();
        entity.id = payout.id();
        entity.createdAt = payout.createdAt();
        entity.applyDomain(payout);
        entity.applyDestination(payout.destination());
        return entity;
    }

    /** Maps to the domain payout, rehydrating the audit trail. */
    public Payout toDomain(List<StateTransition> history) {
        Destination destination = new Destination(destinationType, destinationMsisdn,
                destinationBankCode, destinationAccountNumber, destinationAccountName,
                destinationCountry, destinationNetwork, destinationAddress);
        return new Payout(id, internalRef, sourceWallet, walletLedgerAccount,
                com.sharkpay.money.Money.of(amountMinor, currency),
                com.sharkpay.money.Money.of(feeMinor, currency),
                com.sharkpay.money.Money.of(nonRefundableFeeMinor, currency), rail, destination,
                state, providerRef, failureReason, returnReason, attempts, executeAfter,
                nextAttemptAt, expiresAt, holdEntryId, settleEntryId, returnEntryId, metadata,
                createdAt, updatedAt, history);
    }

    /** Refreshes every business field from the (possibly mutated) domain. */
    public void applyDomain(Payout payout) {
        Objects.requireNonNull(payout, "payout is required");
        this.id = payout.id();
        this.internalRef = payout.internalRef();
        this.sourceWallet = payout.sourceWalletId();
        this.walletLedgerAccount = payout.walletLedgerAccountId();
        this.amountMinor = payout.amount().amountMinor();
        this.feeMinor = payout.fee().amountMinor();
        this.nonRefundableFeeMinor = payout.nonRefundableFee().amountMinor();
        this.currency = payout.amount().currency();
        this.exponent = payout.amount().exponent();
        this.rail = payout.rail();
        this.state = payout.state();
        this.providerRef = payout.providerRef();
        this.failureReason = payout.failureReason();
        this.returnReason = payout.returnReason();
        this.attempts = payout.attempts();
        this.executeAfter = payout.executeAfter();
        this.nextAttemptAt = payout.nextAttemptAt();
        this.expiresAt = payout.expiresAt();
        this.holdEntryId = payout.holdEntryId();
        this.settleEntryId = payout.settleEntryId();
        this.returnEntryId = payout.returnEntryId();
        this.metadata = payout.metadata().isEmpty() ? null : payout.metadata();
        this.updatedAt = payout.updatedAt();
    }

    /**
     * Populates the immutable, insert-only destination columns. The
     * aggregate's destination never changes, and the columns are
     * {@code updatable = false} — they are written exactly once, at insert.
     */
    private void applyDestination(Destination destination) {
        Objects.requireNonNull(destination, "destination is required");
        this.destinationType = destination.type();
        this.destinationMsisdn = destination.msisdn();
        this.destinationBankCode = destination.bankCode();
        this.destinationAccountNumber = destination.accountNumber();
        this.destinationAccountName = destination.accountName();
        this.destinationCountry = destination.country();
        this.destinationNetwork = destination.network();
        this.destinationAddress = destination.address();
    }
}
