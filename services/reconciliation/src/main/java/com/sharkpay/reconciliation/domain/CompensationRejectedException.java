package com.sharkpay.reconciliation.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ledger (money authority) rejected a compensation posting with a
 * business rule (unbalanced legs, insufficient funds, reversal pairing…).
 * The compensation entry stays PROPOSED and nothing was posted. Surfaces as
 * 422 {@code compensation_rejected} with the ledger's code and reason in
 * the error details — the operators must amend and re-propose (RB-7 step 2).
 */
public class CompensationRejectedException extends ReconciliationException {

    private final String ledgerCode;
    private final String ledgerReason;

    public CompensationRejectedException(String ledgerCode, String ledgerReason) {
        super("ledger rejected the compensation posting (" + ledgerCode + "): " + ledgerReason);
        this.ledgerCode = ledgerCode;
        this.ledgerReason = ledgerReason;
    }

    public String ledgerCode() {
        return ledgerCode;
    }

    public String ledgerReason() {
        return ledgerReason;
    }

    /** Error-envelope details (contracts/openapi/v1/common.yaml). */
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ledger_code", ledgerCode);
        details.put("ledger_reason", ledgerReason);
        return details;
    }
}
