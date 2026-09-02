package com.sharkpay.gateway.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity for the {@code quota_buckets} table: one counter per
 * (api key, window kind, window start). Window boundaries roll the
 * counter — a new minute/month simply starts a new row.
 */
@Entity
@Table(name = "quota_buckets")
@IdClass(QuotaBucketId.class)
public class QuotaBucketEntity {

    @Id
    @Column(name = "key_id", nullable = false, length = 40)
    private String keyId;

    @Id
    @Column(name = "window_kind", nullable = false, length = 8)
    private String windowKind;

    @Id
    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "used", nullable = false)
    private long used;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuotaBucketEntity() {
    }

    public QuotaBucketEntity(String keyId, String windowKind, Instant windowStart,
                             Instant windowEnd, Instant updatedAt) {
        this.keyId = keyId;
        this.windowKind = windowKind;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.used = 0L;
        this.updatedAt = updatedAt;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getWindowKind() {
        return windowKind;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public long getUsed() {
        return used;
    }

    public void setUsed(long used) {
        this.used = used;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QuotaBucketEntity that
                && Objects.equals(keyId, that.keyId)
                && Objects.equals(windowKind, that.windowKind)
                && Objects.equals(windowStart, that.windowStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, windowKind, windowStart);
    }

    @Override
    public String toString() {
        return "QuotaBucketEntity{key=" + keyId + ", kind=" + windowKind + ", start="
                + windowStart + ", used=" + used + "}";
    }
}
