package com.sharkpay.wallet.storage;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code wallets} table (one wallet per principal x
 * currency; lifecycle ACTIVE ⇄ FROZEN with audit reason).
 */
@Entity
@Table(name = "wallets")
public class WalletEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "currency", nullable = false, length = 4)
    private String currency;

    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "ledger_account_id", nullable = false)
    private UUID ledgerAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletEntity() {
    }

    private WalletEntity(String id, UUID principalId, String currency, String status,
                         String statusReason, Instant statusChangedAt, UUID ledgerAccountId,
                         Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.principalId = principalId;
        this.currency = currency;
        this.status = status;
        this.statusReason = statusReason;
        this.statusChangedAt = statusChangedAt;
        this.ledgerAccountId = ledgerAccountId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static WalletEntity fromDomain(Wallet wallet) {
        return new WalletEntity(wallet.id(), wallet.principalId(), wallet.currency(),
                wallet.status().name(), wallet.statusReason(), wallet.statusChangedAt(),
                wallet.ledgerAccountId(), wallet.createdAt(), wallet.updatedAt());
    }

    /** Maps to the domain (state stored as the enum name). */
    public Wallet toDomain() {
        return new Wallet(id, principalId, currency, ledgerAccountId,
                WalletStatus.valueOf(status), statusReason, statusChangedAt, createdAt, updatedAt);
    }

    /** Refreshes the mutable lifecycle fields from the domain object. */
    public void applyDomain(Wallet wallet) {
        this.status = wallet.status().name();
        this.statusReason = wallet.statusReason();
        this.statusChangedAt = wallet.statusChangedAt();
        this.updatedAt = wallet.updatedAt();
    }

    public String getId() {
        return id;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
