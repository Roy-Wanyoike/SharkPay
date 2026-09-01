package com.sharkpay.fx.domain;

/**
 * FX position account reference convention: {@code fx-position:<CCY>} — one
 * FX position account per currency in the ledger's chart of accounts
 * (docs/DATA-MODEL.md &#167;2). FX P&L is observable from these accounts.
 */
public final class AccountRefs {

    public static final String FX_POSITION_PREFIX = "fx-position:";

    private AccountRefs() {
    }

    /** Ledger account reference of the FX position account for a currency. */
    public static String fxPosition(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new FxDomainException("currency is required for an fx position account ref");
        }
        return FX_POSITION_PREFIX + currency;
    }
}
