package com.sharkpay.fx.service;

import com.sharkpay.fx.domain.AccountRefs;
import com.sharkpay.fx.domain.ReconciliationReport;
import com.sharkpay.fx.ports.ConversionRepository;
import com.sharkpay.fx.ports.LedgerPort;
import com.sharkpay.fx.ports.LedgerStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Position reconciliation: for every currency involved in a conversion,
 * fetch the {@code fx-position:<CCY>} statement from the ledger and report
 * the net position (Σ credits − Σ debits) plus the number of entries
 * examined.
 *
 * <p>With the 4-leg convention the source currency position grows positive
 * (credits) and the target currency position grows negative (debits); the
 * positions valued at market rates are the FX book. A zero net position
 * with non-zero entries examined means the book was flat (fully hedged /
 * unwound).
 */
public final class ReconcilePositionsUseCase {

    private final ConversionRepository conversions;
    private final LedgerPort ledger;

    public ReconcilePositionsUseCase(ConversionRepository conversions, LedgerPort ledger) {
        this.conversions = Objects.requireNonNull(conversions, "conversionRepository is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
    }

    public List<ReconciliationReport> reconcile() {
        List<String> currencies = conversions.findInvolvedCurrencies();
        List<ReconciliationReport> reports = new ArrayList<>(currencies.size());
        for (String currency : currencies) {
            LedgerStatement statement = ledger.getStatement(AccountRefs.fxPosition(currency));
            reports.add(new ReconciliationReport(currency, statement.netPositionMinor(), statement.lines().size()));
        }
        return List.copyOf(reports);
    }
}
