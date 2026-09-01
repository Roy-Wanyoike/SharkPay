package com.sharkpay.fx.ports;

import com.sharkpay.fx.domain.Direction;

import java.util.List;

/**
 * Statement of one ledger account, as needed for FX position
 * reconciliation.
 *
 * @param accountRef the account whose statement this is
 * @param lines      posting lines on the account (order as returned by the
 *                   ledger)
 */
public record LedgerStatement(String accountRef, List<LedgerLine> lines) {

    public LedgerStatement {
        if (accountRef == null || accountRef.isBlank()) {
            throw new IllegalArgumentException("accountRef is required");
        }
        lines = List.copyOf(lines);
    }

    /**
     * Net position of the account in minor units: Σ credits − Σ debits.
     */
    public long netPositionMinor() {
        long net = 0;
        for (LedgerLine line : lines) {
            net += line.direction() == Direction.CREDIT ? line.amountMinor() : -line.amountMinor();
        }
        return net;
    }
}
