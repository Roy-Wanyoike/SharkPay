package com.sharkpay.wallet.storage;

import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.domain.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code holds} table (the funds-control ledger;
 * {@code captured + released = amount} in terminal states — enforced by the
 * ck_holds_terminal_split CHECK).
 */
@Entity
@Table(name = "holds")
public class HoldEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "wallet_id", nullable = false, length = 40)
    private String walletId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 4)
    private String currency;

    @Column(name = "exponent", nullable = false)
    private int exponent;

    @Column(name = "captured_minor", nullable = false)
    private long capturedMinor;

    @Column(name = "released_minor", nullable = false)
    private long releasedMinor;

    @Column(name = "state", nullable = false, length = 10)
    private String state;

    @Column(name = "source", nullable = false, length = 12)
    private String source;

    @Column(name = "source_ref", nullable = false)
    private UUID sourceRef;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HoldEntity() {
    }

    private HoldEntity(String id, String walletId, long amountMinor, String currency, int exponent,
                       long capturedMinor, long releasedMinor, String state, String source,
                       UUID sourceRef, String reason, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.walletId = walletId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.exponent = exponent;
        this.capturedMinor = capturedMinor;
        this.releasedMinor = releasedMinor;
        this.state = state;
        this.source = source;
        this.sourceRef = sourceRef;
        this.reason = reason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static HoldEntity fromDomain(Hold hold) {
        return new HoldEntity(hold.id(), hold.walletId(), hold.amount().amountMinor(),
                hold.amount().currency(), hold.amount().exponent(), hold.captured().amountMinor(),
                hold.released().amountMinor(), hold.state().name(), hold.source().wireName(),
                hold.sourceRef(), hold.reason(), hold.createdAt(), hold.updatedAt());
    }

    /** Maps to the domain (state/source stored as enum names / wire names). */
    public Hold toDomain() {
        return new Hold(id, walletId,
                com.sharkpay.money.Money.of(amountMinor, currency),
                Source.fromWire(source), sourceRef, reason, HoldState.valueOf(state),
                com.sharkpay.money.Money.of(capturedMinor, currency),
                com.sharkpay.money.Money.of(releasedMinor, currency),
                createdAt, updatedAt);
    }

    /** Refreshes the mutable lifecycle fields from the domain object. */
    public void applyDomain(Hold hold) {
        this.capturedMinor = hold.captured().amountMinor();
        this.releasedMinor = hold.released().amountMinor();
        this.state = hold.state().name();
        this.updatedAt = hold.updatedAt();
    }

    public String getId() {
        return id;
    }

    public String getWalletId() {
        return walletId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public int getExponent() {
        return exponent;
    }

    public long getCapturedMinor() {
        return capturedMinor;
    }

    public long getReleasedMinor() {
        return releasedMinor;
    }

    public String getState() {
        return state;
    }

    public String getSource() {
        return source;
    }

    public UUID getSourceRef() {
        return sourceRef;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
