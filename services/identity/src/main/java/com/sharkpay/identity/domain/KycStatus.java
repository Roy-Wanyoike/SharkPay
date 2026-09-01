package com.sharkpay.identity.domain;

/**
 * State of a KYC decision record. PENDING decisions have no decision time yet.
 */
public enum KycStatus {
    PENDING,
    APPROVED,
    REJECTED
}
