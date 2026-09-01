package com.sharkpay.fx.storage;

import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.domain.ConversionState;
import com.sharkpay.fx.domain.Rate;
import com.sharkpay.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of the {@code conversions} table (V1__fx_init.sql). Wallet
 * account refs are the caller-supplied ledger account references of the
 * customer's wallets. Domain translation lives in
 * {@link #fromDomain(Conversion)} / {@link #toDomain()} (mirrors the wallet
 * service's storage package). Currency columns are length 4 — USDC/USDT are
 * four letters; the quote id is the public {@code fxq_...} string, not a
 * UUID.
 */
@Entity
@Table(name = "conversions")
public class ConversionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    public UUID id;

    /** Public conversion id, {@code cnv_...} (contract pattern). */
    @Column(name = "conversion_id", nullable = false, unique = true, length = 40)
    public String conversionId;

    /** Public quote id of the consumed quote, {@code fxq_...}. */
    @Column(name = "quote_id", nullable = false, length = 40)
    public String quoteId;

    @Column(name = "source_wallet_ref", nullable = false)
    public String sourceWalletRef;

    @Column(name = "destination_wallet_ref", nullable = false)
    public String destinationWalletRef;

    @Column(name = "source_amount_minor", nullable = false)
    public long sourceAmountMinor;

    @Column(name = "source_currency", nullable = false, length = 4)
    public String sourceCurrency;

    @Column(name = "source_exponent", nullable = false)
    public int sourceExponent;

    @Column(name = "target_amount_minor", nullable = false)
    public long targetAmountMinor;

    @Column(name = "target_currency", nullable = false, length = 4)
    public String targetCurrency;

    @Column(name = "target_exponent", nullable = false)
    public int targetExponent;

    @Column(name = "rate_num", nullable = false)
    public long rateNum;

    @Column(name = "rate_den", nullable = false)
    public long rateDen;

    /** Idempotency key used against the ledger, {@code fx:cnv_...}. */
    @Column(name = "ledger_txn_key", nullable = false, unique = true, length = 64)
    public String ledgerTxnKey;

    /** Journal entry id of the 4-leg conversion posting (ledger UUID). */
    @Column(name = "ledger_entry_id", nullable = false)
    public UUID ledgerEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public ConversionState status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** New entity for a domain conversion (fresh surrogate id). */
    public static ConversionEntity fromDomain(Conversion conversion) {
        Objects.requireNonNull(conversion, "conversion is required");
        ConversionEntity entity = new ConversionEntity();
        entity.id = UUID.randomUUID();
        entity.conversionId = conversion.id();
        entity.quoteId = conversion.quoteId();
        entity.sourceWalletRef = conversion.sourceWalletRef();
        entity.destinationWalletRef = conversion.destinationWalletRef();
        entity.sourceAmountMinor = conversion.sourceAmount().amountMinor();
        entity.sourceCurrency = conversion.sourceAmount().currency();
        entity.sourceExponent = conversion.sourceAmount().exponent();
        entity.targetAmountMinor = conversion.targetAmount().amountMinor();
        entity.targetCurrency = conversion.targetAmount().currency();
        entity.targetExponent = conversion.targetAmount().exponent();
        entity.rateNum = conversion.rate().numerator();
        entity.rateDen = conversion.rate().denominator();
        entity.ledgerTxnKey = conversion.ledgerTxnKey();
        entity.ledgerEntryId = UUID.fromString(conversion.ledgerEntryId());
        entity.status = conversion.state();
        entity.createdAt = conversion.createdAt();
        return entity;
    }

    /** Maps to the domain conversion. */
    public Conversion toDomain() {
        return new Conversion(conversionId, quoteId, sourceWalletRef, destinationWalletRef,
                Money.of(sourceAmountMinor, sourceCurrency), Money.of(targetAmountMinor, targetCurrency),
                new Rate(rateNum, rateDen, sourceCurrency, targetCurrency), ledgerTxnKey,
                ledgerEntryId.toString(), status, createdAt);
    }

    /** Refreshes every business field from the domain conversion (same id). */
    public void applyDomain(Conversion conversion) {
        Objects.requireNonNull(conversion, "conversion is required");
        this.conversionId = conversion.id();
        this.quoteId = conversion.quoteId();
        this.sourceWalletRef = conversion.sourceWalletRef();
        this.destinationWalletRef = conversion.destinationWalletRef();
        this.sourceAmountMinor = conversion.sourceAmount().amountMinor();
        this.sourceCurrency = conversion.sourceAmount().currency();
        this.sourceExponent = conversion.sourceAmount().exponent();
        this.targetAmountMinor = conversion.targetAmount().amountMinor();
        this.targetCurrency = conversion.targetAmount().currency();
        this.targetExponent = conversion.targetAmount().exponent();
        this.rateNum = conversion.rate().numerator();
        this.rateDen = conversion.rate().denominator();
        this.ledgerTxnKey = conversion.ledgerTxnKey();
        this.ledgerEntryId = UUID.fromString(conversion.ledgerEntryId());
        this.status = conversion.state();
        this.createdAt = conversion.createdAt();
    }
}
