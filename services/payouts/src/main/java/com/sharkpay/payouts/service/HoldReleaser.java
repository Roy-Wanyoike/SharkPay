package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.ports.LedgerPort;

/**
 * Releases a payout's hold via a strict ledger reversal of the hold entry
 * ({@code payouts:{id}:release}) — the money returns to the wallet in full
 * (amount + fee; nothing ever left). Used by cancel, TTL expiry, risk-deny
 * and terminal-failure paths. Idempotent per payout: the state machine
 * guarantees each payout terminates at most once, and the ledger's
 * {@code (source, transaction_key)} uniqueness is the second lock.
 */
final class HoldReleaser {

    private HoldReleaser() {
    }

    static void release(LedgerPort ledger, Payout payout, String reason) {
        if (!payout.isHeld()) {
            return; // never accepted — nothing was ever held
        }
        LedgerPort.PostingResult outcome = ledger.reverse(payout.holdEntryId(),
                PayoutMoney.releaseKey(payout), payout.internalRef(), reason);
        switch (outcome) {
            case LedgerPort.PostingResult.Committed committed -> {
                // reversal confirmed; the journal carries the entry under
                // the release key (money-state alignment §7.4)
            }
            case LedgerPort.PostingResult.Rejected rejected -> {
                throw new com.sharkpay.payouts.domain.LedgerPostingException(
                        PayoutMoney.releaseKey(payout),
                        "hold release rejected (" + rejected.code() + ": " + rejected.reason()
                                + ") — payout " + payout.id() + " parked for ops", null);
            }
        }
    }
}
