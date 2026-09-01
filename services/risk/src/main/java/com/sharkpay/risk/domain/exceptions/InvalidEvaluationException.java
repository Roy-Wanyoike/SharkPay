package com.sharkpay.risk.domain.exceptions;

/** Malformed evaluation request (bad ids, non-positive amount, bad enums...). */
public class InvalidEvaluationException extends RiskException {

    public InvalidEvaluationException(String message) {
        super(message);
    }
}
