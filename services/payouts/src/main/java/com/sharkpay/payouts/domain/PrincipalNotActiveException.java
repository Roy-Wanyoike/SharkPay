package com.sharkpay.payouts.domain;

/** Owning principal is not ACTIVE (422 principal_not_active). */
public class PrincipalNotActiveException extends PayoutsDomainException {

    private final java.util.UUID principalId;
    private final String status;

    public PrincipalNotActiveException(java.util.UUID principalId, String status) {
        super("principal " + principalId + " is not active (status " + status + ")");
        this.principalId = principalId;
        this.status = status;
    }

    public java.util.UUID principalId() {
        return principalId;
    }

    public String status() {
        return status;
    }
}
