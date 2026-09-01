package com.sharkpay.risk.domain.exceptions;

/**
 * Base class of risk domain errors. Subtypes carry the semantics the REST
 * layer maps onto HTTP status codes (400/404/409).
 */
public class RiskException extends RuntimeException {

    public RiskException(String message) {
        super(message);
    }
}
