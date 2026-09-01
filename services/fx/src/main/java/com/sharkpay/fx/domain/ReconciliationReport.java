package com.sharkpay.fx.domain;

/**
 * Result of an FX position reconciliation for one currency
 * (ReconcilePositionsUseCase): net position of the {@code fx-position:<CCY>}
 * account and how many ledger entries were examined.
 *
 * @param currency         currency of the position account
 * @param netPositionMinor Σ credits − Σ debits on {@code fx-position:<CCY>}
 *                         in minor units. With the 4-leg convention source
 *                         currencies accumulate positive positions and
 *                         target currencies negative ones; the imbalance
 *                         valued at market rates is the FX P&L.
 * @param entriesExamined  number of ledger lines inspected on the account
 */
public record ReconciliationReport(String currency, long netPositionMinor, int entriesExamined) {

    public ReconciliationReport {
        if (currency == null || currency.isBlank()) {
            throw new FxDomainException("reconciliation report currency is required");
        }
        if (entriesExamined < 0) {
            throw new FxDomainException("entriesExamined must be >= 0: " + entriesExamined);
        }
    }
}
