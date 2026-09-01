package com.sharkpay.payments.ports;

/**
 * Definitive provider business rejection (rail rejected the transfer,
 * unsupported currency/operation, amount outside limits): retrying the same
 * request can never succeed. Maps to Temporal non-retryable
 * {@code ApplicationFailure} — the workflow compensates immediately.
 */
public class ProviderRejectedException extends RuntimeException {

    public ProviderRejectedException(String message) {
        super(message);
    }
}
