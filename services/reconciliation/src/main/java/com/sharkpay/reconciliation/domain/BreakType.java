package com.sharkpay.reconciliation.domain;

/**
 * The reconciliation break taxonomy (PRD D10 / FR-1001, RUNBOOKS RB-7
 * "Diagnosis step 1"). Every discrepancy between a provider statement line
 * and the internal ledger side classifies into exactly one of these — the
 * comparison engine never invents categories and never guesses.
 *
 * <ul>
 *   <li>{@code MISSING_ON_PROVIDER} — the internal side recorded a movement
 *       the provider's statement does not carry;</li>
 *   <li>{@code MISSING_INTERNAL} — the provider's statement carries a
 *       movement the internal side never recorded;</li>
 *   <li>{@code AMOUNT_MISMATCH} — both sides agree on the reference but the
 *       principal amounts differ (exact minor-unit comparison, same
 *       currency);</li>
 *   <li>{@code STATUS_MISMATCH} — mapped provider status differs from the
 *       internal status, or the provider status does not map to any known
 *       status (never guess — RB-7 / provider AMBIGUITY CONTRACT);</li>
 *   <li>{@code FEE_MISMATCH} — the fee amounts differ (same currency);</li>
 *   <li>{@code CURRENCY_MISMATCH} — the two sides report different
 *       currencies for the same reference: its own break, because a minor
 *       unit of one currency is incomparable to a minor unit of another.</li>
 * </ul>
 */
public enum BreakType {

    MISSING_ON_PROVIDER("missing_on_provider"),
    MISSING_INTERNAL("missing_internal"),
    AMOUNT_MISMATCH("amount_mismatch"),
    STATUS_MISMATCH("status_mismatch"),
    FEE_MISMATCH("fee_mismatch"),
    CURRENCY_MISMATCH("currency_mismatch");

    private final String wireName;

    BreakType(String wireName) {
        this.wireName = wireName;
    }

    /** The wire/API/DB name of the break type. */
    public String wireName() {
        return wireName;
    }

    /** Parses the wire name (storage/API); never guesses. */
    public static BreakType fromWireName(String wireName) {
        for (BreakType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown break type: " + wireName);
    }
}
