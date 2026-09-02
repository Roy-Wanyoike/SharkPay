package com.sharkpay.payouts.domain;

/**
 * The ledger port could not be reached or failed transiently (500
 * internal_error — safe to retry with the same keys). Distinct from a ledger
 * business rejection, which is a normal domain outcome
 * ({@code PostingResult.rejected}).
 */
public class LedgerPostingException extends PayoutsDomainException {

    public LedgerPostingException(String transactionKey, String message, Throwable cause) {
        super("ledger posting " + transactionKey + " failed: " + message, cause);
    }
}
