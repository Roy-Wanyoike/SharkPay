package com.sharkpay.risk.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * velocity_counters row (V1__risk_init.sql): windowed per-subject counters.
 * The composite key is (subject, window_bucket, currency); window_bucket
 * encodes the window duration + bucket index (see VelocityBuckets).
 */
@Entity
@IdClass(VelocityCounterId.class)
@Table(name = "velocity_counters")
public class VelocityCounterEntity {

    @Id
    @Column(name = "subject", nullable = false)
    public String subject;

    @Id
    @Column(name = "window_bucket", nullable = false)
    public String windowBucket;

    @Id
    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Column(name = "\"count\"", nullable = false)
    public int txnCount;

    @Column(name = "amount_minor", nullable = false)
    public long amountMinor;

    protected VelocityCounterEntity() {
        // JPA
    }

    public VelocityCounterEntity(String subject, String windowBucket, String currency,
                                  int txnCount, long amountMinor) {
        this.subject = subject;
        this.windowBucket = windowBucket;
        this.currency = currency;
        this.txnCount = txnCount;
        this.amountMinor = amountMinor;
    }
}
