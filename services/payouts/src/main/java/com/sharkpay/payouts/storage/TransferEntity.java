package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.domain.StateTransition;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.domain.TransferState;
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
 * JPA mapping of the {@code transfers} table (V1__payouts_init.sql). Field
 * access with public fields; domain translation lives in
 * {@link #fromDomain(Transfer)}, {@link #toDomain(List)} and
 * {@link #applyDomain(Transfer)} (mirrors the wallet service's storage
 * package). Money is integer minor units; metadata is a JSON column.
 */
@Entity
@Table(name = "transfers")
public class TransferEntity {

    /** Public transfer id (trf_..., primary key). */
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 40)
    public String id;

    /** Ledger source_ref (surrogate UUID). */
    @Column(name = "internal_ref", nullable = false, updatable = false, unique = true)
    public UUID internalRef;

    @Column(name = "source_wallet", nullable = false, length = 40)
    public String sourceWallet;

    @Column(name = "destination_wallet", nullable = false, length = 40)
    public String destinationWallet;

    @Column(name = "amount_minor", nullable = false)
    public long amountMinor;

    @Column(name = "currency", nullable = false, length = 4)
    public String currency;

    @Column(name = "exponent", nullable = false)
    public int exponent;

    @Column(name = "fee_minor", nullable = false)
    public long feeMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    public TransferState state;

    /** Ledger journal entry id — set once committed. */
    @Column(name = "entry_id", updatable = true)
    public UUID entryId;

    @Column(name = "failure_reason", length = 512)
    public String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    public Map<String, String> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /** New entity for a domain transfer (the id is the domain id). */
    public static TransferEntity fromDomain(Transfer transfer) {
        TransferEntity entity = new TransferEntity();
        entity.id = transfer.id();
        entity.createdAt = transfer.createdAt();
        entity.applyDomain(transfer);
        return entity;
    }

    /** Maps to the domain transfer, rehydrating the audit trail. */
    public Transfer toDomain(List<StateTransition> history) {
        return new Transfer(id, internalRef, sourceWallet, destinationWallet,
                com.sharkpay.money.Money.of(amountMinor, currency),
                com.sharkpay.money.Money.of(feeMinor, currency), state, entryId, failureReason,
                metadata, createdAt, updatedAt, history);
    }

    /** Refreshes every business field from the (possibly mutated) domain. */
    public void applyDomain(Transfer transfer) {
        Objects.requireNonNull(transfer, "transfer is required");
        this.id = transfer.id();
        this.internalRef = transfer.internalRef();
        this.sourceWallet = transfer.sourceWalletId();
        this.destinationWallet = transfer.destinationWalletId();
        this.amountMinor = transfer.amount().amountMinor();
        this.currency = transfer.amount().currency();
        this.exponent = transfer.amount().exponent();
        this.feeMinor = transfer.fee().amountMinor();
        this.state = transfer.state();
        this.entryId = transfer.entryId();
        this.failureReason = transfer.failureReason();
        this.metadata = transfer.metadata().isEmpty() ? null : transfer.metadata();
        this.updatedAt = transfer.updatedAt();
    }
}
