package com.sharkpay.gateway.storage;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Composite id of {@link QuotaBucketEntity}: (key_id, window_kind, window_start). */
public class QuotaBucketId implements Serializable {

    private String keyId;
    private String windowKind;
    private Instant windowStart;

    public QuotaBucketId() {
    }

    public QuotaBucketId(String keyId, String windowKind, Instant windowStart) {
        this.keyId = keyId;
        this.windowKind = windowKind;
        this.windowStart = windowStart;
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

    @Override
    public boolean equals(Object other) {
        return other instanceof QuotaBucketId that
                && Objects.equals(keyId, that.keyId)
                && Objects.equals(windowKind, that.windowKind)
                && Objects.equals(windowStart, that.windowStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, windowKind, windowStart);
    }
}
