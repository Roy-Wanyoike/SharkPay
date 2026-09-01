package com.sharkpay.wallet.storage;

import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.ProjectionLeg;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.StatementLine;
import com.sharkpay.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the append-only {@code wallet_postings} projection table:
 * one wallet leg of a committed ledger entry with its running
 * {@code balance_after} (recomputed in posting_id order by the adapter).
 */
@Entity
@Table(name = "wallet_postings")
public class WalletPostingEntity {

    @EmbeddedId
    private WalletPostingId id;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "entry_type", nullable = false, length = 12)
    private String entryType;

    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    @Column(name = "currency", nullable = false, length = 4)
    private String currency;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "source", nullable = false, length = 12)
    private String source;

    @Column(name = "source_ref", nullable = false)
    private UUID sourceRef;

    @Column(name = "reason")
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected WalletPostingEntity() {
    }

    private WalletPostingEntity(WalletPostingId id, UUID entryId, String entryType, String direction,
                                String currency, long amountMinor, long balanceAfter, String source,
                                UUID sourceRef, String reason, Instant occurredAt, Instant recordedAt) {
        this.id = id;
        this.entryId = entryId;
        this.entryType = entryType;
        this.direction = direction;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.balanceAfter = balanceAfter;
        this.source = source;
        this.sourceRef = sourceRef;
        this.reason = reason;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    /** New line (balance_after filled by the adapter after the ordered recompute). */
    public static WalletPostingEntity fromLeg(String walletId, ProjectionLeg leg) {
        return new WalletPostingEntity(new WalletPostingId(walletId, leg.postingId()),
                leg.entryId(), leg.entryType(), leg.direction().wireName(), leg.amount().currency(),
                leg.amount().amountMinor(), 0L, leg.source().wireName(), leg.sourceRef(), leg.reason(),
                leg.occurredAt(), Instant.now());
    }

    /** Maps to the domain statement line. */
    public StatementLine toDomain() {
        ProjectionLeg leg = toLeg();
        return new StatementLine(leg, Money.of(balanceAfter, leg.amount().currency()));
    }

    /** ProjectionLeg form (balance_after omitted — the adapter recomputes). */
    public ProjectionLeg toLeg() {
        return new ProjectionLeg(id.getPostingId(), entryId, entryType,
                Direction.fromWire(direction), Money.of(amountMinor, currency),
                Source.fromWire(source), sourceRef, reason, occurredAt);
    }

    public void setBalanceAfter(long balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public WalletPostingId getId() {
        return id;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public String getEntryType() {
        return entryType;
    }

    public String getDirection() {
        return direction;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getBalanceAfter() {
        return balanceAfter;
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

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
