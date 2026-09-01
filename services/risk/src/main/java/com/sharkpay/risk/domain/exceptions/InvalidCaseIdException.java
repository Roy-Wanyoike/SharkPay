package com.sharkpay.risk.domain.exceptions;

/** Unparseable case identifier. */
public class InvalidCaseIdException extends RiskException {

    public InvalidCaseIdException(String raw) {
        super("'" + raw + "' is not a valid case id (expected case_<uuid-hex> or a UUID)");
    }
}
