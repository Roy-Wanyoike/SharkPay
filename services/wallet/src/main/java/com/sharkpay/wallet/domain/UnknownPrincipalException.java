package com.sharkpay.wallet.domain;

/**
 * The wallet-owning principal does not exist (identity lookup miss).
 */
public class UnknownPrincipalException extends WalletDomainException {

    public UnknownPrincipalException(java.util.UUID principalId) {
        super("principal " + principalId + " not found");
    }
}
