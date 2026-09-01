package com.sharkpay.payments.ports;

/**
 * Transient provider-gateway failure (transport error, timeout, open circuit
 * breaker): the call may succeed on retry. Maps to Temporal retryable
 * {@code ApplicationFailure} in the activity layer (the retry-then-success
 * path).
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
