package com.sharkpay.payouts.domain;

/** Owning principal's KYC tier is insufficient for payouts (422). */
public class KycRequiredException extends PayoutsDomainException {

    private final java.util.UUID principalId;

    public KycRequiredException(java.util.UUID principalId) {
        super("payouts require KYC tier at least LIMITED for principal " + principalId);
        this.principalId = principalId;
    }

    public java.util.UUID principalId() {
        return principalId;
    }
}
