package com.sharkpay.payouts.domain;

/**
 * Transfer lifecycle (docs/STATE-MACHINES.md §3 — normative):
 * {@code CREATED → SUCCEEDED} (single atomic ledger transaction) or
 * {@code CREATED → FAILED} (pre-flight / ledger rejection — never partially
 * posted). V1 execution is synchronous, so responses are terminal.
 */
public enum TransferState {
    CREATED, SUCCEEDED, FAILED;

    /** Wire name (contracts/openapi/v1/transfers.yaml TransferState enum). */
    public String wireName() {
        return name();
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    /** Case-insensitive wire parse. */
    public static TransferState fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("transfer state is required");
        }
        return valueOf(value.trim().toUpperCase());
    }
}
