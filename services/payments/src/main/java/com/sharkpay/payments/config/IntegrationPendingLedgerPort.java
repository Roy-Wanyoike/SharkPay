package com.sharkpay.payments.config;

import com.sharkpay.money.Money;
import com.sharkpay.payments.ports.LedgerPort;

import java.util.UUID;

/**
 * Fail-fast placeholder {@link LedgerPort} adapter: journal entries require
 * the Go ledger service's posting API, wired at integration time by the
 * integrator (ADR 003 §3). No posting, no reversal — the ledger stays the
 * sole money authority.
 */
public final class IntegrationPendingLedgerPort implements LedgerPort {

    @Override
    public UUID postEntry(UUID paymentId, EntryType type, String walletId, Money amount,
                          String reason) {
        throw notWired("postEntry " + type + " for payment " + paymentId);
    }

    @Override
    public UUID reverseEntry(UUID entryId, UUID paymentId, String reason) {
        throw notWired("reverseEntry " + entryId);
    }

    private static IllegalStateException notWired(String operation) {
        return new IllegalStateException("LedgerPort adapter is not wired yet: the Go ledger"
                + " REST client lands at integration time (ADR 003)."
                + " Cannot " + operation + ".");
    }
}
