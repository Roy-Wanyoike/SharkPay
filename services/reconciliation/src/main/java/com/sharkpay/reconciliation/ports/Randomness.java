package com.sharkpay.reconciliation.ports;

import java.util.UUID;

/**
 * Deterministic-id/entropy port (the only source of randomness in the
 * service — everything else derives from the clock). Production wiring
 * uses {@code com.sharkpay.reconciliation.config.StandardRandomness}; tests
 * inject the sequential fake for deterministic ids.
 */
public interface Randomness {

    /** A fresh UUID v7 (time-ordered — event ids per the event catalog). */
    UUID uuidV7();

    /** A new public recon-run id ({@code run_} + 32 hex chars). */
    String runId();

    /** A new public break id ({@code brk_} + 32 hex chars). */
    String breakId();

    /** A new public compensation id ({@code cmp_} + 32 hex chars). */
    String compensationId();

    /** A new public settlement-report id ({@code str_} + 32 hex chars). */
    String settlementId();
}
