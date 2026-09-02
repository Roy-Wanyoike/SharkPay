package com.sharkpay.reconciliation.domain;

/**
 * The provider statement (or ledger statement) could not be fetched —
 * provider unreachable, circuit breaker open, transport error. This is an
 * <i>expected</i> upstream failure mode: the recon run being executed is
 * marked {@code FAILED} with this exception's message as the failure
 * reason, so the outcome is auditable and a retry is a new run (or an
 * idempotent replay of the same failed run).
 */
public class StatementUnavailableException extends ReconciliationException {

    public StatementUnavailableException(String side, String provider, Throwable cause) {
        // side names which statement could not be fetched ("provider
        // statement" / "ledger statement") — the message must not append
        // "statement" again or it reads "provider statement statement
        // unavailable", which is also what lands in the FAILED run's
        // auditable failure_reason
        super(side + " unavailable for provider " + provider + ": " + cause.getMessage(), cause);
    }
}
