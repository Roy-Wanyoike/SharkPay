package com.sharkpay.risk.domain.exceptions;

/** Compliance case not found. */
public class CaseNotFoundException extends RiskException {

    public CaseNotFoundException(String caseId) {
        super("Case " + caseId + " not found");
    }
}
