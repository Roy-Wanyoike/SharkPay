package com.sharkpay.reconciliation.domain;

/**
 * Canonical, rail-agnostic status vocabulary used to compare a provider
 * statement line against the internal side. Values deliberately match the
 * semantics of the providers gateway's {@code TransferStatus} minus UNKNOWN
 * (an unmappable status is a STATUS_MISMATCH break, never a value here).
 */
public enum ReconStatus {

    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    CONFIRMED("CONFIRMED"),
    FAILED("FAILED"),
    RETURNED("RETURNED");

    private final String wireName;

    ReconStatus(String wireName) {
        this.wireName = wireName;
    }

    /** The canonical wire name (compared verbatim on both sides). */
    public String wireName() {
        return wireName;
    }
}
