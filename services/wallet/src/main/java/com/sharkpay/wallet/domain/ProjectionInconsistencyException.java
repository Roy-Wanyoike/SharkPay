package com.sharkpay.wallet.domain;

/**
 * The ledger balance projection would break a money invariant the ledger
 * guarantees upstream (negative wallet balance, currency mismatch on an
 * account, or an amount that overflows int64 minor units). The offending
 * event is rejected for dead-lettering instead of corrupting the projection.
 */
public class ProjectionInconsistencyException extends WalletDomainException {

    public ProjectionInconsistencyException(String message) {
        super(message);
    }

    public ProjectionInconsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
