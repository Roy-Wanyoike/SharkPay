package com.sharkpay.payouts.config;

import com.sharkpay.payouts.domain.LedgerPostingException;
import com.sharkpay.payouts.ports.LedgerPort;

import java.util.UUID;

/**
 * Fail-fast placeholder {@link LedgerPort} adapter: posting journal entries
 * requires the Go ledger service's internal idempotent posting API
 * (POST /internal/transactions + /internal/transactions/{id}/reverse),
 * wired at integration time by the integrator (ADR 003 §3). Refusing
 * loudly per call keeps the money path honest: no transfer or payout can
 * move money against a ledger that was never posted to.
 */
public final class IntegrationPendingLedgerPort implements LedgerPort {

    @Override
    public PostingResult post(LedgerPosting posting) {
        throw new LedgerPostingException(posting.transactionKey(),
                "LedgerPort adapter is not wired yet: the REST ledger posting adapter lands at "
                        + "integration time (ADR 003). Cannot post " + posting.transactionKey()
                        + " (" + posting.legs().size() + " legs, entry type "
                        + posting.entryType().wireName() + ").", null);
    }

    @Override
    public PostingResult reverse(UUID entryId, String transactionKey, UUID sourceRef,
                                 String reason) {
        throw new LedgerPostingException(transactionKey,
                "LedgerPort adapter is not wired yet: the REST ledger reversal adapter lands at "
                        + "integration time (ADR 003). Cannot reverse entry " + entryId + ".",
                null);
    }
}
