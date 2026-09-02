package com.sharkpay.reconciliation.storage;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.domain.ReconBreak.Builder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code recon_breaks} table: one classified
 * discrepancy. The four money slots (provider/internal amount/fee) are
 * minor-unit + currency + exponent triples, all three columns NULL
 * together on an absent side.
 */
@Entity
@Table(name = "recon_breaks")
public class ReconBreakEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "run_id", nullable = false, length = 40)
    private String runId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "break_type", nullable = false, length = 24)
    private String breakType;

    @Column(name = "provider_ref", length = 128)
    private String providerRef;

    @Column(name = "internal_ref", length = 128)
    private String internalRef;

    @Column(name = "provider_amount_minor")
    private Long providerAmountMinor;

    @Column(name = "provider_amount_currency", length = 4)
    private String providerAmountCurrency;

    @Column(name = "provider_amount_exponent")
    private Integer providerAmountExponent;

    @Column(name = "internal_amount_minor")
    private Long internalAmountMinor;

    @Column(name = "internal_amount_currency", length = 4)
    private String internalAmountCurrency;

    @Column(name = "internal_amount_exponent")
    private Integer internalAmountExponent;

    @Column(name = "provider_fee_minor")
    private Long providerFeeMinor;

    @Column(name = "provider_fee_currency", length = 4)
    private String providerFeeCurrency;

    @Column(name = "provider_fee_exponent")
    private Integer providerFeeExponent;

    @Column(name = "internal_fee_minor")
    private Long internalFeeMinor;

    @Column(name = "internal_fee_currency", length = 4)
    private String internalFeeCurrency;

    @Column(name = "internal_fee_exponent")
    private Integer internalFeeExponent;

    @Column(name = "provider_status", length = 32)
    private String providerStatus;

    @Column(name = "internal_status", length = 32)
    private String internalStatus;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "state", nullable = false, length = 14)
    private String state;

    @Column(name = "bucket", nullable = false, length = 8)
    private String bucket;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "last_actor", length = 128)
    private String lastActor;

    @Column(name = "last_transition_at")
    private Instant lastTransitionAt;

    @Column(name = "compensation_id", length = 40)
    private String compensationId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    protected ReconBreakEntity() {
    }

    /** Maps the domain break onto a fresh entity (insert shape). */
    public static ReconBreakEntity fromDomain(ReconBreak break_) {
        ReconBreakEntity entity = new ReconBreakEntity();
        entity.id = break_.id();
        entity.runId = break_.runId();
        entity.provider = break_.provider();
        entity.breakType = break_.breakType().wireName();
        entity.providerRef = break_.providerRef();
        entity.internalRef = break_.internalRef();
        entity.providerAmountMinor = minor(break_.providerAmount());
        entity.providerAmountCurrency = currency(break_.providerAmount());
        entity.providerAmountExponent = exponent(break_.providerAmount());
        entity.internalAmountMinor = minor(break_.internalAmount());
        entity.internalAmountCurrency = currency(break_.internalAmount());
        entity.internalAmountExponent = exponent(break_.internalAmount());
        entity.providerFeeMinor = minor(break_.providerFee());
        entity.providerFeeCurrency = currency(break_.providerFee());
        entity.providerFeeExponent = exponent(break_.providerFee());
        entity.internalFeeMinor = minor(break_.internalFee());
        entity.internalFeeCurrency = currency(break_.internalFee());
        entity.internalFeeExponent = exponent(break_.internalFee());
        entity.providerStatus = break_.providerStatus();
        entity.internalStatus = break_.internalStatus();
        entity.detectedAt = break_.detectedAt();
        entity.state = break_.state().wireName();
        entity.bucket = break_.bucket().wireName();
        entity.note = break_.note();
        entity.lastActor = break_.lastActor();
        entity.lastTransitionAt = break_.lastTransitionAt();
        entity.compensationId = break_.compensationId();
        entity.resolvedAt = break_.resolvedAt();
        entity.escalatedAt = break_.escalatedAt();
        return entity;
    }

    /** Applies the mutable lifecycle fields onto this entity (update shape). */
    public void applyDomain(ReconBreak break_) {
        this.state = break_.state().wireName();
        this.bucket = break_.bucket().wireName();
        this.note = break_.note();
        this.lastActor = break_.lastActor();
        this.lastTransitionAt = break_.lastTransitionAt();
        this.compensationId = break_.compensationId();
        this.resolvedAt = break_.resolvedAt();
        this.escalatedAt = break_.escalatedAt();
    }

    /** Restores the domain aggregate. */
    public ReconBreak toDomain() {
        Builder builder = new Builder(id, runId, provider,
                com.sharkpay.reconciliation.domain.BreakType.fromWireName(breakType))
                .providerRef(providerRef)
                .internalRef(internalRef)
                .providerAmount(money(providerAmountMinor, providerAmountCurrency,
                        providerAmountExponent))
                .internalAmount(money(internalAmountMinor, internalAmountCurrency,
                        internalAmountExponent))
                .providerFee(money(providerFeeMinor, providerFeeCurrency, providerFeeExponent))
                .internalFee(money(internalFeeMinor, internalFeeCurrency, internalFeeExponent))
                .providerStatus(providerStatus)
                .internalStatus(internalStatus)
                .detectedAt(detectedAt)
                .state(com.sharkpay.reconciliation.domain.BreakState.fromWireName(state))
                .bucket(com.sharkpay.reconciliation.domain.AgingBucket.fromWireName(bucket))
                .note(note)
                .lastActor(lastActor)
                .lastTransitionAt(lastTransitionAt)
                .compensationId(compensationId)
                .resolvedAt(resolvedAt)
                .escalatedAt(escalatedAt);
        return ReconBreak.rehydrate(builder);
    }

    // Money is reconstructed through the library so the exponent is the
    // currency's canonical one (the stored exponent is the same by
    // construction — Money.of validated it on the way in).
    private static Money money(Long minor, String currency, Integer exponent) {
        return minor == null ? null : Money.of(minor, currency);
    }

    private static Long minor(Money money) {
        return money == null ? null : money.amountMinor();
    }

    private static String currency(Money money) {
        return money == null ? null : money.currency();
    }

    private static Integer exponent(Money money) {
        return money == null ? null : money.exponent();
    }

    public String getId() {
        return id;
    }
}
