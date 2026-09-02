package com.sharkpay.payouts.domain;

/**
 * Payout lifecycle (docs/STATE-MACHINES.md §2 — normative):
 *
 * <pre>
 * CREATED → PENDING_RISK → PROCESSING → SENT → SUCCEEDED
 *                 │            │           │
 *                 ▼            ▼           ▼
 *             BLOCKED       FAILED     RETURNED
 * </pre>
 *
 * plus {@code CANCELLED} reachable from CREATED (user cancel), PENDING_RISK
 * (user cancel / TTL expiry) and PROCESSING (system TTL expiry after the
 * provider confirms cancellation — payouts.yaml: auto-cancel applies "if the
 * provider has not accepted it", i.e. before SENT). Mission-to-contract state
 * mapping: created → scheduled = PENDING_RISK, submitted = PROCESSING,
 * pending_provider = SENT, settled = SUCCEEDED, failed = FAILED,
 * returned = RETURNED.
 */
public enum PayoutState {
    CREATED, PENDING_RISK, PROCESSING, SENT, SUCCEEDED, FAILED, RETURNED, BLOCKED, CANCELLED;

    /** Wire name (contracts/openapi/v1/payouts.yaml PayoutState enum). */
    public String wireName() {
        return name();
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == RETURNED || this == BLOCKED
                || this == CANCELLED;
    }

    /** True when the provider may still accept the payout (pre-SENT). */
    public boolean isCancellable() {
        return this == CREATED || this == PENDING_RISK;
    }

    /** Case-insensitive wire parse. */
    public static PayoutState fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("payout state is required");
        }
        return valueOf(value.trim().toUpperCase());
    }
}
