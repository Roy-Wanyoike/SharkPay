package com.sharkpay.money;

/**
 * Base class for all money-library errors. These are contract/programming
 * errors, not retryable domain outcomes, so the hierarchy is unchecked.
 */
public class MoneyException extends RuntimeException {

    public MoneyException(String message) {
        super(message);
    }

    public MoneyException(String message, Throwable cause) {
        super(message, cause);
    }
}
