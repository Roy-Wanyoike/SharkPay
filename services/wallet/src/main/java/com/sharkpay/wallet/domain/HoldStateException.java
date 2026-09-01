package com.sharkpay.wallet.domain;

/**
 * A hold lifecycle transition was attempted from a terminal state
 * (released/captured holds are final).
 */
public class HoldStateException extends WalletDomainException {

    public HoldStateException(String holdId, HoldState state, String attempted) {
        super("hold " + holdId + " is " + state.wireName() + "; cannot " + attempted);
    }
}
